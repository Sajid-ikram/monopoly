package com.monopoly.android.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.monopoly.android.net.ConnectionStatus
import com.monopoly.android.net.GameConnection
import com.monopoly.android.net.SavedSeat
import com.monopoly.android.net.SessionStore
import com.monopoly.core.engine.Command
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.protocol.ClientMessage
import com.monopoly.protocol.JoinFailure
import com.monopoly.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.UUID

/** How this client is getting into a game. */
sealed interface SessionIntent {

    /** Open a new game. The server picks the code. */
    data class Host(val displayName: String) : SessionIntent

    /** Join someone else's game by the code they read out. */
    data class JoinByCode(val gameCode: String, val displayName: String) : SessionIntent

    /** Return to a seat this device already holds a token for. */
    data class Resume(val seat: SavedSeat) : SessionIntent
}

/**
 * A game being played over the network.
 *
 * The client is not a participant in the rules. It sends commands, receives the
 * events the server's engine produced, and folds them. It never decides
 * anything: there is no optimistic update, no local validation that could
 * disagree, and no path by which this class can make the board show something
 * the server did not say. The cost is a round trip before a button takes
 * effect; the benefit is that four phones cannot end up playing four different
 * games.
 *
 * Everything that makes a bad connection survivable lives in three places:
 * [GameConnection] reopens the socket, the resume token reclaims the same seat,
 * and [lastSequence] says exactly how much was missed so the server can send
 * only that.
 */
