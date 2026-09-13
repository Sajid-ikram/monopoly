package com.monopoly.server

import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.Seat
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.Base64

/** A newly created game, and the host's credentials for it. */
data class HostedGame(
    val session: GameSession,
    val playerId: PlayerId,
    val resumeToken: String,
)

/**
 * All games currently in memory, addressed by their short code.
 *
 * Games are held here and written through to [store] as they happen, so a
 * server restart interrupts them rather than ending them: [restore] reads them
 * back and everyone's resume token still works. With [GameStore.None] the
 * behaviour is the old one — everything lives and dies with the process.
 */
class GameRegistry(
    private val store: GameStore = GameStore.None,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val games = HashMap<String, GameSession>()

    /**
     * Reads back every game that was in progress when the server last stopped.
     *
     * @return how many games came back.
     */
    suspend fun restore(): Int = mutex.withLock {
        val restored = store.restoreAll()
        restored.forEach { game ->
            games[game.code] = GameSession(
                code = game.code,
                initialState = game.state,
                initialSequence = game.sequence,
                initialSeats = game.seats,
                journal = game.journal,
                now = now,
            )
        }
        restored.size
    }

    suspend fun create(
        hostName: String,
        hostToken: Token,
        rules: GameRules,
    ): HostedGame = mutex.withLock {
        val code = allocateCodeLocked()
        val playerId = PlayerId(newId())
        // The seed is the only entropy in a game. Taking it from a
        // cryptographic source stops anyone predicting the dice from having
        // watched an earlier game.
        val seed = random.nextLong()
        val session = GameSession(
            code = code,
            initialState = GameFactory.newLobby(
                gameId = code,
                host = Seat(playerId, hostName, hostToken),
                rules = rules,
                seed = seed,
            ),
            journal = store.open(
                GameRecord.Opened(
                    code = code,
                    hostId = playerId,
                    hostName = hostName,
                    hostToken = hostToken,
                    rules = rules,
                    seed = seed,
                ),
            ),
            now = now,
        )
        games[code] = session

        val resumeToken = newToken()
        session.enrol(playerId, resumeToken)
        HostedGame(session, playerId, resumeToken)
    }

    suspend fun find(code: String): GameSession? = mutex.withLock {
        games[code.uppercase()]
    }

    /** Takes a game out of play for good, on disk as well as in memory. */
    suspend fun remove(code: String): GameSession? = mutex.withLock {
        val removed = games.remove(code.uppercase())
        removed?.closeJournal()
        store.discard(code)
        removed
    }

    suspend fun activeGames(): List<GameSession> = mutex.withLock { games.values.toList() }

    /** A fresh player id. Opaque, and never reused across games. */
    fun newId(): String = randomBase64(ID_BYTES)

    /**
     * A resume token: the bearer credential for one seat.
     *
     * Long and cryptographically random because possessing one *is* being that
     * player. A guessable token would let a stranger take over a seat mid-game.
     */
    fun newToken(): String = randomBase64(TOKEN_BYTES)

    /** Must be called with [mutex] held. */
    private fun allocateCodeLocked(): String {
        repeat(CODE_ATTEMPTS) {
            val code = randomCode()
            if (code !in games) return code
        }
        // Astronomically unlikely with a live game count in the thousands, but
        // failing loudly beats handing two groups the same code.
        error("Could not allocate a free game code")
    }

    private fun randomCode(): String =
        (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    private fun randomBase64(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private companion object {
        /**
         * No vowels, so a random code cannot spell something unfortunate, and
         * none of O/0/I/1, which people mistype when reading a code aloud.
         */
        const val CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXYZ23456789"
        const val CODE_LENGTH = 4
        const val CODE_ATTEMPTS = 200
        const val ID_BYTES = 9
        const val TOKEN_BYTES = 24
    }
}
