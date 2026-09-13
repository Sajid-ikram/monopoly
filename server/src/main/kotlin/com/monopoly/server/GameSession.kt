package com.monopoly.server

import com.monopoly.core.engine.Command
import com.monopoly.core.engine.GameEngine
import com.monopoly.core.engine.GameEvent
import com.monopoly.core.engine.Outcome
import com.monopoly.core.engine.RejectionReason
import com.monopoly.core.engine.applyAll
import com.monopoly.core.model.GamePhase
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.protocol.SequencedEvent
import com.monopoly.protocol.ServerMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One game, and the only place its state is allowed to change.
 *
 * Every mutation runs under [mutex], so commands from several players are
 * serialised into one total order. That order is what the sequence numbers
 * describe, and every connected client ends up applying the identical sequence.
 *
 * The session is a referee, not a second implementation of Monopoly: it decides
 * *whether* a command is allowed to run and *when*, and hands the question of
 * what it does to [GameEngine].
 */
class GameSession(
    val code: String,
    initialState: GameState,
    initialSequence: Long = 0,
    initialSeats: Map<PlayerId, String> = emptyMap(),
    private val journal: GameJournal = GameJournal.None,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private var state: GameState = initialState
    private var sequence: Long = initialSequence

    /** The full event history, which is what lets a client replay a gap. */
    private val log = ArrayList<SequencedEvent>()

    /**
     * Replies already sent, keyed by the client's command id.
     *
     * This is what makes retrying safe. A client whose reply was lost resends
     * the same command id; we return the original answer rather than running
     * the command a second time. Without it, a flaky connection could pay rent
     * twice.
     */
    private val handledCommands = object : LinkedHashMap<String, ServerMessage>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ServerMessage>) =
            size > MAX_REMEMBERED_COMMANDS
    }

    private val members = LinkedHashMap<PlayerId, Member>()

    /** Wall-clock time the last connection dropped, for the idle sweeper. */
    private var emptySince: Long? = now()

    /** A seat, which outlives any particular connection to it. */
    private class Member(
        val resumeToken: String,
        var connection: ClientChannel? = null,
        /**
         * When this seat was last left empty, or null while somebody is in it.
         *
         * This is what the turn timer measures. It belongs to the seat rather
         * than the game because each player's absence is their own: two people
         * dropping a minute apart are not equally overdue.
         */
        var awaySince: Long? = null,
    )

    init {
        initialSeats.forEach { (player, token) ->
            members[player] = Member(token, awaySince = now())
        }
    }

    // ------------------------------------------------------------------ reading

    suspend fun snapshot(): ServerMessage.Snapshot = mutex.withLock {
        ServerMessage.Snapshot(state, sequence)
    }

    suspend fun currentState(): GameState = mutex.withLock { state }

    suspend fun sequenceNumber(): Long = mutex.withLock { sequence }

    suspend fun isFinished(): Boolean = mutex.withLock { state.phase is GamePhase.GameOver }

    suspend fun hasSeatFor(playerId: PlayerId): Boolean = mutex.withLock { playerId in members }

    /**
     * True when nobody has been connected for [idleMillis].
     *
     * A game is kept alive well past the last disconnection on purpose: the
     * whole point of resume tokens is that everyone's phone dying at once is
     * recoverable.
     */
    suspend fun isAbandoned(idleMillis: Long): Boolean = mutex.withLock {
        val since = emptySince ?: return@withLock false
        now() - since > idleMillis
    }

    // ------------------------------------------------------------------ joining

    /** Registers a seat that the engine has already accepted, issuing its token. */
    suspend fun enrol(playerId: PlayerId, resumeToken: String) = mutex.withLock {
        members[playerId] = Member(resumeToken, awaySince = now())
        journal.append(GameRecord.SeatTaken(playerId, resumeToken))
    }

    /** Looks up the seat a resume token belongs to. */
    suspend fun seatFor(resumeToken: String): PlayerId? = mutex.withLock {
        members.entries.firstOrNull { it.value.resumeToken == resumeToken }?.key
    }

    /**
     * Attaches a socket to a seat, replacing any existing one.
     *
     * Replacing rather than refusing is deliberate. A phone that lost signal
     * leaves a socket the server has not yet noticed is dead; if the returning
     * player were refused because "that seat is connected", they would be
     * locked out of their own game until a TCP timeout expired.
     */
    suspend fun attach(playerId: PlayerId, channel: ClientChannel): Boolean = mutex.withLock {
        val member = members[playerId] ?: return@withLock false
        member.connection?.close()
        member.connection = channel
        member.awaySince = null
        emptySince = null
        true
    }

    /** Detaches a socket, but only if it is still the current one for that seat. */
    suspend fun detach(playerId: PlayerId, channel: ClientChannel) = mutex.withLock {
        val member = members[playerId] ?: return@withLock
        // A late close from a socket that has already been replaced must not
        // knock the player's new connection offline.
        if (member.connection !== channel) return@withLock
        member.connection = null
        member.awaySince = now()
        channel.close()
        if (members.values.none { it.connection != null }) emptySince = now()
    }

    suspend fun forget(playerId: PlayerId) = mutex.withLock {
        members.remove(playerId)?.connection?.close()
        journal.append(GameRecord.SeatReleased(playerId))
        if (members.values.none { it.connection != null }) emptySince = now()
    }

    /** Closes the file this game is written to. Called when it is torn down. */
    fun closeJournal() {
        journal.close()
    }

    // ----------------------------------------------------------------- commands

    /**
     * Runs a command and, if it was accepted, appends and broadcasts its events.
     *
     * The whole operation happens under the lock, so two players acting at the
     * same moment cannot interleave: one of them simply runs second and may
     * find their command no longer legal, which is the correct outcome.
     */
    suspend fun submit(
        actor: PlayerId,
        commandId: String,
        command: Command,
    ): ServerMessage = mutex.withLock {
        handledCommands[commandId]?.let { return@withLock it }

        // A client may only ever act as itself. The actor travels inside the
        // command for the engine's benefit, but it is never taken on trust:
        // this socket's identity is the one that counts.
        if (command.actor != actor) {
            return@withLock ServerMessage.CommandRejected(
                commandId = commandId,
                reason = RejectionReason.UNKNOWN_PLAYER,
                detail = "A command may only be issued for yourself",
                currentSequence = sequence,
            ).also { handledCommands[commandId] = it }
        }

        when (val outcome = GameEngine.reduce(state, command)) {
            is Outcome.Rejected -> ServerMessage.CommandRejected(
                commandId = commandId,
                reason = outcome.reason,
                detail = outcome.detail,
                currentSequence = sequence,
            ).also { handledCommands[commandId] = it }

            is Outcome.Accepted -> {
                val appended = appendLocked(outcome.events)
                state = outcome.state
                ServerMessage.CommandAccepted(
                    commandId = commandId,
                    firstSequence = appended.first().sequence,
                    lastSequence = appended.last().sequence,
                ).also {
                    handledCommands[commandId] = it
                    broadcastLocked(ServerMessage.Events(appended.first().sequence, appended))
                }
            }
        }
    }

    /**
     * Applies events the server itself originated, such as a player's connection
     * dropping. These bypass [GameEngine] because they are not player actions,
     * but they are sequenced and broadcast exactly like everything else so no
     * client has to learn about them by a second route.
     */
    suspend fun emitServerEvents(events: List<GameEvent>) = mutex.withLock {
        if (events.isEmpty()) return@withLock
        val appended = appendLocked(events)
        state = state.applyAll(events)
        broadcastLocked(ServerMessage.Events(appended.first().sequence, appended))
    }

    // ------------------------------------------------------------ absent player

    /**
     * Plays one move for whoever the game is waiting on, if they are not there.
     *
     * A seat that is empty when its turn comes around would otherwise stop the
     * game for everybody, with nothing anyone can do about it — the failure
     * this whole design is meant to rule out, arriving by a different door.
     *
     * Only a *disconnected* player is ever played for. Somebody who is looking
     * at the board and thinking is not holding the game up in a way software
     * should fix, and taking their turn from under them would be worse than
     * waiting. That makes the rule easy to state: the game only moves on when
     * there is nobody there to move it.
     *
     * One move per call, so an absent player's turn plays out at a readable
     * pace rather than resolving in a single jump nobody can follow.
     *
     * @return true when a move was played.
     */
    suspend fun playForAbsentPlayer(graceMillis: Long): Boolean = mutex.withLock {
        val waiting = waitingOn(state) ?: return@withLock false
        val member = members[waiting] ?: return@withLock false
        if (member.connection != null) return@withLock false

        val since = member.awaySince ?: return@withLock false
        if (now() - since < graceMillis) return@withLock false

        val command = standInMove(state, waiting) ?: return@withLock false
        val outcome = GameEngine.reduce(state, command)
        if (outcome !is Outcome.Accepted) return@withLock false

        val appended = appendLocked(outcome.events)
        state = outcome.state
        broadcastLocked(ServerMessage.Events(appended.first().sequence, appended))
        true
    }

    /** Whose input the game is currently blocked on, if anyone's. */
    private fun waitingOn(state: GameState): PlayerId? = when (val phase = state.phase) {
        is GamePhase.Lobby, is GamePhase.GameOver -> null

        is GamePhase.AwaitingRoll,
        is GamePhase.AwaitingJailDecision,
        is GamePhase.AwaitingPurchase,
        is GamePhase.AwaitingTurnEnd,
        -> state.players.getOrNull(state.currentPlayerIndex)?.id

        is GamePhase.Auction -> phase.currentBidder
        is GamePhase.AwaitingDebtSettlement -> phase.debtor
        is GamePhase.AwaitingTradeResponse -> phase.offer.to
    }

    /**
     * The move to make on an absent player's behalf.
     *
     * Every one of these is the choice that costs them least: it keeps their
     * money, gives away nothing, and commits them to nothing they might have
     * refused. Declining a property rather than buying it, folding rather than
     * bidding, refusing a trade rather than accepting one — an absent player
     * should come back to a game that moved on without them, not to one that
     * spent their money on their behalf.
     */
    private fun standInMove(state: GameState, player: PlayerId): Command? =
        when (val phase = state.phase) {
            is GamePhase.Lobby, is GamePhase.GameOver -> null

            // Rolling is compulsory and has no downside; there is no version of
            // this turn where they would rather not have rolled.
            is GamePhase.AwaitingRoll, is GamePhase.AwaitingJailDecision ->
                Command.RollDice(player)

            is GamePhase.AwaitingPurchase -> Command.DeclineProperty(player)
            is GamePhase.Auction -> Command.WithdrawFromAuction(player)
            is GamePhase.AwaitingTurnEnd -> Command.EndTurn(player)
            is GamePhase.AwaitingTradeResponse -> Command.RejectTrade(player)
            is GamePhase.AwaitingDebtSettlement -> raiseOrFold(state, phase, player)
        }

    /**
     * A debt is the one place standing in cannot be free.
     *
     * The game cannot continue until it is settled, so somebody has to choose
     * what to give up. The order is the same one a player would use: cash
     * first, then buildings, then mortgages, and bankruptcy only once there is
     * genuinely nothing left — which is also the only point at which the engine
     * will accept it.
     */
    private fun raiseOrFold(
        state: GameState,
        phase: GamePhase.AwaitingDebtSettlement,
        player: PlayerId,
    ): Command? {
        val debtor = state.playerOrNull(player) ?: return null
        if (debtor.money >= phase.amount) return Command.SettleDebt(player)

        val deeds = state.deedsOf(player)
        // The most-developed street first: selling down from the top is the
        // only order that keeps a group evenly built at every step.
        deeds.filter { it.houses > 0 }.maxByOrNull { it.houses }?.let {
            return Command.SellHouse(player, it.spaceIndex)
        }
        deeds.firstOrNull { !it.mortgaged }?.let {
            return Command.MortgageProperty(player, it.spaceIndex)
        }
        return Command.DeclareBankruptcy(player)
    }

    // --------------------------------------------------------------- recovering

    /**
     * Works out how to bring a client at [lastSequence] up to date.
     *
     * Replaying a short gap is far cheaper than resending a whole game, but
     * past a point the snapshot wins — and a client that is behind by more than
     * we can explain gets one unconditionally rather than being trusted.
     */
    suspend fun catchUp(lastSequence: Long): ServerMessage = mutex.withLock {
        if (lastSequence == sequence) {
            return@withLock ServerMessage.Events(sequence + 1, emptyList())
        }
        val behind = sequence - lastSequence
        if (lastSequence < 0 || lastSequence > sequence || behind > SNAPSHOT_THRESHOLD) {
            return@withLock ServerMessage.Snapshot(state, sequence)
        }
        val missing = log.filter { it.sequence > lastSequence }
        // The log is trimmed to a bounded size, so a gap we cannot fill from it
        // falls back to a snapshot rather than sending an incomplete run.
        if (missing.size.toLong() != behind) {
            return@withLock ServerMessage.Snapshot(state, sequence)
        }
        ServerMessage.Events(lastSequence + 1, missing)
    }

    // ------------------------------------------------------------------ sending

    suspend fun broadcast(message: ServerMessage) = mutex.withLock {
        broadcastLocked(message)
    }

    suspend fun sendTo(playerId: PlayerId, message: ServerMessage) = mutex.withLock {
        members[playerId]?.connection?.offer(message)
    }

    suspend fun closeAll() = mutex.withLock {
        members.values.forEach { it.connection?.close() }
    }

    // ------------------------------------------------------------------ private

    /** Must be called with [mutex] held. */
    private fun appendLocked(events: List<GameEvent>): List<SequencedEvent> {
        val appended = events.map { event -> SequencedEvent(++sequence, event) }
        // Written before it is broadcast, so there is no window in which a
        // player has seen something the server would forget on a restart.
        appended.forEach { journal.append(GameRecord.Happened(it)) }
        log += appended
        // Keep memory bounded on a long game. Anyone who falls behind the
        // retained window is served a snapshot instead, so nothing is lost.
        if (log.size > MAX_RETAINED_EVENTS) {
            log.subList(0, log.size - MAX_RETAINED_EVENTS).clear()
        }
        return appended
    }

    /** Must be called with [mutex] held. */
    private fun broadcastLocked(message: ServerMessage) {
        members.values.forEach { it.connection?.offer(message) }
    }

    private companion object {
        /**
         * Past this many missed events, a snapshot is smaller than the replay.
         * Roughly a dozen turns' worth.
         */
        const val SNAPSHOT_THRESHOLD = 250L

        /** Enough history to cover any realistic disconnection. */
        const val MAX_RETAINED_EVENTS = 5_000

        /**
         * Command ids remembered for deduplication. Far more than a client
         * could have in flight, so a legitimate retry is always recognised.
         */
        const val MAX_REMEMBERED_COMMANDS = 512
    }
}
