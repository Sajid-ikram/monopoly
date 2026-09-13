package com.monopoly.server

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.Seat
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A seat nobody is sitting in must not be able to stop the game.
 *
 * The rule these all test is a narrow one on purpose: the game plays on only
 * when there is *nobody there*. A connected player thinking for five minutes is
 * a social problem and software should stay out of it; a dead battery is not,
 * and three other people should not have to abandon the game over it.
 */
class AbsentPlayerTest {

    private val host = PlayerId("host")
    private val guest = PlayerId("guest")

    private var clock = 1_000L

    private fun sessionOf(state: GameState): GameSession =
        GameSession("TEST", state, now = { clock })

    /** A started two-player game, with both seats enrolled and nobody attached. */
    private suspend fun startedGame(): GameSession {
        val lobby = GameFactory.newLobby(
            gameId = "TEST",
            host = Seat(host, "Host", Token.TOP_HAT),
            rules = GameRules.CLASSIC,
            seed = 7L,
        )
        val session = sessionOf(lobby)
        session.enrol(host, "host-token")
        session.submit(guest, "join", Command.JoinGame(guest, "Guest", Token.BOOT))
        session.enrol(guest, "guest-token")
        session.submit(host, "start", Command.StartGame(host))
        return session
    }

    private suspend fun GameSession.waitingFor(): PlayerId? {
        val state = currentState()
        return when (val phase = state.phase) {
            is GamePhase.Auction -> phase.currentBidder
            is GamePhase.AwaitingDebtSettlement -> phase.debtor
            is GamePhase.AwaitingTradeResponse -> phase.offer.to
            is GamePhase.Lobby, is GamePhase.GameOver -> null
            else -> state.players.getOrNull(state.currentPlayerIndex)?.id
        }
    }

    private fun away(seconds: Long) {
        clock += seconds * 1_000
    }

    private val grace = 60_000L

    @Test
    fun `an absent player is given time before the game moves on`() = runBlocking<Unit> {
        val session = startedGame()

        away(10)
        assertFalse(
            session.playForAbsentPlayer(grace),
            "a ten-second blip is a tunnel, not an abandonment",
        )
        assertIs<GamePhase.AwaitingRoll>(session.currentState().phase)
    }

    @Test
    fun `once the grace is up, the dice are rolled for them`() = runBlocking<Unit> {
        val session = startedGame()
        val waiting = session.waitingFor()

        away(90)
        assertTrue(session.playForAbsentPlayer(grace))

        val state = session.currentState()
        assertTrue(state.turn.hasRolled, "the turn actually moved")
        assertEquals(waiting, state.players[state.currentPlayerIndex].id, "still their turn")
    }

    @Test
    fun `a connected player is never played for, however long they take`() = runBlocking<Unit> {
        val session = startedGame()
        val waiting = checkNotNull(session.waitingFor())
        session.attach(waiting, ClientChannel())

        away(60 * 60)
        assertFalse(
            session.playForAbsentPlayer(grace),
            "somebody is there; thinking is not a fault to be corrected",
        )
        assertIs<GamePhase.AwaitingRoll>(session.currentState().phase)
    }

    @Test
    fun `coming back stops the clock`() = runBlocking<Unit> {
        val session = startedGame()
        val waiting = checkNotNull(session.waitingFor())

        away(50)
        session.attach(waiting, ClientChannel())
        away(50)

        assertFalse(
            session.playForAbsentPlayer(grace),
            "the grace starts again from when they returned",
        )
    }

    @Test
    fun `an absent player is never made to buy anything`() = runBlocking<Unit> {
        val session = startedGame()
        val waiting = checkNotNull(session.waitingFor())
        // Park them on Old Kent Road with the purchase decision pending.
        val state = session.currentState()
        val pending = sessionOf(
            state.copy(
                players = state.players.map { if (it.id == waiting) it.copy(position = 1) else it },
                phase = GamePhase.AwaitingPurchase(1),
            ),
        )
        pending.enrol(waiting, "token")
        val moneyBefore = pending.currentState().players.first { it.id == waiting }.money

        away(90)
        assertTrue(pending.playForAbsentPlayer(grace))

        val after = pending.currentState()
        assertEquals(
            moneyBefore,
            after.players.first { it.id == waiting }.money,
            "declining costs them nothing; buying would have spent their money",
        )
        assertTrue(after.deeds[1] == null, "and they own nothing they did not choose to own")
    }

