package com.monopoly.protocol

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEvent
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import kotlinx.serialization.Serializable

/**
 * Bumped whenever a message shape changes incompatibly. The server rejects a
 * client that disagrees, with a message saying so, rather than letting a stale
 * build fail in confusing ways halfway through a game.
 */
const val PROTOCOL_VERSION: Int = 1

/**
 * An event with its position in the game's total order.
 *
 * [sequence] starts at 1 for the first event of a game and never skips. It is
 * what makes recovery cheap: a client says how far it got, and the server knows
 * exactly what it missed without either side sending the whole game.
 */
@Serializable
data class SequencedEvent(
    val sequence: Long,
    val event: GameEvent,
)

// ---------------------------------------------------------------- client → server

@Serializable
sealed interface ClientMessage {

    /**
     * Opens a new game and takes the host seat in it.
     *
     * The server picks the game code, because a client-chosen code could
     * collide with a live game and drop two groups of friends into the same
     * lobby.
     */
    @Serializable
    data class CreateGame(
        val protocolVersion: Int = PROTOCOL_VERSION,
        val displayName: String,
        val preferredToken: Token? = null,
        val rules: GameRules = GameRules.CLASSIC,
    ) : ClientMessage

    /**
     * Opens a session.
     *
     * [resumeToken] is issued on first join and stored on the device. Presenting
     * it reclaims the same seat, which is the difference between "my train went
     * through a tunnel" and "I lost the game". A client with no token is a new
     * player joining the lobby.
     *
     * [lastSequence] is the highest sequence this client has applied. The server
     * replies with either the missing events or a fresh snapshot, whichever is
     * cheaper.
     */
    @Serializable
    data class Join(
        val protocolVersion: Int = PROTOCOL_VERSION,
        val gameCode: String,
        val displayName: String,
        val preferredToken: Token? = null,
        val resumeToken: String? = null,
        val lastSequence: Long = 0,
    ) : ClientMessage

    /**
     * Asks the server to run a command.
     *
     * [commandId] is a client-generated unique id and the reason a shaky
     * connection cannot cost you money. If the reply is lost, the client resends
     * the identical message; the server recognises the id, performs nothing a
     * second time, and returns the original result. Every command is therefore
     * safe to retry, which means the client can retry aggressively.
     */
    @Serializable
    data class Submit(
        val commandId: String,
        val command: Command,
    ) : ClientMessage

    /**
     * Liveness probe. [clientSentAtMillis] is echoed back untouched so the
     * client can compute round-trip time without the two clocks agreeing on
     * what time it is.
     */
    @Serializable
    data class Ping(val clientSentAtMillis: Long) : ClientMessage

    /** Asks for a full snapshot, when a client decides its state is suspect. */
    @Serializable
    data object RequestSnapshot : ClientMessage

    /** A deliberate exit, as opposed to a dropped connection. */
    @Serializable
    data object Leave : ClientMessage
}

// ---------------------------------------------------------------- server → client

@Serializable
sealed interface ServerMessage {

    /**
     * Accepts a session and establishes the client's identity and starting
     * state in one message, so there is no window where the client is connected
     * but does not know who it is.
     */
    @Serializable
    data class Welcome(
        val protocolVersion: Int = PROTOCOL_VERSION,
        /** Share this with the other players; it is how they find the game. */
        val gameCode: String,
        val playerId: PlayerId,
        /** Store this. It is what makes the next reconnect seamless. */
        val resumeToken: String,
        val state: GameState,
        val sequence: Long,
    ) : ServerMessage

    /**
     * A contiguous run of events. [fromSequence] is always exactly one more than
     * the last sequence the client had, so a client that receives a batch which
     * does not line up knows immediately that it has missed something and can
     * ask for a snapshot rather than silently applying events out of order.
     */
    @Serializable
    data class Events(
        val fromSequence: Long,
        val events: List<SequencedEvent>,
    ) : ServerMessage

    /**
     * The whole game state. Sent when a client has fallen too far behind for
     * replay to be worthwhile, or when it asks.
     */
    @Serializable
    data class Snapshot(
        val state: GameState,
        val sequence: Long,
    ) : ServerMessage

    /**
     * Confirms a command was applied. The client matches [commandId] against
     * what it sent so it can stop retrying and clear any optimistic UI.
     */
    @Serializable
    data class CommandAccepted(
        val commandId: String,
        val firstSequence: Long,
        val lastSequence: Long,
    ) : ServerMessage

    /**
     * The command was not legal. This is a normal outcome, not an error: a
     * client that is a little behind will sometimes offer a move that was valid
     * a second ago. [currentSequence] lets it check whether it is out of date.
     */
    @Serializable
    data class CommandRejected(
        val commandId: String,
        val reason: RejectionReason,
        val detail: String? = null,
        val currentSequence: Long,
    ) : ServerMessage

    @Serializable
    data class Pong(
        val clientSentAtMillis: Long,
        val serverTimeMillis: Long,
    ) : ServerMessage

    /** Someone's connection came or went. The seat and its assets are untouched. */
    @Serializable
    data class PresenceChanged(
        val playerId: PlayerId,
        val connected: Boolean,
    ) : ServerMessage

    /** The session could not be established or must end. */
    @Serializable
    data class Rejected(
        val code: JoinFailure,
        val detail: String? = null,
    ) : ServerMessage
}

/** Why a session could not be opened. Each maps to a distinct thing to tell the player. */
@Serializable
enum class JoinFailure {
    PROTOCOL_VERSION_MISMATCH,
    GAME_NOT_FOUND,
    GAME_FULL,
    GAME_ALREADY_STARTED,
    NAME_TAKEN,
    TOKEN_TAKEN,
    INVALID_RESUME_TOKEN,
    NOT_HOST,
}
