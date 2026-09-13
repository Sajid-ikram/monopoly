package com.monopoly.server

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEvent
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.protocol.ClientMessage
import com.monopoly.protocol.JoinFailure
import com.monopoly.protocol.PROTOCOL_VERSION
import com.monopoly.protocol.ServerMessage
import com.monopoly.protocol.decodeClientMessage
import com.monopoly.protocol.encode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch

/**
 * The `/play` endpoint: one WebSocket per player, for the life of their session.
 *
 * The socket is a transport and nothing more. It carries commands in and
 * sequenced events out; every decision about the game belongs to [GameSession],
 * and every decision about the rules belongs to the engine underneath it.
 */
fun Application.gameSocket(registry: GameRegistry) {
    routing {
        webSocket("/play") {
            val connection = ClientChannel()
            var seat: Seated? = null

            // One writer coroutine per socket, draining that socket's own queue,
            // so broadcasting never blocks on a slow client.
            val writer = launch {
                try {
                    connection.outgoing.consumeEach { message ->
                        send(Frame.Text(message.encode()))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // The read loop notices the socket is gone and cleans up.
                    call.application.log.debug("Writer stopped for ${seat?.playerId?.value}", failure)
                }
            }

            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue

                    val message = try {
                        decodeClientMessage(text)
                    } catch (malformed: Exception) {
                        call.application.log.warn("Undecodable message dropped", malformed)
                        connection.offer(
                            ServerMessage.Rejected(
                                JoinFailure.PROTOCOL_VERSION_MISMATCH,
                                "Message could not be decoded",
                            ),
                        )
                        continue
                    }

                    if (message is ClientMessage.Leave) {
                        seat?.let { leaveGame(it, connection, registry) }
                        seat = null
                        close(CloseReason(CloseReason.Codes.NORMAL, "Left the game"))
                        break
                    }

                    seat = dispatch(message, registry, connection, seat) ?: seat
                }
            } finally {
                // A dropped connection is the normal condition this design is
                // built around, so the seat survives it untouched.
                seat?.let { releaseSocket(it, connection, registry) }
                writer.cancel()
                connection.close()
            }
        }
    }
}

/** A socket that has successfully claimed a seat. */
private data class Seated(val session: GameSession, val playerId: PlayerId)

/** Returns a new seat when the message established one, otherwise null. */
private suspend fun dispatch(
    message: ClientMessage,
    registry: GameRegistry,
    connection: ClientChannel,
    seat: Seated?,
): Seated? = when (message) {
    is ClientMessage.CreateGame -> createGame(message, registry, connection)

    is ClientMessage.Join -> joinGame(message, registry, connection)

    is ClientMessage.Submit -> {
        if (seat == null) {
            connection.offer(notInAGame())
        } else {
            connection.offer(seat.session.submit(seat.playerId, message.commandId, message.command))
        }
        null
    }

    is ClientMessage.Ping -> {
        connection.offer(ServerMessage.Pong(message.clientSentAtMillis, System.currentTimeMillis()))
        null
    }

    is ClientMessage.RequestSnapshot -> {
        if (seat == null) {
            connection.offer(notInAGame())
        } else {
            connection.clearDropFlag()
            connection.offer(seat.session.snapshot())
        }
        null
    }

    // Handled by the read loop, which has to close the socket afterwards.
    is ClientMessage.Leave -> null
}

private suspend fun createGame(
    message: ClientMessage.CreateGame,
    registry: GameRegistry,
    connection: ClientChannel,
): Seated? {
    if (message.protocolVersion != PROTOCOL_VERSION) {
        connection.offer(versionMismatch(message.protocolVersion))
        return null
    }

    val hosted = registry.create(
        hostName = message.displayName.trim().ifBlank { "Host" },
        hostToken = message.preferredToken ?: Token.TOP_HAT,
        rules = message.rules,
    )
    hosted.session.attach(hosted.playerId, connection)

    connection.offer(
        ServerMessage.Welcome(
            gameCode = hosted.session.code,
            playerId = hosted.playerId,
            resumeToken = hosted.resumeToken,
            state = hosted.session.currentState(),
            sequence = hosted.session.sequenceNumber(),
        ),
    )
    return Seated(hosted.session, hosted.playerId)
}

