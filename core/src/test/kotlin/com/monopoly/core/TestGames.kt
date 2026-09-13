package com.monopoly.core

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEngine
import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.Outcome
import com.monopoly.core.engine.Rng
import com.monopoly.core.engine.Seat
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import kotlin.test.assertIs

/** Fixtures shared by the engine tests. */
object TestGames {

    val ALICE = PlayerId("alice")
    val BOB = PlayerId("bob")
    val CARA = PlayerId("cara")

    fun seats(count: Int = 2): List<Seat> = listOf(
        Seat(ALICE, "Alice", Token.TOP_HAT),
        Seat(BOB, "Bob", Token.BOOT),
        Seat(CARA, "Cara", Token.CAT),
    ).take(count)

    fun lobby(
        playerCount: Int = 2,
        rules: GameRules = GameRules.CLASSIC,
        seed: Long = 20260913L,
    ): GameState = GameFactory.newGame("test-game", seats(playerCount), rules, seed)

    /** A game past the lobby, ready for the first player to roll. */
    fun started(
        playerCount: Int = 2,
        rules: GameRules = GameRules.CLASSIC,
        seed: Long = 20260913L,
    ): GameState {
        val state = lobby(playerCount, rules, seed)
        // The host always holds the first seat, whatever the shuffled turn order.
        return state.accept(Command.StartGame(state.players.first().id))
    }

    /**
     * Finds a seed that makes the next roll come out as [first] and [second].
     *
     * Tests need specific dice — "land on Mayfair", "roll doubles twice" — but
     * the engine deliberately has no hook to inject them, because any such hook
     * would be a way to cheat. Searching the seed space instead keeps the engine
     * honest and the tests exact. The search finishes in a few thousand tries.
     */
    fun seedRolling(first: Int, second: Int): Long {
        var seed = 1L
        while (seed < 5_000_000L) {
            val (_, roll) = Rng.seeded(seed).rollDice()
            if (roll.first == first && roll.second == second) return seed
            seed++
        }
        error("No seed found producing $first/$second")
    }

    /** Runs a command that is expected to succeed, returning the new state. */
    fun GameState.accept(command: Command): GameState {
        val outcome = GameEngine.reduce(this, command)
        assertIs<Outcome.Accepted>(outcome, "Expected $command to be accepted, got $outcome")
        return outcome.state
    }

    /** Runs a command that is expected to fail, returning the rejection. */
    fun GameState.reject(command: Command): Outcome.Rejected {
        val outcome = GameEngine.reduce(this, command)
        assertIs<Outcome.Rejected>(outcome, "Expected $command to be rejected, got $outcome")
        return outcome
    }

    /** The events a command produces, without discarding them. */
    fun GameState.events(command: Command) =
        (GameEngine.reduce(this, command) as Outcome.Accepted).events
}
