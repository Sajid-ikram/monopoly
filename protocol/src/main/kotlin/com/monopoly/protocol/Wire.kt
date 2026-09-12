package com.monopoly.protocol

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration both ends use.
 *
 * Encoding is centralised here so the client and server cannot drift into
 * subtly different settings — a mismatch in, say, default handling would show
 * up as a rare desync rather than an obvious error.
 */
val MonopolyJson: Json = Json {
    // Old clients must survive fields added by a newer server. Without this,
    // shipping any new field would hard-break every installed build.
    ignoreUnknownKeys = true

    // Defaults are omitted from the wire. Game states are mostly defaults, and
    // these messages go over mobile connections on every single turn.
    encodeDefaults = false

    // Sealed hierarchies are written with an explicit discriminator so the
    // message type is readable in a packet capture when debugging a live game.
    classDiscriminator = "type"
}

fun ClientMessage.encode(): String = MonopolyJson.encodeToString(this)

fun ServerMessage.encode(): String = MonopolyJson.encodeToString(this)

fun decodeClientMessage(text: String): ClientMessage = MonopolyJson.decodeFromString(text)

fun decodeServerMessage(text: String): ServerMessage = MonopolyJson.decodeFromString(text)
