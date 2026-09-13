package com.monopoly.core

import com.monopoly.core.TestGames.accept
import com.monopoly.core.TestGames.reject
import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.engine.Seat
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lobby runs through the same command/event pipeline as the game, so that a
 * client which reconnects during setup recovers exactly as it would mid-game.
 */
class LobbyTest {

    private fun hostedLobby(rules: GameRules = GameRules.CLASSIC): GameState =
        GameFactory.newLobby(
            gameId = "lobby-test",
            host = Seat(TestGames.ALICE, "Alice", Token.TOP_HAT),
            rules = rules,
            seed = 1L,
        )

    @Test
    fun `a new lobby holds only the host, who sits in seat zero`() {
        val lobby = hostedLobby()
        assertEquals(1, lobby.players.size)
        assertEquals(TestGames.ALICE, lobby.players.first().id)
        assertEquals(GamePhase.Lobby, lobby.phase)
    }

    @Test
    fun `joining seats a player with the starting cash already dealt`() {
        val after = hostedLobby().accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        assertEquals(2, after.players.size)
        assertEquals(GameRules.CLASSIC.startingMoney, after.player(TestGames.BOB).money)
    }

    @Test
    fun `a retried join does not seat the same player twice`() {
        val once = hostedLobby().accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        // This is the case that matters on a flaky connection: the client sent
        // the join, never saw the reply, and sent it again.
        assertEquals(
            RejectionReason.ALREADY_JOINED,
            once.reject(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT)).reason,
        )
        assertEquals(2, once.players.size)
    }

    @Test
    fun `two players cannot share a token or a name`() {
        val lobby = hostedLobby().accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        assertEquals(
            RejectionReason.TOKEN_TAKEN,
            lobby.reject(Command.JoinGame(TestGames.CARA, "Cara", Token.BOOT)).reason,
        )
        assertEquals(
            RejectionReason.NAME_TAKEN,
            lobby.reject(Command.JoinGame(TestGames.CARA, "bob", Token.CAT)).reason,
        )
    }

    @Test
    fun `a full lobby turns players away`() {
        var lobby = hostedLobby(GameRules.CLASSIC.copy(maxPlayers = 2))
        lobby = lobby.accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        assertEquals(
            RejectionReason.GAME_FULL,
            lobby.reject(Command.JoinGame(TestGames.CARA, "Cara", Token.CAT)).reason,
        )
    }

    @Test
    fun `nobody can join once the game has started`() {
        val started = TestGames.started(playerCount = 2)
        assertEquals(
            RejectionReason.WRONG_PHASE,
            started.reject(Command.JoinGame(PlayerId("late"), "Late", Token.CAT)).reason,
        )
    }

    @Test
    fun `leaving frees the seat, and the next player inherits the host role`() {
        val lobby = hostedLobby().accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        val after = lobby.accept(Command.LeaveLobby(TestGames.ALICE))

        assertEquals(1, after.players.size)
        // The host is whoever holds seat zero, so Bob is now the host.
        assertEquals(TestGames.BOB, after.players.first().id)
        assertTrue(after.accept(Command.JoinGame(TestGames.CARA, "Cara", Token.CAT)).players.size == 2)
    }

    @Test
    fun `the last player in a lobby cannot leave`() {
        assertEquals(
            RejectionReason.LAST_PLAYER_CANNOT_LEAVE,
            hostedLobby().reject(Command.LeaveLobby(TestGames.ALICE)).reason,
        )
    }

    @Test
    fun `only the host can change the rules`() {
        val lobby = hostedLobby().accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        assertEquals(
            RejectionReason.NOT_HOST,
            lobby.reject(Command.SetRules(TestGames.BOB, GameRules.QUICK)).reason,
        )

        val after = lobby.accept(Command.SetRules(TestGames.ALICE, GameRules.QUICK))
        assertEquals(GameRules.QUICK, after.rules)
    }

    @Test
    fun `changing the starting cash re-deals everyone already seated`() {
        val lobby = hostedLobby().accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        // Otherwise whoever joined before the change would play a short stack.
        val richer = lobby.accept(
            Command.SetRules(TestGames.ALICE, GameRules.CLASSIC.copy(startingMoney = 2500)),
        )
        richer.players.forEach { assertEquals(2500, it.money, "${it.name} has the wrong stack") }

        val poorer = richer.accept(
            Command.SetRules(TestGames.ALICE, GameRules.CLASSIC.copy(startingMoney = 500)),
        )
        poorer.players.forEach { assertEquals(500, it.money, "${it.name} has the wrong stack") }
    }

    @Test
    fun `a token can be swapped for a free one but not a taken one`() {
        val lobby = hostedLobby().accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))

        val after = lobby.accept(Command.ChangeToken(TestGames.BOB, Token.CAR))
        assertEquals(Token.CAR, after.player(TestGames.BOB).token)

        assertEquals(
            RejectionReason.TOKEN_TAKEN,
            after.reject(Command.ChangeToken(TestGames.BOB, Token.TOP_HAT)).reason,
        )
    }

    @Test
    fun `rules cannot be narrowed below the number of players already seated`() {
        var lobby = hostedLobby()
        lobby = lobby.accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        lobby = lobby.accept(Command.JoinGame(TestGames.CARA, "Cara", Token.CAT))

        assertEquals(
            RejectionReason.GAME_FULL,
            lobby.reject(Command.SetRules(TestGames.ALICE, GameRules.CLASSIC.copy(maxPlayers = 2))).reason,
        )
    }

    @Test
    fun `a lobby filled by joining plays exactly like one built up front`() {
        var lobby = hostedLobby()
        lobby = lobby.accept(Command.JoinGame(TestGames.BOB, "Bob", Token.BOOT))
        val started = lobby.accept(Command.StartGame(TestGames.ALICE))

        assertEquals(GamePhase.AwaitingRoll, started.phase)
        assertEquals(2, started.players.size)
        started.players.forEach { assertEquals(GameRules.CLASSIC.startingMoney, it.money) }
    }
}
