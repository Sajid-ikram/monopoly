package com.monopoly.core.engine

import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.Railroad
import com.monopoly.core.board.Street
import com.monopoly.core.board.Utility
import com.monopoly.core.model.GameState

/**
 * Rent lookup, kept separate from the engine so it can be unit-tested against
 * the printed rent tables and reused by the UI to preview what a square costs.
 */
object Rent {

    /** Railroad rent doubles with each railroad the owner holds: 25/50/100/200. */
    private const val RAILROAD_BASE = 25

    /** Utility rent is a multiple of the dice roll, not a fixed sum. */
    private const val UTILITY_SINGLE_MULTIPLIER = 4
    private const val UTILITY_PAIR_MULTIPLIER = 10

    /**
     * Rent owed for landing on [spaceIndex], or 0 if nothing is owed.
     *
     * @param roll the roll that brought the player here; required for utilities.
     * @param forcedMultiplier set by Chance cards that say "pay twice the usual
     *   rent" or "ten times the dice". Overrides the normal calculation.
     */
    fun rentFor(
        state: GameState,
        spaceIndex: Int,
        roll: DiceRoll?,
        forcedMultiplier: Int? = null,
    ): Int {
        val deed = state.deeds[spaceIndex] ?: return 0
        // A mortgaged property earns nothing. This is the rule players most
        // often forget, and the one that makes mortgaging a real trade-off.
        if (deed.mortgaged) return 0

        return when (val space = ClassicBoard[spaceIndex]) {
            is Street -> streetRent(state, space, deed.houses, forcedMultiplier)
            is Railroad -> railroadRent(state.railroadsOwned(deed.owner), forcedMultiplier)
            is Utility -> utilityRent(state.utilitiesOwned(deed.owner), roll, forcedMultiplier)
            else -> 0
        }
    }

    private fun streetRent(
        state: GameState,
        street: Street,
        houses: Int,
        forcedMultiplier: Int?,
    ): Int {
        val base = if (houses > 0) {
            street.rentTiers[houses]
        } else {
            val owner = state.ownerOf(street.index)
            // Undeveloped rent doubles once the owner holds the whole colour group.
            val doubled = owner != null && state.ownsFullGroup(owner, street.group)
            if (doubled) street.rentTiers[0] * 2 else street.rentTiers[0]
        }
        return base * (forcedMultiplier ?: 1)
    }

    private fun railroadRent(owned: Int, forcedMultiplier: Int?): Int {
        if (owned <= 0) return 0
        val base = RAILROAD_BASE shl (owned - 1)
        return base * (forcedMultiplier ?: 1)
    }

    private fun utilityRent(owned: Int, roll: DiceRoll?, forcedMultiplier: Int?): Int {
        if (owned <= 0 || roll == null) return 0
        val multiplier = forcedMultiplier
            ?: if (owned >= 2) UTILITY_PAIR_MULTIPLIER else UTILITY_SINGLE_MULTIPLIER
        return multiplier * roll.total
    }
}
