package com.monopoly.core.model

import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.ColorGroup
import com.monopoly.core.board.Railroad
import com.monopoly.core.board.Street
import com.monopoly.core.board.Utility
import com.monopoly.core.engine.Rng
import com.monopoly.core.rules.GameRules
import kotlinx.serialization.Serializable

/**
 * The complete state of one game.
 *
 * This is the *entire* truth: given a [GameState] and nothing else, any client
 * can render the game and any server can continue it. That completeness is
 * deliberate — reconnect is implemented by shipping one of these, so anything
 * kept outside it would be state a returning player silently loses.
 *
 * Instances are immutable. The engine produces new ones.
 */
@Serializable
data class GameState(
    val gameId: String,
    val rules: GameRules,
    /** Seat order. Bankrupt players stay in the list so history stays readable. */
    val players: List<Player>,
    val currentPlayerIndex: Int = 0,
    val phase: GamePhase = GamePhase.Lobby,
    /** Ownable spaces that have been bought, keyed by board index. */
    val deeds: Map<Int, Deed> = emptyMap(),
    val chanceDeck: CardDeck,
    val communityChestDeck: CardDeck,
    /** Serialised [Rng] state. Advanced by every random draw. */
    val rngState: Long,
    val turn: TurnState = TurnState(),
    /** Only ever non-zero under the Free Parking house rule. */
    val freeParkingPot: Int = 0,
    /**
     * Monotonic counter, incremented once per applied event.
     *
     * This is the anchor for resynchronisation: a reconnecting client reports
     * the last version it saw, and the server replays from there or sends a
     * fresh snapshot if the gap is too large.
     */
    val version: Long = 0,
) {
    val rng: Rng get() = Rng(rngState)

    val currentPlayer: Player get() = players[currentPlayerIndex]

    val activePlayers: List<Player> get() = players.filter { it.isActive }

    fun playerOrNull(id: PlayerId): Player? = players.firstOrNull { it.id == id }

    fun player(id: PlayerId): Player =
        playerOrNull(id) ?: error("No such player in game $gameId: ${id.value}")

    fun indexOf(id: PlayerId): Int = players.indexOfFirst { it.id == id }

    /** Every space owned by [id], in board order. */
    fun deedsOf(id: PlayerId): List<Deed> =
        deeds.values.filter { it.owner == id }.sortedBy { it.spaceIndex }

    fun ownerOf(spaceIndex: Int): PlayerId? = deeds[spaceIndex]?.owner

    /** True when [id] holds every street of [group] and none are mortgaged. */
    fun ownsFullGroup(id: PlayerId, group: ColorGroup): Boolean {
        val indices = ClassicBoard.streetsByGroup[group] ?: return false
        return indices.all { deeds[it]?.owner == id }
    }

    /** Railroads owned by [id], used to scale railroad rent 25/50/100/200. */
    fun railroadsOwned(id: PlayerId): Int =
        ClassicBoard.railroadIndices.count { deeds[it]?.owner == id }

    /** Utilities owned by [id], used to pick the 4x or 10x dice multiplier. */
    fun utilitiesOwned(id: PlayerId): Int =
        ClassicBoard.utilityIndices.count { deeds[it]?.owner == id }

    /** Houses currently standing on the board, against [GameRules.houseSupply]. */
    val housesInUse: Int
        get() = deeds.values.sumOf { it.houseCount }

    val hotelsInUse: Int
        get() = deeds.values.count { it.hasHotel }

    val housesAvailable: Int get() = rules.houseSupply - housesInUse

    val hotelsAvailable: Int get() = rules.hotelSupply - hotelsInUse

    /**
     * Everything [id] could raise by mortgaging and selling every asset.
     * Used to decide whether a debt is survivable or is genuine bankruptcy.
     */
    fun liquidationValue(id: PlayerId): Int {
        val player = player(id)
        val fromAssets = deedsOf(id).sumOf { deed ->
            val space = ClassicBoard[deed.spaceIndex]
            val buildingRefund = when (space) {
                // Buildings sell back to the bank at half what they cost.
                is Street -> deed.houses * (space.buildCost / 2)
                else -> 0
            }
            val landValue = when {
                deed.mortgaged -> 0
                space is Street -> space.mortgageValue
                space is Railroad -> space.mortgageValue
                space is Utility -> space.mortgageValue
                else -> 0
            }
            buildingRefund + landValue
        }
        return player.money + fromAssets
    }

    /**
     * Net worth, the official tiebreaker when a game is ended early on time.
     * Cash, plus land at face value, plus buildings at cost.
     */
    fun netWorth(id: PlayerId): Int {
        val player = player(id)
        val assets = deedsOf(id).sumOf { deed ->
            val space = ClassicBoard[deed.spaceIndex]
            val land = when {
                deed.mortgaged -> 0
                space is Street -> space.price
                space is Railroad -> space.price
                space is Utility -> space.price
                else -> 0
            }
            val buildings = if (space is Street) deed.houses * space.buildCost else 0
            land + buildings
        }
        return player.money + assets
    }

    init {
        require(players.isNotEmpty()) { "A game needs at least one player" }
        require(currentPlayerIndex in players.indices) {
            "currentPlayerIndex $currentPlayerIndex out of bounds for ${players.size} players"
        }
        require(players.map { it.id }.toSet().size == players.size) {
            "Duplicate PlayerId in game $gameId"
        }
        require(version >= 0) { "version must not be negative" }
    }
}
