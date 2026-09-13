package com.monopoly.core

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEngine
import com.monopoly.core.engine.GameEvent
import com.monopoly.core.engine.Outcome
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.engine.applyAll
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Dealing again with the same people.
 *
 * A rematch is a command on the existing game rather than a new game on purpose:
 * the code everyone joined with keeps working, so nobody has to be told a new
 * one and nobody who has wandered off to make tea gets left behind.
 */
class RematchTest {

    /** A finished game: Bob bankrupt, Alice holding property and the win. */
    private fun finished(): GameState {
        val state = TestGames.started(playerCount = 2)
        return state.copy(
            players = state.players.map { player ->
                when (player.id) {
                    TestGames.ALICE -> player.copy(money = 4_200, position = 24)
                    else -> player.copy(money = 0, bankrupt = true, position = 11)
                }
            },
            deeds = mapOf(
                1 to Deed(spaceIndex = 1, owner = TestGames.ALICE, houses = 3),
                3 to Deed(spaceIndex = 3, owner = TestGames.ALICE, mortgaged = true),
            ),
            phase = GamePhase.GameOver(TestGames.ALICE),
        )
    }

    private fun GameState.accept(command: Command): GameState {
        val outcome = GameEngine.reduce(this, command)
        return assertIs<Outcome.Accepted>(outcome, "expected $command to be accepted").state
    }

    private fun GameState.reject(command: Command): Outcome.Rejected =
        assertIs(GameEngine.reduce(this, command))

    @Test
    fun `a rematch puts everyone back in the lobby with a clean board`() {
        val after = finished().accept(Command.Rematch(TestGames.ALICE))

        assertIs<GamePhase.Lobby>(after.phase)
        assertTrue(after.deeds.isEmpty(), "the board is cleared")
        assertTrue(after.players.all { it.position == 0 }, "everyone starts on GO")
        assertEquals(
            listOf(after.rules.startingMoney, after.rules.startingMoney),
            after.players.map { it.money },
            "and with the same money as anybody else",
        )
    }

    @Test
    fun `the player who went bankrupt is dealt back in`() {
        val after = finished().accept(Command.Rematch(TestGames.ALICE))

        val bob = after.players.first { it.id == TestGames.BOB }
        assertTrue(!bob.bankrupt, "losing the last game is not a reason to miss the next")
        assertEquals(after.rules.startingMoney, bob.money)
    }

    @Test
    fun `the host may call a rematch even after going bankrupt themselves`() {
        // The host holds the first seat, and is just as likely to lose as
        // anyone. A host who cannot restart because they lost is a game nobody
        // can get out of.
        val state = finished().let { finished ->
            finished.copy(
                players = finished.players.map { it.copy(bankrupt = it.id == TestGames.ALICE) },
                phase = GamePhase.GameOver(TestGames.BOB),
            )
        }

        val after = state.accept(Command.Rematch(TestGames.ALICE))
        assertIs<GamePhase.Lobby>(after.phase)
    }

    @Test
    fun `the host is still the host after the seats are shuffled`() {
        // Starting a game shuffles players into turn order, so "the host is the
        // first seat" stops being true the moment play begins. Anything that
        // asks who the host is has to ask the state, not the seating.
        val lobby = TestGames.lobby(playerCount = 3)
        assertEquals(TestGames.ALICE, lobby.hostId)

        val started = lobby.accept(Command.StartGame(TestGames.ALICE))
        assertEquals(TestGames.ALICE, started.hostId, "the shuffle does not move the role")
    }

    @Test
    fun `a rematch does not quietly hand the role to whoever went first`() {
        val after = finished().accept(Command.Rematch(TestGames.ALICE))
        assertEquals(TestGames.ALICE, after.hostId)
    }

    @Test
    fun `only the host may call a rematch`() {
        assertEquals(
            RejectionReason.NOT_HOST,
            finished().reject(Command.Rematch(TestGames.BOB)).reason,
        )
    }

    @Test
    fun `a rematch cannot be called mid-game`() {
        assertEquals(
            RejectionReason.WRONG_PHASE,
            TestGames.started().reject(Command.Rematch(TestGames.ALICE)).reason,
        )
    }

    @Test
    fun `names and pieces are kept, because they are who people are`() {
        val before = finished()
        val after = before.accept(Command.Rematch(TestGames.ALICE))

        assertEquals(before.players.map { it.id }, after.players.map { it.id })
        assertEquals(before.players.map { it.name }, after.players.map { it.name })
        assertEquals(before.players.map { it.token }, after.players.map { it.token })
    }

    @Test
    fun `being away carries over, because it is about the socket not the game`() {
        val before = finished().let { state ->
            state.copy(players = state.players.map { it.copy(connected = it.id == TestGames.ALICE) })
        }
        val after = before.accept(Command.Rematch(TestGames.ALICE))

        assertEquals(
            before.players.associate { it.id to it.connected },
            after.players.associate { it.id to it.connected },
            "a player who is not there does not become present by being dealt in",
        )
    }

    @Test
    fun `the new game is not the same game over again`() {
        val before = finished()
        val after = before.accept(Command.Rematch(TestGames.ALICE))

        assertNotEquals(
            before.rngState,
            after.rngState,
            "a rematch dealt from the same seed would replay the same dice",
        )
    }

    @Test
    fun `a rematch replays from the event log like everything else`() {
        val before = finished()
        val outcome = assertIs<Outcome.Accepted>(
            GameEngine.reduce(before, Command.Rematch(TestGames.ALICE)),
        )

        // The whole point of an event rather than a fresh session: a client
        // that folds the stream arrives at the new game without being handed
        // a state it has to take on trust.
        assertTrue(outcome.events.any { it is GameEvent.GameRestarted })
        assertEquals(
            outcome.state,
            before.applyAll(outcome.events),
            "folding the events gives the same board the engine computed",
        )
    }

    @Test
    fun `the rematch can then be started like any other game`() {
        val lobby = finished().accept(Command.Rematch(TestGames.ALICE))
        val playing = lobby.accept(Command.StartGame(TestGames.ALICE))

        assertIs<GamePhase.AwaitingRoll>(playing.phase)
        assertEquals(2, playing.players.size)
    }
}
