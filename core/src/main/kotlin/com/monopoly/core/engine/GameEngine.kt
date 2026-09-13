package com.monopoly.core.engine

import com.monopoly.core.board.ChanceSpace
import com.monopoly.core.board.ClassicBoard
import com.monopoly.core.board.CommunityChestSpace
import com.monopoly.core.board.FreeParking
import com.monopoly.core.board.GoToJail
import com.monopoly.core.board.Purchasable
import com.monopoly.core.board.TaxSpace
import com.monopoly.core.model.CardEffect
import com.monopoly.core.model.ClassicCards
import com.monopoly.core.model.Deed
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.Player
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.TradeBundle
import com.monopoly.core.model.TradeOffer

/**
 * The rules of Monopoly, as one pure function.
 *
 * [reduce] is the only entry point. It takes the current state and one command,
 * and either rejects it or returns the events it produced. It performs no I/O,
 * reads no clock, and draws no randomness except through the [Rng] carried in
 * the state — so the same state plus the same command always gives the same
 * result, on the server and on every client.
 *
 * That property is the whole architecture in one sentence. It is what lets the
 * server stay authoritative (a client's command can only ever be accepted or
 * rejected, never trusted), and what lets a client that dropped out mid-turn be
 * handed an event log and arrive at exactly the board everyone else is seeing.
 */
object GameEngine {

    fun reduce(state: GameState, command: Command): Outcome {
        // Joining is the one command whose actor is not a player yet, so it is
        // dispatched before the "who are you" check below.
        if (command is Command.JoinGame) return joinGame(state, command)

        val actor = state.playerOrNull(command.actor)
            ?: return Outcome.Rejected(RejectionReason.UNKNOWN_PLAYER)
        if (actor.bankrupt) return Outcome.Rejected(RejectionReason.PLAYER_BANKRUPT)

        return when (command) {
            is Command.JoinGame -> error("Handled above")
            is Command.LeaveLobby -> leaveLobby(state, command)
            is Command.SetRules -> setRules(state, command)
            is Command.ChangeToken -> changeToken(state, command)
            is Command.StartGame -> startGame(state, command)
            is Command.RollDice -> rollDice(state, command)
            is Command.BuyProperty -> buyProperty(state, command)
            is Command.DeclineProperty -> declineProperty(state, command)
            is Command.PlaceBid -> placeBid(state, command)
            is Command.WithdrawFromAuction -> withdrawFromAuction(state, command)
            is Command.PayJailFine -> payJailFine(state, command)
            is Command.UseJailCard -> useJailCard(state, command)
            is Command.BuildHouse -> buildHouse(state, command)
            is Command.SellHouse -> sellHouse(state, command)
            is Command.MortgageProperty -> mortgage(state, command)
            is Command.UnmortgageProperty -> unmortgage(state, command)
            is Command.ProposeTrade -> proposeTrade(state, command)
            is Command.AcceptTrade -> acceptTrade(state, command)
            is Command.RejectTrade -> rejectTrade(state, command)
            is Command.CounterTrade -> counterTrade(state, command)
            is Command.SettleDebt -> settleDebt(state, command)
            is Command.DeclareBankruptcy -> declareBankruptcy(state, command)
            is Command.EndTurn -> endTurn(state, command)
        }
    }

    // -------------------------------------------------------------------- lobby

    private fun joinGame(state: GameState, command: Command.JoinGame): Outcome {
        if (state.phase != GamePhase.Lobby) {
            return Outcome.Rejected(RejectionReason.WRONG_PHASE, "The game has already started")
        }
        // Idempotent by design: a client that retries a join after a dropped
        // reply must not end up occupying two seats.
        if (state.playerOrNull(command.actor) != null) {
            return Outcome.Rejected(RejectionReason.ALREADY_JOINED)
        }
        if (state.players.size >= state.rules.maxPlayers) {
            return Outcome.Rejected(RejectionReason.GAME_FULL)
        }
        if (state.players.any { it.name.equals(command.displayName, ignoreCase = true) }) {
            return Outcome.Rejected(RejectionReason.NAME_TAKEN)
        }
        if (state.players.any { it.token == command.token }) {
            return Outcome.Rejected(RejectionReason.TOKEN_TAKEN)
        }

        val tx = Transaction(state)
        tx.emit(
            GameEvent.PlayerJoined(
                Player(
                    id = command.actor,
                    name = command.displayName,
                    token = command.token,
                    money = state.rules.startingMoney,
                ),
            ),
        )
        return tx.accept()
    }

    private fun leaveLobby(state: GameState, command: Command.LeaveLobby): Outcome {
        if (state.phase != GamePhase.Lobby) {
            return Outcome.Rejected(RejectionReason.WRONG_PHASE, "Leaving mid-game is a disconnect")
        }
        // The host holds seat zero, so when they leave the next player inherits
        // it. An empty game has no representation, so the last seat cannot go.
        if (state.players.size <= 1) {
            return Outcome.Rejected(RejectionReason.LAST_PLAYER_CANNOT_LEAVE)
        }

        val tx = Transaction(state)
        tx.emit(GameEvent.PlayerLeft(command.actor))
        return tx.accept()
    }

    private fun setRules(state: GameState, command: Command.SetRules): Outcome {
        if (state.phase != GamePhase.Lobby) return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        if (state.players.first().id != command.actor) {
            return Outcome.Rejected(RejectionReason.NOT_HOST)
        }
        if (state.players.size > command.rules.maxPlayers) {
            return Outcome.Rejected(
                RejectionReason.GAME_FULL,
                "${state.players.size} players already seated",
            )
        }

        val tx = Transaction(state)
        tx.emit(GameEvent.RulesChanged(command.rules))
        // Starting cash is dealt on join, so a change to it has to reach the
        // players already sitting down or the lobby would deal unequal stacks.
        if (command.rules.startingMoney != state.rules.startingMoney) {
            state.players.forEach { player ->
                val delta = command.rules.startingMoney - player.money
                when {
                    delta > 0 -> tx.emit(
                        GameEvent.MoneyTransferred(null, player.id, delta, MoneyReason.STARTING_CASH),
                    )
                    delta < 0 -> tx.emit(
                        GameEvent.MoneyTransferred(player.id, null, -delta, MoneyReason.STARTING_CASH),
                    )
                }
            }
        }
        return tx.accept()
    }

