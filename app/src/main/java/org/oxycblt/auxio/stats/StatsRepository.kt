/*
 * Copyright (c) 2026 Auxio Project
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/** All event mutations share one writer and Room transactions; failures propagate to the caller. */
interface StatsRepository {
    val recordingError: StateFlow<String?>

    fun reportRecordingFailure()

    fun observeRecords(): Flow<StatsRecords>

    fun observeHistory(filter: HistoryFilter, limit: Int): Flow<List<PlayEvent>>

    suspend fun checkpoint(snapshot: SessionSnapshot)

    suspend fun recover(uid: Music.UID): ListeningSession?

    suspend fun closeUnfinished()

    suspend fun updatePlayEvent(id: Long, songUid: Music.UID, timestamp: Long, listenTimeMs: Long)

    suspend fun deletePlayEvent(id: Long, songUid: Music.UID)
}

class StatsRepositoryImpl
@Inject
constructor(private val statsDao: StatsDao, private val musicRepository: MusicRepository) :
    StatsRepository {
    private val writer = Mutex()
    override val recordingError = MutableStateFlow<String?>(null)

    override fun reportRecordingFailure() {
        recordingError.value = "Listening progress could not be saved. Retrying…"
    }

    override fun observeRecords() =
        combine(statsDao.observeEvents(), statsDao.observeAggregates()) { _, _ ->
            statsDao.records()
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeHistory(filter: HistoryFilter, limit: Int): Flow<List<PlayEvent>> {
        if (!filter.unavailable)
            return statsDao.observeHistory(
                filter.start,
                filter.end,
                filter.songUid,
                false,
                "",
                limit,
            )
        // Use library aliases exactly as the dashboard does. One delimited binding avoids
        // SQLite's variable limit for large libraries. Music UIDs cannot contain commas.
        return statsDao
            .observeEvents()
            .map { events ->
                events
                    .map { it.songUid }
                    .distinct()
                    .filter { musicRepository.library?.findSong(it) != null }
                    .joinToString(",", prefix = ",", postfix = ",")
            }
            .distinctUntilChanged()
            .flatMapLatest { available ->
                statsDao.observeHistory(
                    filter.start,
                    filter.end,
                    filter.songUid,
                    true,
                    available,
                    limit,
                )
            }
    }

    override suspend fun checkpoint(snapshot: SessionSnapshot) =
        writer.withLock {
            try {
                statsDao.checkpoint(snapshot)
                recordingError.value = null
            } catch (e: Exception) {
                reportRecordingFailure()
                throw e
            }
        }

    override suspend fun recover(uid: Music.UID): ListeningSession? =
        writer.withLock { statsDao.unfinished()?.takeIf { it.songUid == uid } }

    override suspend fun closeUnfinished() = writer.withLock { statsDao.closeUnfinished() }

    override suspend fun updatePlayEvent(
        id: Long,
        songUid: Music.UID,
        timestamp: Long,
        listenTimeMs: Long,
    ) = writer.withLock { statsDao.edit(id, timestamp, listenTimeMs) }

    override suspend fun deletePlayEvent(id: Long, songUid: Music.UID) =
        writer.withLock { statsDao.delete(id) }
}

data class SongStatsInfo(
    val song: Song,
    val playCount: Long,
    val totalListenTimeMs: Long,
    val lastPlayedTimestamp: Long,
)

data class OverallStats(val totalPlayCount: Long, val totalListenTimeMs: Long)

data class HistoryFilter(
    val start: Long = Long.MIN_VALUE,
    val end: Long = Long.MAX_VALUE,
    val songUid: Music.UID? = null,
    val unavailable: Boolean = false,
)
