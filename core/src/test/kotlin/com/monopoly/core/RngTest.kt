package com.monopoly.core

import com.monopoly.core.engine.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generator is the foundation of the whole replication scheme: if two
 * machines ever draw different dice from the same state, every guarantee above
 * it collapses.
 */
class RngTest {

    @Test
    fun `the same seed always produces the same sequence`() {
        val first = generateSequence(Rng.seeded(42L)) { it.rollDice().first }
            .take(200).map { it.state }.toList()
        val second = generateSequence(Rng.seeded(42L)) { it.rollDice().first }
            .take(200).map { it.state }.toList()
        assertEquals(first, second)
    }

    @Test
    fun `different seeds diverge`() {
        val a = Rng.seeded(1L).rollDice().second
        val b = Rng.seeded(2L).rollDice().second
        // Not a strong claim, but a generator that ignored its seed would fail it.
        assertTrue(a != b || Rng.seeded(1L).nextLong().second != Rng.seeded(2L).nextLong().second)
    }

    @Test
    fun `dice always land in range`() {
        var rng = Rng.seeded(7L)
        repeat(10_000) {
            val (next, roll) = rng.rollDice()
            rng = next
            assertTrue(roll.first in 1..6, "die out of range: ${roll.first}")
            assertTrue(roll.second in 1..6, "die out of range: ${roll.second}")
        }
    }

    @Test
    fun `every face shows up over many rolls`() {
        var rng = Rng.seeded(99L)
        val seen = mutableSetOf<Int>()
        repeat(2_000) {
            val (next, roll) = rng.rollDice()
            rng = next
            seen += roll.first
            seen += roll.second
        }
        assertEquals((1..6).toSet(), seen)
    }

    @Test
    fun `the distribution is not obviously skewed`() {
        var rng = Rng.seeded(2024L)
        val counts = IntArray(7)
        val rolls = 60_000
        repeat(rolls) {
            val (next, roll) = rng.rollDice()
            rng = next
            counts[roll.first]++
            counts[roll.second]++
        }
        val expected = (rolls * 2) / 6
        (1..6).forEach { face ->
            val drift = kotlin.math.abs(counts[face] - expected).toDouble() / expected
            // A loose bound: this is here to catch a broken reduction, not to be
            // a statistical test of randomness quality.
            assertTrue(drift < 0.05, "Face $face appeared ${counts[face]}, expected about $expected")
        }
    }

    @Test
    fun `advancing the generator never repeats the previous state`() {
        var rng = Rng.seeded(5L)
        val seen = mutableSetOf(rng.state)
        repeat(5_000) {
            rng = rng.nextLong().first
            assertTrue(seen.add(rng.state), "State repeated after ${seen.size} draws")
        }
    }
}