    private fun changeToken(state: GameState, command: Command.ChangeToken): Outcome {
        if (state.phase != GamePhase.Lobby) return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        if (state.players.any { it.id != command.actor && it.token == command.token }) {
            return Outcome.Rejected(RejectionReason.TOKEN_TAKEN)
        }

        val tx = Transaction(state)
        tx.emit(GameEvent.TokenChanged(command.actor, command.token))
        return tx.accept()
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startGame(state: GameState, command: Command.StartGame): Outcome {
        if (state.phase != GamePhase.Lobby) {
            return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        }
        // The host holds the first seat, and only the host may start.
        if (state.players.first().id != command.actor) {
            return Outcome.Rejected(RejectionReason.NOT_HOST)
        }
        if (state.players.size < state.rules.minPlayers) {
            return Outcome.Rejected(
                RejectionReason.NOT_ENOUGH_PLAYERS,
                "Need ${state.rules.minPlayers}, have ${state.players.size}",
            )
        }

        // Seat order is shuffled rather than taken from join order, so being
        // quickest to tap "join" is not an advantage.
        val (rng, order) = shuffle(state.rng, state.players.map { it.id })
        val tx = Transaction(state)
        tx.emit(GameEvent.GameStarted(order, rng.state))
        return tx.accept()
    }

    private fun <T> shuffle(rng: Rng, items: List<T>): Pair<Rng, List<T>> {
        val working = items.toMutableList()
        var current = rng
        for (i in working.indices.reversed()) {
            val (advanced, j) = current.nextInt(i + 1)
            current = advanced
            val swap = working[i]
            working[i] = working[j]
            working[j] = swap
        }
        return current to working
    }

    // -------------------------------------------------------------------- turns

    private fun rollDice(state: GameState, command: Command.RollDice): Outcome {
        requireTurn(state, command.actor)?.let { return it }
        val fromJail = state.phase == GamePhase.AwaitingJailDecision
        if (state.phase != GamePhase.AwaitingRoll && !fromJail) {
            return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        }

        val tx = Transaction(state)
        val (rng, roll) = state.rng.rollDice()
        tx.emit(GameEvent.DiceRolled(command.actor, roll, rng.state))

        if (fromJail) resolveJailRoll(tx, command.actor, roll)
        else resolveFreeRoll(tx, command.actor, roll)

        return tx.accept()
    }

    /** A roll made from inside jail: doubles get you out, three failures cost you. */
    private fun resolveJailRoll(tx: Transaction, actor: PlayerId, roll: DiceRoll) {
        val servedTurns = tx.state.player(actor).jailTurns + 1

        if (roll.isDoubles) {
            tx.emit(GameEvent.JailStatusChanged(actor, false, 0, JailRelease.ROLLED_DOUBLES))
            moveAndResolve(tx, actor, roll)
            // Getting out on doubles does not also grant another roll.
            finishRollIfIdle(tx, mayRollAgain = false)
            return
        }

        if (servedTurns < tx.state.rules.maxTurnsInJail) {
            tx.emit(GameEvent.JailStatusChanged(actor, true, servedTurns))
            tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingTurnEnd(mayRollAgain = false)))
            return
        }

