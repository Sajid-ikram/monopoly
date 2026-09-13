package com.monopoly.android.game

import androidx.compose.runtime.mutableStateListOf
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.engine.GameEvent
import com.monopoly.core.engine.applyEvent
import com.monopoly.core.model.GameState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Turns a run of events into the two things a player actually sees: lines in
 * the activity log, and pieces moving on the board.
 *
 * Shared by the local and networked holders on purpose. Both receive exactly
 * the same events — one from the engine in this process, one from the engine on
 * the server — so anything that read them differently would be a second place
 * for the two to disagree.
 */
class GameJournal {

    val log = mutableStateListOf<String>()

    /**
     * Unlimited, so recording a batch of events never waits on the animation.
     * The game is always ahead of the board, never held up by it — which is
     * also why a fast tapper is never blocked by a piece still walking.
     */
    private val queue = Channel<BoardUpdate>(Channel.UNLIMITED)
    val updates: ReceiveChannel<BoardUpdate> = queue

    /**
     * Folds [events] over [before], narrating as it goes, and returns the state
     * they add up to.
     *
     * Each event is described against the state it applied to, so names still
     * resolve for a player who is in the middle of leaving the board.
     */
    fun record(before: GameState, events: List<GameEvent>): GameState {
        var running = before
        // Set by a roll and consumed by the move that follows it, which is how
        // a counted move is told apart from a card's jump.
        var pendingRoll: Int? = null

        events.forEach { event ->
            describe(event, running)?.let { log.add(0, it) }

            when (event) {
                is GameEvent.DiceRolled -> pendingRoll = event.roll.total

                is GameEvent.PlayerMoved -> {
                    if (event.from != event.to) {
                        // Only a move whose distance matches the roll is the one
                        // the dice caused. Being sent to jail also follows a
                        // roll, but does not match it, so the piece is lifted
                        // rather than walked there.
                        val forward = ClassicBoard.forwardDistance(event.from, event.to)
                        queue.trySend(
                            BoardUpdate.Move(
                                player = event.player,
                                from = event.from,
                                to = event.to,
                                walk = pendingRoll == forward,
                            ),
                        )
                    }
                    pendingRoll = null
                }

                else -> Unit
            }

            running = running.applyEvent(event)
        }

        trim()
        return running
    }

    /** Notes a line that came from the session rather than from the game. */
    fun note(line: String) {
        log.add(0, line)
        trim()
    }

    /**
     * Abandons whatever the board was showing and starts again from [state].
     *
     * Queued rather than applied directly, so it takes effect after the moves
     * already in flight rather than being overwritten by them.
     */
    fun resynced() {
        queue.trySend(BoardUpdate.Snap)
    }

    private fun trim() {
        if (log.size > MAX_LOG_LINES) log.removeRange(MAX_LOG_LINES, log.size)
    }

    companion object {
        const val MAX_LOG_LINES = 200
    }
}
