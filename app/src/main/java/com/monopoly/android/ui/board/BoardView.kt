package com.monopoly.android.ui.board

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monopoly.android.ui.theme.BoardEdge
import com.monopoly.android.ui.theme.BoardFace
import com.monopoly.android.ui.theme.ChanceFace
import com.monopoly.android.ui.theme.ChanceOrange
import com.monopoly.android.ui.theme.ChestBlue
import com.monopoly.android.ui.theme.ChestFace
import com.monopoly.android.ui.theme.MonopolyRed
import com.monopoly.android.ui.theme.SpaceFace
import com.monopoly.android.ui.theme.displayColor
import com.monopoly.android.ui.theme.seatColor
import com.monopoly.core.board.ChanceSpace
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.CommunityChestSpace
import com.monopoly.core.board.FreeParking
import com.monopoly.core.board.Go
import com.monopoly.core.board.GoToJail
import com.monopoly.core.board.JailSpace
import com.monopoly.core.board.Purchasable
import com.monopoly.core.board.Station
import com.monopoly.core.board.Space
import com.monopoly.core.board.Street
import com.monopoly.core.board.TaxSpace
import com.monopoly.core.board.Utility
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId

/**
 * Which edge of the board a space sits on, and how its face is turned.
 *
 * The two side edges are rotated a quarter turn, as on the printed board. The
 * top edge is **not** turned upside down, though a real board does exactly that:
 * you can pick up a board and turn it, and you cannot do that with a screen.
 * Its colour band moves to the bottom of the square instead, which keeps the
 * band facing the middle of the board while leaving the name the right way up.
 */
private enum class Edge(val rotation: Float, val bandAtTop: Boolean) {
    BOTTOM(rotation = 0f, bandAtTop = true),
    LEFT(rotation = 90f, bandAtTop = true),
    TOP(rotation = 0f, bandAtTop = false),
    RIGHT(rotation = 270f, bandAtTop = true),
}

/** Where a space sits, and how its face is turned. */
private data class Geometry(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
    val edge: Edge,
    val isCorner: Boolean,
) {
    /** Corners always read upright; only the runs between them are turned. */
    val rotation: Float get() = if (isCorner) 0f else edge.rotation
}

/**
 * Lays the forty spaces out around the edge.
 *
 * The side is made of two corners and the nine single-unit spaces between them;
 * see [CORNER_UNITS] for how deep a corner is.
 */
private fun geometryOf(index: Int, side: Dp): Geometry {
    val unit = side / UNITS_PER_SIDE
    val corner = unit * CORNER_UNITS
    val run = side - corner // where the far corner begins

    return when (index) {
        0 -> Geometry(run, run, corner, corner, Edge.BOTTOM, isCorner = true)
        in 1..9 -> Geometry(
            x = side - corner - unit * index,
            y = run,
            width = unit,
            height = corner,
            edge = Edge.BOTTOM,
            isCorner = false,
        )
        10 -> Geometry(0.dp, run, corner, corner, Edge.LEFT, isCorner = true)
        in 11..19 -> Geometry(
            x = 0.dp,
            y = side - corner - unit * (index - 10),
            width = corner,
            height = unit,
            edge = Edge.LEFT,
            isCorner = false,
        )
        20 -> Geometry(0.dp, 0.dp, corner, corner, Edge.TOP, isCorner = true)
        in 21..29 -> Geometry(
            x = corner + unit * (index - 21),
            y = 0.dp,
            width = unit,
            height = corner,
            edge = Edge.TOP,
            isCorner = false,
        )
        30 -> Geometry(run, 0.dp, corner, corner, Edge.TOP, isCorner = true)
        else -> Geometry(
            x = run,
            y = corner + unit * (index - 31),
            width = corner,
            height = unit,
            edge = Edge.RIGHT,
            isCorner = false,
        )
    }
}

/**
 * Corners are deeper than the runs between them, as on the printed board.
 *
 * The depth is what a name has to fit into, and 1.5 was not quite enough: a
 * two-line street name pushed its price off the bottom of the square.
 */
