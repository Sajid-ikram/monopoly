package com.monopoly.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.monopoly.core.engine.DiceRoll

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

/** Both dice as they were thrown. */
@Composable
fun DicePair(roll: DiceRoll, size: Dp, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(size * 0.25f),
    ) {
        Die(roll.first, size)
        Die(roll.second, size)
    }
}
