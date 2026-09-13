package com.monopoly.core.model

import kotlinx.serialization.Serializable

/**
 * One side of a trade: what a player hands over.
 *
 * Buildings are absent on purpose. Houses and hotels never change hands in
 * Monopoly — they are sold back to the bank first — so there is nothing to put
 * here, and leaving the field out means the rule cannot be broken by accident.
 */
@Serializable
data class TradeBundle(
    val cash: Int = 0,
    /** Board indices of the deeds offered. */
    val spaces: List<Int> = emptyList(),
    /** Ids of "get out of jail free" cards offered. */
    val jailCards: List<String> = emptyList(),
) {
    init {
        require(cash >= 0) { "A trade cannot offer negative cash" }
        require(spaces.toSet().size == spaces.size) { "Duplicate space in a trade" }
        require(jailCards.toSet().size == jailCards.size) { "Duplicate card in a trade" }
    }

    val isEmpty: Boolean get() = cash == 0 && spaces.isEmpty() && jailCards.isEmpty()

    companion object {
        val NOTHING = TradeBundle()
    }
}

/**
 * A proposed swap, waiting on an answer.
 *
 * The offer names both halves explicitly rather than describing a price, so
 * what is being agreed to is never ambiguous — and so the engine can check that
 * both players still hold what they are promising at the moment it is accepted,
 * not at the moment it was proposed.
 */
@Serializable
data class TradeOffer(
    val from: PlayerId,
    val to: PlayerId,
    /** What [from] gives up. */
    val offered: TradeBundle,
    /** What [from] asks for in return. */
    val requested: TradeBundle,
) {
    init {
        require(from != to) { "A player cannot trade with themselves" }
    }

    val isEmpty: Boolean get() = offered.isEmpty && requested.isEmpty

    /** The same deal seen from the other side, for a counter-offer. */
    fun reversed(): TradeOffer = TradeOffer(
        from = to,
        to = from,
        offered = requested,
        requested = offered,
    )
}
