package com.monopoly.core.engine

import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.model.TradeBundle
import com.monopoly.core.rules.GameRules
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

    /**
     * Takes a seat in the lobby. The server assigns [actor] before running this,
     * so it is the one command whose actor is not yet a player in the game.
     *
     * Joining goes through the engine like everything else rather than being
     * handled off to the side by the server, so that the lobby replicates by
     * the same mechanism as the game and a client that reconnects during setup
     * recovers exactly the way it would mid-game.
     */
    @Serializable
    data class JoinGame(
        override val actor: PlayerId,
        val displayName: String,
        val token: Token,
    ) : Command

    /**
     * Gives up a lobby seat. Leaving a game already in progress is a
     * disconnection, not this: that seat and its assets are held.
     */
    @Serializable
    data class LeaveLobby(override val actor: PlayerId) : Command

    /** Host-only, lobby-only: set the house rules everyone will play by. */
    @Serializable
    data class SetRules(override val actor: PlayerId, val rules: GameRules) : Command

    @Serializable
    data class ChangeToken(override val actor: PlayerId, val token: Token) : Command

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

    /**
     * Offers [recipient] a swap of cash, property and jail cards.
     *
     * Legal during your own turn, and also while you are the one staring at a
     * debt you cannot pay — selling a property to another player is often the
     * only way out, and a game that forbade it there would force bankruptcies
     * that the table would never have agreed to.
     */
    @Serializable
    data class ProposeTrade(
        override val actor: PlayerId,
        val recipient: PlayerId,
        val offered: TradeBundle,
        val requested: TradeBundle,
    ) : Command

    @Serializable
    data class AcceptTrade(override val actor: PlayerId) : Command

    @Serializable
    data class RejectTrade(override val actor: PlayerId) : Command

    /**
     * Answers an offer with a different one, in the other direction. Saves the
     * recipient having to reject and then wait for their own turn to reply.
     */
    @Serializable
    data class CounterTrade(
        override val actor: PlayerId,
        val offered: TradeBundle,
        val requested: TradeBundle,
    ) : Command

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
    GAME_FULL,
    ALREADY_JOINED,
    NAME_TAKEN,
    TOKEN_TAKEN,
    NOT_HOST,
    LAST_PLAYER_CANNOT_LEAVE,
    CANNOT_TRADE_WITH_YOURSELF,
    EMPTY_TRADE,
    NO_TRADE_PENDING,
    NOT_TRADE_RECIPIENT,
    TRADE_ASSET_UNAVAILABLE,
    TRADE_CASH_UNAVAILABLE,
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

