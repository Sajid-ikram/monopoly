package com.monopoly.android.net

import com.monopoly.protocol.ClientMessage
import com.monopoly.protocol.ServerMessage
import com.monopoly.protocol.decodeServerMessage
import com.monopoly.protocol.encode
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What the player is told about the connection, and nothing more. */
sealed interface ConnectionStatus {

    data object Connecting : ConnectionStatus

    data object Live : ConnectionStatus

    /**
     * The socket is down and will be retried. [attempt] counts from 1, so the
     * UI can stay quiet about the first blip and only speak up once it looks
     * like a real outage.
     */
    data class Reconnecting(val attempt: Int) : ConnectionStatus

    /**
     * The session is over and retrying would not help: the code does not exist,
     * the game had already started, the builds disagree. Distinguished from
     * [Reconnecting] because the two need completely different words.
     */
    data class Ended(val reason: String) : ConnectionStatus
}

/**
 * One WebSocket to the game server, reopened as often as it takes.
 *
 * A dropped connection is the normal condition this is built around, not an
 * error path: phones go through tunnels, switch from wifi to mobile, and get
 * put in pockets. So there is no "connection failed" state for the player to
 * act on — the socket is simply reopened, the seat is reclaimed with the resume
 * token, and the game carries on from wherever the client had got to.
 *
 * This layer knows about sockets and retries. It knows nothing about Monopoly:
 * what to say on a new socket, and what to say again after a reconnect, are
 * both asked of [hooks].
 */
class GameConnection(
    private val url: String,
    private val hooks: Hooks,
) {

    interface Hooks {
        /**
         * The first message on every new socket.
         *
         * Asked for afresh each time rather than stored, because it changes:
         * the first attempt creates or joins a game, and every later one
         * resumes a seat the client now holds a token for, from the sequence it
         * has now reached.
         */
        fun handshake(): ClientMessage

        /**
         * Commands that were sent but never acknowledged, resent after a
         * reconnect.
         *
         * Safe because every command carries a client-generated id and the
         * server performs a repeated id no second time. That is what makes a
         * shaky connection cost nothing: the client can resend without ever
         * having to work out whether the first attempt got through.
         */
        fun unacknowledged(): List<ClientMessage>

        suspend fun onMessage(message: ServerMessage)

        fun onStatus(status: ConnectionStatus)
    }

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    /**
     * Messages waiting to go out. Unlimited and never dropped: a command sent
     * while the socket happens to be down is held here and delivered when it
     * comes back, rather than being lost with a shrug.
     */
    private val outbox = Channel<ClientMessage>(Channel.UNLIMITED)

    private var pump: Job? = null

    @Volatile
    private var ended = false

    fun start(scope: CoroutineScope) {
        if (pump != null) return
        pump = scope.launch { run() }
    }

    /** Queues a message. Never suspends, never fails, never blocks the UI. */
    fun send(message: ClientMessage) {
        outbox.trySend(message)
    }

    /**
     * Stops retrying for good.
     *
     * Called when the server has said the session cannot continue, or when the
     * player has left deliberately. Everything else is a blip.
     */
    fun stop(reason: String? = null) {
        if (ended) return
        ended = true
        reason?.let { hooks.onStatus(ConnectionStatus.Ended(it)) }
        pump?.cancel()
        pump = null
        outbox.close()
        client.close()
    }

    private suspend fun run() {
        var attempt = 0
        while (!ended) {
            hooks.onStatus(
                if (attempt == 0) {
                    ConnectionStatus.Connecting
                } else {
                    ConnectionStatus.Reconnecting(attempt)
                },
            )

            try {
                client.webSocket(url) {
                    attempt = 0
                    hooks.onStatus(ConnectionStatus.Live)

                    // Order matters. The handshake claims the seat, so nothing
                    // after it can be understood until it has landed; the
                    // unacknowledged commands go next, ahead of anything queued
                    // since, so the game is replayed in the order it was played.
                    send(Frame.Text(hooks.handshake().encode()))
                    hooks.unacknowledged().forEach { send(Frame.Text(it.encode())) }

                    val writer = launch {
                        for (message in outbox) {
                            send(Frame.Text(message.encode()))
                        }
                    }
                    val keepAlive = launch {
                        while (isActive) {
                            delay(PING_INTERVAL_MILLIS)
                            send(Frame.Text(ClientMessage.Ping(System.currentTimeMillis()).encode()))
                        }
                    }

                    try {
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            val message = try {
                                decodeServerMessage(text)
                            } catch (malformed: Exception) {
                                // A message this build does not understand is
                                // not worth dropping a good connection over.
                                continue
                            }
                            hooks.onMessage(message)
                        }
                    } finally {
                        keepAlive.cancel()
                        writer.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Every network failure is the same failure as far as this loop
                // is concerned: the socket is gone, so open another one.
            }

            if (ended) return
            attempt++
            delay(backoffMillis(attempt))
        }
    }

    /**
     * Doubling, capped, with jitter.
     *
     * The cap stops a long outage from turning into a five-minute wait after
     * the network has already come back. The jitter matters because a whole
     * game's worth of clients drop at the same instant when the server
     * restarts, and without it they would all return in the same instant too.
     */
    private fun backoffMillis(attempt: Int): Long {
        val doubling = FIRST_RETRY_MILLIS shl (attempt - 1).coerceAtMost(MAX_DOUBLINGS)
        val capped = doubling.coerceAtMost(MAX_RETRY_MILLIS)
        return capped / 2 + Random.nextLong(capped / 2 + 1)
    }

    private companion object {
        const val FIRST_RETRY_MILLIS = 500L
        const val MAX_RETRY_MILLIS = 15_000L
        const val MAX_DOUBLINGS = 8

        /**
         * Well inside the server's 40-second read timeout, and short enough to
         * hold open the NAT mappings that mobile networks reap aggressively.
         */
        const val PING_INTERVAL_MILLIS = 20_000L
    }
}
