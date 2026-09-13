package com.monopoly.android.ui.board

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monopoly.android.game.LocalGame
import com.monopoly.android.ui.theme.seatColor
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.Street
import com.monopoly.core.engine.Command
import com.monopoly.core.engine.Rent
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.Player
import com.monopoly.core.model.TradeOffer

/**
 * Everything the player can do, and nothing they cannot.
 *
 * The buttons are derived from [GameState.phase] rather than from local flags,
 * so the UI cannot drift out of step with what the engine will actually accept.
 * If a button is on screen, the command behind it is legal.
 */
@Composable
fun ControlPanel(game: LocalGame, modifier: Modifier = Modifier) {
    val state = game.state
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PlayerRoster(state)

        game.lastRejection?.let { reason ->
            RejectionNotice(reason.readable()) { game.dismissRejection() }
        }

        PhaseActions(game)

        TradeAction(game)

        HorizontalDivider()

        Holdings(game)

        HorizontalDivider()

        ActivityLog(game)
    }
}

/**
 * The Trade button, and the composer it opens.
 *
 * Whether the button appears at all is [GameState.canOpenTrade] — the same
 * function the engine uses to decide. Asking the state rather than re-deriving
 * the rule here is what stops the button from ever being offered for a command
 * that would be refused.
 */
