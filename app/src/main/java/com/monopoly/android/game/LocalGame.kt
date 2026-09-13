package com.monopoly.android.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monopoly.core.engine.Command
import com.monopoly.core.engine.applyEvent
import com.monopoly.core.engine.GameEngine
import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.Outcome
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.engine.Seat
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules

/**
 * A hot-seat game, played on one device.
 *
 * This drives the engine directly instead of going through the server, which
 * makes it a useful thing in its own right — passing a phone round a table is a
 * real way to play — and a useful check: the board below renders nothing but
 * [GameState], so anything that looks wrong here is wrong in the engine, not in
 * the network layer.
 *
 * Swapping this for a networked session later changes only how [dispatch] gets
 * its new state: send the command, wait for events, fold them. The board does
 * not have to know the difference.
 */
@Stable
class LocalGame(initial: GameState) {

    var state: GameState by mutableStateOf(initial)
        private set

    /** The most recent refusal, shown to the player and cleared on the next act. */
    var lastRejection: RejectionReason? by mutableStateOf(null)
        private set

    /** Newest first, so the log reads top-down without scrolling. */
    val log = mutableStateListOf<String>()

    fun dispatch(command: Command) {
        when (val outcome = GameEngine.reduce(state, command)) {
            is Outcome.Accepted -> {
                // Describe each event against the state it applied to, so names
                // still resolve for a player who is leaving the board.
                var running = state
                outcome.events.forEach { event ->
                    describe(event, running)?.let { log.add(0, it) }
                    running = running.applyEvent(event)
                }
                state = outcome.state
                lastRejection = null
                if (log.size > MAX_LOG_LINES) log.removeRange(MAX_LOG_LINES, log.size)
            }

            is Outcome.Rejected -> lastRejection = outcome.reason
        }
    }

    fun dismissRejection() {
        lastRejection = null
    }

    companion object {
        const val MAX_LOG_LINES = 200

        private val names = listOf("Alice", "Bob", "Cara", "Dev", "Eve", "Femi", "Gus", "Hana")

        /** A ready-to-start lobby with [playerCount] seats. */
        fun newGame(
            playerCount: Int,
            rules: GameRules = GameRules.CLASSIC,
            seed: Long = System.nanoTime(),
        ): LocalGame {
            val seats = (0 until playerCount).map { index ->
                Seat(
                    id = PlayerId("p$index"),
                    name = names[index],
                    token = Token.entries[index],
                )
            }
            return LocalGame(GameFactory.newGame("local", seats, rules, seed))
        }
    }
}
