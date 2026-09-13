package com.monopoly.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monopoly.android.game.BoardAnimator
import com.monopoly.android.game.GameHolder

/**
 * The game, laid out for whatever screen it finds itself on.
 *
 * Wide screens put the board beside the controls; narrow ones stack them, with
 * the board kept square so it never squashes. Both arrangements render the same
 * two composables — the layout changes, the content does not.
 */
@Composable
fun GameScreen(game: GameHolder, modifier: Modifier = Modifier) {
    val animator = remember { BoardAnimator() }

    // One consumer for the whole screen. The game never waits on it: updates
    // are queued as they happen and played out here at the board's own pace, so
    // a fast tapper is never blocked by an animation still finishing, and a
    // slow connection never has to wait for one either.
    LaunchedEffect(game) {
        animator.syncTo(game.state)
        for (update in game.updates) {
            animator.apply(update, game.state)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight

        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BoardView(
                    state = game.state,
                    shownPositions = animator.positions,
                    movingPlayer = animator.moving,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                )
                ControlPanel(
                    game = game,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoardView(
                    state = game.state,
                    shownPositions = animator.positions,
                    movingPlayer = animator.moving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                ControlPanel(game = game, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
