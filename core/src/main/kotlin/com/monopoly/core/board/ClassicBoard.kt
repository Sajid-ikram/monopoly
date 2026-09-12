package com.monopoly.core.board

/**
 * The standard 40-space US Monopoly board.
 *
 * This is the single source of truth for board geometry and prices. It is a
 * constant, shared by every game in flight; nothing here is ever mutated.
 */
object ClassicBoard {

    const val SPACE_COUNT = 40
    const val GO_INDEX = 0
    const val JAIL_INDEX = 10
    const val FREE_PARKING_INDEX = 20
    const val GO_TO_JAIL_INDEX = 30

    val spaces: List<Space> = listOf(
        Go(0),
        Street(1, "Mediterranean Avenue", ColorGroup.BROWN, 60, listOf(2, 10, 30, 90, 160, 250), 50),
        CommunityChestSpace(2),
        Street(3, "Baltic Avenue", ColorGroup.BROWN, 60, listOf(4, 20, 60, 180, 320, 450), 50),
        TaxSpace(4, "Income Tax", 200),
        Railroad(5, "Reading Railroad"),
        Street(6, "Oriental Avenue", ColorGroup.LIGHT_BLUE, 100, listOf(6, 30, 90, 270, 400, 550), 50),
        ChanceSpace(7),
        Street(8, "Vermont Avenue", ColorGroup.LIGHT_BLUE, 100, listOf(6, 30, 90, 270, 400, 550), 50),
        Street(9, "Connecticut Avenue", ColorGroup.LIGHT_BLUE, 120, listOf(8, 40, 100, 300, 450, 600), 50),
        JailSpace(10),
        Street(11, "St. Charles Place", ColorGroup.PINK, 140, listOf(10, 50, 150, 450, 625, 750), 100),
        Utility(12, "Electric Company"),
        Street(13, "States Avenue", ColorGroup.PINK, 140, listOf(10, 50, 150, 450, 625, 750), 100),
        Street(14, "Virginia Avenue", ColorGroup.PINK, 160, listOf(12, 60, 180, 500, 700, 900), 100),
        Railroad(15, "Pennsylvania Railroad"),
        Street(16, "St. James Place", ColorGroup.ORANGE, 180, listOf(14, 70, 200, 550, 750, 950), 100),
        CommunityChestSpace(17),
        Street(18, "Tennessee Avenue", ColorGroup.ORANGE, 180, listOf(14, 70, 200, 550, 750, 950), 100),
        Street(19, "New York Avenue", ColorGroup.ORANGE, 200, listOf(16, 80, 220, 600, 800, 1000), 100),
        FreeParking(20),
        Street(21, "Kentucky Avenue", ColorGroup.RED, 220, listOf(18, 90, 250, 700, 875, 1050), 150),
        ChanceSpace(22),
        Street(23, "Indiana Avenue", ColorGroup.RED, 220, listOf(18, 90, 250, 700, 875, 1050), 150),
        Street(24, "Illinois Avenue", ColorGroup.RED, 240, listOf(20, 100, 300, 750, 925, 1100), 150),
        Railroad(25, "B&O Railroad"),
        Street(26, "Atlantic Avenue", ColorGroup.YELLOW, 260, listOf(22, 110, 330, 800, 975, 1150), 150),
        Street(27, "Ventnor Avenue", ColorGroup.YELLOW, 260, listOf(22, 110, 330, 800, 975, 1150), 150),
        Utility(28, "Water Works"),
        Street(29, "Marvin Gardens", ColorGroup.YELLOW, 280, listOf(24, 120, 360, 850, 1025, 1200), 150),
        GoToJail(30),
        Street(31, "Pacific Avenue", ColorGroup.GREEN, 300, listOf(26, 130, 390, 900, 1100, 1275), 200),
        Street(32, "North Carolina Avenue", ColorGroup.GREEN, 300, listOf(26, 130, 390, 900, 1100, 1275), 200),
        CommunityChestSpace(33),
        Street(34, "Pennsylvania Avenue", ColorGroup.GREEN, 320, listOf(28, 150, 450, 1000, 1200, 1400), 200),
        Railroad(35, "Short Line Railroad"),
        ChanceSpace(36),
        Street(37, "Park Place", ColorGroup.DARK_BLUE, 350, listOf(35, 175, 500, 1100, 1300, 1500), 200),
        TaxSpace(38, "Luxury Tax", 100),
        Street(39, "Boardwalk", ColorGroup.DARK_BLUE, 400, listOf(50, 200, 600, 1400, 1700, 2000), 200),
    )

    init {
        require(spaces.size == SPACE_COUNT) { "Board must have $SPACE_COUNT spaces" }
        spaces.forEachIndexed { i, space ->
            require(space.index == i) { "Space at position $i declares index ${space.index}" }
        }
    }

    /** Every space index that can be owned, in board order. */
    val purchasableIndices: List<Int> =
        spaces.filterIsInstance<Purchasable>().map { it.index }

    val railroadIndices: List<Int> =
        spaces.filterIsInstance<Railroad>().map { it.index }

    val utilityIndices: List<Int> =
        spaces.filterIsInstance<Utility>().map { it.index }

    /** Street indices grouped by colour, so "owns the whole group" is a cheap check. */
    val streetsByGroup: Map<ColorGroup, List<Int>> =
        spaces.filterIsInstance<Street>()
            .groupBy { it.group }
            .mapValues { (_, streets) -> streets.map { it.index } }

    operator fun get(index: Int): Space = spaces[normalize(index)]

    fun purchasableAt(index: Int): Purchasable? = spaces[normalize(index)] as? Purchasable

    fun streetAt(index: Int): Street? = spaces[normalize(index)] as? Street

    /** Wraps a raw position onto the board, so passing GO is just arithmetic. */
    fun normalize(position: Int): Int = ((position % SPACE_COUNT) + SPACE_COUNT) % SPACE_COUNT

    /**
     * Forward distance from [from] to [to], always travelling in the direction of
     * play. Used by cards that say "advance to" rather than "move N spaces".
     */
    fun forwardDistance(from: Int, to: Int): Int =
        normalize(to - from)
}
