/*
 * Copyright (c) 2024 Auxio Project
 * StatsRepository.kt is part of Auxio.
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

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Manages music listening statistics in a structured manner.
 *
 * @author Auxio Project
 */
interface StatsRepository {
    /**
     * Record that a song was played.
     *
     * @param song The song that was played.
     * @param listenTimeMs The amount of time the song was listened to, in milliseconds.
     */
    suspend fun recordPlay(song: Song, listenTimeMs: Long)

    /**
     * Get statistics for all songs within a time period.
     *
     * @param timePeriod The time period to filter by.
     * @return A list of [SongStatsInfo] for all songs with recorded stats in the time period.
     */
    suspend fun getAllSongStats(timePeriod: TimePeriod = TimePeriod.ALL_TIME): List<SongStatsInfo>

    /**
     * Get aggregated statistics for albums within a time period.
     *
     * @param timePeriod The time period to filter by.
     * @return A list of [AlbumStatsInfo] for all albums with recorded stats in the time period.
     */
    suspend fun getAlbumStats(
        timePeriod: TimePeriod = TimePeriod.ALL_TIME,
        songStats: List<SongStatsInfo>? = null
    ): List<AlbumStatsInfo>

    /**
     * Get aggregated statistics for artists within a time period.
     *
     * @param timePeriod The time period to filter by.
     * @return A list of [ArtistStatsInfo] for all artists with recorded stats in the time period.
     */
    suspend fun getArtistStats(
        timePeriod: TimePeriod = TimePeriod.ALL_TIME,
        songStats: List<SongStatsInfo>? = null
    ): List<ArtistStatsInfo>

    /**
     * Get overall listening statistics within a time period.
     *
     * @param timePeriod The time period to filter by.
     * @return [OverallStats] containing total play count and listen time for the time period.
     */
    suspend fun getOverallStats(
        timePeriod: TimePeriod = TimePeriod.ALL_TIME,
        songStats: List<SongStatsInfo>? = null
    ): OverallStats

    /**
     * Get daily listening statistics within a time period.
     *
     * @param timePeriod The time period to filter by.
     * @return A list of [DailyStatsInfo] for each day in the time period.
     */
    suspend fun getDailyStats(timePeriod: TimePeriod = TimePeriod.ALL_TIME): List<DailyStatsInfo>

    /**
     * Get all play events, sorted by timestamp.
     *
     * @return A list of all [PlayEvent]s.
     */
    suspend fun getSongHistory(): List<PlayEvent>
}

