/*
 * Copyright (c) 2024 Auxio Project
 * StatsDatabase.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.stats

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.migrateMusicUids

/**
 * Provides raw access to the database storing music listening statistics.
 *
 * @author Auxio Project
 */
@Database(
    entities = [SongStats::class, PlayEvent::class, ListeningSession::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Music.UID.TypeConverters::class)
abstract class StatsDatabase : RoomDatabase() {
    /**
     * Get the current [StatsDao].
     *
     * @return A [StatsDao] providing control of the database's stats tables.
     */
    abstract fun statsDao(): StatsDao

    companion object {
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS ListeningSession (attemptId TEXT NOT NULL PRIMARY KEY, songUid TEXT NOT NULL, startedAt INTEGER, committedMs INTEGER NOT NULL, eventId INTEGER, state TEXT NOT NULL, suppressed INTEGER NOT NULL)"
                    )
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migrateMusicUids(db, "SongStats", "songUid")
                    migrateMusicUids(db, "PlayEvent", "songUid")
                }
            }
    }
}

/**
 * Provides control of the persisted stats table.
 *
 * @author Auxio Project
 */
@Dao
interface StatsDao {
    @Query("SELECT * FROM PlayEvent ORDER BY timestamp DESC, id DESC")
    fun observeEvents(): Flow<List<PlayEvent>>

    @Query(
        "SELECT * FROM PlayEvent WHERE timestamp >= :start AND (:end = 9223372036854775807 OR timestamp < :end) AND (:songUid IS NULL OR songUid = :songUid) AND (:unavailable = 0 OR instr(:available, ',' || songUid || ',') = 0) ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    fun observeHistory(
        start: Long,
        end: Long,
        songUid: Music.UID?,
        unavailable: Boolean,
        available: String,
        limit: Int,
    ): Flow<List<PlayEvent>>

    @Transaction
    suspend fun records(): StatsRecords =
        StatsRecords(getAllPlayEvents(), getAllStatsByListenTime())

    @Query("SELECT DISTINCT songUid FROM PlayEvent") suspend fun recordedUids(): List<Music.UID>

    @Query("SELECT * FROM SongStats") fun observeAggregates(): Flow<List<SongStats>>

    @Query("SELECT * FROM PlayEvent WHERE id = :id") suspend fun event(id: Long): PlayEvent?

    @Query("SELECT * FROM ListeningSession WHERE attemptId = :id")
    suspend fun session(id: String): ListeningSession?

    @Query("SELECT * FROM ListeningSession WHERE state = 'OPEN' ORDER BY rowid DESC LIMIT 1")
    suspend fun unfinished(): ListeningSession?

    @Query("UPDATE ListeningSession SET state = 'CLOSED' WHERE state = 'OPEN'")
    suspend fun closeUnfinished()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSession(session: ListeningSession)

    @Query("UPDATE ListeningSession SET suppressed = 1 WHERE eventId = :id")
    suspend fun suppress(id: Long)

    // Apply only the requested delta. Existing historical inconsistencies are not repaired.
    suspend fun adjust(uid: Music.UID, count: Long, duration: Long) {
        val old = getSongStats(uid)
        val latest = getAllPlayEventsForSong(uid).maxOfOrNull { it.timestamp } ?: 0L
        insertOrUpdateStats(
            SongStats(
                uid,
                (old?.playCount ?: 0) + count,
                (old?.totalListenTimeMs ?: 0) + duration,
                latest,
            )
        )
    }

    @Transaction
    suspend fun checkpoint(snapshot: SessionSnapshot) {
        val old = session(snapshot.attemptId)
        if (old?.state == "CLOSED") return
        val duration = maxOf(old?.committedMs ?: 0, snapshot.listenedMs)
        var eventId = old?.eventId
        val suppressed = old?.suppressed ?: false
        if (!suppressed && duration >= ListeningSessionMachine.QUALIFY_MS) {
            if (eventId == null) {
                eventId =
                    insertPlayEvent(
                        PlayEvent(
                            songUid = snapshot.songUid,
                            timestamp = requireNotNull(snapshot.startedAt),
                            listenTimeMs = duration,
                        )
                    )
                adjust(snapshot.songUid, 1, duration)
            } else {
                val event = event(eventId)
                if (event != null) {
                    val delta = duration - old!!.committedMs
                    updatePlayEvent(event.id, event.timestamp, event.listenTimeMs + delta)
                    adjust(snapshot.songUid, 0, delta)
                }
            }
        }
        putSession(
            ListeningSession(
                snapshot.attemptId,
                snapshot.songUid,
                snapshot.startedAt,
                duration,
                eventId,
                if (snapshot.closed) "CLOSED" else "OPEN",
                suppressed,
            )
        )
    }

    @Transaction
    suspend fun edit(id: Long, timestamp: Long, duration: Long) {
        require(duration > 0 && timestamp >= 0)
        val old = requireNotNull(event(id)) { "History entry no longer exists" }
        updatePlayEvent(id, timestamp, duration)
        adjust(old.songUid, 0, duration - old.listenTimeMs)
    }

    @Transaction
    suspend fun delete(id: Long) {
        val old = event(id) ?: return
        suppress(id)
        deletePlayEvent(id)
        adjust(old.songUid, -1, -old.listenTimeMs)
    }

    /**
     * Get stats for a specific song.
     *
     * @param songUid The UID of the song.
     * @return The [SongStats] for the song, or null if no stats exist.
     */
    @Query("SELECT * FROM SongStats WHERE songUid = :songUid")
    suspend fun getSongStats(songUid: Music.UID): SongStats?

