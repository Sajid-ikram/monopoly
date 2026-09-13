package com.monopoly.core.model

import kotlinx.serialization.Serializable

/**
 * The mutable state of one ownable space.
 *
 * Only spaces that have been bought have a [Deed]; an absent entry in
 * [GameState.deeds] means the bank still holds it.
 */
@Serializable
data class Deed(
    val spaceIndex: Int,
    val owner: PlayerId,
    /**
     * 0 = bare land, 1..4 = houses, 5 = hotel. Always 0 for stations and
     * utilities, which cannot be developed.
     */
    val houses: Int = 0,
    val mortgaged: Boolean = false,
) {
    init {
        require(houses in 0..HOTEL) { "houses must be 0..$HOTEL, was $houses" }
        require(!(mortgaged && houses > 0)) {
            "Space $spaceIndex cannot be mortgaged while developed"
        }
    }

    val hasHotel: Boolean get() = houses == HOTEL
    val houseCount: Int get() = if (hasHotel) 0 else houses

    companion object {
        const val HOTEL = 5
    }
}
