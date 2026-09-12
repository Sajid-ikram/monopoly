package com.monopoly.core

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEngine
import com.monopoly.core.engine.GameEvent
import com.monopoly.core.engine.Outcome
import com.monopoly.core.engine.applyAll
import com.monopoly.core.engine.applyEvent
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The load-bearing guarantee of the whole design.
 *
 * A client never computes the game; it receives events and folds them. If
 * folding the server's events ever produced a different state than the server
 * holds, players would see boards that quietly disagree — and they would find
 * out at the worst possible moment, when somebody is charged rent they do not
 * think they owe. These tests pin that down over whole games rather than
 * single moves.
 */
class ReplayTest {

    @Test
    fun `replaying a full game reproduces the server state exactly`() {
        repeat(25) { run ->
            val start = TestGames.lobby(playerCount = 3, seed = 1000L + run)
            val (final, events) = playOut(start, maxCommands = 400)

            val replayed = start.applyAll(events)
            assertEquals(final, replayed, "Replay diverged on run $run")
        }
    }

    @Test
    fun `replaying from a mid-game snapshot also converges`() {
        val start = TestGames.lobby(playerCount = 4, seed = 77L)
        val (_, events) = playOut(start, maxCommands = 300)
        assertTrue(events.size > 50, "Expected a substantial game, got ${events.size} events")

        // Simulate a player who dropped out halfway: take the state as of the
        // midpoint, then apply only the events they missed.
        val split = events.size / 2
        val snapshot = start.applyAll(events.take(split))
        val caughtUp = snapshot.applyAll(events.drop(split))

        assertEquals(start.applyAll(events), caughtUp)
    }

    @Test
    fun `the version counter advances by exactly one per event`() {
        val start = TestGames.lobby(playerCount = 2, seed = 5L)
        val (final, events) = playOut(start, maxCommands = 200)
        assertEquals(start.version + events.size, final.version)
    }

    @Test
    fun `applying the same events twice from the same start is stable`() {
        val start = TestGames.lobby(playerCount = 3, seed = 314L)
        val (_, events) = playOut(start, maxCommands = 200)
        assertEquals(start.applyAll(events), start.applyAll(events))
    }

    @Test
    fun `the applier never throws on an event for a player it does not know`() {
        val state = TestGames.started()
        val ghost = com.monopoly.core.model.PlayerId("ghost")
        // Staying total matters more than surfacing a server bug from inside a
        // client's replay loop, so an unknown id is absorbed, not thrown on.
        val after = state.applyEvent(GameEvent.ConnectionChanged(ghost, false))
        assertEquals(state.players, after.players)
        assertEquals(state.version + 1, after.version)
    }

    @Test
    fun `no money is created or destroyed except by the bank`() {
        val start = TestGames.lobby(playerCount = 3, seed = 4242L)
        val (final, events) = playOut(start, maxCommands = 300)

        // Track every transfer that involves the bank; the rest must net to zero.
        val bankNet = events.filterIsInstance<GameEvent.MoneyTransferred>().sumOf { event ->
            when {
                event.from == null && event.to != null -> event.amount
                event.from != null && event.to == null -> -event.amount
                else -> 0
            }
        }
        // Bankruptcy to the bank removes a player's cash from the game entirely,
        // so account for that separately.
        val burned = events.filterIsInstance<GameEvent.PlayerBankrupted>()
            .filter { it.creditor == null }
            .sumOf { bankruptcy ->
                val before = start.applyAll(events.takeWhile { it !== bankruptcy })
                before.playerOrNull(bankruptcy.player)?.money ?: 0
            }

        val startingTotal = start.players.sumOf { it.money }
        val endingTotal = final.players.sumOf { it.money }
        assertEquals(startingTotal + bankNet - burned, endingTotal)
    }

    /**
     * Plays a game to completion (or until [maxCommands]) by always taking the
     * simplest legal action for whatever the current phase is waiting on.
     *
     * This is not an AI and is not trying to play well. It is a way to walk a
     * large number of real state transitions, including the awkward ones —
     * auctions, jail, unpayable debts — without hand-writing each scenario.
     */
    private fun playOut(start: GameState, maxCommands: Int): Pair<GameState, List<GameEvent>> {
        var state = start
        val log = mutableListOf<GameEvent>()

        // The lobby is not a phase the driver below handles; start the game.
        state = step(state, Command.StartGame(state.players.first().id), log)

        var issued = 0
        while (issued < maxCommands && state.phase !is GamePhase.GameOver) {
            val command = nextCommand(state) ?: break
            val before = state.version
            state = step(state, command, log)
            issued++
            // A command that changes nothing would spin this loop forever.
            if (state.version == before) break
        }
        return state to log
    }

    private fun step(state: GameState, command: Command, log: MutableList<GameEvent>): GameState {
        return when (val outcome = GameEngine.reduce(state, command)) {
            is Outcome.Accepted -> {
                log += outcome.events
                outcome.state
            }
            // A rejection is a no-op by design, so the driver just moves on.
            is Outcome.Rejected -> state
        }
    }

    private fun nextCommand(state: GameState): Command? {
        val current = state.currentPlayer.id
        return when (val phase = state.phase) {
            is GamePhase.Lobby -> Command.StartGame(state.players.first().id)
            is GamePhase.AwaitingRoll -> Command.RollDice(current)
            is GamePhase.AwaitingJailDecision -> Command.RollDice(current)
            is GamePhase.AwaitingTurnEnd -> Command.EndTurn(current)

            is GamePhase.AwaitingPurchase -> {
                val price = com.monopoly.core.board.ClassicBoard
                    .purchasableAt(phase.spaceIndex)?.price ?: 0
                // Buy when affordable, otherwise pass and let it go to auction.
                if (state.player(current).money >= price) Command.BuyProperty(current)
                else Command.DeclineProperty(current)
            }

            is GamePhase.Auction -> {
                val bidder = phase.currentBidder ?: return null
                // Everyone folds immediately; the auction path still gets walked.
                Command.WithdrawFromAuction(bidder)
            }

            is GamePhase.AwaitingDebtSettlement -> settleOrFail(state, phase)

            is GamePhase.GameOver -> null
        }
    }

    /** Raises cash by mortgaging and selling, or goes bankrupt if it cannot. */
    private fun settleOrFail(
        state: GameState,
        phase: GamePhase.AwaitingDebtSettlement,
    ): Command {
        val debtor = phase.debtor
        if (state.player(debtor).money >= phase.amount) return Command.SettleDebt(debtor)

        val holdings = state.deedsOf(debtor)
        // Buildings have to go before the land underneath them can be mortgaged.
        holdings.firstOrNull { it.houses > 0 }?.let { developed ->
            return Command.SellHouse(debtor, developed.spaceIndex)
        }
        holdings.firstOrNull { !it.mortgaged }?.let { unmortgaged ->
            return Command.MortgageProperty(debtor, unmortgaged.spaceIndex)
        }
        return Command.DeclareBankruptcy(debtor)
    }
}
