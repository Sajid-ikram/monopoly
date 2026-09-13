package com.monopoly.android.ui.board

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monopoly.android.game.GameHolder
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
import kotlinx.coroutines.delay

/**
 * Everything the player can do, and nothing they cannot.
 *
 * The buttons are derived from [GameState.phase] rather than from local flags,
 * so the UI cannot drift out of step with what the engine will actually accept.
 * If a button is on screen, the command behind it is legal.
 */
@Composable
fun ControlPanel(game: GameHolder, modifier: Modifier = Modifier) {
    val state = game.state
    YourTurnNudge(game)
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PlayerRoster(state)

        game.you?.let { you ->
            if (state.playerOrNull(you)?.bankrupt == true) KnockedOutNotice(state)
        }

        game.lastRejection?.let { reason ->
            RejectionNotice(reason.readable()) { game.dismissRejection() }
        }

        WaitingNotice(game)

        // Everything that acts on the game, held still while one of those
        // actions is still in the post.
        Box {
            Column(
                modifier = Modifier.alpha(if (game.busy) DIMMED_ALPHA else 1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PhaseActions(game)

                TradeAction(game)

                HorizontalDivider()

                Holdings(game)
            }
            if (game.busy) TapBlocker()
        }

        HorizontalDivider()

        ActivityLog(game)
    }
}

/**
 * Swallows taps while a command is unanswered.
 *
 * Nothing on screen has changed yet — the client does not guess at results —
 * so a second press would send a second command rather than doing nothing. The
 * controls are dimmed by [Modifier.alpha] and made inert by this.
 */
@Composable
private fun BoxScope.TapBlocker() {
    Box(
        modifier = Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    )
}

/**
 * Says the server is being waited on, but only once that is worth saying.
 *
 * On a good connection the reply beats the finger off the screen, and a spinner
 * that appears for thirty milliseconds is noise. After [QUIET_WAIT_MILLIS] it
 * stops being a round trip and starts being a wait, which is the moment a
 * player wants to know their tap was heard.
 */
