package com.monopoly.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        gameModule()
    }.start(wait = true)
}

/**
 * Wires up the server. Kept separate from [main] so tests can start it in
 * process without binding a real port.
 */
fun Application.gameModule(registry: GameRegistry = GameRegistry()) {
    install(CallLogging)
    install(WebSockets) {
        // Mobile networks and carrier NAT will silently drop an idle connection.
        // Pinging keeps it alive, and — more importantly — makes a connection
        // that has actually died fail fast rather than hanging until a TCP
        // timeout, so the client can start reconnecting while the player is
        // still looking at the board.
        pingPeriod = PING_PERIOD
        timeout = PONG_TIMEOUT
        maxFrameSize = MAX_FRAME_BYTES
        masking = false
    }

    gameSocket(registry)

    routing {
        // Cheap endpoint for uptime checks and container health probes.
        get("/health") {
            call.respondText("ok")
        }
    }

    startIdleGameSweeper(registry)
}

/**
 * Drops games nobody has been connected to for a long while.
 *
 * The window is deliberately generous. Resume tokens exist precisely so that
 * everybody's phone dying at once is survivable, and a game reclaimed too
 * eagerly would defeat that.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun Application.startIdleGameSweeper(registry: GameRegistry) {
    GlobalScope.launch {
        while (isActive) {
            delay(SWEEP_INTERVAL)
            try {
                registry.activeGames()
                    .filter { it.isAbandoned(ABANDON_AFTER.inWholeMilliseconds) }
                    .forEach { session ->
                        registry.remove(session.code)
                        session.closeAll()
                        log.info("Reclaimed idle game ${session.code}")
                    }
            } catch (failure: Exception) {
                // A sweep failure must never take the server down with it.
                log.warn("Idle game sweep failed", failure)
            }
        }
    }
}

private const val DEFAULT_PORT = 8080
private const val MAX_FRAME_BYTES = 1L shl 20
private val PING_PERIOD = 20.seconds
private val PONG_TIMEOUT = 40.seconds
private val SWEEP_INTERVAL = 5.minutes
private val ABANDON_AFTER = 120.minutes
