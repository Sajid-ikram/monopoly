package com.monopoly.core.engine

import com.monopoly.core.model.CardDeck
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.Player
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.model.TurnState
import com.monopoly.core.rules.GameRules
import kotlinx.serialization.Serializable

/** Which pile a card came from. */
@Serializable
enum class DeckKind { CHANCE, COMMUNITY_CHEST }

/** Why money moved. Drives the activity log and the on-screen explanation. */
@Serializable
enum class MoneyReason {
    STARTING_CASH,
    GO_SALARY,
    PROPERTY_PURCHASE,
    AUCTION_PURCHASE,
    RENT,
    TAX,
    CARD,
    JAIL_FINE,
    BUILDING_PURCHASE,
    BUILDING_SALE,
    MORTGAGE,
    UNMORTGAGE,
    FREE_PARKING,
    BANKRUPTCY_TRANSFER,
    TRADE,
}

/** How a player got out of jail, for the log. */
@Serializable
enum class JailRelease { PAID_FINE, USED_CARD, ROLLED_DOUBLES, SERVED_TIME }

/**
 * A single, already-decided change to the game.
 *
 * Events are the unit of replication. They are deliberately primitive and
 * fully self-describing — an event names the absolute values it sets rather
 * than an instruction to recompute something — so that [applyEvent] needs no
 * randomness, no clock and no rule knowledge. That is what makes a client's
 * replay provably identical to the server's, and what lets the server rebuild
 * any game from its log.
 */
@Serializable
sealed interface GameEvent {

    /** A player took a seat in the lobby, with their cash already dealt. */
    @Serializable
    data class PlayerJoined(val player: Player) : GameEvent

    /** A player gave up their lobby seat. Only ever happens before the game starts. */
    @Serializable
    data class PlayerLeft(val player: PlayerId) : GameEvent

    @Serializable
    data class RulesChanged(val rules: GameRules) : GameEvent

    @Serializable
    data class TokenChanged(val player: PlayerId, val token: Token) : GameEvent

    @Serializable
    data class GameStarted(val seatOrder: List<PlayerId>, val rngState: Long) : GameEvent

    @Serializable
    data class DiceRolled(
        val player: PlayerId,
        val roll: DiceRoll,
        val rngState: Long,
    ) : GameEvent

    /** Absolute destination, already normalised onto the board. */
    @Serializable
    data class PlayerMoved(
        val player: PlayerId,
        val from: Int,
        val to: Int,
        val passedGo: Boolean,
    ) : GameEvent

    /**
     * Money moving. A null [from] or [to] means the bank, which has no balance
     * to track.
     */
    @Serializable
    data class MoneyTransferred(
        val from: PlayerId?,
        val to: PlayerId?,
        val amount: Int,
        val reason: MoneyReason,
    ) : GameEvent

    @Serializable
    data class FreeParkingPotChanged(val newValue: Int) : GameEvent

    /** Assigns or reassigns ownership. Used by purchase, auction and bankruptcy. */
    @Serializable
    data class DeedAssigned(
        val spaceIndex: Int,
        val owner: PlayerId,
        val houses: Int = 0,
        val mortgaged: Boolean = false,
    ) : GameEvent

    /** Returns a space to the bank. */
    @Serializable
    data class DeedReleased(val spaceIndex: Int) : GameEvent

    @Serializable
    data class HousesChanged(val spaceIndex: Int, val houses: Int) : GameEvent

    @Serializable
    data class MortgageChanged(val spaceIndex: Int, val mortgaged: Boolean) : GameEvent

    @Serializable
    data class JailStatusChanged(
        val player: PlayerId,
        val inJail: Boolean,
        val jailTurns: Int,
        val release: JailRelease? = null,
    ) : GameEvent

    @Serializable
    data class CardDrawn(
        val player: PlayerId,
        val deck: DeckKind,
        val cardId: String,
        /** The deck *after* the draw, so replay never re-derives an order. */
        val deckState: CardDeck,
    ) : GameEvent

    /** A "get out of jail free" card leaving the deck into a player's hand. */
    @Serializable
    data class JailCardHeld(
        val player: PlayerId,
        val cardId: String,
        val deck: DeckKind,
        val deckState: CardDeck,
    ) : GameEvent

    /** A held jail card being spent and returned to the bottom of its deck. */
    @Serializable
    data class JailCardReturned(
        val player: PlayerId,
        val cardId: String,
        val deck: DeckKind,
        val deckState: CardDeck,
    ) : GameEvent

    @Serializable
    data class TurnStateChanged(val turn: TurnState) : GameEvent

    /** Passes play to [nextPlayerIndex] and clears the per-turn bookkeeping. */
    @Serializable
    data class TurnAdvanced(val nextPlayerIndex: Int) : GameEvent

    @Serializable
    data class PhaseChanged(val phase: GamePhase) : GameEvent

    @Serializable
    data class PlayerBankrupted(val player: PlayerId, val creditor: PlayerId?) : GameEvent

    /**
     * A player's socket attached or dropped. Not a game action — the seat and
     * all its assets are untouched — but every client shows it, so it is
     * replicated like everything else.
     */
    @Serializable
    data class ConnectionChanged(val player: PlayerId, val connected: Boolean) : GameEvent
}