private const val CORNER_UNITS = 1.7f
private const val UNITS_PER_SIDE = 2 * CORNER_UNITS + 9

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
    /**
     * Where the pieces are *drawn*, which trails the state while a move plays
     * out. Empty means "wherever the state says", which is what a preview or a
     * freshly opened board gets.
     */
    shownPositions: Map<PlayerId, Int> = emptyMap(),
    /** The piece currently travelling, drawn raised so the eye follows it. */
    movingPlayer: PlayerId? = null,
    onSpaceClick: (Int) -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .background(BoardEdge, RoundedCornerShape(8.dp))
            .padding(3.dp)
            .background(BoardFace, RoundedCornerShape(6.dp)),
    ) {
        val side: Dp = minOf(maxWidth, maxHeight)
        val unit = side / UNITS_PER_SIDE
        val occupants = state.players
            .filter { it.isActive }
            .groupBy { shownPositions[it.id] ?: it.position }

        ClassicBoard.spaces.forEach { space ->
            val geometry = geometryOf(space.index, side)
            val deed = state.deeds[space.index]
            SpaceSlot(
                space = space,
                deed = deed,
                ownerSeat = deed?.let { owned ->
                    state.players.indexOfFirst { it.id == owned.owner }
                },
                tokens = occupants[space.index].orEmpty().map { player ->
                    val seat = state.players.indexOfFirst { it.id == player.id }
                    TokenMark(
                        initial = player.name.take(1).uppercase(),
                        color = seatColor(seat),
                        inJail = player.inJail && space.index == ClassicBoard.JAIL_INDEX,
                        travelling = player.id == movingPlayer,
                    )
                },
                geometry = geometry,
                unit = unit,
                modifier = Modifier
                    .offset(x = geometry.x, y = geometry.y)
                    .clickable { onSpaceClick(space.index) },
            )
        }

        val corner = unit * CORNER_UNITS
        Box(
            modifier = Modifier
                .offset(x = corner, y = corner)
                .size(side - corner * 2)
                .padding(unit * 0.4f),
            contentAlignment = Alignment.Center,
        ) {
            BoardCentre(state = state, unit = unit)
        }
    }
}

/** A player's piece, as it appears on a square. */
private data class TokenMark(
    val initial: String,
    val color: Color,
    val inJail: Boolean,
    val travelling: Boolean = false,
)

/**
 * One square: the turned face, plus the pieces standing on it.
 *
 * Tokens are drawn outside the rotation on purpose. If they turned with the
 * face, a piece on a side edge would show its letter lying down — the board
 * rotates, the players do not.
 */
@Composable
private fun SpaceSlot(
    space: Space,
    deed: Deed?,
    ownerSeat: Int?,
    tokens: List<TokenMark>,
    geometry: Geometry,
    unit: Dp,
    modifier: Modifier = Modifier,
) {
    // A quarter-turned face is laid out in the un-turned orientation first, so
    // its width and height swap relative to the slot it will occupy.
    val quarterTurned = geometry.rotation == 90f || geometry.rotation == 270f
    val naturalWidth = if (quarterTurned) geometry.height else geometry.width
    val naturalHeight = if (quarterTurned) geometry.width else geometry.height

    Box(
        modifier = modifier
            .size(geometry.width, geometry.height)
            .padding(0.4.dp)
            .background(
                if (deed != null) ownerTint(ownerSeat) else space.baseColor(),
                RoundedCornerShape(1.dp),
            )
            .border(0.6.dp, BoardEdge.copy(alpha = 0.5f), RoundedCornerShape(1.dp)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                // requiredSize, not size: a plain size is coerced by the parent,
                // which on the side edges would squash the face from the
                // square's depth down to its width before the rotation ever
                // happened, cutting the price off the bottom.
                .requiredSize(naturalWidth, naturalHeight)
                .rotate(geometry.rotation),
        ) {
            if (geometry.isCorner) {
                CornerFace(space, unit)
            } else {
                EdgeFace(space, deed, unit, geometry.edge.bandAtTop)
            }
        }

        if (tokens.isNotEmpty()) {
            TokenCluster(
                tokens = tokens,
                unit = unit,
                modifier = Modifier.align(geometry.edge.tokenAlignment(geometry.isCorner)),
            )
        }
    }
}