    @Test
    fun `an absent player folds rather than bids`() = runBlocking<Unit> {
        val session = startedGame()
        val state = session.currentState()
        val bidders = state.players.map { it.id }
        val auction = sessionOf(
            state.copy(
                phase = GamePhase.Auction(spaceIndex = 1, activeBidders = bidders, turnIndex = 0),
            ),
        )
        bidders.forEach { auction.enrol(it, "token-${it.value}") }

        away(90)
        assertTrue(auction.playForAbsentPlayer(grace))

        val phase = auction.currentState().phase
        // One fold either leaves a shorter auction or ends it outright; either
        // way the thing that must not happen is it sitting there forever.
        assertTrue(
            phase !is GamePhase.Auction || phase.activeBidders.size < bidders.size,
            "the auction moved on, was: $phase",
        )
    }

    @Test
    fun `an absent player refuses a trade rather than accepting one`() = runBlocking<Unit> {
        val session = startedGame()
        val state = session.currentState()
        val proposer = state.players[state.currentPlayerIndex].id
        val recipient = state.players.first { it.id != proposer }.id

        val offered = com.monopoly.core.model.TradeBundle(cash = 1)
        val pending = sessionOf(
            state.copy(
                phase = GamePhase.AwaitingTradeResponse(
                    offer = com.monopoly.core.model.TradeOffer(
                        from = proposer,
                        to = recipient,
                        offered = offered,
                        requested = com.monopoly.core.model.TradeBundle.NOTHING,
                    ),
                    resumePhase = GamePhase.AwaitingTurnEnd(),
                ),
            ),
        )
        pending.enrol(recipient, "token")
        val before = pending.currentState().players.first { it.id == recipient }.money

        away(90)
        assertTrue(pending.playForAbsentPlayer(grace))

        assertEquals(
            before,
            pending.currentState().players.first { it.id == recipient }.money,
            "nothing changed hands on behalf of someone who was not there",
        )
    }

    @Test
    fun `a debt is raised by selling before anyone is declared bankrupt`() = runBlocking<Unit> {
        val session = startedGame()
        val state = session.currentState()
        val debtor = state.players[state.currentPlayerIndex].id

        // Broke, but holding a street worth mortgaging.
        val owing = sessionOf(
            state.copy(
                players = state.players.map { if (it.id == debtor) it.copy(money = 0) else it },
                deeds = mapOf(1 to Deed(spaceIndex = 1, owner = debtor)),
                phase = GamePhase.AwaitingDebtSettlement(debtor, creditor = null, amount = 20),
            ),
        )
        owing.enrol(debtor, "token")

        away(90)
        assertTrue(owing.playForAbsentPlayer(grace), "mortgages rather than folding")

        val after = owing.currentState()
        assertTrue(after.deeds.getValue(1).mortgaged, "the property was mortgaged")
        assertFalse(after.players.first { it.id == debtor }.bankrupt, "not bankrupted prematurely")
    }

    @Test
    fun `bankruptcy is the last resort, once there is nothing left to sell`() = runBlocking<Unit> {
        val session = startedGame()
        val state = session.currentState()
        val debtor = state.players[state.currentPlayerIndex].id

        val owing = sessionOf(
            state.copy(
                players = state.players.map { if (it.id == debtor) it.copy(money = 0) else it },
                phase = GamePhase.AwaitingDebtSettlement(debtor, creditor = null, amount = 500),
            ),
        )
        owing.enrol(debtor, "token")

        away(90)
        assertTrue(owing.playForAbsentPlayer(grace))
        assertTrue(
            owing.currentState().players.first { it.id == debtor }.bankrupt,
            "no assets, no way to pay: the only move left",
        )
    }

    @Test
    fun `a lobby is never played for`() = runBlocking<Unit> {
        val lobby = GameFactory.newLobby("TEST", Seat(host, "Host", Token.TOP_HAT), seed = 1L)
        val session = sessionOf(lobby)
        session.enrol(host, "host-token")

        away(60 * 60)
        assertFalse(
            session.playForAbsentPlayer(grace),
            "nobody is owed a turn before the game starts",
        )
    }

    @Test
    fun `an absent player's whole turn plays out over several ticks`() = runBlocking<Unit> {
        val session = startedGame()
        away(90)

        // A turn is several decisions, and each tick makes one of them, so the
        // other players can follow what is happening rather than watching the
        // board jump.
        var moves = 0
        repeat(20) { if (session.playForAbsentPlayer(grace)) moves++ }

        assertTrue(moves > 1, "more than one decision was made, was $moves")
        assertTrue(
            session.currentState().version > 0,
            "and the game is somewhere it could not have stayed",
        )
    }
}