@Composable
private fun WaitingNotice(game: GameHolder) {
    var lingering by remember { mutableStateOf(false) }

    LaunchedEffect(game.busy) {
        if (!game.busy) {
            lingering = false
        } else {
            delay(QUIET_WAIT_MILLIS)
            lingering = true
        }
    }

    if (!game.busy || !lingering) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Text(
            "Sent. Waiting for the server…",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val QUIET_WAIT_MILLIS = 400L

/**
 * A tap on the wrist when the game comes round to you.
 *
 * Between turns a phone goes face down on the table and the player goes back to
 * the conversation — which is exactly as it should be, and exactly why the game
 * has to say when it needs them. Haptics rather than sound, because the whole
 * point is that it works in a pocket and does not interrupt the room.
 *
 * Nothing fires in a hot-seat game: the phone is being passed hand to hand, so
 * whoever is holding it already knows.
 */
@Composable
private fun YourTurnNudge(game: GameHolder) {
    val you = game.you ?: return
    val haptics = LocalHapticFeedback.current
    val state = game.state

    val waitingOnYou = when (val phase = state.phase) {
        is GamePhase.Lobby, is GamePhase.GameOver -> false
        is GamePhase.Auction -> phase.currentBidder == you
        is GamePhase.AwaitingDebtSettlement -> phase.debtor == you
        is GamePhase.AwaitingTradeResponse -> phase.offer.to == you
        else -> state.players.getOrNull(state.currentPlayerIndex)?.id == you
    }

    // Keyed on the answer, not the state, so it fires on the edge rather than
    // once per event while it stays your turn.
    LaunchedEffect(waitingOnYou) {
        if (waitingOnYou) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/** How far the controls fade while they are inert. */
private const val DIMMED_ALPHA = 0.45f

/**
 * The Trade button, and the composer it opens.
 *
 * Whether the button appears at all is [GameState.canOpenTrade] — the same
 * function the engine uses to decide. Asking the state rather than re-deriving
 * the rule here is what stops the button from ever being offered for a command
 * that would be refused.
 */
@Composable
private fun TradeAction(game: GameHolder) {
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
    // trade their way out of a bankruptcy. Over the network it is only ever
    // you, and canOpenTrade below decides whether this is your moment.
    val trader = game.you ?: when (phase) {
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
                        // Said before "in jail" because it is the one that
                        // explains why the game is about to move without them.
                        !player.connected -> " — away"
                        player.inJail -> " — in jail"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal,
                    // Someone who is not there reads as not there.
                    color = if (player.connected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
private fun PhaseActions(game: GameHolder) {
    val state = game.state
    val current = state.players[state.currentPlayerIndex]

    when (val phase = state.phase) {
        is GamePhase.Lobby -> {
            Text("Waiting to start", style = MaterialTheme.typography.titleMedium)
            val host = state.hostId
            if (host != null && game.controls(host)) {
                Button(
                    onClick = { game.dispatch(Command.StartGame(host)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start game") }
            }
        }

        is GamePhase.AwaitingRoll -> {
            Text("${current.name} to roll", style = MaterialTheme.typography.titleMedium)
            if (game.controls(current.id)) {
                Button(
                    onClick = { game.dispatch(Command.RollDice(current.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Roll dice") }
            }
        }

        is GamePhase.AwaitingJailDecision -> {
            Text("${current.name} is in jail", style = MaterialTheme.typography.titleMedium)
            Text(
                "Turn ${current.jailTurns + 1} of ${state.rules.maxTurnsInJail}. " +
                    "Roll doubles to walk free, or pay the fine.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (game.controls(current.id)) {
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
        }

        is GamePhase.AwaitingPurchase -> {
            val space = ClassicBoard.purchasableAt(phase.spaceIndex)
            Text("${space?.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Unowned. ${current.name} may buy it for £${space?.price}.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (game.controls(current.id)) {
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
            if (game.controls(phase.debtor)) {
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
        }

        is GamePhase.AwaitingTurnEnd -> {
            Text("${current.name}'s turn", style = MaterialTheme.typography.titleMedium)
            if (game.controls(current.id)) {
                Button(
                    onClick = { game.dispatch(Command.EndTurn(current.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (phase.mayRollAgain) "Roll again (doubles)" else "End turn")
                }
            }
        }

        // The offer itself is rendered by TradeAction just below; this only says
        // who the game is waiting on, so nobody stares at a stalled board
        // wondering why.
        is GamePhase.AwaitingTradeResponse -> Text(
            "Waiting for ${state.player(phase.offer.to).name} to answer",
            style = MaterialTheme.typography.titleMedium,
        )

        is GamePhase.GameOver -> GameOverCard(game, phase)
    }
}

/**
 * The end of the game, which is a moment rather than a line of text.
 *
 * Final standings matter even to the people who lost — "how close was it"
 * is the first thing anyone asks — so everybody is listed with what they were
 * worth, in order, rather than just naming the winner and stopping.
 */
@Composable
private fun GameOverCard(game: GameHolder, phase: GamePhase.GameOver) {
    val state = game.state
    val standings = state.players.sortedWith(
        // The winner first even if a bankrupt player somehow ended up richer on
        // paper, then by what everyone was actually worth.
        compareByDescending<Player> { it.id == phase.winner }
            .thenByDescending { state.netWorth(it.id) },
    )

    Text(
        phase.winner?.let { "${state.player(it).name} wins" } ?: "Nobody wins",
        style = MaterialTheme.typography.headlineSmall,
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        standings.forEachIndexed { place, player ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${place + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    player.name + if (player.bankrupt) " — bankrupt" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (player.id == phase.winner) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "£${state.netWorth(player.id)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    val host = state.hostId
    if (host != null && game.controls(host)) {
        Button(
            onClick = { game.dispatch(Command.Rematch(host)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Play again") }
        Text(
            "Same code, same people, clean board.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Waiting for ${host?.let { state.playerOrNull(it)?.name } ?: "the host"} " +
                "to start another game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What a player who is out of the game sees.
 *
 * Being knocked out is not the same as being ejected: you keep watching, and
 * you are dealt back in on a rematch. Saying so beats a board that simply stops
 * responding to you with no explanation.
 */
@Composable
private fun KnockedOutNotice(state: GameState) {
    if (state.phase is GamePhase.GameOver) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        Text(
            "You are out of this game. You can watch it finish, and you will be " +
                "dealt back in if there is another.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AuctionControls(game: GameHolder, phase: GamePhase.Auction) {
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
    if (!game.controls(bidder.id)) return
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
private fun Holdings(game: GameHolder) {
    val state = game.state
    var expanded by remember { mutableStateOf(false) }
    val you = game.you
    val phase = state.phase
    val owner = when {
        // Over the network your own property is the only property you can act
        // on, and you want to look at it during someone else's turn — that is
        // when you work out what to build.
        you != null -> state.playerOrNull(you) ?: return
        // Passing one phone round, "yours" is whoever is holding it. During a
        // debt that is the debtor, who needs to raise the money, and it may not
        // be their turn.
        phase is GamePhase.AwaitingDebtSettlement -> state.player(phase.debtor)
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
private fun DeedRow(game: GameHolder, owner: Player, deed: Deed) {
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
                        append(" · rent £")
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
private fun ActivityLog(game: GameHolder) {
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