/** Pieces sit toward the outer rim, clear of the colour band and the name. */
private fun Edge.tokenAlignment(isCorner: Boolean): Alignment = when {
    isCorner -> Alignment.BottomStart
    this == Edge.BOTTOM -> Alignment.BottomCenter
    this == Edge.LEFT -> Alignment.CenterStart
    this == Edge.TOP -> Alignment.TopCenter
    else -> Alignment.CenterEnd
}

@Composable
private fun EdgeFace(space: Space, deed: Deed?, unit: Dp, bandAtTop: Boolean) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (bandAtTop) ColourBand(space, deed, unit)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = unit * 0.04f, vertical = unit * 0.06f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val label = space.boardLabel()
            // Every square gets its symbol back now that the face is no longer
            // being squashed: a station or utility is recognised by its icon
            // long before anyone reads the name.
            SpaceGlyph(space, unit, visible = true)

            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = (unit.value * 0.2f).coerceIn(5f, 10f).sp,
                        lineHeight = (unit.value * 0.23f).coerceIn(6f, 11f).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = BoardEdge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            space.subtitle(deed)?.let { (text, tint) ->
                Text(
                    text,
                    style = TextStyle(
                        fontSize = (unit.value * 0.18f).coerceIn(5f, 9f).sp,
                        fontWeight = if (tint == MonopolyRed) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = tint,
                    maxLines = 1,
                )
            }
        }

        if (!bandAtTop) ColourBand(space, deed, unit)
    }
}

@Composable
private fun ColumnScope.ColourBand(space: Space, deed: Deed?, unit: Dp) {
    if (space !is Street) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(unit * 0.34f)
            .background(space.group.displayColor)
            .border(0.6.dp, BoardEdge.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        if (deed != null && deed.houses > 0) Buildings(deed.houses, unit)
    }
}

/** The little symbol that tells a square apart at a glance. */
@Composable
private fun SpaceGlyph(space: Space, unit: Dp, visible: Boolean) {
    if (!visible) return
    val glyph = when (space) {
        is Station -> "🚂"
        is Utility -> if (space.index == 12) "💡" else "🚰"
        is ChanceSpace -> "?"
        is CommunityChestSpace -> "🎁"
        is TaxSpace -> "💰"
        else -> null
    } ?: return

    Text(
        glyph,
        style = TextStyle(
            fontSize = (unit.value * (if (space is ChanceSpace) 0.6f else 0.32f))
                .coerceIn(8f, 26f).sp,
            fontWeight = FontWeight.Black,
        ),
        color = if (space is ChanceSpace) ChanceOrange else BoardEdge,
        maxLines = 1,
    )
}

