package com.monopoly.core.model

import com.monopoly.core.engine.DiceRoll
import kotlinx.serialization.Serializable

/**
 * What the game is waiting for, right now.
 *
 * Every phase names whose input is required, which is what lets a client that
 * has just reconnected render the correct controls from the snapshot alone
 * without replaying any history.
 */
@Serializable
sealed interface GamePhase {

    /** Players are still joining and picking tokens. */
    @Serializable
    data object Lobby : GamePhase

    /** The current player must roll the dice. */
    @Serializable
    data object AwaitingRoll : GamePhase

    /** The current player is in jail and must choose how to get out. */
    @Serializable
    data object AwaitingJailDecision : GamePhase

    /** The current player landed on an unowned property and must buy or pass. */
    @Serializable
    data class AwaitingPurchase(val spaceIndex: Int) : GamePhase

    /** A declined property is being auctioned to every solvent player. */
    @Serializable
    data class Auction(
        val spaceIndex: Int,
        val highestBid: Int = 0,
        val highestBidder: PlayerId? = null,
        /** Players still in the auction, in bidding order. */
        val activeBidders: List<PlayerId>,
        /** Index into [activeBidders] of whoever must bid or fold next. */
        val turnIndex: Int = 0,
    ) : GamePhase {
        val currentBidder: PlayerId? get() = activeBidders.getOrNull(turnIndex)
    }

    /**
     * [debtor] owes money they cannot currently pay and must raise it by
     * mortgaging or selling, or declare bankruptcy. The game blocks here rather
     * than auto-selling, because which asset to give up is a real decision.
     */
    @Serializable
    data class AwaitingDebtSettlement(
        val debtor: PlayerId,
        /** Null when the debt is owed to the bank. */
        val creditor: PlayerId?,
        val amount: Int,
    ) : GamePhase

    /**
     * The roll is resolved. The current player may build, trade or mortgage,
     * then ends their turn (or rolls again, if they rolled doubles).
     */
    @Serializable
    data class AwaitingTurnEnd(val mayRollAgain: Boolean = false) : GamePhase

    /**
     * A trade has been proposed and the other player has not answered yet.
     *
     * [resumePhase] is whatever the game was doing when the offer was made, so
     * that answering puts play back exactly where it left off. Carrying it here
     * rather than re-deriving it matters because a trade can be proposed from
     * several different points in a turn — including out of a debt that the
     * trade is meant to help pay.
     */
    @Serializable
    data class AwaitingTradeResponse(
        val offer: TradeOffer,
        val resumePhase: GamePhase,
    ) : GamePhase

    @Serializable
    data class GameOver(val winner: PlayerId?) : GamePhase
}

/** Bookkeeping that resets at the start of each player's turn. */
@Serializable
data class TurnState(
    /** Consecutive doubles this turn; the third sends the player to jail. */
    val doublesRolled: Int = 0,
    val lastRoll: DiceRoll? = null,
    /**
     * Set once the player has rolled and moved. Needed because building and
     * trading are legal before *and* after the roll, but rolling is not.
     */
    val hasRolled: Boolean = false,
)
