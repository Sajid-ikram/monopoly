package com.monopoly.android.ui.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monopoly.android.game.NetworkGame
import com.monopoly.android.net.ConnectionStatus
import com.monopoly.android.ui.board.GameScreen
import com.monopoly.core.model.GamePhase

/**
 * A networked game, from "connecting" through the lobby to the board.
 *
 * One screen rather than three routes, because the connection can move between
 * those states at any moment and none of them should cost the player their
 * place. A dropped socket shows a strip along the top; it does not throw anyone
 * back to a menu.
 */
@Composable
fun OnlineGameScreen(
    game: NetworkGame,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(game) { game.connect(this) }

    // Leaving the screen closes the socket but keeps the seat: the player has
    // not left the game, and the token on disk will walk them back into it.
    DisposableEffect(game) {
        onDispose { game.disconnect() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ConnectionStrip(game)

        val state = game.stateOrNull
        val ended = game.status as? ConnectionStatus.Ended

        when {
            ended != null -> SessionEnded(ended.reason, onLeave)

            state == null -> Waiting(game, onLeave)

            state.phase is GamePhase.Lobby -> WaitingRoom(
                game = game,
                state = state,
                onLeave = {
                    game.leave()
                    onLeave()
                },
            )

            else -> GameScreen(game = game, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The connection, shown only when it is worth mentioning.
 *
 * A permanent indicator turns into furniture and stops being read. This is
 * invisible while the game is working, which is what makes it mean something
 * when it appears.
 */
@Composable
private fun ConnectionStrip(game: NetworkGame) {
    val status = game.status
    val (message, tint) = when (status) {
        is ConnectionStatus.Live -> return
        is ConnectionStatus.Ended -> return
        is ConnectionStatus.Connecting ->
            "Connecting…" to MaterialTheme.colorScheme.secondaryContainer
        is ConnectionStatus.Reconnecting ->
            reconnectingMessage(status.attempt) to MaterialTheme.colorScheme.errorContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFC8102E)),
        )
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The first retry is a blip and saying so would be noise; by the third it is
 * worth telling the player their seat is safe, because that is the thing they
 * are actually worried about.
 */
private fun reconnectingMessage(attempt: Int): String = when {
    attempt <= 2 -> "Reconnecting…"
    else -> "Still reconnecting. Your seat is being held — nothing is lost."
}

/**
 * Before the first welcome there is no game to show, only a wait.
 *
 * The way out matters as much as the spinner. A wrong server address fails by
 * hanging rather than by refusing — there is nothing there to say no — so
 * without a door this screen is a dead end you can only leave by killing the
 * app, which is exactly where the address would have been corrected.
 */
@Composable
private fun Waiting(game: NetworkGame, onLeave: () -> Unit) {
    val attempt = (game.status as? ConnectionStatus.Reconnecting)?.attempt ?: 0

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text("Connecting…", style = MaterialTheme.typography.bodyMedium)

            // Said only once it has clearly not worked, so a normal connection
            // is not accompanied by a troubleshooting note.
            if (attempt >= SUGGEST_CHECKING_AFTER) {
                Text(
                    "No answer yet. Check the server address under Server " +
                        "settings, and that the machine running it is on the " +
                        "same network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            OutlinedButton(onClick = onLeave) { Text("Back") }
        }
    }
}

/** Two failed attempts is a few seconds — enough to tell a blip from a mistake. */
private const val SUGGEST_CHECKING_AFTER = 2

@Composable
private fun SessionEnded(reason: String, onLeave: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                reason,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}
