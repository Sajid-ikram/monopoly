package com.monopoly.core

import com.monopoly.core.TestGames.accept
import com.monopoly.core.TestGames.reject
import com.monopoly.core.engine.Command
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.TradeBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TradeTest {

    /** A started game, with whoever holds the turn named for convenience. */
    private fun game(playerCount: Int = 3): Triple<GameState, PlayerId, PlayerId> {
        val state = TestGames.started(playerCount)
        val current = state.currentPlayer.id
        val other = state.players.first { it.id != current }.id
        return Triple(state, current, other)
    }

    // --------------------------------------------------------------- proposing

    @Test
    fun `a proposal pauses the game and remembers where to go back to`() {
        val (state, current, other) = game()
        val before = state.phase

        val after = state.accept(
            Command.ProposeTrade(current, other, TradeBundle(cash = 100), TradeBundle.NOTHING),
        )
        val phase = after.phase
        assertIs<GamePhase.AwaitingTradeResponse>(phase)
        assertEquals(current, phase.offer.from)
        assertEquals(other, phase.offer.to)
        assertEquals(before, phase.resumePhase)
    }

    @Test
    fun `rejecting puts play back exactly where it was`() {
        val (state, current, other) = game()
        val before = state.phase
        val offered = state.accept(
            Command.ProposeTrade(current, other, TradeBundle(cash = 100), TradeBundle.NOTHING),
        )

        val after = offered.accept(Command.RejectTrade(other))
        assertEquals(before, after.phase)
        // And nothing moved.
        assertEquals(state.player(current).money, after.player(current).money)
        assertEquals(state.player(other).money, after.player(other).money)
    }

    @Test
    fun `the proposer can withdraw their own offer`() {
        val (state, current, other) = game()
        val offered = state.accept(
            Command.ProposeTrade(current, other, TradeBundle(cash = 100), TradeBundle.NOTHING),
        )
        assertEquals(state.phase, offered.accept(Command.RejectTrade(current)).phase)
    }

    @Test
    fun `an uninvolved player cannot answer someone else's trade`() {
        val (state, current, other) = game(playerCount = 3)
        val bystander = state.players.first { it.id != current && it.id != other }.id
        val offered = state.accept(
            Command.ProposeTrade(current, other, TradeBundle(cash = 100), TradeBundle.NOTHING),
        )

        assertEquals(
            RejectionReason.NOT_TRADE_RECIPIENT,
            offered.reject(Command.AcceptTrade(bystander)).reason,
        )
        assertEquals(
            RejectionReason.NOT_TRADE_RECIPIENT,
            offered.reject(Command.RejectTrade(bystander)).reason,
        )
    }

    @Test
    fun `you cannot trade with yourself`() {
        val (state, current, _) = game()
        assertEquals(
            RejectionReason.CANNOT_TRADE_WITH_YOURSELF,
            state.reject(
                Command.ProposeTrade(current, current, TradeBundle(cash = 1), TradeBundle.NOTHING),
            ).reason,
        )
    }

    @Test
    fun `an empty trade is refused`() {
        val (state, current, other) = game()
        assertEquals(
            RejectionReason.EMPTY_TRADE,
            state.reject(
                Command.ProposeTrade(current, other, TradeBundle.NOTHING, TradeBundle.NOTHING),
            ).reason,
        )
    }

    @Test
    fun `only the player whose turn it is may open a trade`() {
        val (state, current, other) = game()
        assertEquals(
            RejectionReason.WRONG_PHASE,
            state.reject(
                Command.ProposeTrade(other, current, TradeBundle(cash = 50), TradeBundle.NOTHING),
            ).reason,
        )
    }

    @Test
    fun `trading is not allowed during an auction`() {
        val (state, current, other) = game()
        val auction = state.copy(phase = GamePhase.AwaitingPurchase(39))
            .accept(Command.DeclineProperty(current))
        assertIs<GamePhase.Auction>(auction.phase)

        assertEquals(
            RejectionReason.WRONG_PHASE,
            auction.reject(
                Command.ProposeTrade(current, other, TradeBundle(cash = 50), TradeBundle.NOTHING),
            ).reason,
        )
    }

    // -------------------------------------------------------------- exchanging

    @Test
    fun `an accepted trade moves cash and deeds both ways`() {
        val (base, current, other) = game()
        val state = base.copy(
            deeds = mapOf(
                39 to Deed(39, current),
                1 to Deed(1, other),
            ),
        )

        val offered = state.accept(
            Command.ProposeTrade(
                actor = current,
                recipient = other,
                offered = TradeBundle(cash = 200, spaces = listOf(39)),
                requested = TradeBundle(cash = 50, spaces = listOf(1)),
            ),
        )
        val after = offered.accept(Command.AcceptTrade(other))

        assertEquals(other, after.ownerOf(39))
        assertEquals(current, after.ownerOf(1))
        assertEquals(state.player(current).money - 200 + 50, after.player(current).money)
        assertEquals(state.player(other).money + 200 - 50, after.player(other).money)
        assertEquals(base.phase, after.phase)
    }

    @Test
    fun `a mortgaged property stays mortgaged when it changes hands`() {
        val (base, current, other) = game()
        val state = base.copy(deeds = mapOf(39 to Deed(39, current, mortgaged = true)))

        val after = state
            .accept(
                Command.ProposeTrade(
                    current, other,
                    TradeBundle(spaces = listOf(39)),
                    TradeBundle(cash = 10),
                ),
            )
            .accept(Command.AcceptTrade(other))

        assertEquals(other, after.ownerOf(39))
        assertTrue(after.deeds.getValue(39).mortgaged, "The mortgage should travel with the deed")
    }

    @Test
    fun `jail cards can be traded`() {
        val (base, current, other) = game()
        val cardId = "ch_jail_free"
        val state = base.copy(
            chanceDeck = base.chanceDeck.remove(cardId),
            players = base.players.map {
                if (it.id == current) it.copy(getOutOfJailCards = listOf(cardId)) else it
            },
        )

        val after = state
            .accept(
                Command.ProposeTrade(
                    current, other,
                    TradeBundle(jailCards = listOf(cardId)),
                    TradeBundle(cash = 50),
                ),
            )
            .accept(Command.AcceptTrade(other))

        assertTrue(after.player(current).getOutOfJailCards.isEmpty())
        assertEquals(listOf(cardId), after.player(other).getOutOfJailCards)
    }

    @Test
    fun `you cannot offer what you do not own`() {
        val (base, current, other) = game()
        val state = base.copy(deeds = mapOf(39 to Deed(39, other)))
        assertEquals(
            RejectionReason.TRADE_ASSET_UNAVAILABLE,
            state.reject(
                Command.ProposeTrade(
                    current, other,
                    TradeBundle(spaces = listOf(39)),
                    TradeBundle(cash = 1),
                ),
            ).reason,
        )
    }

    @Test
    fun `you cannot ask for what the other player does not own`() {
        val (state, current, other) = game()
        assertEquals(
            RejectionReason.TRADE_ASSET_UNAVAILABLE,
            state.reject(
                Command.ProposeTrade(
                    current, other,
                    TradeBundle(cash = 1),
                    TradeBundle(spaces = listOf(39)),
                ),
            ).reason,
        )
    }

    @Test
    fun `you cannot promise cash you do not have`() {
        val (base, current, other) = game()
        val state = base.copy(
            players = base.players.map { if (it.id == current) it.copy(money = 10) else it },
        )
        assertEquals(
            RejectionReason.TRADE_CASH_UNAVAILABLE,
            state.reject(
                Command.ProposeTrade(current, other, TradeBundle(cash = 500), TradeBundle.NOTHING),
            ).reason,
        )
    }

    @Test
    fun `a property in a developed group cannot be traded`() {
        val (base, current, other) = game()
        // The brown group, with a house on the other street of the pair.
        val state = base.copy(
            deeds = mapOf(
                1 to Deed(1, current),
                3 to Deed(3, current, houses = 1),
            ),
        )
        // Mediterranean itself has no buildings, but Baltic does, and buildings
        // belong to the group rather than the street.
        assertEquals(
            RejectionReason.MUST_SELL_BUILDINGS_FIRST,
            state.reject(
                Command.ProposeTrade(
                    current, other,
                    TradeBundle(spaces = listOf(1)),
                    TradeBundle(cash = 1),
                ),
            ).reason,
        )
    }

    @Test
    fun `the deal is rechecked at acceptance, not just at proposal`() {
        val (base, current, other) = game()
        val state = base.copy(deeds = mapOf(39 to Deed(39, current)))

        val offered = state.accept(
            Command.ProposeTrade(
                current, other,
                TradeBundle(cash = 1400),
                TradeBundle(cash = 10),
            ),
        )
        // Between proposing and answering, the proposer spends the money they
        // promised. Accepting must not conjure it back into existence.
        val poorer = offered.copy(
            players = offered.players.map { if (it.id == current) it.copy(money = 5) else it },
        )
        assertEquals(
            RejectionReason.TRADE_CASH_UNAVAILABLE,
            poorer.reject(Command.AcceptTrade(other)).reason,
        )
    }

    // ---------------------------------------------------------------- countering

    @Test
    fun `a counter replaces the offer and turns it around`() {
        val (state, current, other) = game()
        val offered = state.accept(
            Command.ProposeTrade(current, other, TradeBundle(cash = 100), TradeBundle.NOTHING),
        )

        val countered = offered.accept(
            Command.CounterTrade(other, TradeBundle.NOTHING, TradeBundle(cash = 300)),
        )
        val phase = countered.phase
        assertIs<GamePhase.AwaitingTradeResponse>(phase)
        assertEquals(other, phase.offer.from)
        assertEquals(current, phase.offer.to)
        assertEquals(300, phase.offer.requested.cash)
        // Still returns to the same place once it is settled.
        assertEquals(state.phase, phase.resumePhase)
    }

    @Test
    fun `only the recipient may counter`() {
        val (state, current, other) = game()
        val offered = state.accept(
            Command.ProposeTrade(current, other, TradeBundle(cash = 100), TradeBundle.NOTHING),
        )
        assertEquals(
            RejectionReason.NOT_TRADE_RECIPIENT,
            offered.reject(
                Command.CounterTrade(current, TradeBundle(cash = 5), TradeBundle.NOTHING),
            ).reason,
        )
    }

    @Test
    fun `countering then accepting settles the countered deal`() {
        val (base, current, other) = game()
        val state = base.copy(deeds = mapOf(39 to Deed(39, other)))

        val after = state
            .accept(
                Command.ProposeTrade(
                    current, other,
                    TradeBundle(cash = 100),
                    TradeBundle(spaces = listOf(39)),
                ),
            )
            .accept(
                Command.CounterTrade(
                    other,
                    TradeBundle(spaces = listOf(39)),
                    TradeBundle(cash = 400),
                ),
            )
            .accept(Command.AcceptTrade(current))

        assertEquals(current, after.ownerOf(39))
        assertEquals(state.player(current).money - 400, after.player(current).money)
    }

    // -------------------------------------------------------------------- debts

    @Test
    fun `a player facing an unpayable debt may still trade their way out`() {
        val (base, current, _) = game(playerCount = 2)
        val creditor = base.players.first { it.id != current }.id
        val indebted = base.copy(
            phase = GamePhase.AwaitingDebtSettlement(current, creditor, 400),
            turn = base.turn.copy(hasRolled = true),
            deeds = mapOf(39 to Deed(39, current)),
            players = base.players.map { if (it.id == current) it.copy(money = 0) else it },
        )

        // Selling Boardwalk to the creditor for enough to cover the debt.
        val traded = indebted
            .accept(
                Command.ProposeTrade(
                    current, creditor,
                    TradeBundle(spaces = listOf(39)),
                    TradeBundle(cash = 500),
                ),
            )
            .accept(Command.AcceptTrade(creditor))

        assertEquals(500, traded.player(current).money)
        assertEquals(creditor, traded.ownerOf(39))
        // And the debt is still there, waiting, now payable.
        val phase = traded.phase
        assertIs<GamePhase.AwaitingDebtSettlement>(phase)

        val settled = traded.accept(Command.SettleDebt(current))
        assertEquals(100, settled.player(current).money)
        assertTrue(!settled.player(current).bankrupt)
    }
}
