package com.monopoly.protocol

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEngine
import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.Outcome
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.engine.Seat
import com.monopoly.core.engine.applyAll
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Serialisation is part of the correctness story, not a detail: the client's
 * copy of the game arrives through this code. A field that silently fails to
 * encode would produce exactly the kind of drifting board this design exists to
 * prevent.
 */
class WireTest {

    private fun freshGame() = GameFactory.newGame(
        gameId = "wire-test",
        seats = listOf(
            Seat(PlayerId("alice"), "Alice", Token.TOP_HAT),
            Seat(PlayerId("bob"), "Bob", Token.BOOT),
        ),
        seed = 99L,
    )

    @Test
    fun `a game state survives a round trip unchanged`() {
        val state = freshGame()
        val encoded = MonopolyJson.encodeToString(state)
        val decoded = MonopolyJson.decodeFromString<com.monopoly.core.model.GameState>(encoded)
        assertEquals(state, decoded)
    }

    @Test
    fun `a state mid-game, with deeds and cards, survives a round trip`() {
        var state = freshGame()
        state = (GameEngine.reduce(state, Command.StartGame(PlayerId("alice"))) as Outcome.Accepted).state
        repeat(20) {
            val outcome = GameEngine.reduce(state, Command.RollDice(state.currentPlayer.id))
            if (outcome is Outcome.Accepted) state = outcome.state
            val end = GameEngine.reduce(state, Command.EndTurn(state.currentPlayer.id))
            if (end is Outcome.Accepted) state = end.state
        }

        val decoded = MonopolyJson.decodeFromString<com.monopoly.core.model.GameState>(
            MonopolyJson.encodeToString(state),
        )
        assertEquals(state, decoded)
    }

    @Test
    fun `every client message round trips`() {
        val messages = listOf(
            ClientMessage.Join(gameCode = "ABCD", displayName = "Alice", preferredToken = Token.CAT),
            ClientMessage.Join(gameCode = "ABCD", displayName = "Alice", resumeToken = "tok", lastSequence = 42),
            ClientMessage.Submit("cmd-1", Command.RollDice(PlayerId("alice"))),
            ClientMessage.Submit("cmd-2", Command.PlaceBid(PlayerId("bob"), 250)),
            ClientMessage.Ping(1_700_000_000_000L),
            ClientMessage.RequestSnapshot,
            ClientMessage.Leave,
        )
        messages.forEach { message ->
            assertEquals(message, decodeClientMessage(message.encode()), "Failed for $message")
        }
    }

    @Test
    fun `every server message round trips`() {
        val state = freshGame()
        val messages = listOf(
            ServerMessage.Welcome(
                playerId = PlayerId("alice"),
                resumeToken = "tok",
                state = state,
                sequence = 0,
            ),
            ServerMessage.Snapshot(state, sequence = 17),
            ServerMessage.CommandAccepted("cmd-1", firstSequence = 5, lastSequence = 9),
            ServerMessage.CommandRejected("cmd-2", RejectionReason.NOT_YOUR_TURN, null, currentSequence = 9),
            ServerMessage.Pong(1L, 2L),
            ServerMessage.PresenceChanged(PlayerId("bob"), connected = false),
            ServerMessage.Rejected(JoinFailure.GAME_FULL, "8 players already"),
        )
        messages.forEach { message ->
            assertEquals(message, decodeServerMessage(message.encode()), "Failed for $message")
        }
    }

    @Test
    fun `an event batch round trips and still replays correctly`() {
        val start = freshGame()
        val outcome = GameEngine.reduce(start, Command.StartGame(PlayerId("alice")))
        assertIs<Outcome.Accepted>(outcome)

        val batch = ServerMessage.Events(
            fromSequence = 1,
            events = outcome.events.mapIndexed { i, event -> SequencedEvent(i + 1L, event) },
        )

        val decoded = decodeServerMessage(batch.encode())
        assertIs<ServerMessage.Events>(decoded)
        assertEquals(batch, decoded)

        // The point of sending events rather than state: folding them here must
        // land on precisely what the server computed.
        val replayed = start.applyAll(decoded.events.map { it.event })
        assertEquals(outcome.state, replayed)
    }

    @Test
    fun `unknown fields from a newer server are ignored rather than fatal`() {
        // An old client must not break when a new server adds a field. Without
        // this, shipping any change would hard-break every installed build.
        val json = """{"type":"com.monopoly.protocol.ClientMessage.Ping",""" +
            """"clientSentAtMillis":5,"somethingNew":true}"""
        val decoded = decodeClientMessage(json)
        assertEquals(ClientMessage.Ping(5L), decoded)
    }

    @Test
    fun `the wire format names its message types readably`() {
        val encoded = ClientMessage.Ping(1L).encode()
        assertTrue(encoded.contains("\"type\""), "Expected a type discriminator in: $encoded")
    }
}
