package com.monopoly.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.monopoly.android.game.LocalGame
import com.monopoly.android.game.NetworkGame
import com.monopoly.android.game.SessionIntent
import com.monopoly.android.net.SessionStore
import com.monopoly.android.ui.board.GameScreen
import com.monopoly.android.ui.lobby.HomeScreen
import com.monopoly.android.ui.lobby.OnlineGameScreen

/** Which of the three things the app can be showing. */
private sealed interface Route {
    data object Home : Route
    data class Online(val game: NetworkGame) : Route
    data class PassAndPlay(val game: LocalGame) : Route
}

/**
 * The whole app: a home screen, and whichever game was started from it.
 *
 * Three destinations and no navigation library. The state that matters lives on
 * the server, and the only thing this has to remember is which game is open —
 * a back stack would be pretending there is more here than there is.
 */
@Composable
fun MonopolyApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SessionStore(context) }
    var route: Route by remember { mutableStateOf(Route.Home) }

    when (val current = route) {
        is Route.Home -> HomeScreen(
            initialName = store.displayName,
            initialServerUrl = store.serverUrl,
            savedSeat = store.lastSeat(),
            onPlayOnline = { intent, serverUrl ->
                store.serverUrl = serverUrl
                nameOf(intent)?.let { store.displayName = it }
                route = Route.Online(NetworkGame(intent, serverUrl, store))
            },
            onPassAndPlay = {
                route = Route.PassAndPlay(LocalGame.newGame(playerCount = 3))
            },
            modifier = modifier,
        )

        is Route.Online -> OnlineGameScreen(
            game = current.game,
            onLeave = { route = Route.Home },
            modifier = modifier,
        )

        is Route.PassAndPlay -> GameScreen(game = current.game, modifier = modifier)
    }
}

private fun nameOf(intent: SessionIntent): String? = when (intent) {
    is SessionIntent.Host -> intent.displayName
    is SessionIntent.JoinByCode -> intent.displayName
    // A resumed seat already has its name on the server; the local one is only
    // a copy of it, so there is nothing new to save.
    is SessionIntent.Resume -> null
}
