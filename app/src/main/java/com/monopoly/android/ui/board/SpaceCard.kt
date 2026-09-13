package com.monopoly.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.monopoly.android.ui.theme.displayColor
import com.monopoly.core.board.ChanceSpace
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.CommunityChestSpace
import com.monopoly.core.board.FreeParking
import com.monopoly.core.board.Go
import com.monopoly.core.board.GoToJail
import com.monopoly.core.board.JailSpace
import com.monopoly.core.board.Purchasable
import com.monopoly.core.board.Station
import com.monopoly.core.board.Street
import com.monopoly.core.board.TaxSpace
import com.monopoly.core.board.Utility
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GameState

/**
 * The title deed for one square, as if you had picked the card up off the table.
 *
 * On a real board everybody can read everybody's deeds: you lean over and look
 * at what the rent would be before you decide whether to trade for it. Without
 * this the only property a player can inspect is their own, which quietly makes
 * the game less strategic than the physical one it is copying.
 *
 * Read-only on purpose. Acting on a property stays in the control panel, where
 * the phase decides what is legal; this only answers questions.
 */
@Composable
fun SpaceCard(state: GameState, spaceIndex: Int, onDismiss: () -> Unit) {
    val space = ClassicBoard[spaceIndex]
    val deed = state.deeds[spaceIndex]

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (space is Street) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(space.group.displayColor, RoundedCornerShape(4.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(4.dp),
                            ),
                    )
                }

                Text(space.name, style = MaterialTheme.typography.titleLarge)

                Ownership(state, deed)

                when (space) {
                    is Street -> StreetRents(state, space, deed)
                    is Station -> StationRents(state, deed)
                    is Utility -> UtilityRents(state, deed)
                    is TaxSpace -> Text(
                        "Pay £${space.amount} to the bank.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is Go -> Text(
                        "Collect £${state.rules.goSalary} every time you pass.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is JailSpace -> Text(
                        "Just visiting, unless you were sent here. Roll doubles, " +
                            "pay £${state.rules.jailFine}, or use a card to get out.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is GoToJail -> Text(
                        "Straight to jail. Do not pass GO.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is FreeParking -> Text(
                        if (state.rules.freeParkingJackpot) {
                            "House rule: the pot goes to whoever lands here. " +
                                "Currently £${state.freeParkingPot}."
                        } else {
                            "Nothing happens here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is ChanceSpace, is CommunityChestSpace -> Text(
                        "Draw a card and do what it says.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (space is Purchasable) {
                    HorizontalDivider()
                    Line("Price", "£${space.price}")
                    Line("Mortgage", "£${space.mortgageValue}")
                    Line("Cost to redeem", "£${space.unmortgageCost}")
                    if (space is Street) Line("House / hotel", "£${space.buildCost} each")
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun Ownership(state: GameState, deed: Deed?) {
    val owner = deed?.let { state.playerOrNull(it.owner) }
    Text(
        when {
            owner == null -> "Unowned"
            deed.mortgaged -> "${owner.name} — mortgaged, so no rent is due"
            deed.hasHotel -> "${owner.name} — hotel"
            deed.houses > 0 -> "${owner.name} — ${deed.houses} house${if (deed.houses > 1) "s" else ""}"
            else -> "${owner.name} — undeveloped"
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * The rent ladder, with the rung currently in force marked.
 *
 * The whole ladder rather than just today's number, because the question a
 * player is actually asking is "what does this become if they build on it".
 */
@Composable
private fun StreetRents(state: GameState, street: Street, deed: Deed?) {
    val owned = deed != null && !deed.mortgaged
    val wholeGroup = deed != null && state.ownsFullGroup(deed.owner, street.group)
    val level = deed?.houses ?: -1

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // An undeveloped street in a complete group rents at double, which is
        // the single most important thing to know before agreeing to a trade.
        val baseLabel = if (wholeGroup && level == 0) {
            "Rent  (doubled — whole group)"
        } else {
            "Rent"
        }
        val base = if (wholeGroup && level == 0) street.rentTiers[0] * 2 else street.rentTiers[0]
        Line(baseLabel, "£$base", emphasised = owned && level == 0)

        (1..4).forEach { houses ->
            Line(
                "With $houses house${if (houses > 1) "s" else ""}",
                "£${street.rentTiers[houses]}",
                emphasised = owned && level == houses,
            )
        }
        Line(
            "With a hotel",
            "£${street.rentTiers[Deed.HOTEL]}",
            emphasised = owned && level == Deed.HOTEL,
        )
    }
}

@Composable
private fun StationRents(state: GameState, deed: Deed?) {
    val held = deed?.let { state.stationsOwned(it.owner) } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..4).forEach { count ->
            Line(
                "If $count station${if (count > 1) "s" else ""} held",
                "£${25 shl (count - 1)}",
                emphasised = deed != null && !deed.mortgaged && held == count,
            )
        }
    }
}

@Composable
private fun UtilityRents(state: GameState, deed: Deed?) {
    val held = deed?.let { state.utilitiesOwned(it.owner) } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Line(
            "If one utility held",
            "4 × the dice",
            emphasised = deed != null && !deed.mortgaged && held == 1,
        )
        Line(
            "If both held",
            "10 × the dice",
            emphasised = deed != null && !deed.mortgaged && held == 2,
        )
    }
}

@Composable
private fun Line(label: String, value: String, emphasised: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasised) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