@Stable
class NetworkGame(
    private val intent: SessionIntent,
    serverUrl: String,
    private val store: SessionStore,
) : GameHolder, GameConnection.Hooks {

    private val journal = GameJournal()
    override val log: List<String> get() = journal.log
    override val updates: ReceiveChannel<BoardUpdate> = journal.updates

    private val connection = GameConnection(serverUrl, this)

    /**
     * Null until the server's welcome arrives.
     *
     * The client starts with no state at all rather than a plausible-looking
     * empty game, because a fabricated state is exactly the thing this design
     * is trying not to have. The lobby screen shows until this is filled in.
     */
    var stateOrNull: GameState? by mutableStateOf(null)
        private set

    /**
     * The authoritative state.
     *
     * Only valid once [joined] — the board is never shown before then.
     */
    override val state: GameState
        get() = requireNotNull(stateOrNull) { "No game state before the server's welcome" }

    val joined: Boolean get() = stateOrNull != null

    override var lastRejection: RejectionReason? by mutableStateOf(null)
        private set

    var status: ConnectionStatus by mutableStateOf(ConnectionStatus.Connecting)
        private set

    /** The seat this device holds, once the server has granted one. */
    var seat: SavedSeat? by mutableStateOf(
        (intent as? SessionIntent.Resume)?.seat,
    )
        private set

    /**
     * The highest sequence number applied.
     *
     * This is the whole of the recovery protocol on the client side: it is sent
     * on every reconnect, and the server replies with either the handful of
     * events that were missed or a fresh snapshot, whichever is cheaper.
     */
    private var lastSequence: Long = 0

    /**
     * Commands sent but not yet answered, oldest first.
     *
     * Kept so they can be resent after a reconnect. A linked map because order
     * matters — replaying a roll after the end of turn it preceded would be a
     * different game.
     */
    private val pending = LinkedHashMap<String, ClientMessage.Submit>()

    /** The host is the first seat, which is the one that may start the game. */
    val isHost: Boolean
        get() = stateOrNull?.players?.firstOrNull()?.id == seat?.playerId

    fun connect(scope: CoroutineScope) {
        connection.start(scope)
    }

    /** A deliberate exit: the seat is given up, not held. */
    fun leave() {
        connection.send(ClientMessage.Leave)
        seat?.let { store.forget(it.gameCode) }
        connection.stop()
    }

    /**
     * Closes the socket without giving up the seat.
     *
     * For when the screen goes away but the player has not left — the token is
     * kept, so the next launch offers to walk straight back into the game.
     */
    fun disconnect() {
        connection.stop()
    }

    override val you: PlayerId? get() = seat?.playerId

    override fun dispatch(command: Command) {
        // The id is what makes a retry free: the server recognises a repeated
        // id, does nothing a second time, and returns the original answer. So
        // the client is free to resend without tracking whether it needs to.
        val submit = ClientMessage.Submit(
            commandId = UUID.randomUUID().toString(),
            command = command,
        )
        pending[submit.commandId] = submit
        lastRejection = null
        connection.send(submit)
    }

    override fun dismissRejection() {
        lastRejection = null
    }

    // ------------------------------------------------------------------ hooks

    override fun handshake(): ClientMessage {
        // Once there is a seat, every later attempt is a resume — including the
        // one right after hosting. A second CreateGame would open a second game
        // and strand everyone who had already joined the first.
        seat?.let { held ->
            return ClientMessage.Join(
                gameCode = held.gameCode,
                displayName = held.displayName,
                resumeToken = held.resumeToken,
                lastSequence = lastSequence,
            )
        }

        return when (intent) {
            is SessionIntent.Host -> ClientMessage.CreateGame(displayName = intent.displayName)

            is SessionIntent.JoinByCode -> ClientMessage.Join(
                gameCode = intent.gameCode,
                displayName = intent.displayName,
            )

            is SessionIntent.Resume -> ClientMessage.Join(
                gameCode = intent.seat.gameCode,
                displayName = intent.seat.displayName,
                resumeToken = intent.seat.resumeToken,
                lastSequence = lastSequence,
            )
        }
    }

    override fun unacknowledged(): List<ClientMessage> = pending.values.toList()

    override fun onStatus(status: ConnectionStatus) {
        this.status = status
    }

    /**
     * Applied as one atomic change.
     *
     * This runs on the socket's thread, not the main one, which Compose's
     * snapshot state is built to allow. What it does not give for free is
     * atomicity: without this the UI could recompose between the log line and
     * the state it describes, and show a player being charged for rent a frame
     * before the money moves.
     */
    override suspend fun onMessage(message: ServerMessage) {
        Snapshot.withMutableSnapshot { receive(message) }
    }

    private fun receive(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> onWelcome(message)
            is ServerMessage.Events -> onEvents(message)
            is ServerMessage.Snapshot -> onSnapshot(message.state, message.sequence)
            is ServerMessage.CommandAccepted -> pending.remove(message.commandId)

            is ServerMessage.CommandRejected -> {
                pending.remove(message.commandId)
                lastRejection = message.reason
            }

            // Nothing to do with a pong. The keep-alive ping exists to hold the
            // socket and its NAT mapping open; arriving back is the whole point.
            is ServerMessage.Pong -> Unit

            is ServerMessage.PresenceChanged -> {
                val who = stateOrNull?.playerOrNull(message.playerId)?.name ?: "Someone"
                journal.note(if (message.connected) "$who reconnected." else "$who dropped out.")
            }

            is ServerMessage.Rejected -> onRejected(message)
        }
    }

    private fun onWelcome(welcome: ServerMessage.Welcome) {
        val held = SavedSeat(
            gameCode = welcome.gameCode,
            playerId = welcome.playerId,
            resumeToken = welcome.resumeToken,
            displayName = welcome.state.playerOrNull(welcome.playerId)?.name
                ?: displayNameFromIntent(),
        )
        seat = held
        // Written to disk before anything else, because this token is the only
        // thing that can get this seat back. Losing it to a crash in the next
        // line would mean losing the game.
        store.remember(held)

        val resuming = joined
        onSnapshot(welcome.state, welcome.sequence)
        if (resuming) journal.note("Back in the game.")
    }

    /**
     * A contiguous run of events, applied in order.
     *
     * The gap check is the important part. Events are only meaningful applied
     * to the state they were computed against, so a batch that does not follow
     * on from what this client has is not something to apply carefully — it is
     * something to refuse and replace with a snapshot.
     */
    private fun onEvents(batch: ServerMessage.Events) {
        if (batch.events.isEmpty()) return

        // Nothing can be applied before the welcome establishes what sequence
        // this client is starting from. The welcome is always coming, and it
        // carries the whole state, so there is nothing to recover here — just
        // wait for it rather than announcing a problem that does not exist.
        val before = stateOrNull ?: return

        if (batch.fromSequence != lastSequence + 1) {
            // Already seen: a resend after a reconnect, harmless to ignore.
            if (batch.events.last().sequence <= lastSequence) return
            journal.note("Lost track of the game — asking the server for a fresh copy.")
            connection.send(ClientMessage.RequestSnapshot)
            return
        }

        stateOrNull = journal.record(before, batch.events.map { it.event })
        lastSequence = batch.events.last().sequence
    }

    private fun onSnapshot(snapshot: GameState, sequence: Long) {
        stateOrNull = snapshot
        lastSequence = sequence
        // Whatever the board was in the middle of showing is about a game that
        // has moved on, so the pieces are put where they belong rather than
        // finishing a walk that no longer means anything.
        journal.resynced()
    }

    /**
     * The session cannot be established or cannot continue.
     *
     * Retrying a wrong code forever would leave the player watching a spinner
     * that will never resolve, so these stop the connection rather than feeding
     * it back into the reconnect loop.
     */
    private fun onRejected(rejection: ServerMessage.Rejected) {
        seat?.let { store.forget(it.gameCode) }
        connection.stop(rejection.code.readable(rejection.detail))
    }

    private fun displayNameFromIntent(): String = when (intent) {
        is SessionIntent.Host -> intent.displayName
        is SessionIntent.JoinByCode -> intent.displayName
        is SessionIntent.Resume -> intent.seat.displayName
    }
}

/**
 * Why a session could not be opened, in words a player can act on.
 *
 * [detail] is the server's own explanation and is appended when there is one,
 * because these are the failures a player sees while trying to get into a game
 * with friends waiting — the moment where a vague message costs the most.
 */
fun JoinFailure.readable(detail: String? = null): String {
    val explanation = when (this) {
        JoinFailure.GAME_NOT_FOUND ->
            "No game with that code. Check the letters, or ask for it again — " +
                "a game is forgotten a couple of hours after everyone leaves."
        JoinFailure.GAME_FULL -> "That game is full."
        JoinFailure.GAME_ALREADY_STARTED -> "That game has already started."
        JoinFailure.NAME_TAKEN -> "Someone in that game is already using that name."
        JoinFailure.TOKEN_TAKEN -> "Someone in that game already has that piece."
        JoinFailure.INVALID_RESUME_TOKEN ->
            "That seat is gone. The game may have ended while you were away."
        JoinFailure.NOT_HOST -> "Only the host can do that."
        JoinFailure.PROTOCOL_VERSION_MISMATCH ->
            "This app and the server are different versions. One of them needs updating."
    }
    return if (detail == null) explanation else "$explanation ($detail)"
}
