package com.monopoly.server

import com.monopoly.core.engine.GameFactory
import com.monopoly.core.engine.Seat
import com.monopoly.core.engine.applyAll
import com.monopoly.core.model.GameState
import com.monopoly.core.model.PlayerId
import com.monopoly.core.model.Token
import com.monopoly.core.rules.GameRules
import com.monopoly.protocol.MonopolyJson
import com.monopoly.protocol.SequencedEvent
import kotlinx.serialization.Serializable
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.extension

/**
 * One line of a game's history on disk.
 *
 * Everything needed to rebuild a game, and nothing that can be derived. The
 * events are the game; the rest is what the engine does not model — which seat
 * belongs to which secret, and the seed the whole thing grew from.
 */
@Serializable
sealed interface GameRecord {

    /** Always the first line. What [GameFactory] needs to make the opening state. */
    @Serializable
    data class Opened(
        val code: String,
        val hostId: PlayerId,
        val hostName: String,
        val hostToken: Token,
        val rules: GameRules,
        val seed: Long,
    ) : GameRecord

    /**
     * A seat and the token that reclaims it.
     *
     * Written here rather than derived from events because a resume token is a
     * server-side secret: it deliberately never appears in the event stream
     * that every client receives.
     */
    @Serializable
    data class SeatTaken(val player: PlayerId, val resumeToken: String) : GameRecord

    /** A seat given up in the lobby. */
    @Serializable
    data class SeatReleased(val player: PlayerId) : GameRecord

    /** One sequenced event, exactly as it was broadcast. */
    @Serializable
    data class Happened(val entry: SequencedEvent) : GameRecord
}

/** A game rebuilt from disk, ready to be put back in the registry. */
data class RestoredGame(
    val code: String,
    val state: GameState,
    val sequence: Long,
    val seats: Map<PlayerId, String>,
    val journal: GameJournal,
)

/**
 * Where a game's history is appended as it happens.
 *
 * Appending rather than snapshotting, because the event log is already the
 * authoritative account of the game — writing a state instead would be storing
 * a derived thing and keeping the derivation to ourselves.
 */
interface GameJournal {
    fun append(record: GameRecord)
    fun close()

    /** For tests and for running without a data directory. */
    object None : GameJournal {
        override fun append(record: GameRecord) = Unit
        override fun close() = Unit
    }
}

/** Somewhere games are kept between runs of the server. */
interface GameStore {

    /** Starts a journal for a new game. */
    fun open(opened: GameRecord.Opened): GameJournal

    /** Every game found on disk, rebuilt. */
    fun restoreAll(): List<RestoredGame>

    /** Forgets a game for good, once it is finished or abandoned. */
    fun discard(code: String)

    /** Keeps nothing. A server with this store loses its games on restart. */
    object None : GameStore {
        override fun open(opened: GameRecord.Opened): GameJournal = GameJournal.None
        override fun restoreAll(): List<RestoredGame> = emptyList()
        override fun discard(code: String) = Unit
    }
}

/**
 * Games as JSON Lines, one file per game.
 *
 * A line per event is not the most compact format available, but it is the one
 * you can read with `tail` while a game is going wrong, and a partial write at
 * the end of a crashed process costs exactly one line rather than the file.
 */
class FileGameStore(private val directory: Path) : GameStore {

    init {
        Files.createDirectories(directory)
    }

    override fun open(opened: GameRecord.Opened): GameJournal {
        val journal = FileGameJournal(fileFor(opened.code))
        journal.append(opened)
        return journal
    }

    override fun restoreAll(): List<RestoredGame> {
        val files = Files.list(directory).use { stream ->
            stream.filter { it.extension == EXTENSION }.toList()
        }
        return files.mapNotNull { file -> restore(file) }
    }

    override fun discard(code: String) {
        Files.deleteIfExists(fileFor(code))
    }

    private fun fileFor(code: String): Path = directory.resolve("${code.uppercase()}.$EXTENSION")

    /**
     * Rebuilds one game, or gives up on it.
     *
     * A file we cannot read is skipped rather than thrown, because the
     * alternative is one corrupt game stopping the server from starting and
     * taking every other game down with it.
     */
    private fun restore(file: Path): RestoredGame? = try {
        val records = Files.readAllLines(file)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    MonopolyJson.decodeFromString<GameRecord>(line)
                } catch (malformed: Exception) {
                    // A half-written final line is the expected casualty of a
                    // process dying mid-append. Everything before it is intact.
                    null
                }
            }

        val opened = records.firstOrNull() as? GameRecord.Opened
            ?: error("${file.fileName} does not begin with an Opened record")

        var state = GameFactory.newLobby(
            gameId = opened.code,
            host = Seat(opened.hostId, opened.hostName, opened.hostToken),
            rules = opened.rules,
            seed = opened.seed,
        )
        val seats = LinkedHashMap<PlayerId, String>()
        var sequence = 0L

        records.forEach { record ->
            when (record) {
                is GameRecord.Opened -> Unit
                is GameRecord.SeatTaken -> seats[record.player] = record.resumeToken
                is GameRecord.SeatReleased -> seats.remove(record.player)
                is GameRecord.Happened -> {
                    state = state.applyAll(listOf(record.entry.event))
                    sequence = record.entry.sequence
                }
            }
        }

        // Nobody is connected to a game the server has just rebuilt, whatever
        // the log said when it was written.
        state = state.copy(players = state.players.map { it.copy(connected = false) })

        RestoredGame(
            code = opened.code,
            state = state,
            sequence = sequence,
            seats = seats,
            journal = FileGameJournal(file),
        )
    } catch (unreadable: Exception) {
        null
    }

    private companion object {
        const val EXTENSION = "jsonl"
    }
}

/** Appends to one game's file, flushing as it goes. */
private class FileGameJournal(path: Path) : GameJournal {

    private val writer: BufferedWriter = Files.newBufferedWriter(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND,
    )

    @Synchronized
    override fun append(record: GameRecord) {
        // Flushed on every record rather than buffered. A game is a handful of
        // short lines per turn, so the cost is nothing measurable, and the
        // alternative is losing the last few moves of every game in progress
        // to exactly the crash this exists to survive.
        writer.write(MonopolyJson.encodeToString<GameRecord>(record))
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    override fun close() {
        try {
            writer.close()
        } catch (ignored: Exception) {
            // Closing a file we are done with is not worth failing a game over.
        }
    }
}
