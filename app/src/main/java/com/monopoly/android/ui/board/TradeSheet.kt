package com.monopoly.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.monopoly.android.game.GameHolder
import com.monopoly.android.ui.theme.displayColor
import com.monopoly.android.ui.theme.seatColor
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.Street
import com.monopoly.core.engine.Command
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.TradeBundle
import com.monopoly.core.model.TradeOffer

/**
 * The offer a player is putting together, before it is sent.
 *
 * Held as plain UI state and only turned into a [TradeBundle] on send. Nothing
 * here is authoritative — the engine re-checks every part of it, twice, so this
 * is free to be a convenience rather than a source of truth.
 */
private class TradeDraft {
    var partner by mutableStateOf<PlayerId?>(null)
    var giveCash by mutableStateOf(0)
    var getCash by mutableStateOf(0)
    var giveSpaces by mutableStateOf(emptySet<Int>())
    var getSpaces by mutableStateOf(emptySet<Int>())
    var giveCards by mutableStateOf(emptySet<String>())
    var getCards by mutableStateOf(emptySet<String>())

    /** Clears the other side when the partner changes, so nothing stale is sent. */
    fun choosePartner(id: PlayerId) {
        if (partner == id) return
        partner = id
        getCash = 0
        getSpaces = emptySet()
        getCards = emptySet()
    }

    fun offered() = TradeBundle(giveCash, giveSpaces.sorted(), giveCards.sorted())
    fun requested() = TradeBundle(getCash, getSpaces.sorted(), getCards.sorted())

    val isEmpty: Boolean get() = offered().isEmpty && requested().isEmpty
}

/**
 * Builds and sends a trade.
 *
 * [counterTo] is set when answering an existing offer, in which case the draft
 * starts out mirroring what was proposed — haggling usually means changing one
 * number, not rebuilding the deal from nothing.
 */
@Composable
fun TradeComposer(
    game: GameHolder,
    proposer: PlayerId,
    counterTo: TradeOffer?,
    onDismiss: () -> Unit,
) {
    val state = game.state
    val draft = remember {
        TradeDraft().apply {
            counterTo?.let { original ->
                // The counter is the mirror image: what they asked me for is now
                // what I am offering.
                partner = original.from
                giveCash = original.requested.cash
                giveSpaces = original.requested.spaces.toSet()
                giveCards = original.requested.jailCards.toSet()
                getCash = original.offered.cash
                getSpaces = original.offered.spaces.toSet()
                getCards = original.offered.jailCards.toSet()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (counterTo == null) "Propose a trade" else "Counter-offer",
                    style = MaterialTheme.typography.titleLarge,
                )

                PartnerPicker(state, proposer, draft)

                val partner = draft.partner
                if (partner == null) {
                    Text(
                        "Choose who to trade with.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    HorizontalDivider()
                    SideEditor(
                        title = "You give",
                        state = state,
                        owner = proposer,
                        cash = draft.giveCash,
                        onCash = { draft.giveCash = it },
                        spaces = draft.giveSpaces,
                        onSpaces = { draft.giveSpaces = it },
                        cards = draft.giveCards,
                        onCards = { draft.giveCards = it },
                    )
                    HorizontalDivider()
                    SideEditor(
                        title = "You get",
                        state = state,
                        owner = partner,
                        cash = draft.getCash,
                        onCash = { draft.getCash = it },
                        spaces = draft.getSpaces,
                        onSpaces = { draft.getSpaces = it },
                        cards = draft.getCards,
                        onCards = { draft.getCards = it },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val to = draft.partner ?: return@Button
                            if (counterTo == null) {
                                game.dispatch(
                                    Command.ProposeTrade(
                                        proposer, to, draft.offered(), draft.requested(),
                                    ),
                                )
                            } else {
                                game.dispatch(
                                    Command.CounterTrade(proposer, draft.offered(), draft.requested()),
                                )
                            }
                            onDismiss()
                        },
                        enabled = draft.partner != null && !draft.isEmpty,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (counterTo == null) "Send offer" else "Send counter")
                    }
                }
            }
        }
    }
}

