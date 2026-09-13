package com.monopoly.android.ui.lobby

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monopoly.android.game.NetworkGame
import com.monopoly.android.ui.theme.BoardEdge
import com.monopoly.android.ui.theme.seatColor
import com.monopoly.core.engine.Command
import com.monopoly.core.model.GameState

/**
 * The lobby: the code to share, and who has arrived so far.
 *
 * This is the screen the host stares at while reading four letters into a group
 * chat, so the code is the largest thing on it and the list underneath it
 * updates as people appear — which is the only confirmation that the code was
 * heard correctly.
 */
@Composable
fun WaitingRoom(
    game: NetworkGame,
    state: GameState,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val code = game.seat?.gameCode.orEmpty()
    val enoughPlayers = state.players.size >= MINIMUM_PLAYERS

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Game code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    code,
                    style = TextStyle(
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 14.sp,
                    ),
                    color = BoardEdge,
                )
            }

            OutlinedButton(
                onClick = {
                    // The code has to reach the people you are playing with, and
                    // they are already in a chat somewhere. Handing it to the
                    // share sheet beats any list of apps this screen could draw.
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Join my Monopoly game. The code is $code.",
                        )
                    }
                    context.startActivity(Intent.createChooser(share, "Share the code"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share the code") }

            HorizontalDivider()

            Text(
                "In the lobby (${state.players.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.players.forEachIndexed { seatIndex, player ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(seatColor(seatIndex)),
                        )
                        Text(
                            player.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (game.controls(player.id)) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                game.controls(player.id) -> "you"
                                seatIndex == 0 -> "host"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            if (game.isHost) {
                Button(
                    onClick = {
                        game.seat?.let { game.dispatch(Command.StartGame(it.playerId)) }
                    },
                    enabled = enoughPlayers,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start game") }
                if (!enoughPlayers) {
                    Text(
                        "Waiting for at least one more player.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Waiting for ${state.players.firstOrNull()?.name ?: "the host"} to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text("Leave")
            }
        }
    }
}

private const val MINIMUM_PLAYERS = 2
