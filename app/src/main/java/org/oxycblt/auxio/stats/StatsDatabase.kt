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
import androidx.room.TypeConverters
import org.oxycblt.musikr.Music

/**
 * Provides raw access to the database storing music listening statistics.
 *
 * @author Auxio Project
 */
@Database(entities = [SongStats::class], version = 1, exportSchema = false)
@TypeConverters(Music.UID.TypeConverters::class)
abstract class StatsDatabase : RoomDatabase() {
    /**
     * Get the current [StatsDao].
     *
     * @return A [StatsDao] providing control of the database's stats tables.
     */
    abstract fun statsDao(): StatsDao
}

/**
 * Provides control of the persisted stats table.
 *
 * @author Auxio Project
 */
@Dao
interface StatsDao {
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
     * Insert or update song stats.
     *
     * @param stats The [SongStats] to insert or update.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOrUpdateStats(stats: SongStats)

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
    val lastPlayedTimestamp: Long
)
