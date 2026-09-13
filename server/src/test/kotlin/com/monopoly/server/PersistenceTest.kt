package com.monopoly.server

import com.monopoly.core.engine.Command
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import com.monopoly.protocol.ServerMessage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A restart must interrupt a game, not end it.
 *
 * These tests all follow the same shape: play, throw the registry away, build a
 * new one over the same directory, and check that what comes back is the game
 * that was being played — including the resume tokens, without which the seats
 * would be unreachable and the state would be a museum piece.
 */
class PersistenceTest {

    private fun tempStore(): Pair<Path, FileGameStore> {
        val directory = Files.createTempDirectory("monopoly-store")
        directory.toFile().deleteOnExit()
        return directory to FileGameStore(directory)
    }

    /** A registry over the same directory, as if the process had restarted. */
    private suspend fun restarted(directory: Path): GameRegistry =
        GameRegistry(store = FileGameStore(directory)).also { it.restore() }

    @Test
    fun `a game in progress survives a restart`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)

        val hosted = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)
        val code = hosted.session.code
        val guest = PlayerId("guest")
        hosted.session.submit(guest, "join", Command.JoinGame(guest, "Guest", Token.BOOT))
        hosted.session.enrol(guest, "guest-token")
        hosted.session.submit(hosted.playerId, "start", Command.StartGame(hosted.playerId))

        val before = hosted.session.currentState()
        val sequenceBefore = hosted.session.sequenceNumber()

        val session = assertNotNull(restarted(directory).find(code), "game came back")
        val after = session.currentState()

        assertEquals(sequenceBefore, session.sequenceNumber(), "sequence continues")
        assertEquals(before.players.map { it.id }, after.players.map { it.id })
        assertEquals(before.players.map { it.money }, after.players.map { it.money })
        assertEquals(before.currentPlayerIndex, after.currentPlayerIndex)
        assertEquals(before.rngState, after.rngState, "the dice carry on where they left off")
        assertIs<GamePhase.AwaitingRoll>(after.phase)
    }

    @Test
    fun `resume tokens survive, so the seats are still reachable`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)

        val hosted = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)
        val code = hosted.session.code

        val session = assertNotNull(restarted(directory).find(code))
        assertEquals(
            hosted.playerId,
            session.seatFor(hosted.resumeToken),
            "the host's token still opens the host's seat",
        )
        assertNull(session.seatFor("not-a-token"))
    }

    @Test
    fun `play continues after a restart, from the sequence it left off at`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)

        val hosted = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)
        val code = hosted.session.code
        val guest = PlayerId("guest")
        hosted.session.submit(guest, "join", Command.JoinGame(guest, "Guest", Token.BOOT))
        hosted.session.enrol(guest, "guest-token")
        hosted.session.submit(hosted.playerId, "start", Command.StartGame(hosted.playerId))
        val sequenceBefore = hosted.session.sequenceNumber()

        val session = assertNotNull(restarted(directory).find(code))
        val current = session.currentState().players[session.currentState().currentPlayerIndex].id
        val outcome = session.submit(current, "roll", Command.RollDice(current))

        val accepted = assertIs<ServerMessage.CommandAccepted>(outcome)
        assertEquals(
            sequenceBefore + 1,
            accepted.firstSequence,
            "numbering carries on rather than starting again",
        )
    }

    @Test
    fun `a restarted game is written through to the same file`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)
        val hosted = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)
        val code = hosted.session.code

        val session = assertNotNull(restarted(directory).find(code))
        val guest = PlayerId("guest")
        session.submit(guest, "join", Command.JoinGame(guest, "Guest", Token.BOOT))

        // A second restart has to see what the first one did, or durability
        // only ever survives one crash.
        val again = assertNotNull(restarted(directory).find(code))
        assertEquals(2, again.currentState().players.size, "the join was kept")
        assertEquals(1, directory.listDirectoryEntries().size, "one file per game")
    }

    @Test
    fun `a finished game is removed from disk, not just from memory`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)
        val hosted = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)

        assertEquals(1, directory.listDirectoryEntries().size)
        registry.remove(hosted.session.code)

        assertTrue(directory.listDirectoryEntries().isEmpty(), "the file is gone too")
        assertNull(restarted(directory).find(hosted.session.code))
    }

    @Test
    fun `a half-written last line costs one line, not the game`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)
        val hosted = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)
        val code = hosted.session.code
        val guest = PlayerId("guest")
        hosted.session.submit(guest, "join", Command.JoinGame(guest, "Guest", Token.BOOT))

        // Exactly what a process killed mid-append leaves behind.
        val file = directory.listDirectoryEntries().single()
        file.appendText("""{"type":"com.monopoly.server.GameRecord.Happ""")

        val session = assertNotNull(restarted(directory).find(code), "the game still loads")
        assertEquals(2, session.currentState().players.size, "everything before it survived")
    }

    @Test
    fun `a file that is not a game at all is skipped rather than fatal`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)
        val good = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)

        Files.writeString(directory.resolve("JUNK.jsonl"), "this is not json\n")

        val recovered = restarted(directory)
        assertNotNull(recovered.find(good.session.code), "the healthy game still loads")
        assertNull(recovered.find("JUNK"))
    }

    @Test
    fun `nobody is marked connected in a game that has just been read back`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)
        val hosted = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)

        // The host is connected in the live game; after a restart there are no
        // sockets at all, and saying otherwise would stop the turn timer from
        // ever noticing they are gone.
        val session = assertNotNull(restarted(directory).find(hosted.session.code))
        assertTrue(session.currentState().players.none { it.connected })
    }

    @Test
    fun `the journal is readable while a game is in progress`() = runBlocking<Unit> {
        val (directory, store) = tempStore()
        val registry = GameRegistry(store = store)
        val hosted = registry.create("Host", Token.TOP_HAT, GameRules.CLASSIC)

        // Flushed per record, not buffered until close: a game being diagnosed
        // is a game still being played.
        val contents = directory.listDirectoryEntries().single().readText()
        assertTrue(contents.contains(hosted.session.code), "the opening record is already on disk")
        assertTrue(contents.contains(hosted.resumeToken), "and so is the host's seat")
    }
}
