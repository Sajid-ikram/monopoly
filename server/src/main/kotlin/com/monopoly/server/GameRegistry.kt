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
 * Everything here is in-process: restarting the server loses every game in
 * flight. That is a deliberate first step, not an oversight — the event log is
 * already the right shape to persist, so adding durability later is a matter of
 * writing it somewhere rather than restructuring anything.
 */
class GameRegistry(
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val games = HashMap<String, GameSession>()

    suspend fun create(
        hostName: String,
        hostToken: Token,
        rules: GameRules,
    ): HostedGame = mutex.withLock {
        val code = allocateCodeLocked()
        val playerId = PlayerId(newId())
        val session = GameSession(
            code = code,
            initialState = GameFactory.newLobby(
                gameId = code,
                host = Seat(playerId, hostName, hostToken),
                rules = rules,
                // The seed is the only entropy in a game. Taking it from a
                // cryptographic source stops anyone predicting the dice from
                // having watched an earlier game.
                seed = random.nextLong(),
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

    suspend fun remove(code: String): GameSession? = mutex.withLock {
        games.remove(code.uppercase())
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
