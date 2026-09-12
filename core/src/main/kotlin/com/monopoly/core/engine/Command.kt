package com.monopoly.core.engine

import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import kotlinx.serialization.Serializable

/**
 * Something a player is asking to do.
 *
 * Commands are *intents*, not state changes. A client never mutates its own
 * game; it sends a command and waits for the events the server derives from it.
 * This is what keeps a laggy or tampered-with client from corrupting a game:
 * the worst a bad command can do is get rejected.
 *
 * Every command carries the [actor] rather than relying on whose turn it is,
 * so the engine can reject an out-of-turn command instead of misattributing it
 * when packets arrive late or out of order.
 */
@Serializable
sealed interface Command {
    val actor: PlayerId

    /** Host begins the game; seat order is fixed at this point. */
    @Serializable
    data class StartGame(override val actor: PlayerId) : Command

    /** Roll and move. Also the "try for doubles" move when in jail. */
    @Serializable
    data class RollDice(override val actor: PlayerId) : Command

    @Serializable
    data class BuyProperty(override val actor: PlayerId) : Command

    /** Pass on buying. Starts an auction when the rules call for one. */
    @Serializable
    data class DeclineProperty(override val actor: PlayerId) : Command

    @Serializable
    data class PlaceBid(override val actor: PlayerId, val amount: Int) : Command

    @Serializable
    data class WithdrawFromAuction(override val actor: PlayerId) : Command

    @Serializable
    data class PayJailFine(override val actor: PlayerId) : Command

    @Serializable
    data class UseJailCard(override val actor: PlayerId) : Command

    @Serializable
    data class BuildHouse(override val actor: PlayerId, val spaceIndex: Int) : Command

    @Serializable
    data class SellHouse(override val actor: PlayerId, val spaceIndex: Int) : Command

    @Serializable
    data class MortgageProperty(override val actor: PlayerId, val spaceIndex: Int) : Command

    @Serializable
    data class UnmortgageProperty(override val actor: PlayerId, val spaceIndex: Int) : Command

    /** Retry an outstanding debt after raising cash. */
    @Serializable
    data class SettleDebt(override val actor: PlayerId) : Command

    @Serializable
    data class DeclareBankruptcy(override val actor: PlayerId) : Command

    @Serializable
    data class EndTurn(override val actor: PlayerId) : Command
}

/** Why a command was refused. Surfaced to the player, so keep these specific. */
@Serializable
enum class RejectionReason {
    NOT_YOUR_TURN,
    WRONG_PHASE,
    UNKNOWN_PLAYER,
    PLAYER_BANKRUPT,
    NOT_ENOUGH_PLAYERS,
    INSUFFICIENT_FUNDS,
    NOT_PURCHASABLE,
    ALREADY_OWNED,
    NOT_OWNED_BY_YOU,
    PROPERTY_MORTGAGED,
    INCOMPLETE_COLOR_GROUP,
    UNEVEN_BUILD,
    MAX_DEVELOPMENT_REACHED,
    NO_BUILDINGS_TO_SELL,
    MUST_SELL_BUILDINGS_FIRST,
    BANK_OUT_OF_HOUSES,
    BANK_OUT_OF_HOTELS,
    ALREADY_MORTGAGED,
    NOT_MORTGAGED,
    NO_JAIL_CARD,
    NOT_IN_JAIL,
    BID_TOO_LOW,
    NOT_IN_AUCTION,
    DEBT_OUTSTANDING,
    DEBT_STILL_UNPAYABLE,
    CAN_STILL_PAY,
}

/** The result of offering a [Command] to the engine. */
sealed interface Outcome {

    /**
     * The command was legal. [events] are the state changes it produced, in
     * order; [state] is the result of applying all of them.
     *
     * The server persists and broadcasts [events]; clients apply them to reach
     * the same [state] without trusting anyone to send a whole game over.
     */
    data class Accepted(val events: List<GameEvent>, val state: GameState) : Outcome

    data class Rejected(val reason: RejectionReason, val detail: String? = null) : Outcome
}