@Composable
private fun PartnerPicker(state: GameState, proposer: PlayerId, draft: TradeDraft) {
    Text("With", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.players.forEachIndexed { seat, player ->
            if (player.id == proposer || !player.isActive) return@forEachIndexed
            val selected = draft.partner == player.id
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { draft.choosePartner(player.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(seatColor(seat)),
                )
                Text(
                    player.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SideEditor(
    title: String,
    state: GameState,
    owner: PlayerId,
    cash: Int,
    onCash: (Int) -> Unit,
    spaces: Set<Int>,
    onSpaces: (Set<Int>) -> Unit,
    cards: Set<String>,
    onCards: (Set<String>) -> Unit,
) {
    val player = state.player(owner)
    Text("$title — ${player.name}", style = MaterialTheme.typography.titleSmall)

    CashStepper(cash = cash, max = player.money, onCash = onCash)

    val tradable = state.tradableDeeds(owner)
    if (tradable.isEmpty()) {
        Text(
            "No property that can be traded.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        tradable.forEach { deed ->
            val space = ClassicBoard[deed.spaceIndex]
            val street = space as? Street
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSpaces(
                            if (deed.spaceIndex in spaces) spaces - deed.spaceIndex
                            else spaces + deed.spaceIndex,
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = deed.spaceIndex in spaces, onCheckedChange = null)
                if (street != null) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(width = 6.dp, height = 18.dp)
                            .background(street.group.displayColor, RoundedCornerShape(2.dp)),
                    )
                } else {
                    Box(modifier = Modifier.padding(horizontal = 6.dp).size(width = 6.dp, height = 18.dp))
                }
                Text(
                    space.name + if (deed.mortgaged) " (mortgaged)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    player.getOutOfJailCards.forEach { cardId ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onCards(if (cardId in cards) cards - cardId else cards + cardId)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = cardId in cards, onCheckedChange = null)
            Text(
                "Get out of Jail Free",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * Cash entry by steps rather than a text field.
 *
 * Money in Monopoly moves in round numbers, and a keyboard covering half the
 * screen mid-negotiation is worse than two extra taps.
 */
@Composable
private fun CashStepper(cash: Int, max: Int, onCash: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "£$cash",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(end = 4.dp),
        )
        listOf(-100, -10, 10, 100).forEach { step ->
            OutlinedButton(
                onClick = { onCash((cash + step).coerceIn(0, max)) },
                enabled = (cash + step).coerceIn(0, max) != cash,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (step > 0) "+$step" else "$step",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        TextButton(
            onClick = { onCash(max) },
            enabled = cash != max,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        ) { Text("All", style = MaterialTheme.typography.labelSmall) }
    }
    Text(
        "of £$max",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The offer, as the other player sees it, with the three ways to answer.
 *
 * Shown to both sides: the proposer sees the same summary with only "Withdraw"
 * enabled, so nobody is left staring at a game that has stopped with no
 * explanation of what it is waiting for.
 */
@Composable
fun PendingTradeCard(
    game: GameHolder,
    phase: GamePhase.AwaitingTradeResponse,
    onCounter: () -> Unit,
) {
    val state = game.state
    val offer = phase.offer
    val from = state.player(offer.from)
    val to = state.player(offer.to)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${from.name} offers ${to.name} a trade",
                style = MaterialTheme.typography.titleMedium,
            )

            BundleSummary("${from.name} gives", offer.offered)
            BundleSummary("${to.name} gives", offer.requested)

            if (game.controls(offer.to)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { game.dispatch(Command.AcceptTrade(offer.to)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Accept") }
                    FilledTonalButton(onClick = onCounter, modifier = Modifier.weight(1f)) {
                        Text("Counter")
                    }
                    OutlinedButton(
                        onClick = { game.dispatch(Command.RejectTrade(offer.to)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Decline") }
                }
            } else {
                Text(
                    "Waiting for ${to.name} to answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BundleSummary(label: String, bundle: TradeBundle) {
    val parts = buildList {
        if (bundle.cash > 0) add("£${bundle.cash}")
        bundle.spaces.forEach { add(ClassicBoard[it].name) }
        repeat(bundle.jailCards.size) { add("Get out of Jail Free") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(6.dp),
            )
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (parts.isEmpty()) "nothing" else parts.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
