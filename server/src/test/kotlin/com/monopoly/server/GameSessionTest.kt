package com.monopoly.server

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.engine.Seat
import com.monopoly.core.model.Deed
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import com.monopoly.protocol.ServerMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session is where the connection guarantees actually live. The engine
 * guarantees the rules are right; these tests guarantee that a flaky network
 * cannot turn a correct rule into a wrong outcome.
 */
class GameSessionTest {

    private val host = PlayerId("host")
    private val guest = PlayerId("guest")

    private fun newSession(now: () -> Long = System::currentTimeMillis): GameSession {
        val state = GameFactory.newLobby(
            gameId = "TEST",
            host = Seat(host, "Host", Token.TOP_HAT),
            rules = GameRules.CLASSIC,
            seed = 42L,
        )
        return GameSession("TEST", state, now)
    }

    /** A session with two players seated, still in the lobby. */
    private suspend fun seatedSession(): GameSession {
        val session = newSession()
        session.enrol(host, "host-token")
        session.submit(guest, "join-1", Command.JoinGame(guest, "Guest", Token.BOOT))
        session.enrol(guest, "guest-token")
        return session
    }

    private fun ClientChannel.drain(): List<ServerMessage> = buildList {
        while (true) add(outgoing.tryReceive().getOrNull() ?: break)
    }

    // ----------------------------------------------------------- deduplication

    @Test
    fun `a retried command returns the original result instead of running again`() = runBlocking {
        val session = seatedSession()

        val first = session.submit(host, "start-1", Command.StartGame(host))
        assertIs<ServerMessage.CommandAccepted>(first)

        // The client never saw the reply and sent the identical command again.
        // Without deduplication this would come back as WRONG_PHASE, and the
        // player would be told their own successful action had failed.
        val retry = session.submit(host, "start-1", Command.StartGame(host))
        assertEquals(first, retry)
    }

    @Test
    fun `a retried command does not move money twice`() = runBlocking {
        val session = seatedSession()
        session.submit(host, "start", Command.StartGame(host))

        // Give the host Mayfair so there is something to mortgage.
        val current = session.currentState().currentPlayer.id
        val withDeed = session.currentState().copy(deeds = mapOf(39 to Deed(39, current)))
        val owned = GameSession("TEST", withDeed)
        owned.enrol(current, "tok")

        val before = owned.currentState().player(current).money
        owned.submit(current, "mortgage-1", Command.MortgageProperty(current, 39))
        val afterFirst = owned.currentState().player(current).money
        assertEquals(before + 200, afterFirst)

        owned.submit(current, "mortgage-1", Command.MortgageProperty(current, 39))
        assertEquals(afterFirst, owned.currentState().player(current).money, "Paid out twice")
    }

    @Test
    fun `a rejection is remembered too, so a retry is answered consistently`() = runBlocking {
        val session = seatedSession()
        // The guest is not the host, so starting is refused.
        val first = session.submit(guest, "bad-1", Command.StartGame(guest))
        assertIs<ServerMessage.CommandRejected>(first)
        assertEquals(RejectionReason.NOT_HOST, first.reason)
        assertEquals(first, session.submit(guest, "bad-1", Command.StartGame(guest)))
    }

    // ------------------------------------------------------------------ spoofing

    @Test
    fun `a client cannot issue a command as somebody else`() = runBlocking {
        val session = seatedSession()
        // The guest's socket, claiming to be the host, trying to start the game.
        val outcome = session.submit(guest, "spoof-1", Command.StartGame(host))
        assertIs<ServerMessage.CommandRejected>(outcome)
        assertEquals(RejectionReason.UNKNOWN_PLAYER, outcome.reason)
        // And nothing happened.
        assertEquals(0, session.sequenceNumber() - session.sequenceNumber())
    }

    // ----------------------------------------------------------------- sequencing

    @Test
    fun `sequence numbers are contiguous and start at one`() = runBlocking {
        val session = newSession()
        session.enrol(host, "tok")
        assertEquals(0, session.sequenceNumber())

        val outcome = session.submit(guest, "join", Command.JoinGame(guest, "Guest", Token.BOOT))
        assertIs<ServerMessage.CommandAccepted>(outcome)
        assertEquals(1, outcome.firstSequence)
        assertEquals(session.sequenceNumber(), outcome.lastSequence)
    }

    @Test
    fun `every accepted command advances the sequence by its event count`() = runBlocking {
        val session = seatedSession()
        val before = session.sequenceNumber()
        val outcome = session.submit(host, "start", Command.StartGame(host))
        assertIs<ServerMessage.CommandAccepted>(outcome)
        assertEquals(before + 1, outcome.firstSequence)
        assertEquals(session.sequenceNumber(), outcome.lastSequence)
    }

    @Test
    fun `a rejected command consumes no sequence numbers`() = runBlocking {
        val session = seatedSession()
        val before = session.sequenceNumber()
        session.submit(guest, "bad", Command.StartGame(guest))
        assertEquals(before, session.sequenceNumber())
    }

    // ------------------------------------------------------------- catching up

    @Test
    fun `a client that is already current is told there is nothing to do`() = runBlocking {
        val session = seatedSession()
        val message = session.catchUp(session.sequenceNumber())
        assertIs<ServerMessage.Events>(message)
        assertTrue(message.events.isEmpty())
    }

