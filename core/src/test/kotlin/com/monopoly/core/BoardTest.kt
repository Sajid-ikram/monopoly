package com.monopoly.core

import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.ColorGroup
import com.monopoly.core.board.Street
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The board is transcribed data, and a typo in it would produce a game that
 * plays almost right — the worst kind of bug to find by playing. These check it
 * against the printed board.
 */
class BoardTest {

    @Test
    fun `board has forty spaces in index order`() {
        assertEquals(40, ClassicBoard.spaces.size)
        ClassicBoard.spaces.forEachIndexed { i, space -> assertEquals(i, space.index) }
    }

    @Test
    fun `board has twenty two streets, four stations and two utilities`() {
        assertEquals(22, ClassicBoard.spaces.filterIsInstance<Street>().size)
        assertEquals(4, ClassicBoard.stationIndices.size)
        assertEquals(2, ClassicBoard.utilityIndices.size)
        assertEquals(28, ClassicBoard.purchasableIndices.size)
    }

    @Test
    fun `every colour group has the number of streets it declares`() {
        ColorGroup.entries.forEach { group ->
            val streets = ClassicBoard.streetsByGroup.getValue(group)
            assertEquals(group.streetCount, streets.size, "Wrong street count for $group")
        }
    }

    @Test
    fun `mortgage value is half price and lifting it costs ten percent more`() {
        val mayfair = ClassicBoard.purchasableAt(39)!!
        assertEquals(400, mayfair.price)
        assertEquals(200, mayfair.mortgageValue)
        assertEquals(220, mayfair.unmortgageCost)
    }

    @Test
    fun `rent rises with every level of development`() {
        ClassicBoard.spaces.filterIsInstance<Street>().forEach { street ->
            street.rentTiers.zipWithNext { lower, higher ->
                assertTrue(higher > lower, "${street.name} rent does not increase: ${street.rentTiers}")
            }
        }
    }

    @Test
    fun `positions wrap around the board in both directions`() {
        assertEquals(0, ClassicBoard.normalize(40))
        assertEquals(5, ClassicBoard.normalize(45))
        assertEquals(37, ClassicBoard.normalize(-3))
    }

    @Test
    fun `forward distance always travels in the direction of play`() {
        assertEquals(5, ClassicBoard.forwardDistance(0, 5))
        // From Mayfair to GO is one step forward, not 39 steps back.
        assertEquals(1, ClassicBoard.forwardDistance(39, 0))
        assertEquals(0, ClassicBoard.forwardDistance(12, 12))
    }

    @Test
    fun `corners and non-purchasable squares cannot be bought`() {
        listOf(0, 10, 20, 30, 2, 4, 7).forEach { index ->
            assertNull(ClassicBoard.purchasableAt(index), "Space $index should not be purchasable")
        }
    }
}
