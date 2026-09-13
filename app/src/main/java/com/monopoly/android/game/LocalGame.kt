package com.monopoly.android.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEngine
import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.Outcome
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.engine.Seat
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A hot-seat game, played on one device.
 *
 * This drives the engine directly instead of going through the server, which
 * makes it a useful thing in its own right — passing a phone round a table is a
 * real way to play — and a useful check: the board renders nothing but
 * [GameState], so anything that looks wrong here is wrong in the engine, not in
 * the network layer.
 */
@Stable
class LocalGame(initial: GameState) : GameHolder {

    override var state: GameState by mutableStateOf(initial)
        private set

    override var lastRejection: RejectionReason? by mutableStateOf(null)
        private set

    private val journal = GameJournal()
    override val log: List<String> get() = journal.log
    override val updates: ReceiveChannel<BoardUpdate> = journal.updates

    /** One person is holding the phone for everyone, so no seat is "yours". */
    override val you: PlayerId? = null

    override fun dispatch(command: Command) {
        when (val outcome = GameEngine.reduce(state, command)) {
            is Outcome.Accepted -> {
                journal.record(state, outcome.events)
                state = outcome.state
                lastRejection = null
            }

            is Outcome.Rejected -> lastRejection = outcome.reason
        }
    }

    override fun dismissRejection() {
        lastRejection = null
    }

    companion object {
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
