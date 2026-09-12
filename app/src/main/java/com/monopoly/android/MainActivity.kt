package com.monopoly.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.monopoly.android.ui.theme.MonopolyTheme
import com.monopoly.core.board.ClassicBoard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MonopolyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EngineStatus(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * A placeholder screen, standing in until the board UI is built.
 *
 * It reads real data out of `:core` rather than showing static text, so that
 * this screen failing to render is a genuine signal that the client is no
 * longer wired to the rules engine.
 */
@Composable
fun EngineStatus(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Monopoly", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Rules engine loaded: ${ClassicBoard.SPACE_COUNT} spaces, " +
                "${ClassicBoard.purchasableIndices.size} ownable.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Board and networking UI not built yet.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun EngineStatusPreview() {
    MonopolyTheme {
        EngineStatus()
    }
}