private suspend fun joinGame(
    message: ClientMessage.Join,
    registry: GameRegistry,
    connection: ClientChannel,
): Seated? {
    if (message.protocolVersion != PROTOCOL_VERSION) {
        connection.offer(versionMismatch(message.protocolVersion))
        return null
    }

    val session = registry.find(message.gameCode)
    if (session == null) {
        connection.offer(ServerMessage.Rejected(JoinFailure.GAME_NOT_FOUND))
        return null
    }

    // A resume token means "I was already playing", which is handled completely
    // differently from a new player arriving: no seat is created, and the game
    // does not care what name or token the client now claims.
    message.resumeToken?.let { token ->
        return resumeSeat(session, token, message.lastSequence, connection)
    }

    val state = session.currentState()
    if (state.phase !is GamePhase.Lobby) {
        connection.offer(ServerMessage.Rejected(JoinFailure.GAME_ALREADY_STARTED))
        return null
    }

    val playerId = PlayerId(registry.newId())
    val chosenToken = message.preferredToken
        ?: Token.entries.firstOrNull { candidate -> state.players.none { it.token == candidate } }
    if (chosenToken == null) {
        connection.offer(ServerMessage.Rejected(JoinFailure.GAME_FULL))
        return null
    }

    // Joining runs through the engine like any other command, so the players
    // already in the lobby learn about it through the same event stream as
    // everything else rather than by a side channel.
    val outcome = session.submit(
        actor = playerId,
        commandId = "join:${playerId.value}",
        command = Command.JoinGame(playerId, message.displayName.trim(), chosenToken),
    )
    if (outcome is ServerMessage.CommandRejected) {
        connection.offer(ServerMessage.Rejected(outcome.reason.asJoinFailure(), outcome.detail))
        return null
    }

    val resumeToken = registry.newToken()
    session.enrol(playerId, resumeToken)
    session.attach(playerId, connection)
    connection.offer(
        ServerMessage.Welcome(
            gameCode = session.code,
            playerId = playerId,
            resumeToken = resumeToken,
            state = session.currentState(),
            sequence = session.sequenceNumber(),
        ),
    )
    return Seated(session, playerId)
}

/**
 * Puts a returning player back in their seat.
 *
 * Nothing about the game changes: the seat, its cash and its property were
 * never given up. A socket is reattached, and the client is brought up to date
 * from wherever it left off.
 */
private suspend fun resumeSeat(
    session: GameSession,
    resumeToken: String,
    lastSequence: Long,
    connection: ClientChannel,
): Seated? {
    val playerId = session.seatFor(resumeToken)
    if (playerId == null) {
        connection.offer(ServerMessage.Rejected(JoinFailure.INVALID_RESUME_TOKEN))
        return null
    }

    session.attach(playerId, connection)

    // Welcome carries the full state, so the returning client is correct
    // immediately even if it kept nothing at all.
    //
    // It must go first. A client cannot interpret an event before it has been
    // told which sequence it is starting from: announcing the reconnection
    // ahead of the welcome sends a client that kept nothing an event numbered
    // hundreds ahead of where it thinks it is, and it quite correctly concludes
    // it has lost track and asks for a snapshot it was already being sent.
    connection.offer(
        ServerMessage.Welcome(
            gameCode = session.code,
            playerId = playerId,
            resumeToken = resumeToken,
            state = session.currentState(),
            sequence = session.sequenceNumber(),
        ),
    )

    // Now that this client knows where it stands, tell everyone — it included —
    // that the seat is occupied again.
    session.emitServerEvents(listOf(GameEvent.ConnectionChanged(playerId, connected = true)))
    return Seated(session, playerId)
}

/** A deliberate exit. In the lobby this frees the seat; mid-game it does not. */
private suspend fun leaveGame(
    seat: Seated,
    connection: ClientChannel,
    registry: GameRegistry,
) {
    val state = seat.session.currentState()
    val leftLobby = state.phase is GamePhase.Lobby && state.players.size > 1
    if (leftLobby) {
        seat.session.submit(
            actor = seat.playerId,
            commandId = "leave:${seat.playerId.value}",
            command = Command.LeaveLobby(seat.playerId),
        )
        seat.session.forget(seat.playerId)
        if (seat.session.isFinished()) registry.remove(seat.session.code)
        return
    }
    releaseSocket(seat, connection, registry)
}

/**
 * Detaches this socket from its seat, keeping the seat itself.
 *
 * Passing the specific [connection] matters: the session ignores a detach from
 * a socket that has already been replaced, so a late close arriving after the
 * player has reconnected cannot knock their new connection offline.
 */
private suspend fun releaseSocket(
    seat: Seated,
    connection: ClientChannel,
    registry: GameRegistry,
) {
    seat.session.detach(seat.playerId, connection)
    if (seat.session.hasSeatFor(seat.playerId)) {
        seat.session.emitServerEvents(
            listOf(GameEvent.ConnectionChanged(seat.playerId, connected = false)),
        )
    }
    if (seat.session.isFinished()) registry.remove(seat.session.code)
}

private fun notInAGame() =
    ServerMessage.Rejected(JoinFailure.GAME_NOT_FOUND, "Join a game before acting in one")

private fun versionMismatch(clientVersion: Int) = ServerMessage.Rejected(
    JoinFailure.PROTOCOL_VERSION_MISMATCH,
    "Server speaks v$PROTOCOL_VERSION, client speaks v$clientVersion",
)

private fun RejectionReason.asJoinFailure(): JoinFailure = when (this) {
    RejectionReason.GAME_FULL -> JoinFailure.GAME_FULL
    RejectionReason.NAME_TAKEN -> JoinFailure.NAME_TAKEN
    RejectionReason.TOKEN_TAKEN -> JoinFailure.TOKEN_TAKEN
    RejectionReason.WRONG_PHASE -> JoinFailure.GAME_ALREADY_STARTED
    else -> JoinFailure.GAME_NOT_FOUND
}
