package com.monopoly.core

import com.monopoly.core.engine.DiceRoll
import com.monopoly.core.engine.Rent
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rent checked against the printed title deeds. */
class RentTest {

    private val owner = TestGames.BOB

    private fun stateWith(vararg deeds: Deed): GameState =
        TestGames.started().copy(deeds = deeds.associateBy { it.spaceIndex })

    // Old Kent Road (1) and Whitechapel Road (3) are the brown group.
    private val oldKent = 1
    private val whitechapel = 3

    @Test
    fun `a lone street charges its base rent`() {
        val state = stateWith(Deed(oldKent, owner))
        assertEquals(2, Rent.rentFor(state, oldKent, null))
    }

    @Test
    fun `owning the whole colour group doubles undeveloped rent`() {
        val state = stateWith(Deed(oldKent, owner), Deed(whitechapel, owner))
        assertEquals(4, Rent.rentFor(state, oldKent, null))
        assertEquals(8, Rent.rentFor(state, whitechapel, null))
    }

    @Test
    fun `the doubling stops as soon as one street of the group is sold`() {
        val split = stateWith(Deed(oldKent, owner), Deed(whitechapel, TestGames.ALICE))
        assertEquals(2, Rent.rentFor(split, oldKent, null))
    }

    @Test
    fun `houses use the printed tier and are never doubled again`() {
        val state = stateWith(
            Deed(oldKent, owner, houses = 3),
            Deed(whitechapel, owner),
        )
        // Old Kent Road with three houses: £90, not £180.
        assertEquals(90, Rent.rentFor(state, oldKent, null))
    }

    @Test
    fun `a hotel charges the top tier`() {
        val state = stateWith(Deed(39, owner, houses = Deed.HOTEL))
        assertEquals(2000, Rent.rentFor(state, 39, null))
    }

    @Test
    fun `a mortgaged property earns nothing however developed the group is`() {
        val state = stateWith(
            Deed(oldKent, owner, mortgaged = true),
            Deed(whitechapel, owner),
        )
        assertEquals(0, Rent.rentFor(state, oldKent, null))
    }

    @Test
    fun `an unowned space charges nothing`() {
        assertEquals(0, Rent.rentFor(TestGames.started(), oldKent, null))
    }

    @Test
    fun `station rent doubles with each station held`() {
        val stations = listOf(5, 15, 25, 35)
        val expected = listOf(25, 50, 100, 200)
        stations.indices.forEach { i ->
            val held = stations.take(i + 1).map { Deed(it, owner) }
            val state = stateWith(*held.toTypedArray())
            assertEquals(expected[i], Rent.rentFor(state, stations[0], null), "with ${i + 1} stations")
        }
    }

    @Test
    fun `utility rent is four times the roll, or ten with both`() {
        val roll = DiceRoll(3, 4)
        val one = stateWith(Deed(12, owner))
        assertEquals(28, Rent.rentFor(one, 12, roll))

        val both = stateWith(Deed(12, owner), Deed(28, owner))
        assertEquals(70, Rent.rentFor(both, 12, roll))
    }

    @Test
    fun `a card can force a rent multiplier regardless of holdings`() {
        // "Advance to the nearest station and pay twice the usual rent", with
        // the owner holding only one station: 25 x 2.
        val state = stateWith(Deed(5, owner))
        assertEquals(50, Rent.rentFor(state, 5, null, forcedMultiplier = 2))

        // "Advance to the nearest utility and pay ten times the dice", even
        // though the owner holds a single utility, which normally charges four.
        val utility = stateWith(Deed(12, owner))
        assertEquals(60, Rent.rentFor(utility, 12, DiceRoll(3, 3), forcedMultiplier = 10))
    }
}
