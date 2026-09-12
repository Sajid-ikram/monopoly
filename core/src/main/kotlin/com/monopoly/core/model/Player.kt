package com.monopoly.core.model

import kotlinx.serialization.Serializable

/**
 * Stable identity for a player, assigned by the server when they join.
 *
 * This deliberately is not a connection id or a device id: a player keeps the
 * same [PlayerId] across disconnects, which is what lets them drop off the
 * train, resume on mobile data and still be in the same seat.
 */
@JvmInline
@Serializable
value class PlayerId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlayerId must not be blank" }
    }
}

/** The playing pieces. Chosen in the lobby; purely cosmetic. */
@Serializable
enum class Token {
    TOP_HAT, THIMBLE, BOOT, BATTLESHIP, CAR, SCOTTIE_DOG, WHEELBARROW, CAT
}

@Serializable
data class Player(
    val id: PlayerId,
    val name: String,
    val token: Token,
    /** Board index 0..39. */
    val position: Int = 0,
    val money: Int = 0,
    val inJail: Boolean = false,
    /** How many turns already spent in jail this stint. */
    val jailTurns: Int = 0,
    /** Unused "get out of jail free" cards this player is holding, by card id. */
    val getOutOfJailCards: List<String> = emptyList(),
    val bankrupt: Boolean = false,
    /**
     * Whether this player's socket is currently attached. A disconnected player
     * is *not* removed: the game holds their seat and their assets, and the turn
     * timer decides what happens if they never come back.
     */
    val connected: Boolean = true,
) {
    val isActive: Boolean get() = !bankrupt

    fun withMoney(delta: Int): Player = copy(money = money + delta)
}
