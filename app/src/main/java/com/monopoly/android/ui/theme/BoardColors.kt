package com.monopoly.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.monopoly.core.board.ColorGroup

/** The printed colours of the eight street groups. */
val ColorGroup.displayColor: Color
    get() = when (this) {
        ColorGroup.BROWN -> Color(0xFF955436)
        ColorGroup.LIGHT_BLUE -> Color(0xFFAAE0FA)
        ColorGroup.PINK -> Color(0xFFD93A96)
        ColorGroup.ORANGE -> Color(0xFFF7941D)
        ColorGroup.RED -> Color(0xFFED1B24)
        ColorGroup.YELLOW -> Color(0xFFFEF200)
        ColorGroup.GREEN -> Color(0xFF1FB25A)
        ColorGroup.DARK_BLUE -> Color(0xFF0072BB)
    }

/**
 * Token colours, by seat.
 *
 * Chosen to stay distinguishable for the most common forms of colour blindness,
 * since a player's own piece being hard to find on the board is a real problem
 * rather than a cosmetic one. Tokens also carry their initial, so colour is
 * never the only thing separating two pieces.
 */
private val seatColors = listOf(
    Color(0xFFD32F2F), // red
    Color(0xFF1976D2), // blue
    Color(0xFF388E3C), // green
    Color(0xFFF57C00), // orange
    Color(0xFF7B1FA2), // purple
    Color(0xFF00838F), // teal
    Color(0xFFC2185B), // magenta
    Color(0xFF5D4037), // brown
)

fun seatColor(seatIndex: Int): Color = seatColors[seatIndex.mod(seatColors.size)]

/** The felt-green board face. */
val BoardFace = Color(0xFFCDE6D0)
val BoardEdge = Color(0xFF2E3B2F)
val SpaceFace = Color(0xFFF7F7F2)
