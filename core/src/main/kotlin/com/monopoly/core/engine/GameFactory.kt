package com.monopoly.core.engine

import com.monopoly.core.model.CardDeck
import com.monopoly.core.model.ClassicCards
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.Player
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules

/** A seat in the lobby, before the game starts. */
data class Seat(val id: PlayerId, val name: String, val token: Token)

/**
 * Builds the opening state of a game.
 *
 * The [seed] is the only entropy that ever enters a game. Capture it and you
 * can reproduce the entire match from the event log, which is how a desynced
 * client is diagnosed rather than guessed at.
 */
object GameFactory {

    /**
     * Opens an empty lobby holding only the host.
     *
     * Everyone else arrives through [com.monopoly.core.engine.Command.JoinGame],
     * so the lobby fills up through the same event stream the game itself uses
     * and a client that reconnects during setup recovers identically.
     */
    fun newLobby(
        gameId: String,
        host: Seat,
        rules: GameRules = GameRules.CLASSIC,
        seed: Long,
    ): GameState = newGame(gameId, listOf(host), rules, seed)

    fun newGame(
        gameId: String,
        seats: List<Seat>,
        rules: GameRules = GameRules.CLASSIC,
        seed: Long,
    ): GameState {
        require(seats.isNotEmpty()) { "A game needs at least one seat" }
        require(seats.size <= rules.maxPlayers) {
            "${seats.size} seats exceeds the maximum of ${rules.maxPlayers}"
        }
        require(seats.map { it.token }.toSet().size == seats.size) { "Duplicate token" }

        var rng = Rng.seeded(seed)
        val (afterChance, chance) = CardDeck(ClassicCards.chance.map { it.id }).shuffled(rng)
        rng = afterChance
        val (afterChest, chest) = CardDeck(ClassicCards.communityChest.map { it.id }).shuffled(rng)
        rng = afterChest

        return GameState(
            gameId = gameId,
            rules = rules,
            players = seats.map { seat ->
                Player(
                    id = seat.id,
                    name = seat.name,
                    token = seat.token,
                    position = 0,
                    money = rules.startingMoney,
                )
            },
            phase = GamePhase.Lobby,
            chanceDeck = chance,
            communityChestDeck = chest,
            rngState = rng.state,
        )
    }
}
