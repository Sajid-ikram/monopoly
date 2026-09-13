package com.monopoly.core.engine

import com.monopoly.core.model.Deed
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.Player
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.TurnState

/**
 * Applies one event to one state.
 *
 * Hard rules for everything in this file:
 *
 *  - **Pure.** No randomness, no clock, no I/O, no rule decisions. If applying
 *    an event needs a die roll or a legality check, the event was under-specified
 *    and the fix belongs in [GameEngine], not here.
 *  - **Total.** Every event must apply to any state the server could have been
 *    in when it emitted it. Never throw on a well-formed event.
 *
 * Both properties exist for the same reason: the client runs this exact
 * function over the server's event stream. If it ever disagrees with the
 * server, players see a board that quietly drifts out of sync — the single
 * worst failure mode in a game like this.
 */
fun GameState.applyEvent(event: GameEvent): GameState {
    val next = when (event) {

        is GameEvent.PlayerJoined ->
            // Re-joining is a no-op rather than a duplicate seat, so a retried
            // join cannot put the same person on the board twice.
            if (players.any { it.id == event.player.id }) this
            else copy(players = players + event.player)

        is GameEvent.PlayerLeft -> {
            val remaining = players.filterNot { it.id == event.player }
            // A game with no players cannot be represented, so the last seat
            // never leaves; the server tears the session down instead.
            if (remaining.isEmpty()) this
            else copy(
                players = remaining,
                currentPlayerIndex = currentPlayerIndex.coerceAtMost(remaining.size - 1),
            )
        }

        is GameEvent.RulesChanged -> copy(rules = event.rules)

        is GameEvent.TokenChanged -> updatePlayer(event.player) { it.copy(token = event.token) }

        is GameEvent.GameStarted -> {
            val seated = event.seatOrder.mapNotNull { id -> players.firstOrNull { it.id == id } }
            copy(
                players = seated.ifEmpty { players },
                currentPlayerIndex = 0,
                rngState = event.rngState,
                phase = GamePhase.AwaitingRoll,
                turn = TurnState(),
            )
        }

        is GameEvent.DiceRolled -> copy(
            rngState = event.rngState,
            turn = turn.copy(lastRoll = event.roll, hasRolled = true),
        )

        is GameEvent.PlayerMoved -> updatePlayer(event.player) { it.copy(position = event.to) }

        is GameEvent.MoneyTransferred -> {
            var updated = this
            event.from?.let { payer ->
                updated = updated.updatePlayer(payer) { it.withMoney(-event.amount) }
            }
            event.to?.let { payee ->
                updated = updated.updatePlayer(payee) { it.withMoney(event.amount) }
            }
            updated
        }

        is GameEvent.FreeParkingPotChanged -> copy(freeParkingPot = event.newValue)

        is GameEvent.DeedAssigned -> copy(
            deeds = deeds + (event.spaceIndex to Deed(
                spaceIndex = event.spaceIndex,
                owner = event.owner,
                houses = event.houses,
                mortgaged = event.mortgaged,
            )),
        )

        is GameEvent.DeedReleased -> copy(deeds = deeds - event.spaceIndex)

        is GameEvent.HousesChanged -> {
            val deed = deeds[event.spaceIndex]
            if (deed == null) this
            else copy(deeds = deeds + (event.spaceIndex to deed.copy(houses = event.houses)))
        }

        is GameEvent.MortgageChanged -> {
            val deed = deeds[event.spaceIndex]
            if (deed == null) this
            else copy(deeds = deeds + (event.spaceIndex to deed.copy(mortgaged = event.mortgaged)))
        }

        is GameEvent.JailStatusChanged -> updatePlayer(event.player) {
            it.copy(inJail = event.inJail, jailTurns = event.jailTurns)
        }

        is GameEvent.CardDrawn -> withDeck(event.deck, event.deckState)

        is GameEvent.JailCardHeld -> withDeck(event.deck, event.deckState)
            .updatePlayer(event.player) {
                it.copy(getOutOfJailCards = it.getOutOfJailCards + event.cardId)
            }

        is GameEvent.JailCardReturned -> withDeck(event.deck, event.deckState)
            .updatePlayer(event.player) {
                it.copy(getOutOfJailCards = it.getOutOfJailCards - event.cardId)
            }

        is GameEvent.JailCardTransferred ->
            updatePlayer(event.from) { it.copy(getOutOfJailCards = it.getOutOfJailCards - event.cardId) }
                .updatePlayer(event.to) { holder ->
                    // Guard against a double-add if this event is ever replayed
                    // on a state that already has it.
                    if (event.cardId in holder.getOutOfJailCards) holder
                    else holder.copy(getOutOfJailCards = holder.getOutOfJailCards + event.cardId)
                }

        // Both are records for the activity log. The cash and deeds move through
        // their own events, so there is nothing to apply here.
        is GameEvent.TradeCompleted -> this
        is GameEvent.TradeRejected -> this

        is GameEvent.TurnStateChanged -> copy(turn = event.turn)

        is GameEvent.TurnAdvanced -> copy(
            currentPlayerIndex = event.nextPlayerIndex.coerceIn(players.indices),
            turn = TurnState(),
        )

        is GameEvent.PhaseChanged -> copy(phase = event.phase)

        is GameEvent.PlayerBankrupted -> updatePlayer(event.player) {
            it.copy(bankrupt = true, money = 0)
        }

        is GameEvent.ConnectionChanged -> updatePlayer(event.player) {
            it.copy(connected = event.connected)
        }
    }

    // The version counter is the resync anchor, so it advances for every event
    // without exception — including ones that turn out to be no-ops.
    return next.copy(version = version + 1)
}

/** Folds a whole event stream, for replay and for catching a client up. */
fun GameState.applyAll(events: List<GameEvent>): GameState =
    events.fold(this) { state, event -> state.applyEvent(event) }

private fun GameState.updatePlayer(id: PlayerId, transform: (Player) -> Player): GameState {
    val index = players.indexOfFirst { it.id == id }
    // Tolerate an unknown id rather than throwing: staying total matters more
    // than surfacing a server bug from inside a client's replay loop.
    if (index < 0) return this
    return copy(players = players.toMutableList().apply { this[index] = transform(this[index]) })
}

private fun GameState.withDeck(kind: DeckKind, deck: com.monopoly.core.model.CardDeck): GameState =
    when (kind) {
        DeckKind.CHANCE -> copy(chanceDeck = deck)
        DeckKind.COMMUNITY_CHEST -> copy(communityChestDeck = deck)
    }
