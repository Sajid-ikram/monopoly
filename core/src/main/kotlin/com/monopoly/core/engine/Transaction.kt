package com.monopoly.core.engine

import com.monopoly.core.model.GameState

/**
 * Accumulates the events one command produces, keeping a running state so each
 * step can be decided against the result of the previous one.
 *
 * Nothing here escapes [GameEngine]: a command either completes and yields its
 * whole event list, or is rejected and yields nothing at all. There is no
 * partial application, so a rejected command can never leave a half-moved
 * piece on someone's screen.
 */
internal class Transaction(initial: GameState) {

    var state: GameState = initial
        private set

    private val recorded = mutableListOf<GameEvent>()

    /** Records an event and folds it into the running state. */
    fun emit(event: GameEvent) {
        recorded += event
        state = state.applyEvent(event)
    }

    fun emitAll(events: List<GameEvent>) = events.forEach(::emit)

    fun accept(): Outcome.Accepted = Outcome.Accepted(recorded.toList(), state)
}
