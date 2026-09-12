package com.monopoly.core.engine

/**
 * A deterministic random source whose entire state is a single [Long].
 *
 * The platform [kotlin.random.Random] is deliberately not used anywhere in the
 * engine. Every random draw must be reproducible from state that we can
 * serialise, store and replay, because:
 *
 *  - the server has to be able to re-derive a game from its event log, and
 *  - a reconnecting client must land on byte-identical state.
 *
 * This is SplitMix64 (Steele et al.), chosen because it is a single
 * multiplication-and-xor chain with no lookup tables, so it produces the same
 * sequence on every JVM and is trivial to port if a non-JVM client is ever
 * added.
 */
@JvmInline
value class Rng(val state: Long) {

    /** Advances the generator and returns the next state alongside the draw. */
    fun nextLong(): Pair<Rng, Long> {
        val next = state + GOLDEN_GAMMA
        var z = next
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return Rng(next) to (z xor (z ushr 31))
    }

    /** Uniform value in `0 until bound`. [bound] must be positive. */
    fun nextInt(bound: Int): Pair<Rng, Int> {
        require(bound > 0) { "bound must be positive, was $bound" }
        val (advanced, raw) = nextLong()
        // Take the low 31 bits to get a non-negative int, then reduce. The modulo
        // bias is irrelevant at the bounds this game uses (6, and deck sizes).
        val value = ((raw ushr 33).toInt()) % bound
        return advanced to value
    }

    /** A single die: 1..6. */
    fun rollDie(): Pair<Rng, Int> {
        val (advanced, value) = nextInt(6)
        return advanced to (value + 1)
    }

    /** A pair of dice, rolled in a fixed order so the sequence is reproducible. */
    fun rollDice(): Pair<Rng, DiceRoll> {
        val (afterFirst, first) = rollDie()
        val (afterSecond, second) = afterFirst.rollDie()
        return afterSecond to DiceRoll(first, second)
    }

    companion object {
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL

        fun seeded(seed: Long): Rng = Rng(seed)
    }
}

/**
 * The result of rolling two dice. Kept as the two individual values rather than
 * a total so the UI can animate real dice and so doubles are checkable.
 */
@kotlinx.serialization.Serializable
data class DiceRoll(val first: Int, val second: Int) {
    init {
        require(first in 1..6 && second in 1..6) { "Dice out of range: $first, $second" }
    }

    val total: Int get() = first + second
    val isDoubles: Boolean get() = first == second
}