class StatsRepositoryImpl
@Inject
constructor(private val statsDao: StatsDao, private val musicRepository: MusicRepository) :
    StatsRepository {

    override suspend fun recordPlay(song: Song, listenTimeMs: Long) {
        try {
            val timestamp = System.currentTimeMillis()

            // Record the individual play event
            val playEvent = PlayEvent(songUid = song.uid, timestamp = timestamp, listenTimeMs = listenTimeMs)
            statsDao.insertPlayEvent(playEvent)

            // Update aggregated stats for backwards compatibility and faster all-time queries
            val existingStats = statsDao.getSongStats(song.uid)
            val newStats =
                if (existingStats != null) {
                    existingStats.copy(
                        playCount = existingStats.playCount + 1,
                        totalListenTimeMs = existingStats.totalListenTimeMs + listenTimeMs,
                        lastPlayedTimestamp = timestamp)
                } else {
                    SongStats(
                        songUid = song.uid,
                        playCount = 1,
                        totalListenTimeMs = listenTimeMs,
                        lastPlayedTimestamp = timestamp)
                }
            statsDao.insertOrUpdateStats(newStats)
            L.d("Recorded play for ${song.name}: $listenTimeMs ms")
        } catch (e: Exception) {
            L.e("Failed to record play for song ${song.uid}")
            L.e(e.stackTraceToString())
        }
    }

    override suspend fun getAllSongStats(timePeriod: TimePeriod): List<SongStatsInfo> {
        try {
            val library = musicRepository.library ?: return emptyList()

            // For all-time, prefer aggregated stats but fall back to events if none are available
            if (timePeriod == TimePeriod.ALL_TIME) {
                val allStats = statsDao.getAllStatsByListenTime()
                val mapped =
                    allStats.mapNotNull { stats ->
                        val song = library.findSong(stats.songUid)
                        song?.let {
                            SongStatsInfo(
                                song = it,
                                playCount = stats.playCount,
                                totalListenTimeMs = stats.totalListenTimeMs,
                                lastPlayedTimestamp = stats.lastPlayedTimestamp)
                        }
                    }
                if (mapped.isNotEmpty()) {
                    return mapped
                }
            }

            // For specific time periods or as a fallback, use play events
            val (startTime, endTime) = timePeriod.getTimeRange()
            val playEvents =
                statsDao.getAllPlayEvents(startTime, endTime.takeIf { timePeriod != TimePeriod.ALL_TIME } ?: Long.MAX_VALUE)

            // Group by song and aggregate
            val songStatsMap = mutableMapOf<Music.UID, SongStatsAccumulator>()
            for (event in playEvents) {
                val song = library.findSong(event.songUid) ?: continue
                val accumulator =
                    songStatsMap.getOrPut(event.songUid) {
                        SongStatsAccumulator(song, 0, 0, event.timestamp)
                    }

                songStatsMap[event.songUid] =
                    accumulator.copy(
                        playCount = accumulator.playCount + 1,
                        totalListenTimeMs = accumulator.totalListenTimeMs + event.listenTimeMs,
                        lastPlayedTimestamp = maxOf(accumulator.lastPlayedTimestamp, event.timestamp))
            }

            return songStatsMap.values
                .map { acc ->
                    SongStatsInfo(
                        song = acc.song,
                        playCount = acc.playCount,
                        totalListenTimeMs = acc.totalListenTimeMs,
                        lastPlayedTimestamp = acc.lastPlayedTimestamp)
                }
                .sortedByDescending { it.totalListenTimeMs }
        } catch (e: Exception) {
            L.e("Failed to get song stats")
            L.e(e.stackTraceToString())
            return emptyList()
        }
    }

    override suspend fun getAlbumStats(
        timePeriod: TimePeriod,
        songStats: List<SongStatsInfo>?
    ): List<AlbumStatsInfo> {
        try {
            val songStatsForPeriod = songStats ?: getAllSongStats(timePeriod)

            val albumStatsMap = mutableMapOf<Music.UID, AlbumStatsAccumulator>()

            for (stats in songStatsForPeriod) {
                val albumUid = stats.song.album.uid

                val accumulator =
                    albumStatsMap.getOrPut(albumUid) {
                        AlbumStatsAccumulator(stats.song.album, 0, 0)
                    }

                albumStatsMap[albumUid] =
                    accumulator.copy(
                        totalPlayCount = accumulator.totalPlayCount + stats.playCount,
                        totalListenTimeMs = accumulator.totalListenTimeMs + stats.totalListenTimeMs)
            }

            return albumStatsMap.values
                .map { acc ->
                    AlbumStatsInfo(
                        album = acc.album,
                        totalPlayCount = acc.totalPlayCount,
                        totalListenTimeMs = acc.totalListenTimeMs)
                }
                .sortedByDescending { it.totalListenTimeMs }
        } catch (e: Exception) {
            L.e("Failed to get album stats")
            L.e(e.stackTraceToString())
            return emptyList()
        }
    }

    override suspend fun getArtistStats(
        timePeriod: TimePeriod,
        songStats: List<SongStatsInfo>?
    ): List<ArtistStatsInfo> {
        try {
            val songStatsForPeriod = songStats ?: getAllSongStats(timePeriod)

            val artistStatsMap = mutableMapOf<Music.UID, ArtistStatsAccumulator>()

            for (stats in songStatsForPeriod) {
                for (artist in stats.song.artists) {
                    val accumulator =
                        artistStatsMap.getOrPut(artist.uid) {
                            ArtistStatsAccumulator(artist, 0, 0)
                        }

                    artistStatsMap[artist.uid] =
                        accumulator.copy(
                            totalPlayCount = accumulator.totalPlayCount + stats.playCount,
                            totalListenTimeMs =
                                accumulator.totalListenTimeMs + stats.totalListenTimeMs)
                }
            }

            return artistStatsMap.values
                .map { acc ->
                    ArtistStatsInfo(
                        artist = acc.artist,
                        totalPlayCount = acc.totalPlayCount,
                        totalListenTimeMs = acc.totalListenTimeMs)
                }
                .sortedByDescending { it.totalListenTimeMs }
        } catch (e: Exception) {
            L.e("Failed to get artist stats")
            L.e(e.stackTraceToString())
            return emptyList()
        }
    }

    override suspend fun getOverallStats(
        timePeriod: TimePeriod,
        songStats: List<SongStatsInfo>?
    ): OverallStats {
        try {
            // For all-time, use the aggregated stats for better performance unless we already have
            // pre-fetched song stats.
            if (timePeriod == TimePeriod.ALL_TIME && songStats == null) {
                val totalListenTime = statsDao.getTotalListenTime() ?: 0L
                val totalPlayCount = statsDao.getTotalPlayCount() ?: 0L
                return OverallStats(
                    totalPlayCount = totalPlayCount, totalListenTimeMs = totalListenTime)
            }

            // For specific time periods, aggregate from play events
            val stats = songStats ?: getAllSongStats(timePeriod)
            val totalPlayCount = stats.sumOf { it.playCount }
            val totalListenTime = stats.sumOf { it.totalListenTimeMs }
            return OverallStats(
                totalPlayCount = totalPlayCount, totalListenTimeMs = totalListenTime)
        } catch (e: Exception) {
            L.e("Failed to get overall stats")
            L.e(e.stackTraceToString())
            return OverallStats(0, 0)
        }
    }

    override suspend fun getDailyStats(timePeriod: TimePeriod): List<DailyStatsInfo> {
        try {
            val zone = ZoneId.systemDefault()
            val (startTime, endTime) = timePeriod.getTimeRange()
            val playEvents = statsDao.getAllPlayEvents(startTime, endTime)

            return playEvents
                .filter { it.timestamp > 0 }
                .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                .map { (date, events) ->
                    DailyStatsInfo(
                        date = date, totalListenTimeMs = events.sumOf { it.listenTimeMs })
                }
                .sortedBy { it.date }
        } catch (e: Exception) {
            L.e("Failed to get daily stats")
            L.e(e.stackTraceToString())
            return emptyList()
        }
    }

    override suspend fun getSongHistory(): List<PlayEvent> {
        return try {
            statsDao.getAllPlayEvents()
        } catch (e: Exception) {
            L.e("Failed to get song history")
            L.e(e.stackTraceToString())
            emptyList()
        }
    }

    private data class SongStatsAccumulator(
        val song: Song,
        val playCount: Long,
        val totalListenTimeMs: Long,
        val lastPlayedTimestamp: Long
    )

    private data class AlbumStatsAccumulator(
        val album: Album,
        val totalPlayCount: Long,
        val totalListenTimeMs: Long
    )

    private data class ArtistStatsAccumulator(
        val artist: Artist,
        val totalPlayCount: Long,
        val totalListenTimeMs: Long
    )
}

