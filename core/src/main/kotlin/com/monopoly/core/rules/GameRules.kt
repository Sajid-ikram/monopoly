package com.monopoly.core.rules

import kotlinx.serialization.Serializable

/**
 * Per-game configuration, agreed in the lobby and then frozen.
 *
 * House rules are a setting rather than a hardcoded behaviour because the point
 * of this game is playing the way *your* table plays. The rules object is part
 * of [com.monopoly.core.model.GameState], so it travels with the game and a
 * reconnecting client cannot disagree with the host about what game it is in.
 */
@Serializable
data class GameRules(
    val startingMoney: Int = 1500,
    /** Paid on passing GO. */
    val goSalary: Int = 200,

    /**
     * Official rules: a player who declines to buy triggers an auction.
     * Most digital adaptations silently drop this, which badly distorts play.
     */
    val auctionUnboughtProperties: Boolean = true,

    /**
     * House rule: fines and taxes accumulate on Free Parking and are paid out to
     * whoever lands there. Off by default because it makes games run long.
     */
    val freeParkingJackpot: Boolean = false,

    /** Official rules: no rent is collected from a player sitting in jail. */
    val collectRentWhileInJail: Boolean = true,

    /** Flat fee to buy your way out of jail. */
    val jailFine: Int = 50,
    /** Turns you may sit in jail before the fine becomes compulsory. */
    val maxTurnsInJail: Int = 3,

    /** Rolling doubles this many times in one turn sends you to jail. */
    val doublesBeforeJail: Int = 3,

    /** Bank supply. Running out is a real constraint in the official rules. */
    val houseSupply: Int = 32,
    val hotelSupply: Int = 12,

    /**
     * Official rules: houses must be built evenly across a colour group, and you
     * cannot build a second house on one street until every street in the group
     * has one.
     */
    val enforceEvenBuild: Boolean = true,

    val minPlayers: Int = 2,
    val maxPlayers: Int = 8,
) {
    init {
        require(startingMoney > 0) { "startingMoney must be positive" }
        require(minPlayers >= 2) { "Monopoly needs at least 2 players" }
        require(maxPlayers >= minPlayers) { "maxPlayers must be >= minPlayers" }
    }

    companion object {
        /** The official rules, as written on the box. */
        val CLASSIC = GameRules()

        /** A shorter game, for when you have an hour rather than an evening. */
        val QUICK = GameRules(
            startingMoney = 2000,
            auctionUnboughtProperties = false,
            maxTurnsInJail = 2,
        )
    }
}
