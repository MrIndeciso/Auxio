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
     * Get statistics for all songs.
     *
     * @return A list of [SongStatsInfo] for all songs with recorded stats.
     */
    suspend fun getAllSongStats(): List<SongStatsInfo>

    /**
     * Get aggregated statistics for albums.
     *
     * @return A list of [AlbumStatsInfo] for all albums with recorded stats.
     */
    suspend fun getAlbumStats(): List<AlbumStatsInfo>

    /**
     * Get aggregated statistics for artists.
     *
     * @return A list of [ArtistStatsInfo] for all artists with recorded stats.
     */
    suspend fun getArtistStats(): List<ArtistStatsInfo>

    /**
     * Get overall listening statistics.
     *
     * @return [OverallStats] containing total play count and listen time.
     */
    suspend fun getOverallStats(): OverallStats
}

class StatsRepositoryImpl
@Inject
constructor(private val statsDao: StatsDao, private val musicRepository: MusicRepository) :
    StatsRepository {

    override suspend fun recordPlay(song: Song, listenTimeMs: Long) {
        try {
            val existingStats = statsDao.getSongStats(song.uid)
            val newStats =
                if (existingStats != null) {
                    existingStats.copy(
                        playCount = existingStats.playCount + 1,
                        totalListenTimeMs = existingStats.totalListenTimeMs + listenTimeMs,
                        lastPlayedTimestamp = System.currentTimeMillis())
                } else {
                    SongStats(
                        songUid = song.uid,
                        playCount = 1,
                        totalListenTimeMs = listenTimeMs,
                        lastPlayedTimestamp = System.currentTimeMillis())
                }
            statsDao.insertOrUpdateStats(newStats)
            L.d("Recorded play for ${song.name}: $listenTimeMs ms")
        } catch (e: Exception) {
            L.e("Failed to record play for song ${song.uid}")
            L.e(e.stackTraceToString())
        }
    }

    override suspend fun getAllSongStats(): List<SongStatsInfo> {
        try {
            val library = musicRepository.library ?: return emptyList()
            val allStats = statsDao.getAllStatsByPlayCount()

            return allStats.mapNotNull { stats ->
                val song = library.findSong(stats.songUid)
                song?.let {
                    SongStatsInfo(
                        song = it,
                        playCount = stats.playCount,
                        totalListenTimeMs = stats.totalListenTimeMs,
                        lastPlayedTimestamp = stats.lastPlayedTimestamp)
                }
            }
        } catch (e: Exception) {
            L.e("Failed to get song stats")
            L.e(e.stackTraceToString())
            return emptyList()
        }
    }

    override suspend fun getAlbumStats(): List<AlbumStatsInfo> {
        try {
            val library = musicRepository.library ?: return emptyList()
            val allStats = statsDao.getAllStatsByPlayCount()

            val albumStatsMap = mutableMapOf<Music.UID, AlbumStatsAccumulator>()

            for (stats in allStats) {
                val song = library.findSong(stats.songUid) ?: continue
                val albumUid = song.album.uid

                val accumulator =
                    albumStatsMap.getOrPut(albumUid) {
                        AlbumStatsAccumulator(song.album, 0, 0)
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
                .sortedByDescending { it.totalPlayCount }
        } catch (e: Exception) {
            L.e("Failed to get album stats")
            L.e(e.stackTraceToString())
            return emptyList()
        }
    }

    override suspend fun getArtistStats(): List<ArtistStatsInfo> {
        try {
            val library = musicRepository.library ?: return emptyList()
            val allStats = statsDao.getAllStatsByPlayCount()

            val artistStatsMap = mutableMapOf<Music.UID, ArtistStatsAccumulator>()

            for (stats in allStats) {
                val song = library.findSong(stats.songUid) ?: continue

                for (artist in song.artists) {
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
                .sortedByDescending { it.totalPlayCount }
        } catch (e: Exception) {
            L.e("Failed to get artist stats")
            L.e(e.stackTraceToString())
            return emptyList()
        }
    }

    override suspend fun getOverallStats(): OverallStats {
        try {
            val totalListenTime = statsDao.getTotalListenTime() ?: 0L
            val totalPlayCount = statsDao.getTotalPlayCount() ?: 0L
            return OverallStats(
                totalPlayCount = totalPlayCount, totalListenTimeMs = totalListenTime)
        } catch (e: Exception) {
            L.e("Failed to get overall stats")
            L.e(e.stackTraceToString())
            return OverallStats(0, 0)
        }
    }

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
