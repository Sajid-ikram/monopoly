package com.monopoly.core.model

import kotlinx.serialization.Serializable

/** What drawing a card does. Resolved by the engine, never by the UI. */
@Serializable
sealed interface CardEffect {

    /** Go directly to a named space, collecting salary only if we pass GO. */
    @Serializable
    data class AdvanceTo(val spaceIndex: Int, val collectSalary: Boolean = true) : CardEffect

    /** "Advance to the nearest railroad/utility and pay the owner [rentMultiplier]x." */
    @Serializable
    data class AdvanceToNearest(val kind: Kind, val rentMultiplier: Int) : CardEffect {
        @Serializable
        enum class Kind { RAILROAD, UTILITY }
    }

    /** Relative movement; negative moves backwards. Does not pass GO when negative. */
    @Serializable
    data class MoveSpaces(val spaces: Int) : CardEffect

    @Serializable
    data class CollectFromBank(val amount: Int) : CardEffect

    @Serializable
    data class PayBank(val amount: Int) : CardEffect

    /** Each other solvent player pays the drawer [amount]. */
    @Serializable
    data class CollectFromEachPlayer(val amount: Int) : CardEffect

    /** The drawer pays [amount] to every other solvent player. */
    @Serializable
    data class PayEachPlayer(val amount: Int) : CardEffect

    @Serializable
    data object GoToJail : CardEffect

    /** Kept by the player until used, then returned to the bottom of its deck. */
    @Serializable
    data object GetOutOfJailFree : CardEffect

    /** "Make general repairs": a charge per house and per hotel you own. */
    @Serializable
    data class Repairs(val perHouse: Int, val perHotel: Int) : CardEffect
}

@Serializable
data class Card(
    val id: String,
    val text: String,
    val effect: CardEffect,
)

/**
 * A draw pile.
 *
 * Modelled as an explicit ordered list plus a cursor rather than a shuffled
 * mutable stack, so the whole deck state serialises to a list and an int and a
 * reconnecting client can be handed the exact deck the server has.
 *
 * "Get out of jail free" cards leave the deck when drawn (the holder keeps
 * them) and are appended back on use, matching the physical game.
 */
@Serializable
data class CardDeck(
    val cardIds: List<String>,
    val nextIndex: Int = 0,
) {
    init {
        require(cardIds.isNotEmpty()) { "A deck must hold at least one card" }
        require(nextIndex in cardIds.indices) { "nextIndex $nextIndex out of bounds" }
    }

    /** Draws the top card, moving it to the bottom. */
    fun draw(): Pair<CardDeck, String> {
        val drawn = cardIds[nextIndex]
        return copy(nextIndex = (nextIndex + 1) % cardIds.size) to drawn
    }

    /** Removes a card from circulation, for a kept "get out of jail free". */
    fun remove(cardId: String): CardDeck {
        val remaining = cardIds.filterNot { it == cardId }
        require(remaining.isNotEmpty()) { "Cannot empty the deck by removing $cardId" }
        // The cursor indexes a now-shorter list, so pull it back in bounds while
        // preserving the position of the next card to be drawn.
        val removedBeforeCursor = cardIds.take(nextIndex).count { it == cardId }
        return CardDeck(remaining, (nextIndex - removedBeforeCursor) % remaining.size)
    }

    /** Returns a used card to the bottom of the pile. */
    fun returnToBottom(cardId: String): CardDeck {
        // "Bottom" is the slot just before the cursor: it is the last card that
        // will be reached as the cursor wraps around.
        val restored = cardIds.toMutableList().apply { add(nextIndex, cardId) }
        return CardDeck(restored, (nextIndex + 1) % restored.size)
    }

    /**
     * Deals a fresh deck in a deterministic order derived from [rng].
     * Both the server and any replaying client arrive at the same order.
     */
    fun shuffled(rng: com.monopoly.core.engine.Rng): Pair<com.monopoly.core.engine.Rng, CardDeck> {
        val working = cardIds.toMutableList()
        var current = rng
        // Fisher-Yates, walked from the end so the draw order is well defined.
        for (i in working.indices.reversed()) {
            val (advanced, j) = current.nextInt(i + 1)
            current = advanced
            val swap = working[i]
            working[i] = working[j]
            working[j] = swap
        }
        return current to CardDeck(working, 0)
    }
}