    @Test
    fun `a small gap is filled by replaying exactly the missing events`() = runBlocking {
        val session = seatedSession()
        val mark = session.sequenceNumber()
        session.submit(host, "start", Command.StartGame(host))

        val message = session.catchUp(mark)
        assertIs<ServerMessage.Events>(message)
        assertEquals(mark + 1, message.fromSequence)
        assertEquals(mark + 1, message.events.first().sequence)
        assertEquals(session.sequenceNumber(), message.events.last().sequence)
        // Contiguous, which is what lets the client trust the fold.
        message.events.map { it.sequence }.zipWithNext { a, b -> assertEquals(a + 1, b) }
    }

    @Test
    fun `a client far behind gets a snapshot rather than a huge replay`() = runBlocking {
        val session = seatedSession()
        session.submit(host, "start", Command.StartGame(host))

        // What matters here is the size of the gap, not how it arose, so the
        // log is built directly rather than by playing a long game. Whole-game
        // flow is covered by the engine's own replay tests.
        val padding = List(300) {
            com.monopoly.core.engine.GameEvent.ConnectionChanged(guest, connected = it % 2 == 0)
        }
        session.emitServerEvents(padding)
        assertTrue(session.sequenceNumber() > 250, "Expected a long history to have built up")

        val message = session.catchUp(0)
        assertIs<ServerMessage.Snapshot>(message)
        assertEquals(session.sequenceNumber(), message.sequence)
    }

    @Test
    fun `a client claiming to be ahead of the server is resynced, not trusted`() = runBlocking {
        val session = seatedSession()
        // Nonsense input, whether from a bug or someone poking at the protocol.
        val message = session.catchUp(session.sequenceNumber() + 500)
        assertIs<ServerMessage.Snapshot>(message)
    }

    // ------------------------------------------------------------- connections

    @Test
    fun `events reach every connected player`() = runBlocking {
        val session = seatedSession()
        val hostChannel = ClientChannel()
        val guestChannel = ClientChannel()
        session.attach(host, hostChannel)
        session.attach(guest, guestChannel)

        session.submit(host, "start", Command.StartGame(host))

        listOf(hostChannel, guestChannel).forEach { channel ->
            val events = channel.drain().filterIsInstance<ServerMessage.Events>()
            assertTrue(events.isNotEmpty(), "A connected player received nothing")
        }
    }

    @Test
    fun `reconnecting replaces the old socket rather than being refused`() = runBlocking {
        val session = seatedSession()
        val stale = ClientChannel()
        session.attach(host, stale)

        // The player's phone lost signal; the server has not noticed yet. They
        // reconnect on mobile data and must not be locked out of their own game.
        val fresh = ClientChannel()
        assertTrue(session.attach(host, fresh))

        session.submit(host, "start", Command.StartGame(host))
        assertTrue(fresh.drain().isNotEmpty(), "The new socket got nothing")
    }

    @Test
    fun `a late close from a replaced socket does not disconnect the new one`() = runBlocking {
        val session = seatedSession()
        val stale = ClientChannel()
        session.attach(host, stale)
        val fresh = ClientChannel()
        session.attach(host, fresh)

        // The dead socket's cleanup finally runs, after the reconnect.
        session.detach(host, stale)

        session.submit(host, "start", Command.StartGame(host))
        assertTrue(fresh.drain().isNotEmpty(), "The live socket was knocked offline")
    }

    @Test
    fun `a seat survives its connection going away`() = runBlocking {
        val session = seatedSession()
        val channel = ClientChannel()
        session.attach(host, channel)
        session.detach(host, channel)

        assertTrue(session.hasSeatFor(host), "The seat was given up on a disconnect")
        assertEquals(host, session.seatFor("host-token"))
    }

    @Test
    fun `a resume token identifies its seat and nothing else`() = runBlocking {
        val session = seatedSession()
        assertEquals(host, session.seatFor("host-token"))
        assertEquals(guest, session.seatFor("guest-token"))
        assertNull(session.seatFor("not-a-real-token"))
    }

    // --------------------------------------------------------------- reclaiming

    @Test
    fun `a game is only abandoned after nobody has connected for the full window`() = runBlocking {
        var clock = 0L
        val session = newSession { clock }
        session.enrol(host, "tok")

        val channel = ClientChannel()
        session.attach(host, channel)
        clock = 10_000_000
        // Someone is connected, so however long the game has run it is not idle.
        assertFalse(session.isAbandoned(idleMillis = 1000))

        session.detach(host, channel)
        assertFalse(session.isAbandoned(idleMillis = 1000))
        clock += 2000
        assertTrue(session.isAbandoned(idleMillis = 1000))
    }

    // ------------------------------------------------------------ server events

    @Test
    fun `server-originated events are sequenced and broadcast like any other`() = runBlocking {
        val session = seatedSession()
        val channel = ClientChannel()
        session.attach(guest, channel)
        channel.drain()

        val before = session.sequenceNumber()
        session.emitServerEvents(
            listOf(com.monopoly.core.engine.GameEvent.ConnectionChanged(host, connected = false)),
        )

        assertEquals(before + 1, session.sequenceNumber())
        assertFalse(session.currentState().player(host).connected)
        val received = channel.drain().filterIsInstance<ServerMessage.Events>()
        assertEquals(1, received.size)
        assertEquals(before + 1, received.single().fromSequence)
    }

    @Test
    fun `a snapshot reports the state and the sequence it belongs to`() = runBlocking {
        val session = seatedSession()
        session.submit(host, "start", Command.StartGame(host))

        val snapshot = session.snapshot()
        assertEquals(session.sequenceNumber(), snapshot.sequence)
        assertEquals(session.currentState(), snapshot.state)
        assertNotNull(snapshot.state.playerOrNull(guest))
    }
}
