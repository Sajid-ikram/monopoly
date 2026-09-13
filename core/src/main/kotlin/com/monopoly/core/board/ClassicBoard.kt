package com.monopoly.core.board

/**
 * The standard 40-space UK Monopoly board.
 *
 * This is the single source of truth for board geometry and prices. It is a
 * constant, shared by every game in flight; nothing here is ever mutated.
 *
 * The London edition, so the streets are the ones people here grew up with and
 * the four corners of the board are railway termini rather than US railroads.
 * The numbers are unchanged from the classic game — only the names and the
 * currency differ between editions.
 */
object ClassicBoard {

    const val SPACE_COUNT = 40
    const val GO_INDEX = 0
    const val JAIL_INDEX = 10
    const val FREE_PARKING_INDEX = 20
    const val GO_TO_JAIL_INDEX = 30

    val spaces: List<Space> = listOf(
        Go(0),
        Street(1, "Old Kent Road", ColorGroup.BROWN, 60, listOf(2, 10, 30, 90, 160, 250), 50),
        CommunityChestSpace(2),
        Street(3, "Whitechapel Road", ColorGroup.BROWN, 60, listOf(4, 20, 60, 180, 320, 450), 50),
        TaxSpace(4, "Income Tax", 200),
        Station(5, "King's Cross Station"),
        Street(6, "The Angel Islington", ColorGroup.LIGHT_BLUE, 100, listOf(6, 30, 90, 270, 400, 550), 50),
        ChanceSpace(7),
        Street(8, "Euston Road", ColorGroup.LIGHT_BLUE, 100, listOf(6, 30, 90, 270, 400, 550), 50),
        Street(9, "Pentonville Road", ColorGroup.LIGHT_BLUE, 120, listOf(8, 40, 100, 300, 450, 600), 50),
        JailSpace(10),
        Street(11, "Pall Mall", ColorGroup.PINK, 140, listOf(10, 50, 150, 450, 625, 750), 100),
        Utility(12, "Electric Company"),
        Street(13, "Whitehall", ColorGroup.PINK, 140, listOf(10, 50, 150, 450, 625, 750), 100),
        Street(14, "Northumberland Avenue", ColorGroup.PINK, 160, listOf(12, 60, 180, 500, 700, 900), 100),
        Station(15, "Marylebone Station"),
        Street(16, "Bow Street", ColorGroup.ORANGE, 180, listOf(14, 70, 200, 550, 750, 950), 100),
        CommunityChestSpace(17),
        Street(18, "Marlborough Street", ColorGroup.ORANGE, 180, listOf(14, 70, 200, 550, 750, 950), 100),
        Street(19, "Vine Street", ColorGroup.ORANGE, 200, listOf(16, 80, 220, 600, 800, 1000), 100),
        FreeParking(20),
        Street(21, "Strand", ColorGroup.RED, 220, listOf(18, 90, 250, 700, 875, 1050), 150),
        ChanceSpace(22),
        Street(23, "Fleet Street", ColorGroup.RED, 220, listOf(18, 90, 250, 700, 875, 1050), 150),
        Street(24, "Trafalgar Square", ColorGroup.RED, 240, listOf(20, 100, 300, 750, 925, 1100), 150),
        Station(25, "Fenchurch St Station"),
        Street(26, "Leicester Square", ColorGroup.YELLOW, 260, listOf(22, 110, 330, 800, 975, 1150), 150),
        Street(27, "Coventry Street", ColorGroup.YELLOW, 260, listOf(22, 110, 330, 800, 975, 1150), 150),
        Utility(28, "Water Works"),
        Street(29, "Piccadilly", ColorGroup.YELLOW, 280, listOf(24, 120, 360, 850, 1025, 1200), 150),
        GoToJail(30),
        Street(31, "Regent Street", ColorGroup.GREEN, 300, listOf(26, 130, 390, 900, 1100, 1275), 200),
        Street(32, "Oxford Street", ColorGroup.GREEN, 300, listOf(26, 130, 390, 900, 1100, 1275), 200),
        CommunityChestSpace(33),
        Street(34, "Bond Street", ColorGroup.GREEN, 320, listOf(28, 150, 450, 1000, 1200, 1400), 200),
        Station(35, "Liverpool Street Station"),
        ChanceSpace(36),
        Street(37, "Park Lane", ColorGroup.DARK_BLUE, 350, listOf(35, 175, 500, 1100, 1300, 1500), 200),
        TaxSpace(38, "Super Tax", 100),
        Street(39, "Mayfair", ColorGroup.DARK_BLUE, 400, listOf(50, 200, 600, 1400, 1700, 2000), 200),
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

    val stationIndices: List<Int> =
        spaces.filterIsInstance<Station>().map { it.index }

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