@Composable
private fun TradeAction(game: LocalGame) {
    val state = game.state
    var composing by remember { mutableStateOf(false) }
    var counterTo by remember { mutableStateOf<TradeOffer?>(null) }

    val phase = state.phase
    if (phase is GamePhase.AwaitingTradeResponse) {
        PendingTradeCard(
            game = game,
            phase = phase,
            onCounter = {
                counterTo = phase.offer
                composing = true
            },
        )
    }

    // Whoever may trade is not always the player whose turn it is: a debtor can
    // trade their way out of a bankruptcy.
    val trader = when (phase) {
        is GamePhase.AwaitingDebtSettlement -> phase.debtor
        else -> state.players.getOrNull(state.currentPlayerIndex)?.id
    }
    val canTrade = trader != null &&
        state.canOpenTrade(trader) &&
        state.activePlayers.size > 1

    if (canTrade) {
        OutlinedButton(
            onClick = {
                counterTo = null
                composing = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Propose a trade") }
    }

    if (composing) {
        val proposer = counterTo?.to ?: trader
        if (proposer == null) {
            composing = false
        } else {
            TradeComposer(
                game = game,
                proposer = proposer,
                counterTo = counterTo,
                onDismiss = {
                    composing = false
                    counterTo = null
                },
            )
        }
    }
}

@Composable
private fun PlayerRoster(state: GameState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.players.forEachIndexed { seat, player ->
            val isTurn = state.players.getOrNull(state.currentPlayerIndex)?.id == player.id &&
                state.phase !is GamePhase.Lobby
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isTurn) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(seatColor(seat)),
                )
                Text(
                    text = player.name + when {
                        player.bankrupt -> " — bankrupt"
                        player.inJail -> " — in jail"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                // Counting rather than jumping. It is the difference between
                // noticing you were charged and having to work out why the
                // number is different.
                val shownMoney by animateIntAsState(
                    targetValue = player.money,
                    animationSpec = tween(durationMillis = 550),
                    label = "balance",
                )
                Text(
                    "£$shownMoney",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PhaseActions(game: LocalGame) {
    val state = game.state
    val current = state.players[state.currentPlayerIndex]

    when (val phase = state.phase) {
        is GamePhase.Lobby -> {
            Text("Waiting to start", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { game.dispatch(Command.StartGame(state.players.first().id)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start game") }
        }

        is GamePhase.AwaitingRoll -> {
            Text("${current.name} to roll", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { game.dispatch(Command.RollDice(current.id)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Roll dice") }
        }

        is GamePhase.AwaitingJailDecision -> {
            Text("${current.name} is in jail", style = MaterialTheme.typography.titleMedium)
            Text(
                "Turn ${current.jailTurns + 1} of ${state.rules.maxTurnsInJail}. " +
                    "Roll doubles to walk free, or pay the fine.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { game.dispatch(Command.RollDice(current.id)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Roll for doubles") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { game.dispatch(Command.PayJailFine(current.id)) },
                    enabled = current.money >= state.rules.jailFine,
                    modifier = Modifier.weight(1f),
                ) { Text("Pay £${state.rules.jailFine}") }
                if (current.getOutOfJailCards.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { game.dispatch(Command.UseJailCard(current.id)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Use card") }
                }
            }
        }

        is GamePhase.AwaitingPurchase -> {
            val space = ClassicBoard.purchasableAt(phase.spaceIndex)
            Text("${space?.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Unowned. ${current.name} may buy it for £${space?.price}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { game.dispatch(Command.BuyProperty(current.id)) },
                    enabled = current.money >= (space?.price ?: 0),
                    modifier = Modifier.weight(1f),
                ) { Text("Buy £${space?.price}") }
                OutlinedButton(
                    onClick = { game.dispatch(Command.DeclineProperty(current.id)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.rules.auctionUnboughtProperties) "Auction it" else "Pass")
                }
            }
        }

        is GamePhase.Auction -> AuctionControls(game, phase)

        is GamePhase.AwaitingDebtSettlement -> {
            val debtor = state.player(phase.debtor)
            Text("${debtor.name} owes £${phase.amount}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Raise the money by selling buildings or mortgaging, " +
                    "or declare bankruptcy. Nothing is sold automatically.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { game.dispatch(Command.SettleDebt(phase.debtor)) },
                    enabled = debtor.money >= phase.amount,
                    modifier = Modifier.weight(1f),
                ) { Text("Pay £${phase.amount}") }
                OutlinedButton(
                    onClick = { game.dispatch(Command.DeclareBankruptcy(phase.debtor)) },
                    enabled = state.liquidationValue(phase.debtor) < phase.amount,
                    modifier = Modifier.weight(1f),
                ) { Text("Bankrupt") }
            }
        }

        is GamePhase.AwaitingTurnEnd -> {
            Text("${current.name}'s turn", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { game.dispatch(Command.EndTurn(current.id)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (phase.mayRollAgain) "Roll again (doubles)" else "End turn")
            }
        }

        // The offer itself is rendered by TradeAction just below; this only says
        // who the game is waiting on, so nobody stares at a stalled board
        // wondering why.
        is GamePhase.AwaitingTradeResponse -> Text(
            "Waiting for ${state.player(phase.offer.to).name} to answer",
            style = MaterialTheme.typography.titleMedium,
        )

        is GamePhase.GameOver -> {
            val winner = phase.winner?.let { state.player(it).name } ?: "Nobody"
            Text("$winner wins", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun AuctionControls(game: LocalGame, phase: GamePhase.Auction) {
    val state = game.state
    val bidder = phase.currentBidder?.let { state.player(it) } ?: return
    val space = ClassicBoard.purchasableAt(phase.spaceIndex)

    Text("Auction: ${space?.name}", style = MaterialTheme.typography.titleMedium)
    Text(
        if (phase.highestBidder == null) {
            "No bids yet. ${bidder.name} to bid."
        } else {
            "£${phase.highestBid} by ${state.player(phase.highestBidder!!).name}. " +
                "${bidder.name} to bid."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 50, 100).forEach { increment ->
            val bid = phase.highestBid + increment
            FilledTonalButton(
                onClick = { game.dispatch(Command.PlaceBid(bidder.id, bid)) },
                enabled = bidder.money >= bid,
                modifier = Modifier.weight(1f),
            ) { Text("£$bid") }
        }
    }
    OutlinedButton(
        onClick = { game.dispatch(Command.WithdrawFromAuction(bidder.id)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Fold") }
}

/**
 * The current player's property, with the actions that apply to each.
 *
 * Building and mortgaging are legal at almost any point in your turn, so this
 * stays available rather than appearing only in a dedicated phase.
 */
@Composable
private fun Holdings(game: LocalGame) {
    val state = game.state
    var expanded by remember { mutableStateOf(false) }
    val owner = when (val phase = state.phase) {
        // During a debt it is the debtor who needs to raise money, and it may
        // not be their turn.
        is GamePhase.AwaitingDebtSettlement -> state.player(phase.debtor)
        else -> state.players[state.currentPlayerIndex]
    }
    val deeds = state.deedsOf(owner.id)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${owner.name}'s property (${deeds.size})",
            style = MaterialTheme.typography.titleSmall,
        )
        if (deeds.isNotEmpty()) {
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Manage")
            }
        }
    }

    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            deeds.forEach { deed -> DeedRow(game, owner, deed) }
        }
    }
}

@Composable
private fun DeedRow(game: LocalGame, owner: Player, deed: Deed) {
    val state = game.state
    val space = ClassicBoard[deed.spaceIndex]
    val street = space as? Street
    val purchasable = ClassicBoard.purchasableAt(deed.spaceIndex) ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(space.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(
                        when {
                            deed.mortgaged -> "Mortgaged"
                            deed.hasHotel -> "Hotel"
                            deed.houses > 0 -> "${deed.houses} house${if (deed.houses > 1) "s" else ""}"
                            else -> "Undeveloped"
                        },
                    )
                    if (!deed.mortgaged) {
                        append(" · rent $")
                        append(Rent.rentFor(state, deed.spaceIndex, state.turn.lastRoll))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (street != null) {
                    OutlinedButton(
                        onClick = { game.dispatch(Command.BuildHouse(owner.id, deed.spaceIndex)) },
                        enabled = !deed.mortgaged &&
                            deed.houses < Deed.HOTEL &&
                            state.ownsFullGroup(owner.id, street.group) &&
                            owner.money >= street.buildCost,
                        modifier = Modifier.weight(1f),
                    ) { Text("Build £${street.buildCost}", maxLines = 1) }

                    OutlinedButton(
                        onClick = { game.dispatch(Command.SellHouse(owner.id, deed.spaceIndex)) },
                        enabled = deed.houses > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Sell £${street.buildCost / 2}", maxLines = 1) }
                }

                OutlinedButton(
                    onClick = {
                        if (deed.mortgaged) {
                            game.dispatch(Command.UnmortgageProperty(owner.id, deed.spaceIndex))
                        } else {
                            game.dispatch(Command.MortgageProperty(owner.id, deed.spaceIndex))
                        }
                    },
                    enabled = if (deed.mortgaged) {
                        owner.money >= purchasable.unmortgageCost
                    } else {
                        deed.houses == 0
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (deed.mortgaged) "Redeem £${purchasable.unmortgageCost}"
                        else "Mortgage £${purchasable.mortgageValue}",
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityLog(game: LocalGame) {
    Text("Activity", style = MaterialTheme.typography.titleSmall)
    // A plain Column, not a LazyColumn: this sits inside a scrolling parent,
    // and a lazy list there has no bounded height to measure against. The
    // visible slice is small and fixed, so laziness would buy nothing anyway.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        game.log.take(VISIBLE_LOG_LINES).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val VISIBLE_LOG_LINES = 40

@Composable
private fun RejectionNotice(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onDismiss) { Text("OK") }
    }
}

/**
 * A rejection in words a player understands.
 *
 * These should rarely be seen, since the controls only offer legal moves — but
 * when one does appear it needs to explain itself, not show an enum name.
 */
private fun com.monopoly.core.engine.RejectionReason.readable(): String = when (this) {
    com.monopoly.core.engine.RejectionReason.NOT_YOUR_TURN -> "It is not your turn."
    com.monopoly.core.engine.RejectionReason.WRONG_PHASE -> "That is not possible right now."
    com.monopoly.core.engine.RejectionReason.INSUFFICIENT_FUNDS -> "You cannot afford that."
    com.monopoly.core.engine.RejectionReason.INCOMPLETE_COLOR_GROUP ->
        "You need every street in the colour group before building."
    com.monopoly.core.engine.RejectionReason.UNEVEN_BUILD ->
        "Houses must be built evenly across the group."
    com.monopoly.core.engine.RejectionReason.MUST_SELL_BUILDINGS_FIRST ->
        "Sell the buildings before mortgaging."
    com.monopoly.core.engine.RejectionReason.BANK_OUT_OF_HOUSES -> "The bank has no houses left."
    com.monopoly.core.engine.RejectionReason.BANK_OUT_OF_HOTELS -> "The bank has no hotels left."
    com.monopoly.core.engine.RejectionReason.CAN_STILL_PAY ->
        "You can still raise the money, so you cannot declare bankruptcy."
    com.monopoly.core.engine.RejectionReason.DEBT_STILL_UNPAYABLE ->
        "You still cannot cover the debt."
    com.monopoly.core.engine.RejectionReason.BID_TOO_LOW -> "That bid does not beat the current one."
    com.monopoly.core.engine.RejectionReason.MAX_DEVELOPMENT_REACHED -> "That already has a hotel."
    else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + "."
}