        // Time served: the fine becomes compulsory, then the roll still counts.
        tx.emit(GameEvent.JailStatusChanged(actor, false, 0, JailRelease.SERVED_TIME))
        chargeToBankOrPot(tx, actor, tx.state.rules.jailFine, MoneyReason.JAIL_FINE)
        if (tx.state.phase is GamePhase.AwaitingDebtSettlement) return
        moveAndResolve(tx, actor, roll)
        finishRollIfIdle(tx, mayRollAgain = false)
    }

    /** A normal roll: watch for the third double, then move and resolve. */
    private fun resolveFreeRoll(tx: Transaction, actor: PlayerId, roll: DiceRoll) {
        val doubles = if (roll.isDoubles) tx.state.turn.doublesRolled + 1 else 0
        tx.emit(GameEvent.TurnStateChanged(tx.state.turn.copy(doublesRolled = doubles)))

        if (roll.isDoubles && doubles >= tx.state.rules.doublesBeforeJail) {
            // Speeding: straight to jail, without moving or collecting.
            sendToJail(tx, actor)
            tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingTurnEnd(mayRollAgain = false)))
            return
        }

        moveAndResolve(tx, actor, roll)
        finishRollIfIdle(tx, mayRollAgain = roll.isDoubles)
    }

    private fun moveAndResolve(tx: Transaction, actor: PlayerId, roll: DiceRoll) {
        movePlayer(tx, actor, roll.total)
        resolveLanding(tx, actor, tx.state.player(actor).position, roll)
    }

    /**
     * Closes out a roll, unless resolving the landing already put the game into
     * a phase that is waiting on somebody.
     */
    private fun finishRollIfIdle(tx: Transaction, mayRollAgain: Boolean) {
        if (tx.state.phase.isAwaitingInput()) return
        tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingTurnEnd(mayRollAgain)))
    }

    private fun endTurn(state: GameState, command: Command.EndTurn): Outcome {
        requireTurn(state, command.actor)?.let { return it }
        val phase = state.phase as? GamePhase.AwaitingTurnEnd
            ?: return Outcome.Rejected(RejectionReason.WRONG_PHASE)

        val tx = Transaction(state)
        if (phase.mayRollAgain) {
            // Doubles: the same player rolls again, keeping the doubles count so
            // that the third one still sends them to jail.
            tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingRoll))
            return tx.accept()
        }
        advanceTurn(tx)
        return tx.accept()
    }

    private fun advanceTurn(tx: Transaction) {
        if (checkGameOver(tx)) return

        val players = tx.state.players
        var index = tx.state.currentPlayerIndex
        // Skip anyone bankrupt; they stay in the list so the log stays readable.
        do {
            index = (index + 1) % players.size
        } while (players[index].bankrupt)

        tx.emit(GameEvent.TurnAdvanced(index))
        val next = tx.state.players[index]
        val phase = if (next.inJail) GamePhase.AwaitingJailDecision else GamePhase.AwaitingRoll
        tx.emit(GameEvent.PhaseChanged(phase))
    }

    private fun checkGameOver(tx: Transaction): Boolean {
        val survivors = tx.state.activePlayers
        if (survivors.size > 1) return false
        tx.emit(GameEvent.PhaseChanged(GamePhase.GameOver(survivors.firstOrNull()?.id)))
        return true
    }

    // ----------------------------------------------------------------- movement

    /** Moves [actor] forward [spaces], paying salary if they pass GO. */
    private fun movePlayer(tx: Transaction, actor: PlayerId, spaces: Int) {
        val from = tx.state.player(actor).position
        val raw = from + spaces
        val to = ClassicBoard.normalize(raw)
        // Only forward movement passes GO. A "go back three spaces" card that
        // wraps past GO does not pay out, which is the rule as written.
        val passedGo = spaces > 0 && raw >= ClassicBoard.SPACE_COUNT
        tx.emit(GameEvent.PlayerMoved(actor, from, to, passedGo))
        if (passedGo) payFromBank(tx, actor, tx.state.rules.goSalary, MoneyReason.GO_SALARY)
    }

    /** Jumps [actor] directly to [target], paying salary only if GO is passed. */
    private fun advanceTo(tx: Transaction, actor: PlayerId, target: Int, collectSalary: Boolean) {
        val from = tx.state.player(actor).position
        val distance = ClassicBoard.forwardDistance(from, target)
        val passedGo = collectSalary && from + distance >= ClassicBoard.SPACE_COUNT
        tx.emit(GameEvent.PlayerMoved(actor, from, target, passedGo))
        if (passedGo) payFromBank(tx, actor, tx.state.rules.goSalary, MoneyReason.GO_SALARY)
    }

    private fun sendToJail(tx: Transaction, actor: PlayerId) {
        val from = tx.state.player(actor).position
        // Going to jail never pays salary, however far round the board it is.
        tx.emit(GameEvent.PlayerMoved(actor, from, ClassicBoard.JAIL_INDEX, passedGo = false))
        tx.emit(GameEvent.JailStatusChanged(actor, inJail = true, jailTurns = 0))
        // Doubles do not earn another roll once you are in jail.
        tx.emit(GameEvent.TurnStateChanged(tx.state.turn.copy(doublesRolled = 0)))
    }

    // ------------------------------------------------------------------ landing

    /**
     * Works out what landing on [spaceIndex] costs or offers.
     *
     * [forcedRentMultiplier] is set by the Chance cards that send a player to a
     * station or utility with a penalty rent attached.
     */
    private fun resolveLanding(
        tx: Transaction,
        actor: PlayerId,
        spaceIndex: Int,
        roll: DiceRoll?,
        forcedRentMultiplier: Int? = null,
    ) {
        when (val space = ClassicBoard[spaceIndex]) {
            is Purchasable -> resolvePurchasable(tx, actor, space, roll, forcedRentMultiplier)
            is TaxSpace -> chargeToBankOrPot(tx, actor, space.amount, MoneyReason.TAX)
            is ChanceSpace -> drawCard(tx, actor, DeckKind.CHANCE, roll)
            is CommunityChestSpace -> drawCard(tx, actor, DeckKind.COMMUNITY_CHEST, roll)
            is GoToJail -> sendToJail(tx, actor)
            is FreeParking -> collectFreeParking(tx, actor)
            // GO and Just Visiting need no resolution.
            else -> Unit
        }
    }

    private fun resolvePurchasable(
        tx: Transaction,
        actor: PlayerId,
        space: Purchasable,
        roll: DiceRoll?,
        forcedRentMultiplier: Int?,
    ) {
        val owner = tx.state.ownerOf(space.index)
        when {
            owner == null ->
                tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingPurchase(space.index)))

            owner == actor -> Unit

            // Under one common house rule a player in jail collects nothing.
            !tx.state.rules.collectRentWhileInJail && tx.state.player(owner).inJail -> Unit

            else -> {
                val rent = Rent.rentFor(tx.state, space.index, roll, forcedRentMultiplier)
                if (rent > 0) charge(tx, actor, owner, rent, MoneyReason.RENT)
            }
        }
    }

    private fun collectFreeParking(tx: Transaction, actor: PlayerId) {
        if (!tx.state.rules.freeParkingJackpot) return
        val pot = tx.state.freeParkingPot
        if (pot <= 0) return
        tx.emit(GameEvent.FreeParkingPotChanged(0))
        tx.emit(GameEvent.MoneyTransferred(null, actor, pot, MoneyReason.FREE_PARKING))
    }

    // -------------------------------------------------------------------- cards

    private fun drawCard(tx: Transaction, actor: PlayerId, deck: DeckKind, roll: DiceRoll?) {
        val source = tx.state.deck(deck)
        val (afterDraw, cardId) = source.draw()
        val card = ClassicCards.requireCard(cardId)

        if (card.effect is CardEffect.GetOutOfJailFree) {
            // Kept cards leave the deck entirely until they are spent.
            tx.emit(GameEvent.JailCardHeld(actor, cardId, deck, source.remove(cardId)))
            return
        }

        tx.emit(GameEvent.CardDrawn(actor, deck, cardId, afterDraw))
        applyCardEffect(tx, actor, card.effect, roll)
    }

    private fun applyCardEffect(
        tx: Transaction,
        actor: PlayerId,
        effect: CardEffect,
        roll: DiceRoll?,
    ) {
        when (effect) {
            is CardEffect.AdvanceTo -> {
                advanceTo(tx, actor, effect.spaceIndex, effect.collectSalary)
                resolveLanding(tx, actor, effect.spaceIndex, roll)
            }

            is CardEffect.AdvanceToNearest -> {
                val candidates = when (effect.kind) {
                    CardEffect.AdvanceToNearest.Kind.STATION -> ClassicBoard.stationIndices
                    CardEffect.AdvanceToNearest.Kind.UTILITY -> ClassicBoard.utilityIndices
                }
                val from = tx.state.player(actor).position
                // "Nearest" always means forward; the token never moves backwards.
                val target = candidates.minByOrNull { index ->
                    val d = ClassicBoard.forwardDistance(from, index)
                    if (d == 0) ClassicBoard.SPACE_COUNT else d
                } ?: return

                advanceTo(tx, actor, target, collectSalary = true)
                // If the square is unowned the player may buy it as normal; the
                // penalty rent only applies when somebody already owns it.
                resolveLanding(tx, actor, target, roll, forcedRentMultiplier = effect.rentMultiplier)
            }

            is CardEffect.MoveSpaces -> {
                movePlayer(tx, actor, effect.spaces)
                resolveLanding(tx, actor, tx.state.player(actor).position, roll)
            }

            is CardEffect.CollectFromBank ->
                payFromBank(tx, actor, effect.amount, MoneyReason.CARD)

            is CardEffect.PayBank ->
                chargeToBankOrPot(tx, actor, effect.amount, MoneyReason.CARD)

            is CardEffect.CollectFromEachPlayer ->
                tx.state.activePlayers
                    .filter { it.id != actor }
                    .forEach { other -> charge(tx, other.id, actor, effect.amount, MoneyReason.CARD) }

            is CardEffect.PayEachPlayer ->
                tx.state.activePlayers
                    .filter { it.id != actor }
                    .forEach { other -> charge(tx, actor, other.id, effect.amount, MoneyReason.CARD) }

            is CardEffect.GoToJail -> sendToJail(tx, actor)

            is CardEffect.Repairs -> {
                val owned = tx.state.deedsOf(actor)
                val total = owned.sumOf { it.houseCount } * effect.perHouse +
                    owned.count { it.hasHotel } * effect.perHotel
                if (total > 0) chargeToBankOrPot(tx, actor, total, MoneyReason.CARD)
            }

            // Intercepted in drawCard; a kept card never reaches here.
            is CardEffect.GetOutOfJailFree -> Unit
        }
    }

    // --------------------------------------------------------------------- jail

    private fun payJailFine(state: GameState, command: Command.PayJailFine): Outcome {
        requireTurn(state, command.actor)?.let { return it }
        if (state.phase != GamePhase.AwaitingJailDecision) {
            return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        }
        val player = state.player(command.actor)
        if (!player.inJail) return Outcome.Rejected(RejectionReason.NOT_IN_JAIL)
        if (player.money < state.rules.jailFine) {
            return Outcome.Rejected(RejectionReason.INSUFFICIENT_FUNDS)
        }

        val tx = Transaction(state)
        chargeToBankOrPot(tx, command.actor, state.rules.jailFine, MoneyReason.JAIL_FINE)
        tx.emit(GameEvent.JailStatusChanged(command.actor, false, 0, JailRelease.PAID_FINE))
        // Paying the fine buys the right to roll; it does not replace the roll.
        tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingRoll))
        return tx.accept()
    }

    private fun useJailCard(state: GameState, command: Command.UseJailCard): Outcome {
        requireTurn(state, command.actor)?.let { return it }
        if (state.phase != GamePhase.AwaitingJailDecision) {
            return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        }
        val player = state.player(command.actor)
        if (!player.inJail) return Outcome.Rejected(RejectionReason.NOT_IN_JAIL)
        val cardId = player.getOutOfJailCards.firstOrNull()
            ?: return Outcome.Rejected(RejectionReason.NO_JAIL_CARD)

        val deck = if (ClassicCards.chance.any { it.id == cardId }) {
            DeckKind.CHANCE
        } else {
            DeckKind.COMMUNITY_CHEST
        }

        val tx = Transaction(state)
        val restored = state.deck(deck).returnToBottom(cardId)
        tx.emit(GameEvent.JailCardReturned(command.actor, cardId, deck, restored))
        tx.emit(GameEvent.JailStatusChanged(command.actor, false, 0, JailRelease.USED_CARD))
        tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingRoll))
        return tx.accept()
    }

    // ---------------------------------------------------------- buying property

    private fun buyProperty(state: GameState, command: Command.BuyProperty): Outcome {
        requireTurn(state, command.actor)?.let { return it }
        val phase = state.phase as? GamePhase.AwaitingPurchase
            ?: return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        val space = ClassicBoard.purchasableAt(phase.spaceIndex)
            ?: return Outcome.Rejected(RejectionReason.NOT_PURCHASABLE)
        if (state.ownerOf(space.index) != null) {
            return Outcome.Rejected(RejectionReason.ALREADY_OWNED)
        }
        if (state.player(command.actor).money < space.price) {
            return Outcome.Rejected(RejectionReason.INSUFFICIENT_FUNDS)
        }

        val tx = Transaction(state)
        tx.emit(
            GameEvent.MoneyTransferred(
                command.actor, null, space.price, MoneyReason.PROPERTY_PURCHASE,
            ),
        )
        tx.emit(GameEvent.DeedAssigned(space.index, command.actor))
        tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingTurnEnd(rolledDoublesThisTurn(state))))
        return tx.accept()
    }

    private fun declineProperty(state: GameState, command: Command.DeclineProperty): Outcome {
        requireTurn(state, command.actor)?.let { return it }
        val phase = state.phase as? GamePhase.AwaitingPurchase
            ?: return Outcome.Rejected(RejectionReason.WRONG_PHASE)

        val tx = Transaction(state)
        if (!state.rules.auctionUnboughtProperties) {
            tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingTurnEnd(rolledDoublesThisTurn(state))))
            return tx.accept()
        }

        // Everyone bids, including the player who just passed: declining is a bet
        // that nobody else wants it at the asking price, not a forfeit.
        tx.emit(
            GameEvent.PhaseChanged(
                GamePhase.Auction(
                    spaceIndex = phase.spaceIndex,
                    activeBidders = state.activePlayers.map { it.id },
                ),
            ),
        )
        return tx.accept()
    }

    private fun placeBid(state: GameState, command: Command.PlaceBid): Outcome {
        val auction = state.phase as? GamePhase.Auction
            ?: return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        if (auction.currentBidder != command.actor) {
            return Outcome.Rejected(RejectionReason.NOT_IN_AUCTION)
        }
        if (command.amount <= auction.highestBid) {
            return Outcome.Rejected(RejectionReason.BID_TOO_LOW)
        }
        if (command.amount > state.player(command.actor).money) {
            return Outcome.Rejected(RejectionReason.INSUFFICIENT_FUNDS)
        }

        val tx = Transaction(state)
        tx.emit(
            GameEvent.PhaseChanged(
                auction.copy(
                    highestBid = command.amount,
                    highestBidder = command.actor,
                    turnIndex = (auction.turnIndex + 1) % auction.activeBidders.size,
                ),
            ),
        )
        return tx.accept()
    }

    private fun withdrawFromAuction(
        state: GameState,
        command: Command.WithdrawFromAuction,
    ): Outcome {
        val auction = state.phase as? GamePhase.Auction
            ?: return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        if (auction.currentBidder != command.actor) {
            return Outcome.Rejected(RejectionReason.NOT_IN_AUCTION)
        }

        val remaining = auction.activeBidders - command.actor
        val tx = Transaction(state)

        if (remaining.size <= 1) {
            // One bidder left, or none: the highest bid so far takes it.
            val winner = auction.highestBidder ?: remaining.firstOrNull().takeIf { auction.highestBid > 0 }
            closeAuction(tx, auction, winner)
            return tx.accept()
        }

        // Removing a bidder shifts everyone after them down one seat, so the
        // cursor stays put rather than advancing, and wraps if it ran off the end.
        tx.emit(
            GameEvent.PhaseChanged(
                auction.copy(
                    activeBidders = remaining,
                    turnIndex = auction.turnIndex % remaining.size,
                ),
            ),
        )
        return tx.accept()
    }

    private fun closeAuction(tx: Transaction, auction: GamePhase.Auction, winner: PlayerId?) {
        if (winner != null && auction.highestBid > 0) {
            tx.emit(
                GameEvent.MoneyTransferred(
                    winner, null, auction.highestBid, MoneyReason.AUCTION_PURCHASE,
                ),
            )
            tx.emit(GameEvent.DeedAssigned(auction.spaceIndex, winner))
        }
        // The auction ran during somebody's turn; hand play back to them.
        tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingTurnEnd(rolledDoublesThisTurn(tx.state))))
    }

    // --------------------------------------------------------------- developing

    private fun buildHouse(state: GameState, command: Command.BuildHouse): Outcome {
        val street = ClassicBoard.streetAt(command.spaceIndex)
            ?: return Outcome.Rejected(RejectionReason.NOT_PURCHASABLE)
        val deed = state.deeds[command.spaceIndex]
            ?: return Outcome.Rejected(RejectionReason.NOT_OWNED_BY_YOU)
        if (deed.owner != command.actor) return Outcome.Rejected(RejectionReason.NOT_OWNED_BY_YOU)
        if (!state.ownsFullGroup(command.actor, street.group)) {
            return Outcome.Rejected(RejectionReason.INCOMPLETE_COLOR_GROUP)
        }
        if (deed.houses >= Deed.HOTEL) {
            return Outcome.Rejected(RejectionReason.MAX_DEVELOPMENT_REACHED)
        }

        val group = ClassicBoard.streetsByGroup.getValue(street.group)
        // A mortgaged street anywhere in the group blocks building on all of them.
        if (group.any { state.deeds[it]?.mortgaged == true }) {
            return Outcome.Rejected(RejectionReason.PROPERTY_MORTGAGED)
        }
        if (state.rules.enforceEvenBuild) {
            val lowest = group.minOf { state.deeds[it]?.houses ?: 0 }
            if (deed.houses > lowest) return Outcome.Rejected(RejectionReason.UNEVEN_BUILD)
        }

        // The fifth building is a hotel, which comes from a separate supply and
        // hands its four houses back to the bank.
        val buildingHotel = deed.houses == Deed.HOTEL - 1
        if (buildingHotel && state.hotelsAvailable <= 0) {
            return Outcome.Rejected(RejectionReason.BANK_OUT_OF_HOTELS)
        }
        if (!buildingHotel && state.housesAvailable <= 0) {
            return Outcome.Rejected(RejectionReason.BANK_OUT_OF_HOUSES)
        }
        if (state.player(command.actor).money < street.buildCost) {
            return Outcome.Rejected(RejectionReason.INSUFFICIENT_FUNDS)
        }

        val tx = Transaction(state)
        tx.emit(
            GameEvent.MoneyTransferred(
                command.actor, null, street.buildCost, MoneyReason.BUILDING_PURCHASE,
            ),
        )
        tx.emit(GameEvent.HousesChanged(command.spaceIndex, deed.houses + 1))
        return tx.accept()
    }

    private fun sellHouse(state: GameState, command: Command.SellHouse): Outcome {
        val street = ClassicBoard.streetAt(command.spaceIndex)
            ?: return Outcome.Rejected(RejectionReason.NOT_PURCHASABLE)
        val deed = state.deeds[command.spaceIndex]
            ?: return Outcome.Rejected(RejectionReason.NOT_OWNED_BY_YOU)
        if (deed.owner != command.actor) return Outcome.Rejected(RejectionReason.NOT_OWNED_BY_YOU)
        if (deed.houses <= 0) return Outcome.Rejected(RejectionReason.NO_BUILDINGS_TO_SELL)

        if (state.rules.enforceEvenBuild) {
            val group = ClassicBoard.streetsByGroup.getValue(street.group)
            val highest = group.maxOf { state.deeds[it]?.houses ?: 0 }
            if (deed.houses < highest) return Outcome.Rejected(RejectionReason.UNEVEN_BUILD)
        }

        val tx = Transaction(state)
        tx.emit(GameEvent.HousesChanged(command.spaceIndex, deed.houses - 1))
        // Buildings sell back to the bank at half what they cost.
        tx.emit(
            GameEvent.MoneyTransferred(
                null, command.actor, street.buildCost / 2, MoneyReason.BUILDING_SALE,
            ),
        )
        return tx.accept()
    }

    private fun mortgage(state: GameState, command: Command.MortgageProperty): Outcome {
        val space = ClassicBoard.purchasableAt(command.spaceIndex)
            ?: return Outcome.Rejected(RejectionReason.NOT_PURCHASABLE)
        val deed = state.deeds[command.spaceIndex]
            ?: return Outcome.Rejected(RejectionReason.NOT_OWNED_BY_YOU)
        if (deed.owner != command.actor) return Outcome.Rejected(RejectionReason.NOT_OWNED_BY_YOU)
        if (deed.mortgaged) return Outcome.Rejected(RejectionReason.ALREADY_MORTGAGED)
        if (deed.houses > 0) return Outcome.Rejected(RejectionReason.MUST_SELL_BUILDINGS_FIRST)

        val tx = Transaction(state)
        tx.emit(GameEvent.MortgageChanged(command.spaceIndex, true))
        tx.emit(
            GameEvent.MoneyTransferred(null, command.actor, space.mortgageValue, MoneyReason.MORTGAGE),
        )
        return tx.accept()
    }

    private fun unmortgage(state: GameState, command: Command.UnmortgageProperty): Outcome {
        val space = ClassicBoard.purchasableAt(command.spaceIndex)
            ?: return Outcome.Rejected(RejectionReason.NOT_PURCHASABLE)
        val deed = state.deeds[command.spaceIndex]
            ?: return Outcome.Rejected(RejectionReason.NOT_OWNED_BY_YOU)
        if (deed.owner != command.actor) return Outcome.Rejected(RejectionReason.NOT_OWNED_BY_YOU)
        if (!deed.mortgaged) return Outcome.Rejected(RejectionReason.NOT_MORTGAGED)
        if (state.player(command.actor).money < space.unmortgageCost) {
            return Outcome.Rejected(RejectionReason.INSUFFICIENT_FUNDS)
        }

        val tx = Transaction(state)
        tx.emit(
            GameEvent.MoneyTransferred(
                command.actor, null, space.unmortgageCost, MoneyReason.UNMORTGAGE,
            ),
        )
        tx.emit(GameEvent.MortgageChanged(command.spaceIndex, false))
        return tx.accept()
    }

    // ------------------------------------------------------------------ trading

    private fun proposeTrade(state: GameState, command: Command.ProposeTrade): Outcome {
        if (!state.canOpenTrade(command.actor)) {
            return Outcome.Rejected(
                RejectionReason.WRONG_PHASE,
                "Trades are proposed on your own turn, or while you owe money",
            )
        }
        val offer = buildOffer(command.actor, command.recipient, command.offered, command.requested)
            ?: return Outcome.Rejected(RejectionReason.CANNOT_TRADE_WITH_YOURSELF)

        validate(state, offer)?.let { return it }

        val tx = Transaction(state)
        tx.emit(
            GameEvent.PhaseChanged(
                GamePhase.AwaitingTradeResponse(offer, resumePhase = state.phase),
            ),
        )
        return tx.accept()
    }

    private fun counterTrade(state: GameState, command: Command.CounterTrade): Outcome {
        val pending = state.phase as? GamePhase.AwaitingTradeResponse
            ?: return Outcome.Rejected(RejectionReason.NO_TRADE_PENDING)
        // Only the person being asked may counter; the proposer withdraws and
        // proposes again instead.
        if (pending.offer.to != command.actor) {
            return Outcome.Rejected(RejectionReason.NOT_TRADE_RECIPIENT)
        }

        val offer = buildOffer(
            from = command.actor,
            to = pending.offer.from,
            offered = command.offered,
            requested = command.requested,
        ) ?: return Outcome.Rejected(RejectionReason.CANNOT_TRADE_WITH_YOURSELF)

        validate(state, offer)?.let { return it }

        val tx = Transaction(state)
        // The counter replaces the original but keeps the same resume point, so
        // however long two players haggle, play returns where it left off.
        tx.emit(
            GameEvent.PhaseChanged(
                GamePhase.AwaitingTradeResponse(offer, resumePhase = pending.resumePhase),
            ),
        )
        return tx.accept()
    }

    private fun rejectTrade(state: GameState, command: Command.RejectTrade): Outcome {
        val pending = state.phase as? GamePhase.AwaitingTradeResponse
            ?: return Outcome.Rejected(RejectionReason.NO_TRADE_PENDING)
        // Either side may call it off: the recipient declines, the proposer
        // withdraws.
        if (command.actor != pending.offer.to && command.actor != pending.offer.from) {
            return Outcome.Rejected(RejectionReason.NOT_TRADE_RECIPIENT)
        }

        val tx = Transaction(state)
        tx.emit(GameEvent.TradeRejected(pending.offer))
        tx.emit(GameEvent.PhaseChanged(pending.resumePhase))
        return tx.accept()
    }

    private fun acceptTrade(state: GameState, command: Command.AcceptTrade): Outcome {
        val pending = state.phase as? GamePhase.AwaitingTradeResponse
            ?: return Outcome.Rejected(RejectionReason.NO_TRADE_PENDING)
        if (pending.offer.to != command.actor) {
            return Outcome.Rejected(RejectionReason.NOT_TRADE_RECIPIENT)
        }

        // Re-checked at acceptance, not just at proposal. Between the two, the
        // proposer may have mortgaged, built on, or spent what they promised.
        validate(state, pending.offer)?.let { return it }

        val tx = Transaction(state)
        val offer = pending.offer
        transfer(tx, offer.from, offer.to, offer.offered)
        transfer(tx, offer.to, offer.from, offer.requested)
        tx.emit(GameEvent.TradeCompleted(offer))
        tx.emit(GameEvent.PhaseChanged(pending.resumePhase))
        return tx.accept()
    }

    private fun buildOffer(
        from: PlayerId,
        to: PlayerId,
        offered: TradeBundle,
        requested: TradeBundle,
    ): TradeOffer? = if (from == to) null else TradeOffer(from, to, offered, requested)

    private fun transfer(
        tx: Transaction,
        from: PlayerId,
        to: PlayerId,
        bundle: TradeBundle,
    ) {
        if (bundle.cash > 0) {
            tx.emit(GameEvent.MoneyTransferred(from, to, bundle.cash, MoneyReason.TRADE))
        }
        bundle.spaces.forEach { spaceIndex ->
            val deed = tx.state.deeds[spaceIndex] ?: return@forEach
            // Buildings never change hands, and the validation above has already
            // established there are none, so the deed moves with its mortgage
            // status and nothing else.
            tx.emit(
                GameEvent.DeedAssigned(
                    spaceIndex = spaceIndex,
                    owner = to,
                    houses = 0,
                    mortgaged = deed.mortgaged,
                ),
            )
        }
        bundle.jailCards.forEach { cardId ->
            tx.emit(GameEvent.JailCardTransferred(from, to, cardId))
        }
    }

    /**
     * Checks that both halves of a deal can actually be delivered.
     *
     * Run at proposal *and* at acceptance, because the two are separated by an
     * unbounded amount of play: the proposer can mortgage a property, build on
     * it or spend the cash in between, and accepting a promise that can no
     * longer be kept would create assets out of nothing.
     */
    private fun validate(state: GameState, offer: TradeOffer): Outcome.Rejected? {
        if (offer.isEmpty) {
            return Outcome.Rejected(RejectionReason.EMPTY_TRADE, "Nothing on either side")
        }
        val proposer = state.playerOrNull(offer.from)
            ?: return Outcome.Rejected(RejectionReason.UNKNOWN_PLAYER)
        val recipient = state.playerOrNull(offer.to)
            ?: return Outcome.Rejected(RejectionReason.UNKNOWN_PLAYER)
        if (proposer.bankrupt || recipient.bankrupt) {
            return Outcome.Rejected(RejectionReason.PLAYER_BANKRUPT)
        }

        checkSide(state, proposer, offer.offered)?.let { return it }
        checkSide(state, recipient, offer.requested)?.let { return it }
        return null
    }

    private fun checkSide(state: GameState, giver: Player, bundle: TradeBundle): Outcome.Rejected? {
        if (bundle.cash > giver.money) {
            return Outcome.Rejected(
                RejectionReason.TRADE_CASH_UNAVAILABLE,
                "${giver.name} does not have £${bundle.cash}",
            )
        }

        bundle.spaces.forEach { spaceIndex ->
            val deed = state.deeds[spaceIndex]
            if (deed == null || deed.owner != giver.id) {
                return Outcome.Rejected(
                    RejectionReason.TRADE_ASSET_UNAVAILABLE,
                    "${giver.name} does not own ${ClassicBoard[spaceIndex].name}",
                )
            }
            // A property cannot be traded out of a developed colour group. The
            // buildings would be stranded: they belong to the group, not to the
            // one street, so the whole group has to be sold back to the bank
            // first. Checking the group rather than just this deed is what makes
            // that rule real.
            val street = ClassicBoard.streetAt(spaceIndex)
            if (street != null) {
                val group = ClassicBoard.streetsByGroup.getValue(street.group)
                if (group.any { (state.deeds[it]?.houses ?: 0) > 0 }) {
                    return Outcome.Rejected(
                        RejectionReason.MUST_SELL_BUILDINGS_FIRST,
                        "Sell the buildings on the ${street.group} group first",
                    )
                }
            }
        }

        bundle.jailCards.forEach { cardId ->
            if (cardId !in giver.getOutOfJailCards) {
                return Outcome.Rejected(
                    RejectionReason.TRADE_ASSET_UNAVAILABLE,
                    "${giver.name} is not holding that card",
                )
            }
        }
        return null
    }

    // --------------------------------------------------------------- insolvency

    /**
     * Moves money, or blocks the game on a debt the payer cannot currently cover.
     *
     * Nothing is auto-sold. Which house to give up is one of the few genuinely
     * interesting decisions in Monopoly, and games that make it for you are
     * exactly the badly-behaved ones this engine is trying not to be.
     */
    private fun charge(
        tx: Transaction,
        payer: PlayerId,
        creditor: PlayerId?,
        amount: Int,
        reason: MoneyReason,
    ) {
        if (amount <= 0) return
        if (tx.state.player(payer).money >= amount) {
            tx.emit(GameEvent.MoneyTransferred(payer, creditor, amount, reason))
            return
        }
        tx.emit(GameEvent.PhaseChanged(GamePhase.AwaitingDebtSettlement(payer, creditor, amount)))
    }

    /** A charge owed to the bank, which under one house rule lands on Free Parking. */
    private fun chargeToBankOrPot(
        tx: Transaction,
        payer: PlayerId,
        amount: Int,
        reason: MoneyReason,
    ) {
        val before = tx.state.phase
        charge(tx, payer, creditor = null, amount = amount, reason = reason)
        val wasPaid = tx.state.phase == before
        if (wasPaid && tx.state.rules.freeParkingJackpot) {
            tx.emit(GameEvent.FreeParkingPotChanged(tx.state.freeParkingPot + amount))
        }
    }

    private fun payFromBank(tx: Transaction, payee: PlayerId, amount: Int, reason: MoneyReason) {
        if (amount <= 0) return
        tx.emit(GameEvent.MoneyTransferred(null, payee, amount, reason))
    }

    private fun settleDebt(state: GameState, command: Command.SettleDebt): Outcome {
        val debt = state.phase as? GamePhase.AwaitingDebtSettlement
            ?: return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        if (debt.debtor != command.actor) return Outcome.Rejected(RejectionReason.NOT_YOUR_TURN)
        if (state.player(command.actor).money < debt.amount) {
            return Outcome.Rejected(RejectionReason.DEBT_STILL_UNPAYABLE)
        }

        val tx = Transaction(state)
        tx.emit(
            GameEvent.MoneyTransferred(command.actor, debt.creditor, debt.amount, MoneyReason.RENT),
        )
        if (debt.creditor == null && state.rules.freeParkingJackpot) {
            tx.emit(GameEvent.FreeParkingPotChanged(tx.state.freeParkingPot + debt.amount))
        }
        resumeAfterDebt(tx)
        return tx.accept()
    }

    /** Returns play to whoever's turn it was once a debt stops blocking the game. */
    private fun resumeAfterDebt(tx: Transaction) {
        val current = tx.state.currentPlayer
        if (current.bankrupt) {
            advanceTurn(tx)
            return
        }
        val phase = when {
            current.inJail && !tx.state.turn.hasRolled -> GamePhase.AwaitingJailDecision
            !tx.state.turn.hasRolled -> GamePhase.AwaitingRoll
            else -> GamePhase.AwaitingTurnEnd(rolledDoublesThisTurn(tx.state))
        }
        tx.emit(GameEvent.PhaseChanged(phase))
    }

    private fun declareBankruptcy(state: GameState, command: Command.DeclareBankruptcy): Outcome {
        val debt = state.phase as? GamePhase.AwaitingDebtSettlement
            ?: return Outcome.Rejected(RejectionReason.WRONG_PHASE)
        if (debt.debtor != command.actor) return Outcome.Rejected(RejectionReason.NOT_YOUR_TURN)
        // Bankruptcy is a last resort, not a way to dodge a bill you could pay.
        if (state.liquidationValue(command.actor) >= debt.amount) {
            return Outcome.Rejected(RejectionReason.CAN_STILL_PAY)
        }

        val tx = Transaction(state)
        val assets = state.deedsOf(command.actor)

        if (debt.creditor != null) {
            // Everything passes to the creditor. Buildings are sold back to the
            // bank first, because buildings never transfer between players.
            assets.forEach { deed ->
                val street = ClassicBoard.streetAt(deed.spaceIndex)
                if (street != null && deed.houses > 0) {
                    tx.emit(GameEvent.HousesChanged(deed.spaceIndex, 0))
                    payFromBank(
                        tx,
                        command.actor,
                        deed.houses * (street.buildCost / 2),
                        MoneyReason.BUILDING_SALE,
                    )
                }
                tx.emit(
                    GameEvent.DeedAssigned(
                        deed.spaceIndex, debt.creditor, houses = 0, mortgaged = deed.mortgaged,
                    ),
                )
            }
            val cash = tx.state.player(command.actor).money
            if (cash > 0) {
                tx.emit(
                    GameEvent.MoneyTransferred(
                        command.actor, debt.creditor, cash, MoneyReason.BANKRUPTCY_TRANSFER,
                    ),
                )
            }
        } else {
            // Owed to the bank: every property returns unowned, to be auctioned
            // later. The cash simply leaves the game.
            assets.forEach { deed ->
                if (deed.houses > 0) tx.emit(GameEvent.HousesChanged(deed.spaceIndex, 0))
                tx.emit(GameEvent.DeedReleased(deed.spaceIndex))
            }
        }

        tx.emit(GameEvent.PlayerBankrupted(command.actor, debt.creditor))

        if (tx.state.currentPlayer.id == command.actor) {
            advanceTurn(tx)
        } else if (!checkGameOver(tx)) {
            resumeAfterDebt(tx)
        }
        return tx.accept()
    }

    // ------------------------------------------------------------------- shared

    private fun requireTurn(state: GameState, actor: PlayerId): Outcome.Rejected? =
        if (state.currentPlayer.id == actor) null
        else Outcome.Rejected(RejectionReason.NOT_YOUR_TURN)

    private fun rolledDoublesThisTurn(state: GameState): Boolean =
        state.turn.lastRoll?.isDoubles == true && !state.currentPlayer.inJail

    private fun GameState.deck(kind: DeckKind) = when (kind) {
        DeckKind.CHANCE -> chanceDeck
        DeckKind.COMMUNITY_CHEST -> communityChestDeck
    }

    /** True while the game is blocked waiting for a specific player to act. */
    private fun GamePhase.isAwaitingInput(): Boolean = when (this) {
        is GamePhase.AwaitingPurchase,
        is GamePhase.Auction,
        is GamePhase.AwaitingDebtSettlement,
        is GamePhase.AwaitingTradeResponse,
        is GamePhase.GameOver,
        -> true

        else -> false
    }
}