    /**
     * Get all song stats ordered by play count.
     *
     * @return List of all [SongStats] ordered by play count descending.
     */
    @Query("SELECT * FROM SongStats ORDER BY playCount DESC")
    suspend fun getAllStatsByPlayCount(): List<SongStats>

    /**
     * Get all song stats ordered by total listen time.
     *
     * @return List of all [SongStats] ordered by total listen time descending.
     */
    @Query("SELECT * FROM SongStats ORDER BY totalListenTimeMs DESC")
    suspend fun getAllStatsByListenTime(): List<SongStats>

    /**
     * Get the total listen time across all songs.
     *
     * @return The total listen time in milliseconds.
     */
    @Query("SELECT SUM(totalListenTimeMs) FROM SongStats") suspend fun getTotalListenTime(): Long?

    /**
     * Get the total play count across all songs.
     *
     * @return The total number of plays.
     */
    @Query("SELECT SUM(playCount) FROM SongStats") suspend fun getTotalPlayCount(): Long?

    /**
     * Get play events for a song within a time range.
     *
     * @param songUid The UID of the song.
     * @param startTimestamp Start of the time range (inclusive).
     * @param endTimestamp End of the time range (inclusive).
     * @return List of [PlayEvent]s for the song within the time range.
     */
    @Query(
        "SELECT * FROM PlayEvent WHERE songUid = :songUid AND timestamp >= :startTimestamp AND timestamp <= :endTimestamp ORDER BY timestamp DESC"
    )
    suspend fun getPlayEvents(
        songUid: Music.UID,
        startTimestamp: Long,
        endTimestamp: Long,
    ): List<PlayEvent>

    /**
     * Get all play events within a time range.
     *
     * @param startTimestamp Start of the time range (inclusive).
     * @param endTimestamp End of the time range (inclusive).
     * @return List of all [PlayEvent]s within the time range.
     */
    @Query(
        "SELECT * FROM PlayEvent WHERE timestamp >= :startTimestamp AND timestamp <= :endTimestamp ORDER BY timestamp DESC"
    )
    suspend fun getAllPlayEvents(startTimestamp: Long, endTimestamp: Long): List<PlayEvent>

    /**
     * Get all play events.
     *
     * @return List of all [PlayEvent]s.
     */
    @Query("SELECT * FROM PlayEvent ORDER BY timestamp DESC")
    suspend fun getAllPlayEvents(): List<PlayEvent>

    /**
     * Get all play events for a song.
     *
     * @param songUid The UID of the song.
     * @return List of [PlayEvent]s for the song.
     */
    @Query("SELECT * FROM PlayEvent WHERE songUid = :songUid ORDER BY timestamp DESC")
    suspend fun getAllPlayEventsForSong(songUid: Music.UID): List<PlayEvent>

    /**
     * Insert a play event.
     *
     * @param event The [PlayEvent] to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlayEvent(event: PlayEvent): Long

    /**
     * Insert or update song stats.
     *
     * @param stats The [SongStats] to insert or update.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateStats(stats: SongStats)

    /**
     * Delete stats for a song.
     *
     * @param songUid The UID of the song.
     */
    @Query("DELETE FROM SongStats WHERE songUid = :songUid")
    suspend fun deleteSongStats(songUid: Music.UID)

    /**
     * Update a play event's timestamp and duration.
     *
     * @param id The event ID to update.
     * @param timestamp The new timestamp.
     * @param listenTimeMs The new listen duration.
     */
    @Query(
        "UPDATE PlayEvent SET timestamp = :timestamp, listenTimeMs = :listenTimeMs WHERE id = :id"
    )
    suspend fun updatePlayEvent(id: Long, timestamp: Long, listenTimeMs: Long)

    /**
     * Delete a play event.
     *
     * @param id The event ID to delete.
     */
    @Query("DELETE FROM PlayEvent WHERE id = :id") suspend fun deletePlayEvent(id: Long)

    /** Delete all stats. */
    @Query("DELETE FROM SongStats") suspend fun nukeStats()
}

/**
 * Represents listening statistics for a song.
 *
 * @param songUid The unique identifier of the song.
 * @param playCount The number of times this song has been played.
 * @param totalListenTimeMs The total time spent listening to this song in milliseconds.
 * @param lastPlayedTimestamp The timestamp when this song was last played.
 */
@Entity
data class SongStats(
    @PrimaryKey val songUid: Music.UID,
    val playCount: Long,
    val totalListenTimeMs: Long,
    val lastPlayedTimestamp: Long,
)

/**
 * Represents a single play event for a song.
 *
 * @param id Auto-generated unique ID for the play event.
 * @param songUid The unique identifier of the song that was played.
 * @param timestamp The timestamp when the song was played.
 * @param listenTimeMs The duration the song was listened to in milliseconds.
 */
@Entity
data class PlayEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songUid: Music.UID,
    val timestamp: Long,
    val listenTimeMs: Long,
)

/** Durable progress only; event suppression survives edits, retries and restoration. */
@Entity
data class ListeningSession(
    @PrimaryKey val attemptId: String,
    val songUid: Music.UID,
    val startedAt: Long?,
    val committedMs: Long,
    val eventId: Long?,
    val state: String,
    val suppressed: Boolean,
)

data class StatsRecords(val events: List<PlayEvent>, val aggregates: List<SongStats>)
