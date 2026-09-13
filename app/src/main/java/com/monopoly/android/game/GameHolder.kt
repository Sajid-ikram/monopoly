package com.monopoly.android.game

import androidx.compose.runtime.Stable
import com.monopoly.core.engine.Command
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A game in progress, from the screen's point of view.
 *
 * The board and its controls are written against this and nothing else, so the
 * same screen renders a hot-seat game on one device and a networked game on
 * four. The difference between them is entirely in how [dispatch] gets its new
 * state: the local holder runs the engine itself, the networked one sends the
 * command and waits for the server's events.
 *
 * There is no third possibility where the client decides something. Every
 * implementation is either running the real engine or being told what the real
 * engine did.
 */
@Stable
interface GameHolder {

    /** The authoritative state. Never patched locally to look faster. */
    val state: GameState

    /** The most recent refusal, shown to the player and cleared on the next act. */
    val lastRejection: RejectionReason?

    /** Newest first, so the log reads top-down without scrolling. */
    val log: List<String>

    /** Movement for the board to play out. Consumed by exactly one screen. */
    val updates: ReceiveChannel<BoardUpdate>

    /**
     * Whether a command has been sent and not yet answered.
     *
     * The client never guesses at the result, so between the tap and the
     * server's reply there is genuinely nothing new to show. Saying so is the
     * difference between a slow connection and a broken one — and it is what
     * stops an impatient second tap becoming a second command.
     *
     * Always false for a hot-seat game: the engine is right here, and the
     * answer arrives before the finger leaves the screen.
     */
    val busy: Boolean get() = false

    fun dispatch(command: Command)

    fun dismissRejection()

    /**
     * The seat this device is playing, or null when it is playing all of them.
     *
     * Hot-seat has no "you": one person is holding the phone for everybody, and
     * "your property" means whoever's turn it is. A networked client has
     * exactly one seat, and that distinction is what lets the same panel serve
     * both without a flag threaded through every composable.
     */
    val you: PlayerId?

    /**
     * Whether this device may act for [id].
     *
     * This only decides what to put on screen. The server does not trust it: it
     * checks the same thing itself, against the seat the socket authenticated
     * as, so a tampered client gains nothing by answering true.
     */
    fun controls(id: PlayerId): Boolean = you == null || you == id
}
