package com.monopoly.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monopoly.android.ui.theme.BoardEdge
import com.monopoly.android.ui.theme.BoardFace
import com.monopoly.android.ui.theme.SpaceFace
import com.monopoly.android.ui.theme.displayColor
import com.monopoly.android.ui.theme.seatColor
import com.monopoly.core.board.ChanceSpace
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.CommunityChestSpace
import com.monopoly.core.board.Purchasable
import com.monopoly.core.board.Railroad
import com.monopoly.core.board.Space
import com.monopoly.core.board.Street
import com.monopoly.core.board.TaxSpace
import com.monopoly.core.board.Utility
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GameState

/** Where a space sits on the 11 x 11 grid, in (row, column) from the top left. */
private fun gridPosition(index: Int): Pair<Int, Int> = when (index) {
    // GO is the bottom-right corner and play runs anticlockwise from there.
    in 0..10 -> 10 to (10 - index)
    in 11..20 -> (20 - index) to 0
    in 21..30 -> 0 to (index - 20)
    else -> (index - 30) to 10
}

/**
 * The board.
 *
 * Rendered purely from [GameState] — there is no separate view model holding a
 * parallel idea of where the pieces are. Whatever the engine says is what shows,
 * which is the same discipline that keeps a networked client honest.
 */
@Composable
fun BoardView(
    state: GameState,
    modifier: Modifier = Modifier,
    onSpaceClick: (Int) -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .background(BoardFace, RoundedCornerShape(6.dp))
            .border(2.dp, BoardEdge, RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        // Square cells, sized to whichever dimension is tighter, so the board
        // stays square on a phone in portrait and a tablet in landscape alike.
        val cell: Dp = minOf(maxWidth, maxHeight) / GRID
        val occupants = state.players
            .filter { it.isActive }
            .groupBy { it.position }

        ClassicBoard.spaces.forEach { space ->
            val (row, column) = gridPosition(space.index)
            SpaceCell(
                space = space,
                deed = state.deeds[space.index],
                ownerSeat = state.deeds[space.index]?.let { deed ->
                    state.players.indexOfFirst { it.id == deed.owner }
                },
                tokens = occupants[space.index].orEmpty().map { player ->
                    TokenMark(
                        initial = player.name.take(1).uppercase(),
                        color = seatColor(state.players.indexOfFirst { it.id == player.id }),
                        inJail = player.inJail && space.index == ClassicBoard.JAIL_INDEX,
                    )
                },
                size = cell,
                modifier = Modifier
                    .offset(x = cell * column, y = cell * row)
                    .clickable { onSpaceClick(space.index) },
            )
        }

        // The middle of the board, left for the deck art and the big status.
        Box(
            modifier = Modifier
                .offset(x = cell, y = cell)
                .size(cell * (GRID - 2))
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoardCentre(state = state, width = cell * (GRID - 2))
        }
    }
}

private const val GRID = 11

/** A player's piece, as it appears on a square. */
private data class TokenMark(val initial: String, val color: Color, val inJail: Boolean)

@Composable
private fun SpaceCell(
    space: Space,
    deed: Deed?,
    ownerSeat: Int?,
    tokens: List<TokenMark>,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val owned = deed != null
    Column(
        modifier = modifier
            .size(size)
            .padding(0.5.dp)
            .background(if (owned) ownerTint(ownerSeat) else SpaceFace, RoundedCornerShape(2.dp))
            .border(0.5.dp, BoardEdge.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
    ) {
        // The colour band, on the edge facing the middle of the board.
        if (space is Street) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size * 0.2f)
                    .background(space.group.displayColor),
                contentAlignment = Alignment.Center,
            ) {
                if (deed != null && deed.houses > 0) {
                    Buildings(houses = deed.houses, size = size)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = space.shortLabel(),
                style = TextStyle(
                    fontSize = (size.value * 0.15f).coerceIn(5f, 9f).sp,
                    lineHeight = (size.value * 0.17f).coerceIn(6f, 10f).sp,
                    fontWeight = FontWeight.Medium,
                ),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = BoardEdge,
            )

            if (deed?.mortgaged == true) {
                Text(
                    "MORTGAGED",
                    style = TextStyle(fontSize = (size.value * 0.12f).coerceIn(4f, 7f).sp),
                    color = Color(0xFFB00020),
                    maxLines = 1,
                )
            } else if (space is Purchasable) {
                Text(
                    "$${space.price}",
                    style = TextStyle(fontSize = (size.value * 0.14f).coerceIn(5f, 8f).sp),
                    color = BoardEdge.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                // Everyone on this square, shown at once. Crowded corners are
                // normal in Monopoly, so pieces shrink rather than overflow.
                tokens.take(MAX_VISIBLE_TOKENS).forEach { token ->
                    TokenDot(token, size)
                }
            }
        }
    }
}

private const val MAX_VISIBLE_TOKENS = 4

@Composable
private fun TokenDot(token: TokenMark, cell: Dp) {
    val diameter = (cell * 0.26f).coerceAtLeast(8.dp)
    Box(
        modifier = Modifier
            .padding(0.5.dp)
            .size(diameter)
            .clip(CircleShape)
            .background(token.color)
            .border(
                width = if (token.inJail) 1.5.dp else 0.5.dp,
                color = if (token.inJail) Color(0xFFB00020) else Color.White,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            token.initial,
            style = TextStyle(
                fontSize = (diameter.value * 0.55f).coerceIn(5f, 10f).sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Four houses, or one hotel. */
@Composable
private fun Buildings(houses: Int, size: Dp) {
    Row(horizontalArrangement = Arrangement.Center) {
        if (houses == Deed.HOTEL) {
            Box(
                modifier = Modifier
                    .width(size * 0.22f)
                    .height(size * 0.12f)
                    .background(Color(0xFFB71C1C), RoundedCornerShape(1.dp)),
            )
        } else {
            repeat(houses) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 0.3.dp)
                        .size(size * 0.1f)
                        .background(Color(0xFF1B5E20), RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/** A faint wash of the owner's colour, so holdings are readable at a glance. */
private fun ownerTint(seatIndex: Int?): Color =
    if (seatIndex == null || seatIndex < 0) SpaceFace
    else seatColor(seatIndex).copy(alpha = 0.18f)

@Composable
private fun BoardCentre(state: GameState, width: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "MONOPOLY",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = (width.value * 0.02f).sp,
            ),
            color = BoardEdge,
            maxLines = 1,
        )
        state.turn.lastRoll?.let { roll ->
            Text(
                "${roll.first} + ${roll.second} = ${roll.total}",
                style = MaterialTheme.typography.bodyMedium,
                color = BoardEdge.copy(alpha = 0.8f),
            )
        }
        if (state.freeParkingPot > 0) {
            Text(
                "Free Parking: $${state.freeParkingPot}",
                style = MaterialTheme.typography.labelSmall,
                color = BoardEdge.copy(alpha = 0.7f),
            )
        }
    }
}

/** A name short enough to fit on a square. */
private fun Space.shortLabel(): String = when (this) {
    is Street -> name
        .removeSuffix(" Avenue")
        .removeSuffix(" Place")
        .removeSuffix(" Gardens")
        .removeSuffix(" Walk")
    is Railroad -> name.removeSuffix(" Railroad")
    is Utility -> name
    is TaxSpace -> "$name\n$$amount"
    is ChanceSpace -> "?"
    is CommunityChestSpace -> "Chest"
    else -> name
}
