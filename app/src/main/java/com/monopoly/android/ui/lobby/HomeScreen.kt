package com.monopoly.android.ui.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monopoly.android.game.SessionIntent
import com.monopoly.android.net.SavedSeat
import com.monopoly.android.ui.theme.MonopolyRed

/**
 * Where a game starts: host one, or type in the code a friend read out.
 *
 * Everything on this screen is one of the four things a player actually wants
 * to do. There is no account, no lobby list and no matchmaking, because the
 * game is played with people who are already talking to each other — the code
 * travels over the group chat they are in anyway.
 */
@Composable
fun HomeScreen(
    initialName: String,
    initialServerUrl: String,
    savedSeat: SavedSeat?,
    onPlayOnline: (SessionIntent, serverUrl: String) -> Unit,
    onPassAndPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initialName) }
    var code by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var showServer by remember { mutableStateOf(false) }

    val trimmedName = name.trim()
    val nameReady = trimmedName.isNotEmpty()

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
                "MONOPOLY",
                style = TextStyle(
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                ),
                color = MonopolyRed,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            savedSeat?.let { seat ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "You were in game ${seat.gameCode}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Your seat is still there, with your money and property in it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                onPlayOnline(SessionIntent.Resume(seat), serverUrl)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Rejoin ${seat.gameCode}") }
                    }
                }
            }

            Button(
                onClick = { onPlayOnline(SessionIntent.Host(trimmedName), serverUrl) },
                enabled = nameReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Host a new game") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    "  or join one  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            OutlinedTextField(
                value = code,
                // The server's alphabet has no vowels and no O/0 or I/1, so
                // anything outside it is a typo rather than a code — dropping it
                // as it is typed is kinder than refusing the whole thing later.
                onValueChange = { typed ->
                    code = typed.uppercase().filter { it in GAME_CODE_ALPHABET }.take(CODE_LENGTH)
                },
                label = { Text("Game code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                textStyle = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    onPlayOnline(SessionIntent.JoinByCode(code, trimmedName), serverUrl)
                },
                enabled = nameReady && code.length == CODE_LENGTH,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join game") }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onPassAndPlay,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pass and play on this device") }

            TextButton(
                onClick = { showServer = !showServer },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (showServer) "Hide server settings" else "Server settings") }

            if (showServer) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server") },
                    supportingText = {
                        Text(
                            "The address of the machine running the game server. " +
                                "10.0.2.2 is how the emulator reaches your own computer.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The codes the server can issue.
 *
 * No vowels, so no code is ever an unfortunate word, and none of O/0 or I/1,
 * which people mistype when reading a code aloud. Kept in step with the
 * server's own alphabet by hand: if they disagree, the cost is a valid code
 * this screen refuses to accept.
 */
private const val GAME_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXYZ23456789"
private const val CODE_LENGTH = 4
private const val MAX_NAME_LENGTH = 16
