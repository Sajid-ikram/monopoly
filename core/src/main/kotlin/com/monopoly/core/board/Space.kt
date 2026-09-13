package com.monopoly.core.board

import kotlinx.serialization.Serializable

/** The eight street colour groups, in board order. */
@Serializable
enum class ColorGroup(val streetCount: Int) {
    BROWN(2),
    LIGHT_BLUE(3),
    PINK(3),
    ORANGE(3),
    RED(3),
    YELLOW(3),
    GREEN(3),
    DARK_BLUE(2),
}

/**
 * A single square on the board.
 *
 * Spaces are static data: they never change during a game. Everything mutable
 * about a square (who owns it, how many houses it has) lives in
 * [com.monopoly.core.model.Deed] keyed by [index], so the board can stay a
 * shared immutable constant.
 */
@Serializable
sealed interface Space {
    /** Position on the board, 0 (GO) through 39. */
    val index: Int
    val name: String
}

/** A space a player can buy from the bank. */
@Serializable
sealed interface Purchasable : Space {
    val price: Int
    /** What the bank pays out when this is mortgaged: half the purchase price. */
    val mortgageValue: Int get() = price / 2
    /** Cost to lift a mortgage: the mortgage value plus 10% interest. */
    val unmortgageCost: Int get() = mortgageValue + (mortgageValue / 10)
}

@Serializable
data class Go(override val index: Int = 0) : Space {
    override val name: String get() = "GO"
}

@Serializable
data class Street(
    override val index: Int,
    override val name: String,
    val group: ColorGroup,
    override val price: Int,
    /**
     * Rent by development level, index 0..5:
     * 0 = undeveloped, 1..4 = that many houses, 5 = hotel.
     * Rent for an undeveloped street in a fully-owned group is doubled at
     * lookup time rather than stored here.
     */
    val rentTiers: List<Int>,
    /** Cost of one house (and of the hotel, which replaces four houses). */
    val buildCost: Int,
) : Purchasable {
    init {
        require(rentTiers.size == 6) { "$name must define 6 rent tiers, got ${rentTiers.size}" }
    }
}

@Serializable
data class Station(
    override val index: Int,
    override val name: String,
) : Purchasable {
    override val price: Int get() = 200
}

@Serializable
data class Utility(
    override val index: Int,
    override val name: String,
) : Purchasable {
    override val price: Int get() = 150
}

/** Income Tax or Super Tax: a flat charge paid to the bank. */
@Serializable
data class TaxSpace(
    override val index: Int,
    override val name: String,
    val amount: Int,
) : Space

@Serializable
data class ChanceSpace(override val index: Int) : Space {
    override val name: String get() = "Chance"
}

@Serializable
data class CommunityChestSpace(override val index: Int) : Space {
    override val name: String get() = "Community Chest"
}

/** The jail corner. Landing here is "just visiting"; being *sent* here is not. */
@Serializable
data class JailSpace(override val index: Int = 10) : Space {
    override val name: String get() = "Jail / Just Visiting"
}

@Serializable
data class FreeParking(override val index: Int = 20) : Space {
    override val name: String get() = "Free Parking"
}

@Serializable
data class GoToJail(override val index: Int = 30) : Space {
    override val name: String get() = "Go To Jail"
}
