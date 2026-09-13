package com.monopoly.server

import com.monopoly.protocol.ServerMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The outbound queue for one connected socket.
 *
 * Each connection gets its own buffer, and broadcasting never suspends. One
 * player on a slow train must not be able to stall the turn for everyone else,
 * which is exactly what a shared queue with back-pressure would do.
 *
 * If a client is so far behind that its buffer fills, messages are dropped and
 * the connection is flagged. That is safe rather than lossy: sequence numbers
 * mean the client detects the gap on the next message it does receive, and asks
 * for a snapshot. Losing a batch costs one round trip; stalling the game costs
 * everyone.
 */
class ClientChannel(private val capacity: Int = DEFAULT_CAPACITY) {

    private val queue = Channel<ServerMessage>(capacity)

    /** Consumed by the socket's writer coroutine. */
    val outgoing: ReceiveChannel<ServerMessage> get() = queue

    /**
     * Set when a message had to be dropped. The connection then owes the client
     * a snapshot before anything else it sends can be trusted to be contiguous.
     */
    @Volatile
    var droppedMessages: Boolean = false
        private set

    /** Never suspends, never throws. Safe to call while holding a lock. */
    fun offer(message: ServerMessage) {
        if (queue.trySend(message).isSuccess) return
        droppedMessages = true
    }

    fun clearDropFlag() {
        droppedMessages = false
    }

    fun close() {
        queue.close()
    }

    private companion object {
        /**
         * Generous enough that only a genuinely stuck client overflows it: a
         * whole turn is a handful of messages.
         */
        const val DEFAULT_CAPACITY = 256
    }
}
