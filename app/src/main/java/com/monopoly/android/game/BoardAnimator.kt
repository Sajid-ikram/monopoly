package com.monopoly.android.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import kotlinx.coroutines.delay

/**
 * Something for the board to play out, in the order it happened.
 *
 * One ordered stream rather than a move queue plus a resync flag, because the
 * two have to stay in order relative to each other: a resync that overtook the
 * moves still in flight would put the pieces right and then walk them wrong.
 */
sealed interface BoardUpdate {

    /**
     * A piece travelling from one square to another.
     *
     * [walk] separates the two kinds of move that look completely different on
     * a real table: counting a piece forward square by square after a roll,
     * versus picking it up and putting it somewhere else because a card said so.
     */
    data class Move(
        val player: PlayerId,
        val from: Int,
        val to: Int,
        val walk: Boolean,
    ) : BoardUpdate

    /**
     * Put every piece where the state says it is, with no animation.
     *
     * Sent when the client has been handed a fresh snapshot: whatever the board
     * was in the middle of showing is now about a game that has moved on.
     */
    data object Snap : BoardUpdate
}

/**
 * Where the pieces *appear* to be, which lags behind where they are.
 *
 * The game state is authoritative and updates the instant the server says so.
 * This holds the board's slower opinion, so a piece can be seen travelling.
 * Keeping the two apart is what lets the animation be purely decorative: if it
 * were ever interrupted or skipped, the state it is chasing is still correct,
 * and [syncTo] puts the pieces where they belong.
 */
@Stable
class BoardAnimator {

    private val shown = mutableStateMapOf<PlayerId, Int>()

    /** Board index each token is drawn at. */
    val positions: Map<PlayerId, Int> get() = shown

    /** The piece currently travelling, drawn slightly raised. */
    var moving: PlayerId? by mutableStateOf(null)
        private set

    /** Places every piece where the state says it is, with no animation. */
    fun syncTo(state: GameState) {
        moving = null
        shown.keys.retainAll(state.players.map { it.id }.toSet())
        state.players.forEach { player -> shown[player.id] = player.position }
    }

    /** Adds any player the animation has not seen before. */
    private fun ensureKnown(state: GameState, player: PlayerId) {
        if (player !in shown) shown[player] = state.playerOrNull(player)?.position ?: 0
    }

    suspend fun apply(update: BoardUpdate, state: GameState) {
        when (update) {
            is BoardUpdate.Snap -> syncTo(state)
            is BoardUpdate.Move -> play(update, state)
        }
    }

    private suspend fun play(move: BoardUpdate.Move, state: GameState) {
        ensureKnown(state, move.player)

        if (!move.walk) {
            // A card moved them, or they were sent to jail: the piece is lifted
            // and placed, not counted around.
            shown[move.player] = move.to
            delay(JUMP_PAUSE_MILLIS)
            return
        }

        moving = move.player
        var square = move.from
        // Bounded so a malformed update can never spin here forever.
        repeat(ClassicBoard.SPACE_COUNT) {
            if (square == move.to) return@repeat
            square = ClassicBoard.normalize(square + 1)
            shown[move.player] = square
            // The last step lands rather than hops, so the piece settles.
            if (square != move.to) delay(STEP_MILLIS)
        }
        shown[move.player] = move.to
        delay(LANDING_PAUSE_MILLIS)
        moving = null
    }

    private companion object {
        /**
         * Fast enough not to be a wait, slow enough to read as counting. At a
         * roll of twelve this is about a second, which is roughly how long it
         * takes to move a real piece.
         */
        const val STEP_MILLIS = 85L
        const val LANDING_PAUSE_MILLIS = 120L
        const val JUMP_PAUSE_MILLIS = 260L
    }
}
