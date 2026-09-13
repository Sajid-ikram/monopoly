package com.monopoly.android.net

import android.content.Context
import com.monopoly.core.model.PlayerId

/**
 * A seat this device holds, and the token that can reclaim it.
 *
 * The token is the reason a seat outlives a connection. Without it a player who
 * closed the app — or whose battery died — would come back as a new player with
 * an empty wallet while their money and property sat in a seat nobody could
 * reach.
 */
data class SavedSeat(
    val gameCode: String,
    val playerId: PlayerId,
    val resumeToken: String,
    val displayName: String,
)

/**
 * What survives the app being closed.
 *
 * Deliberately small: the server holds the game, so there is nothing here worth
 * syncing or migrating. Only the things this device cannot be told again — the
 * seat it holds, and where the server is.
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("monopoly.session", Context.MODE_PRIVATE)

    /** Where the server is. Editable, because there is no hosted one yet. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    var displayName: String
        get() = prefs.getString(KEY_NAME, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    /**
     * The seat to offer to rejoin.
     *
     * One at a time: you can only sit in one chair, and offering a list of old
     * games to pick from would be a menu of mostly-dead codes.
     */
    fun lastSeat(): SavedSeat? {
        val code = prefs.getString(KEY_CODE, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val player = prefs.getString(KEY_PLAYER, null) ?: return null
        return SavedSeat(code, PlayerId(player), token, prefs.getString(KEY_NAME, null).orEmpty())
    }

    fun remember(seat: SavedSeat) {
        prefs.edit()
            .putString(KEY_CODE, seat.gameCode)
            .putString(KEY_TOKEN, seat.resumeToken)
            .putString(KEY_PLAYER, seat.playerId.value)
            .putString(KEY_NAME, seat.displayName)
            .apply()
    }

    /** Forgets a seat that has been left deliberately, or that the server denies. */
    fun forget(gameCode: String) {
        if (prefs.getString(KEY_CODE, null) != gameCode) return
        prefs.edit()
            .remove(KEY_CODE)
            .remove(KEY_TOKEN)
            .remove(KEY_PLAYER)
            .apply()
    }

    private companion object {
        const val KEY_SERVER = "serverUrl"
        const val KEY_NAME = "displayName"
        const val KEY_CODE = "gameCode"
        const val KEY_TOKEN = "resumeToken"
        const val KEY_PLAYER = "playerId"
    }
}

/**
 * The emulator's name for the machine it is running on.
 *
 * A default that works the moment you press run, rather than one that requires
 * reading a doc first. On a real phone this has to be changed to the host's LAN
 * address or a deployed server, which is what the field on the home screen is
 * for.
 */
const val DEFAULT_SERVER_URL: String = "ws://10.0.2.2:8080/play"