@Composable
private fun CornerFace(space: Space, unit: Dp) {
    val big = (unit.value * 0.34f).coerceIn(8f, 17f).sp
    val small = (unit.value * 0.18f).coerceIn(5f, 9f).sp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(unit * 0.1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (space) {
            is Go -> {
                Text(
                    "GO",
                    style = TextStyle(fontSize = big * 1.6f, fontWeight = FontWeight.Black),
                    color = MonopolyRed,
                )
                Text(
                    "COLLECT £200",
                    style = TextStyle(fontSize = small, fontWeight = FontWeight.SemiBold),
                    color = BoardEdge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }

            is JailSpace -> {
                Text("⛓", style = TextStyle(fontSize = big), color = BoardEdge)
                Text(
                    "JAIL",
                    style = TextStyle(fontSize = big, fontWeight = FontWeight.Black),
                    color = BoardEdge,
                )
                Text(
                    "just visiting",
                    style = TextStyle(fontSize = small),
                    color = BoardEdge.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }

            is FreeParking -> {
                Text("🅿", style = TextStyle(fontSize = big * 1.2f), color = MonopolyRed)
                Text(
                    "FREE\nPARKING",
                    style = TextStyle(
                        fontSize = small,
                        lineHeight = small * 1.2f,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = BoardEdge,
                    textAlign = TextAlign.Center,
                )
            }

            is GoToJail -> {
                Text("👮", style = TextStyle(fontSize = big * 1.2f), color = BoardEdge)
                Text(
                    "GO TO\nJAIL",
                    style = TextStyle(
                        fontSize = small,
                        lineHeight = small * 1.2f,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = BoardEdge,
                    textAlign = TextAlign.Center,
                )
            }

            else -> Text(space.name, style = TextStyle(fontSize = small), color = BoardEdge)
        }
    }
}

@Composable
private fun TokenCluster(tokens: List<TokenMark>, unit: Dp, modifier: Modifier = Modifier) {
    val diameter = (unit * 0.4f).coerceIn(9.dp, 20.dp)
    Row(
        modifier = modifier.padding(unit * 0.05f),
        horizontalArrangement = Arrangement.spacedBy(-diameter * 0.25f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Crowded squares are normal in Monopoly, so pieces overlap slightly
        // rather than squeezing the square's own label away.
        tokens.take(MAX_VISIBLE_TOKENS).forEach { token ->
            // A travelling piece lifts off the board, so the eye can follow it
            // across a run of squares instead of losing it among the others.
            val lift by animateFloatAsState(
                targetValue = if (token.travelling) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
                label = "token lift",
            )
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val raised = 1f + lift * TRAVEL_LIFT
                        scaleX = raised
                        scaleY = raised
                        shadowElevation = lift * TRAVEL_SHADOW
                        shape = CircleShape
                        clip = false
                    }
                    .size(diameter)
                    .clip(CircleShape)
                    .background(token.color)
                    .border(
                        width = if (token.inJail) 1.6.dp else 1.dp,
                        color = if (token.inJail) MonopolyRed else Color.White,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    token.initial,
                    style = TextStyle(
                        fontSize = (diameter.value * 0.55f).coerceIn(6f, 12f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
        if (tokens.size > MAX_VISIBLE_TOKENS) {
            Text(
                "+${tokens.size - MAX_VISIBLE_TOKENS}",
                style = TextStyle(fontSize = (diameter.value * 0.45f).coerceIn(5f, 10f).sp),
                color = BoardEdge,
            )
        }
    }
}

private const val MAX_VISIBLE_TOKENS = 4

/** How much a travelling piece grows, and how far it floats above the board. */
private const val TRAVEL_LIFT = 0.45f
private const val TRAVEL_SHADOW = 10f

/** Four houses, or one hotel. */
@Composable
private fun Buildings(houses: Int, unit: Dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(unit * 0.03f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (houses == Deed.HOTEL) {
            Box(
                modifier = Modifier
                    .width(unit * 0.4f)
                    .height(unit * 0.22f)
                    .background(MonopolyRed, RoundedCornerShape(1.dp))
                    .border(0.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(1.dp)),
            )
        } else {
            repeat(houses) {
                Box(
                    modifier = Modifier
                        .size(unit * 0.15f)
                        .background(Color(0xFF0E7A3C), RoundedCornerShape(0.5.dp))
                        .border(0.4.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(0.5.dp)),
                )
            }
        }
    }
}

/** A faint wash of the owner's colour, so holdings read at a glance. */
private fun ownerTint(seatIndex: Int?): Color =
    if (seatIndex == null || seatIndex < 0) SpaceFace
    else seatColor(seatIndex).copy(alpha = 0.16f)

/**
 * Chance and Community Chest carry their colour across the whole square.
 *
 * These are solid, pre-mixed colours rather than a translucent tint: the square
 * sits on green felt, and a see-through orange over green comes out olive.
 */
private fun Space.baseColor(): Color = when (this) {
    is ChanceSpace -> ChanceFace
    is CommunityChestSpace -> ChestFace
    else -> SpaceFace
}

@Composable
private fun BoardCentre(state: GameState, unit: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(unit * 0.35f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(unit * 0.5f)) {
            DeckCard("CHANCE", ChanceOrange, unit, tilt = -7f)
            DeckCard("COMMUNITY\nCHEST", ChestBlue, unit, tilt = 6f)
        }

        Box(
            modifier = Modifier
                .background(MonopolyRed, RoundedCornerShape(2.dp))
                .border(1.dp, BoardEdge.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                .padding(horizontal = unit * 0.5f, vertical = unit * 0.16f),
        ) {
            Text(
                "MONOPOLY",
                style = TextStyle(
                    fontSize = (unit.value * 0.5f).coerceIn(13f, 34f).sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (unit.value * 0.06f).coerceIn(1f, 5f).sp,
                ),
                color = Color.White,
                maxLines = 1,
            )
        }

        state.turn.lastRoll?.let { roll ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DicePair(roll, size = (unit * 0.75f).coerceIn(18.dp, 44.dp))
                Text(
                    if (roll.isDoubles) "Doubles — ${roll.total}" else "${roll.total}",
                    style = TextStyle(
                        fontSize = (unit.value * 0.22f).coerceIn(8f, 14f).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = BoardEdge.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = unit * 0.1f),
                )
            }
        }

        if (state.freeParkingPot > 0) {
            Text(
                "Free Parking pot: £${state.freeParkingPot}",
                style = TextStyle(fontSize = (unit.value * 0.2f).coerceIn(7f, 12f).sp),
                color = BoardEdge.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun DeckCard(label: String, color: Color, unit: Dp, tilt: Float) {
    Box(
        modifier = Modifier
            .rotate(tilt)
            .size(width = unit * 1.9f, height = unit * 1.2f)
            .background(color, RoundedCornerShape(2.dp))
            .border(1.dp, BoardEdge.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = (unit.value * 0.17f).coerceIn(5f, 10f).sp,
                lineHeight = (unit.value * 0.2f).coerceIn(6f, 11f).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/** The line under the name: a price, a tax, or a mortgage warning. */
private fun Space.subtitle(deed: Deed?): Pair<String, Color>? = when {
    deed?.mortgaged == true -> "MORTGAGED" to MonopolyRed
    this is TaxSpace -> "£$amount" to BoardEdge.copy(alpha = 0.8f)
    this is Purchasable -> "£$price" to BoardEdge.copy(alpha = 0.75f)
    else -> null
}

/**
 * Names cut to fit a square, with the line breaks chosen rather than left to
 * the layout — an automatic wrap gives you "Northumberla / nd".
 *
 * A square is nine units wide on a phone, so the long ones are hyphenated the
 * way the printed board does it rather than shrunk until nobody can read them.
 */
private fun Space.boardLabel(): String = when (index) {
    1 -> "Old Kent\nRoad"
    3 -> "White-\nchapel"
    4 -> "Income\nTax"
    5 -> "King's\nCross"
    6 -> "The Angel\nIslington"
    8 -> "Euston\nRoad"
    9 -> "Penton-\nville Rd"
    11 -> "Pall Mall"
    12 -> "Electric\nCo."
    13 -> "Whitehall"
    14 -> "Northum-\nberland"
    15 -> "Maryle-\nbone"
    16 -> "Bow\nStreet"
    18 -> "Marlbor-\nough St"
    19 -> "Vine\nStreet"
    21 -> "Strand"
    23 -> "Fleet\nStreet"
    24 -> "Trafalgar\nSquare"
    25 -> "Fenchurch"
    26 -> "Leicester\nSquare"
    27 -> "Coventry\nStreet"
    28 -> "Water\nWorks"
    29 -> "Piccadilly"
    31 -> "Regent\nStreet"
    32 -> "Oxford\nStreet"
    34 -> "Bond\nStreet"
    35 -> "Liverpool\nStreet"
    37 -> "Park Lane"
    38 -> "Super\nTax"
    39 -> "Mayfair"
    else -> when (this) {
        is CommunityChestSpace -> "Chest"
        is ChanceSpace -> "CHANCE"
        else -> ""
    }
}
