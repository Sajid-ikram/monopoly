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

    // Mediterranean Avenue (1) and Baltic Avenue (3) are the brown group.
    private val mediterranean = 1
    private val baltic = 3

    @Test
    fun `a lone street charges its base rent`() {
        val state = stateWith(Deed(mediterranean, owner))
        assertEquals(2, Rent.rentFor(state, mediterranean, null))
    }

    @Test
    fun `owning the whole colour group doubles undeveloped rent`() {
        val state = stateWith(Deed(mediterranean, owner), Deed(baltic, owner))
        assertEquals(4, Rent.rentFor(state, mediterranean, null))
        assertEquals(8, Rent.rentFor(state, baltic, null))
    }

    @Test
    fun `the doubling stops as soon as one street of the group is sold`() {
        val split = stateWith(Deed(mediterranean, owner), Deed(baltic, TestGames.ALICE))
        assertEquals(2, Rent.rentFor(split, mediterranean, null))
    }

    @Test
    fun `houses use the printed tier and are never doubled again`() {
        val state = stateWith(
            Deed(mediterranean, owner, houses = 3),
            Deed(baltic, owner),
        )
        // Mediterranean with three houses: $90, not $180.
        assertEquals(90, Rent.rentFor(state, mediterranean, null))
    }

    @Test
    fun `a hotel charges the top tier`() {
        val state = stateWith(Deed(39, owner, houses = Deed.HOTEL))
        assertEquals(2000, Rent.rentFor(state, 39, null))
    }

    @Test
    fun `a mortgaged property earns nothing however developed the group is`() {
        val state = stateWith(
            Deed(mediterranean, owner, mortgaged = true),
            Deed(baltic, owner),
        )
        assertEquals(0, Rent.rentFor(state, mediterranean, null))
    }

    @Test
    fun `an unowned space charges nothing`() {
        assertEquals(0, Rent.rentFor(TestGames.started(), mediterranean, null))
    }

    @Test
    fun `railroad rent doubles with each railroad held`() {
        val railroads = listOf(5, 15, 25, 35)
        val expected = listOf(25, 50, 100, 200)
        railroads.indices.forEach { i ->
            val held = railroads.take(i + 1).map { Deed(it, owner) }
            val state = stateWith(*held.toTypedArray())
            assertEquals(expected[i], Rent.rentFor(state, railroads[0], null), "with ${i + 1} railroads")
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
        // "Advance to the nearest railroad and pay twice the usual rent", with
        // the owner holding only one railroad: 25 x 2.
        val state = stateWith(Deed(5, owner))
        assertEquals(50, Rent.rentFor(state, 5, null, forcedMultiplier = 2))

        // "Advance to the nearest utility and pay ten times the dice", even
        // though the owner holds a single utility, which normally charges four.
        val utility = stateWith(Deed(12, owner))
        assertEquals(60, Rent.rentFor(utility, 12, DiceRoll(3, 3), forcedMultiplier = 10))
    }
}