/**
 * Information about a song's listening statistics.
 *
 * @param song The song.
 * @param playCount The number of times this song has been played.
 * @param totalListenTimeMs The total time spent listening to this song in milliseconds.
 * @param lastPlayedTimestamp The timestamp when this song was last played.
 */
data class SongStatsInfo(
    val song: Song,
    val playCount: Long,
    val totalListenTimeMs: Long,
    val lastPlayedTimestamp: Long
)

/**
 * Information about an album's aggregated listening statistics.
 *
 * @param album The album.
 * @param totalPlayCount The total number of plays across all songs in the album.
 * @param totalListenTimeMs The total time spent listening to songs in the album in milliseconds.
 */
data class AlbumStatsInfo(
    val album: Album,
    val totalPlayCount: Long,
    val totalListenTimeMs: Long
)

/**
 * Information about an artist's aggregated listening statistics.
 *
 * @param artist The artist.
 * @param totalPlayCount The total number of plays across all songs by the artist.
 * @param totalListenTimeMs The total time spent listening to songs by the artist in milliseconds.
 */
data class ArtistStatsInfo(
    val artist: Artist,
    val totalPlayCount: Long,
    val totalListenTimeMs: Long
)

/**
 * Overall listening statistics across all music.
 *
 * @param totalPlayCount The total number of plays across all songs.
 * @param totalListenTimeMs The total time spent listening to music in milliseconds.
 */
data class OverallStats(val totalPlayCount: Long, val totalListenTimeMs: Long)

/**
 * Information about daily listening statistics.
 *
 * @param date The calendar date represented.
 * @param totalListenTimeMs The total time spent listening to music on that day in milliseconds.
 */
data class DailyStatsInfo(
    val date: LocalDate,
    val totalListenTimeMs: Long
)
