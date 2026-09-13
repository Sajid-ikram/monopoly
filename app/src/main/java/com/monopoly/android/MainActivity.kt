package com.monopoly.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.monopoly.android.game.LocalGame
import com.monopoly.android.ui.board.GameScreen
import com.monopoly.android.ui.theme.MonopolyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MonopolyTheme {
                // A hot-seat game for now: one device, players take turns. The
                // networked session slots in behind the same screen later.
                val game = remember { LocalGame.newGame(playerCount = 3) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GameScreen(
                        game = game,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 600)
@Composable
private fun GameScreenPreview() {
    MonopolyTheme {
        GameScreen(game = remember { LocalGame.newGame(playerCount = 3, seed = 1L) })
    }
}
