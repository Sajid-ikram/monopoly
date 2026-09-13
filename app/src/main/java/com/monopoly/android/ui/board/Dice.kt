package com.monopoly.android.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.monopoly.core.engine.DiceRoll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A die, drawn with pips.
 *
 * Worth the few lines over printing the number: pips are read at a glance
 * without parsing, they make doubles obvious by symmetry, and they make the
 * dice look like an object on the board rather than a label about it.
 */
@Composable
fun Die(
    value: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    face: Color = Color.White,
    pip: Color = Color(0xFF1A1A1A),
) {
    Canvas(
        modifier = modifier
            .size(size)
            .shadow(2.dp, RoundedCornerShape(size * 0.18f))
            .clip(RoundedCornerShape(size * 0.18f)),
    ) {
        drawRect(face)

        val radius = this.size.minDimension * PIP_RADIUS
        // Pip positions on a 3x3 grid, in fractions of the die's face.
        val low = this.size.width * 0.26f
        val mid = this.size.width * 0.5f
        val high = this.size.width * 0.74f

        val positions: List<Offset> = when (value) {
            1 -> listOf(Offset(mid, mid))
            2 -> listOf(Offset(low, low), Offset(high, high))
            3 -> listOf(Offset(low, low), Offset(mid, mid), Offset(high, high))
            4 -> listOf(
                Offset(low, low), Offset(high, low),
                Offset(low, high), Offset(high, high),
            )
            5 -> listOf(
                Offset(low, low), Offset(high, low), Offset(mid, mid),
                Offset(low, high), Offset(high, high),
            )
            6 -> listOf(
                Offset(low, low), Offset(high, low),
                Offset(low, mid), Offset(high, mid),
                Offset(low, high), Offset(high, high),
            )
            else -> emptyList()
        }

        positions.forEach { centre -> drawCircle(pip, radius, centre) }
    }
}

private const val PIP_RADIUS = 0.085f

/**
 * Both dice, thrown rather than simply displayed.
 *
 * The faces shown while tumbling are invented on the spot, and deliberately so:
 * they are decoration, not a draw. The real values come from the engine's
 * seeded generator and are what the dice settle on, so nothing here can affect
 * the roll — or hint at it early.
 */
@Composable
fun DicePair(roll: DiceRoll, size: Dp, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(roll) }
    var tumbling by remember { mutableStateOf(false) }
    val settle = remember { Animatable(1f) }
    val spin = remember { Animatable(0f) }

    LaunchedEffect(roll) {
        tumbling = true
        spin.snapTo(0f)
        launch {
            spin.animateTo(
                targetValue = 1f,
                animationSpec = tween(TUMBLE_STEPS * TUMBLE_MILLIS.toInt(), easing = LinearEasing),
            )
        }
        repeat(TUMBLE_STEPS) {
            shown = DiceRoll(Random.nextInt(1, 7), Random.nextInt(1, 7))
            delay(TUMBLE_MILLIS)
        }
        shown = roll
        tumbling = false
        // A short overshoot, so the dice look like they landed on something
        // rather than being set down.
        settle.snapTo(1.3f)
        settle.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f))
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(size * 0.25f),
    ) {
        Die(
            value = shown.first,
            size = size,
            modifier = Modifier.diceMotion(settle.value, spin.value, tumbling, clockwise = true),
        )
        Die(
            value = shown.second,
            size = size,
            modifier = Modifier.diceMotion(settle.value, spin.value, tumbling, clockwise = false),
        )
    }
}

/** The two dice spin opposite ways, which reads as a throw rather than a slide. */
private fun Modifier.diceMotion(
    settle: Float,
    spin: Float,
    tumbling: Boolean,
    clockwise: Boolean,
) = graphicsLayer {
    scaleX = settle
    scaleY = settle
    rotationZ = if (tumbling) {
        (if (clockwise) 1f else -1f) * spin * TUMBLE_SWEEP_DEGREES
    } else {
        0f
    }
}

private const val TUMBLE_STEPS = 9
private const val TUMBLE_MILLIS = 55L
private const val TUMBLE_SWEEP_DEGREES = 220f
