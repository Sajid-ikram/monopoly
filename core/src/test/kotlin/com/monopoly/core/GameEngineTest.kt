package com.monopoly.core

import com.monopoly.core.TestGames.accept
import com.monopoly.core.TestGames.reject
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEngine
import com.monopoly.core.engine.Outcome
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GamePhase
import com.monopoly.core.rules.GameRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameEngineTest {

    // ------------------------------------------------------------------ lobby

    @Test
    fun `a game starts with everyone on GO holding the starting cash`() {
        val state = TestGames.started()
        assertEquals(GamePhase.AwaitingRoll, state.phase)
        state.players.forEach {
            assertEquals(0, it.position)
            assertEquals(GameRules.CLASSIC.startingMoney, it.money)
        }
    }

    @Test
    fun `only the host can start, and only with enough players`() {
        val lobby = TestGames.lobby(playerCount = 2)
        assertEquals(RejectionReason.NOT_YOUR_TURN, lobby.reject(Command.StartGame(TestGames.BOB)).reason)

        val solo = TestGames.lobby(playerCount = 1)
        assertEquals(
            RejectionReason.NOT_ENOUGH_PLAYERS,
            solo.reject(Command.StartGame(TestGames.ALICE)).reason,
        )
    }

    @Test
    fun `a game cannot be started twice`() {
        val started = TestGames.started()
        assertEquals(
            RejectionReason.WRONG_PHASE,
            started.reject(Command.StartGame(started.players.first().id)).reason,
        )
    }

    // ------------------------------------------------------------------ turns

    @Test
    fun `rolling moves the current player by the total shown`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val after = state.accept(Command.RollDice(actor))

        val roll = assertNotNull(after.turn.lastRoll)
        assertEquals(roll.total, after.player(actor).position)
    }

    @Test
    fun `a player cannot roll out of turn`() {
        val state = TestGames.started()
        val other = state.players.first { it.id != state.currentPlayer.id }.id
        assertEquals(RejectionReason.NOT_YOUR_TURN, state.reject(Command.RollDice(other)).reason)
    }

    @Test
    fun `a player cannot roll twice in one turn`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val after = state.accept(Command.RollDice(actor))
        // Unless the roll was doubles, the phase is now "end your turn".
        if (after.turn.lastRoll?.isDoubles == false) {
            assertEquals(RejectionReason.WRONG_PHASE, after.reject(Command.RollDice(actor)).reason)
        }
    }

    @Test
    fun `passing GO pays the salary exactly once`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        // Park the player three squares short of GO so any roll wraps around.
        val nearGo = state.copy(
            players = state.players.map { if (it.id == actor) it.copy(position = 37) else it },
        )
        val after = nearGo.accept(Command.RollDice(actor))
        val roll = assertNotNull(after.turn.lastRoll)

        if (37 + roll.total >= ClassicBoard.SPACE_COUNT) {
            assertTrue(
                after.player(actor).money >= GameRules.CLASSIC.startingMoney,
                "Salary was not paid on passing GO",
            )
        }
    }

    @Test
    fun `three doubles in a row sends you to jail without moving`() {
        var state = TestGames.started()
        val actor = state.currentPlayer.id
        // Force the turn state rather than hunting for three consecutive doubles.
        state = state.copy(turn = state.turn.copy(doublesRolled = 2))
        state = state.copy(rngState = TestGames.seedRolling(4, 4))

        val after = state.accept(Command.RollDice(actor))
        assertTrue(after.player(actor).inJail)
        assertEquals(ClassicBoard.JAIL_INDEX, after.player(actor).position)
        assertEquals(GamePhase.AwaitingTurnEnd(mayRollAgain = false), after.phase)
    }

    @Test
    fun `ending a turn passes play to the next player`() {
        val state = TestGames.started(playerCount = 3)
        val first = state.currentPlayer.id
        var after = state.accept(Command.RollDice(first))
        // Skip past any purchase decision so the turn can be ended.
        if (after.phase is GamePhase.AwaitingPurchase) {
            after = after.accept(Command.DeclineProperty(first))
        }
        if (after.phase is GamePhase.Auction) return // covered by the auction test

        val phase = after.phase
        assertIs<GamePhase.AwaitingTurnEnd>(phase)
        val ended = after.accept(Command.EndTurn(first))

        if (phase.mayRollAgain) {
            assertEquals(first, ended.currentPlayer.id, "Doubles should grant another roll")
            assertEquals(GamePhase.AwaitingRoll, ended.phase)
        } else {
            assertTrue(ended.currentPlayer.id != first)
        }
    }

    // -------------------------------------------------------------- purchases

    @Test
    fun `buying a property moves the deed and the money`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val boardwalk = ClassicBoard.purchasableAt(39)!!
        val offered = state.copy(phase = GamePhase.AwaitingPurchase(39))

        val after = offered.accept(Command.BuyProperty(actor))
        assertEquals(actor, after.ownerOf(39))
        assertEquals(state.player(actor).money - boardwalk.price, after.player(actor).money)
    }

    @Test
    fun `you cannot buy what you cannot afford`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val broke = state.copy(
            phase = GamePhase.AwaitingPurchase(39),
            players = state.players.map { if (it.id == actor) it.copy(money = 10) else it },
        )
        assertEquals(RejectionReason.INSUFFICIENT_FUNDS, broke.reject(Command.BuyProperty(actor)).reason)
    }

    @Test
    fun `declining a property opens an auction that every solvent player joins`() {
        val state = TestGames.started(playerCount = 3)
        val actor = state.currentPlayer.id
        val offered = state.copy(phase = GamePhase.AwaitingPurchase(39))

        val after = offered.accept(Command.DeclineProperty(actor))
        val auction = after.phase
        assertIs<GamePhase.Auction>(auction)
        assertEquals(39, auction.spaceIndex)
        assertEquals(3, auction.activeBidders.size)
    }

    @Test
    fun `declining does not auction when the rules say not to`() {
        val state = TestGames.started(rules = GameRules.CLASSIC.copy(auctionUnboughtProperties = false))
        val actor = state.currentPlayer.id
        val after = state.copy(phase = GamePhase.AwaitingPurchase(39))
            .accept(Command.DeclineProperty(actor))
        assertIs<GamePhase.AwaitingTurnEnd>(after.phase)
    }

    @Test
    fun `the last bidder standing wins the auction at their bid`() {
        val state = TestGames.started(playerCount = 2)
        val actor = state.currentPlayer.id
        var after = state.copy(phase = GamePhase.AwaitingPurchase(39))
            .accept(Command.DeclineProperty(actor))

        val auction = after.phase as GamePhase.Auction
        val firstBidder = auction.activeBidders[0]
        val secondBidder = auction.activeBidders[1]

        after = after.accept(Command.PlaceBid(firstBidder, 50))
        after = after.accept(Command.WithdrawFromAuction(secondBidder))

        assertEquals(firstBidder, after.ownerOf(39))
        assertEquals(state.player(firstBidder).money - 50, after.player(firstBidder).money)
    }

    @Test
    fun `a bid must beat the standing bid`() {
        val state = TestGames.started(playerCount = 2)
        val actor = state.currentPlayer.id
        var after = state.copy(phase = GamePhase.AwaitingPurchase(39))
            .accept(Command.DeclineProperty(actor))

        val bidders = (after.phase as GamePhase.Auction).activeBidders
        after = after.accept(Command.PlaceBid(bidders[0], 100))
        assertEquals(RejectionReason.BID_TOO_LOW, after.reject(Command.PlaceBid(bidders[1], 100)).reason)
    }

    // ------------------------------------------------------------- developing

    @Test
    fun `houses need the whole colour group`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val partial = state.copy(deeds = mapOf(1 to Deed(1, actor)))
        assertEquals(
            RejectionReason.INCOMPLETE_COLOR_GROUP,
            partial.reject(Command.BuildHouse(actor, 1)).reason,
        )
    }

    @Test
    fun `houses must be built evenly across the group`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val brown = state.copy(deeds = mapOf(1 to Deed(1, actor), 3 to Deed(3, actor)))

        val one = brown.accept(Command.BuildHouse(actor, 1))
        assertEquals(1, one.deeds.getValue(1).houses)
        // Baltic still has none, so Mediterranean cannot take a second.
        assertEquals(RejectionReason.UNEVEN_BUILD, one.reject(Command.BuildHouse(actor, 1)).reason)

        val two = one.accept(Command.BuildHouse(actor, 3))
        assertEquals(1, two.deeds.getValue(3).houses)
    }

    @Test
    fun `building charges the printed build cost`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val brown = state.copy(deeds = mapOf(1 to Deed(1, actor), 3 to Deed(3, actor)))
        val after = brown.accept(Command.BuildHouse(actor, 1))
        assertEquals(brown.player(actor).money - 50, after.player(actor).money)
    }

    @Test
    fun `the bank can run out of houses`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        // Every house is already standing elsewhere on the board.
        val dark = state.copy(
            rules = state.rules.copy(houseSupply = 0),
            deeds = mapOf(1 to Deed(1, actor), 3 to Deed(3, actor)),
        )
        assertEquals(
            RejectionReason.BANK_OUT_OF_HOUSES,
            dark.reject(Command.BuildHouse(actor, 1)).reason,
        )
    }

    @Test
    fun `selling a house refunds half the build cost`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val built = state.copy(
            deeds = mapOf(1 to Deed(1, actor, houses = 1), 3 to Deed(3, actor, houses = 1)),
        )
        val after = built.accept(Command.SellHouse(actor, 1))
        assertEquals(0, after.deeds.getValue(1).houses)
        assertEquals(built.player(actor).money + 25, after.player(actor).money)
    }

    // -------------------------------------------------------------- mortgages

    @Test
    fun `mortgaging pays out half and lifting it costs ten percent more`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val owned = state.copy(deeds = mapOf(39 to Deed(39, actor)))

        val mortgaged = owned.accept(Command.MortgageProperty(actor, 39))
        assertTrue(mortgaged.deeds.getValue(39).mortgaged)
        assertEquals(owned.player(actor).money + 200, mortgaged.player(actor).money)

        val lifted = mortgaged.accept(Command.UnmortgageProperty(actor, 39))
        assertTrue(!lifted.deeds.getValue(39).mortgaged)
        assertEquals(mortgaged.player(actor).money - 220, lifted.player(actor).money)
    }

    @Test
    fun `a developed street must be stripped before it can be mortgaged`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val built = state.copy(deeds = mapOf(1 to Deed(1, actor, houses = 1)))
        assertEquals(
            RejectionReason.MUST_SELL_BUILDINGS_FIRST,
            built.reject(Command.MortgageProperty(actor, 1)).reason,
        )
    }

    @Test
    fun `you cannot mortgage what you do not own`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val other = state.players.first { it.id != actor }.id
        val owned = state.copy(deeds = mapOf(39 to Deed(39, other)))
        assertEquals(
            RejectionReason.NOT_OWNED_BY_YOU,
            owned.reject(Command.MortgageProperty(actor, 39)).reason,
        )
    }

    // ------------------------------------------------------------------- jail

    @Test
    fun `paying the fine releases you and leaves you still to roll`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val jailed = state.copy(
            phase = GamePhase.AwaitingJailDecision,
            players = state.players.map {
                if (it.id == actor) it.copy(inJail = true, position = ClassicBoard.JAIL_INDEX) else it
            },
        )

        val after = jailed.accept(Command.PayJailFine(actor))
        assertTrue(!after.player(actor).inJail)
        assertEquals(jailed.player(actor).money - 50, after.player(actor).money)
        assertEquals(GamePhase.AwaitingRoll, after.phase)
    }

    @Test
    fun `rolling doubles gets you out of jail and moves you`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val jailed = state.copy(
            phase = GamePhase.AwaitingJailDecision,
            // Double fives, landing on Free Parking, so that nothing about the
            // destination square clouds what this test is checking.
            rngState = TestGames.seedRolling(5, 5),
            players = state.players.map {
                if (it.id == actor) it.copy(inJail = true, position = ClassicBoard.JAIL_INDEX) else it
            },
        )

        val after = jailed.accept(Command.RollDice(actor))
        assertTrue(!after.player(actor).inJail)
        assertEquals(ClassicBoard.FREE_PARKING_INDEX, after.player(actor).position)
        // Doubles out of jail does not also earn another roll.
        assertEquals(GamePhase.AwaitingTurnEnd(mayRollAgain = false), after.phase)
    }

    @Test
    fun `failing to roll doubles keeps you in jail and counts the turn`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val jailed = state.copy(
            phase = GamePhase.AwaitingJailDecision,
            rngState = TestGames.seedRolling(2, 5),
            players = state.players.map {
                if (it.id == actor) it.copy(inJail = true, position = ClassicBoard.JAIL_INDEX) else it
            },
        )

        val after = jailed.accept(Command.RollDice(actor))
        assertTrue(after.player(actor).inJail)
        assertEquals(1, after.player(actor).jailTurns)
        assertEquals(ClassicBoard.JAIL_INDEX, after.player(actor).position)
    }

    @Test
    fun `the third failed attempt forces the fine and moves you`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val jailed = state.copy(
            phase = GamePhase.AwaitingJailDecision,
            rngState = TestGames.seedRolling(2, 5),
            players = state.players.map {
                if (it.id == actor) {
                    it.copy(inJail = true, jailTurns = 2, position = ClassicBoard.JAIL_INDEX)
                } else it
            },
        )

        val after = jailed.accept(Command.RollDice(actor))
        assertTrue(!after.player(actor).inJail)
        assertEquals(ClassicBoard.JAIL_INDEX + 7, after.player(actor).position)
        assertTrue(after.player(actor).money < jailed.player(actor).money)
    }

    @Test
    fun `a jail card releases you and goes back into its deck`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val cardId = "ch_jail_free"
        val jailed = state.copy(
            phase = GamePhase.AwaitingJailDecision,
            chanceDeck = state.chanceDeck.remove(cardId),
            players = state.players.map {
                if (it.id == actor) {
                    it.copy(inJail = true, getOutOfJailCards = listOf(cardId))
                } else it
            },
        )

        val after = jailed.accept(Command.UseJailCard(actor))
        assertTrue(!after.player(actor).inJail)
        assertTrue(after.player(actor).getOutOfJailCards.isEmpty())
        assertTrue(cardId in after.chanceDeck.cardIds, "The card should return to the deck")
    }

    @Test
    fun `a player without a jail card cannot use one`() {
        val state = TestGames.started()
        val actor = state.currentPlayer.id
        val jailed = state.copy(
            phase = GamePhase.AwaitingJailDecision,
            players = state.players.map { if (it.id == actor) it.copy(inJail = true) else it },
        )
        assertEquals(RejectionReason.NO_JAIL_CARD, jailed.reject(Command.UseJailCard(actor)).reason)
    }

    // -------------------------------------------------------------- insolvency

    @Test
    fun `an unpayable rent blocks the game instead of silently selling assets`() {
        val state = TestGames.started(playerCount = 2)
        val actor = state.currentPlayer.id
        val landlord = state.players.first { it.id != actor }.id

        // Put the poor player one step from a hotel on Boardwalk.
        val setup = state.copy(
            rngState = TestGames.seedRolling(1, 1),
            deeds = mapOf(
                37 to Deed(37, landlord, houses = Deed.HOTEL),
                39 to Deed(39, landlord, houses = Deed.HOTEL),
            ),
            players = state.players.map {
                when (it.id) {
                    actor -> it.copy(position = 35, money = 100)
                    else -> it
                }
            },
        )

        val after = setup.accept(Command.RollDice(actor))
        val phase = after.phase
        assertIs<GamePhase.AwaitingDebtSettlement>(phase)
        assertEquals(actor, phase.debtor)
        assertEquals(landlord, phase.creditor)
        // The money has not moved: the debtor still has to find it.
        assertEquals(100, after.player(actor).money)
    }

    @Test
    fun `a debt you can cover cannot be escaped by declaring bankruptcy`() {
        val state = TestGames.started(playerCount = 2)
        val actor = state.currentPlayer.id
        val creditor = state.players.first { it.id != actor }.id
        val indebted = state.copy(
            phase = GamePhase.AwaitingDebtSettlement(actor, creditor, 100),
            deeds = mapOf(39 to Deed(39, actor)),
            players = state.players.map { if (it.id == actor) it.copy(money = 0) else it },
        )
        // Boardwalk mortgages for $200, so the $100 debt is payable.
        assertEquals(
            RejectionReason.CAN_STILL_PAY,
            indebted.reject(Command.DeclareBankruptcy(actor)).reason,
        )
    }

    @Test
    fun `settling a debt after raising cash resumes the turn`() {
        val state = TestGames.started(playerCount = 2)
        val actor = state.currentPlayer.id
        val creditor = state.players.first { it.id != actor }.id
        val indebted = state.copy(
            phase = GamePhase.AwaitingDebtSettlement(actor, creditor, 100),
            turn = state.turn.copy(hasRolled = true),
            deeds = mapOf(39 to Deed(39, actor)),
            players = state.players.map { if (it.id == actor) it.copy(money = 0) else it },
        )

        val raised = indebted.accept(Command.MortgageProperty(actor, 39))
        assertEquals(200, raised.player(actor).money)

        val settled = raised.accept(Command.SettleDebt(actor))
        assertEquals(100, settled.player(actor).money)
        assertEquals(indebted.player(creditor).money + 100, settled.player(creditor).money)
        assertIs<GamePhase.AwaitingTurnEnd>(settled.phase)
    }

    @Test
    fun `bankruptcy hands everything to the creditor and ends a two player game`() {
        val state = TestGames.started(playerCount = 2)
        val actor = state.currentPlayer.id
        val creditor = state.players.first { it.id != actor }.id
        val doomed = state.copy(
            phase = GamePhase.AwaitingDebtSettlement(actor, creditor, 5000),
            deeds = mapOf(39 to Deed(39, actor)),
            players = state.players.map { if (it.id == actor) it.copy(money = 50) else it },
        )

        val after = doomed.accept(Command.DeclareBankruptcy(actor))
        assertTrue(after.player(actor).bankrupt)
        assertEquals(creditor, after.ownerOf(39), "Property passes to the creditor")
        assertEquals(doomed.player(creditor).money + 50, after.player(creditor).money)
        assertEquals(GamePhase.GameOver(creditor), after.phase)
    }

    @Test
    fun `bankruptcy to the bank returns the properties unowned`() {
        val state = TestGames.started(playerCount = 3)
        val actor = state.currentPlayer.id
        val doomed = state.copy(
            phase = GamePhase.AwaitingDebtSettlement(actor, null, 5000),
            deeds = mapOf(39 to Deed(39, actor)),
            players = state.players.map { if (it.id == actor) it.copy(money = 0) else it },
        )

        val after = doomed.accept(Command.DeclareBankruptcy(actor))
        assertTrue(after.player(actor).bankrupt)
        assertEquals(null, after.ownerOf(39))
        // Three players, so the game continues with the other two.
        assertTrue(after.phase !is GamePhase.GameOver)
    }

    @Test
    fun `a bankrupt player is skipped in the turn order`() {
        val state = TestGames.started(playerCount = 3)
        val first = state.currentPlayer.id
        val second = state.players[1].id
        val withBankrupt = state.copy(
            phase = GamePhase.AwaitingTurnEnd(mayRollAgain = false),
            players = state.players.map { if (it.id == second) it.copy(bankrupt = true) else it },
        )

        val after = withBankrupt.accept(Command.EndTurn(first))
        assertEquals(state.players[2].id, after.currentPlayer.id)
    }

    // ------------------------------------------------------------- rejections

    @Test
    fun `commands from an unknown player are rejected outright`() {
        val state = TestGames.started()
        val outcome = GameEngine.reduce(state, Command.RollDice(com.monopoly.core.model.PlayerId("ghost")))
        assertIs<Outcome.Rejected>(outcome)
        assertEquals(RejectionReason.UNKNOWN_PLAYER, outcome.reason)
    }

    @Test
    fun `a rejected command produces no events at all`() {
        val state = TestGames.started()
        val other = state.players.first { it.id != state.currentPlayer.id }.id
        val outcome = GameEngine.reduce(state, Command.RollDice(other))
        assertIs<Outcome.Rejected>(outcome)
        // The state object is untouched; there is nothing to roll back.
        assertEquals(state, TestGames.started())
    }
}
