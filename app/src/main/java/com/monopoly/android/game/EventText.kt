package com.monopoly.android.game

import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.engine.DeckKind
import com.monopoly.core.engine.GameEvent
import com.monopoly.core.engine.JailRelease
import com.monopoly.core.engine.MoneyReason
import com.monopoly.core.model.ClassicCards
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.TradeBundle

/**
 * Turns an event into the line a player reads in the activity log.
 *
 * This is one of the reasons the server sends events rather than state
 * snapshots: "Bob paid you $450 for Boardwalk" is right there in the event,
 * whereas deriving it from two snapshots means diffing, and the diff has lost
 * the reason by then.
 *
 * [before] is the state the event applied to, which is what lets names be
 * resolved even for a player who is about to leave the board.
 */
fun describe(event: GameEvent, before: GameState): String? {
    fun name(id: PlayerId?): String =
        id?.let { before.playerOrNull(it)?.name ?: it.value } ?: "the bank"

    fun space(index: Int) = ClassicBoard[index].name

    return when (event) {
        is GameEvent.PlayerJoined -> "${event.player.name} joined."
        is GameEvent.PlayerLeft -> "${name(event.player)} left the lobby."
        is GameEvent.RulesChanged -> "House rules updated."
        is GameEvent.TokenChanged -> null

        is GameEvent.GameStarted ->
            "Game on. Turn order: " + event.seatOrder.joinToString { name(it) } + "."

        is GameEvent.DiceRolled -> {
            val doubles = if (event.roll.isDoubles) " — doubles!" else ""
            "${name(event.player)} rolled ${event.roll.first} + ${event.roll.second} " +
                "= ${event.roll.total}$doubles"
        }

        is GameEvent.PlayerMoved ->
            "${name(event.player)} moved to ${space(event.to)}."

        is GameEvent.MoneyTransferred -> when (event.reason) {
            MoneyReason.GO_SALARY -> "${name(event.to)} passed GO and collected $${event.amount}."
            MoneyReason.RENT -> "${name(event.from)} paid ${name(event.to)} $${event.amount} in rent."
            MoneyReason.TAX -> "${name(event.from)} paid $${event.amount} in tax."
            MoneyReason.JAIL_FINE -> "${name(event.from)} paid the $${event.amount} jail fine."
            MoneyReason.PROPERTY_PURCHASE -> null // covered by the deed event
            MoneyReason.AUCTION_PURCHASE -> null
            MoneyReason.BUILDING_PURCHASE -> null
            MoneyReason.BUILDING_SALE -> "${name(event.to)} sold buildings for $${event.amount}."
            MoneyReason.MORTGAGE -> null
            MoneyReason.UNMORTGAGE -> null
            MoneyReason.FREE_PARKING -> "${name(event.to)} scooped $${event.amount} off Free Parking."
            MoneyReason.STARTING_CASH -> null
            MoneyReason.CARD -> if (event.to != null && event.from == null) {
                "${name(event.to)} collected $${event.amount}."
            } else {
                "${name(event.from)} paid $${event.amount}."
            }
            MoneyReason.BANKRUPTCY_TRANSFER ->
                "${name(event.to)} took $${event.amount} from ${name(event.from)}."
            // Covered by the TradeCompleted line, which reads as one deal
            // rather than as two payments going opposite ways.
            MoneyReason.TRADE -> null
        }

        is GameEvent.DeedAssigned -> when {
            event.houses == 0 && !event.mortgaged ->
                "${name(event.owner)} now owns ${space(event.spaceIndex)}."
            else -> "${space(event.spaceIndex)} transferred to ${name(event.owner)}."
        }

        is GameEvent.DeedReleased -> "${space(event.spaceIndex)} returned to the bank."

        is GameEvent.HousesChanged -> when (event.houses) {
            0 -> "Buildings cleared from ${space(event.spaceIndex)}."
            Deed.HOTEL -> "A hotel went up on ${space(event.spaceIndex)}."
            else -> "${space(event.spaceIndex)} now has ${event.houses} " +
                if (event.houses == 1) "house." else "houses."
        }

        is GameEvent.MortgageChanged ->
            if (event.mortgaged) "${space(event.spaceIndex)} was mortgaged."
            else "${space(event.spaceIndex)} is no longer mortgaged."

        is GameEvent.JailStatusChanged -> when {
            event.inJail -> if (event.jailTurns > 0) {
                "${name(event.player)} stays in jail (turn ${event.jailTurns})."
            } else {
                "${name(event.player)} was sent to jail."
            }
            event.release == JailRelease.PAID_FINE -> "${name(event.player)} paid their way out of jail."
            event.release == JailRelease.USED_CARD -> "${name(event.player)} used a Get Out of Jail Free card."
            event.release == JailRelease.ROLLED_DOUBLES -> "${name(event.player)} rolled doubles and walked free."
            event.release == JailRelease.SERVED_TIME -> "${name(event.player)} served their time."
            else -> null
        }

        is GameEvent.CardDrawn -> {
            val pile = if (event.deck == DeckKind.CHANCE) "Chance" else "Community Chest"
            "$pile: ${ClassicCards.requireCard(event.cardId).text}"
        }

        is GameEvent.JailCardHeld -> "${name(event.player)} kept a Get Out of Jail Free card."
        is GameEvent.JailCardReturned -> null // the release line already says it

        is GameEvent.JailCardTransferred ->
            "${name(event.from)} gave ${name(event.to)} a Get Out of Jail Free card."

        is GameEvent.TradeCompleted -> {
            // One readable line for the whole deal. The individual transfers
            // have their own events, but a list of them reads as noise.
            val gave = summarise(event.offer.offered)
            val got = summarise(event.offer.requested)
            "${name(event.offer.from)} traded $gave to ${name(event.offer.to)} for $got."
        }

        is GameEvent.TradeRejected ->
            "${name(event.offer.to)} turned down ${name(event.offer.from)}'s offer."

        is GameEvent.FreeParkingPotChanged -> null
        is GameEvent.TurnStateChanged -> null

        is GameEvent.TurnAdvanced ->
            "— ${before.players.getOrNull(event.nextPlayerIndex)?.name ?: "Next player"}'s turn —"

        is GameEvent.PhaseChanged -> when (val phase = event.phase) {
            is GamePhase.AwaitingTradeResponse ->
                "${name(phase.offer.from)} offered ${name(phase.offer.to)} a trade."
            is GamePhase.Auction -> "${space(phase.spaceIndex)} goes to auction."
            is GamePhase.AwaitingDebtSettlement ->
                "${name(phase.debtor)} owes $${phase.amount} and must raise it."
            is GamePhase.GameOver ->
                phase.winner?.let { "${name(it)} wins!" } ?: "Game over."
            else -> null
        }

        is GameEvent.PlayerBankrupted -> "${name(event.player)} is bankrupt."

        is GameEvent.ConnectionChanged ->
            if (event.connected) "${name(event.player)} reconnected."
            else "${name(event.player)} lost connection."
    }
}

/** One half of a trade, as a short phrase for the activity log. */
private fun summarise(bundle: TradeBundle): String {
    val parts = buildList {
        if (bundle.cash > 0) add("$${bundle.cash}")
        bundle.spaces.forEach { add(ClassicBoard[it].name) }
        repeat(bundle.jailCards.size) { add("a Get Out of Jail Free card") }
    }
    return if (parts.isEmpty()) "nothing" else parts.joinToString(" and ")
}
