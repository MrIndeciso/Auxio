/*
 * Copyright (c) 2026 Auxio Project
 * StatsViewModel.kt is part of Auxio.
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

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music

@HiltViewModel
class StatsViewModel
internal constructor(
    private val repository: StatsRepository,
    private val music: MusicRepository,
    private val saved: SavedStateHandle,
    private val computeDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        repository: StatsRepository,
        music: MusicRepository,
        saved: SavedStateHandle,
    ) : this(repository, music, saved, Dispatchers.Default)

    val period = saved.getStateFlow("period", TimePeriod.ALL_TIME.name)
    val category = saved.getStateFlow("category", RankingCategory.SONGS.name)
    val byPlays = saved.getStateFlow("byPlays", false)
    val month = saved.getStateFlow("month", YearMonth.now().toString())
    private val custom = saved.getStateFlow("custom", "")
    private val refresh = MutableStateFlow(0)
    val recordingError = repository.recordingError
    var scroll: Parcelable?
        get() = saved["scroll"]
        set(value) {
            saved["scroll"] = value
        }

    var rankingScroll: Parcelable?
        get() = saved["rankingScroll"]
        set(value) {
            saved["rankingScroll"] = value
        }

    val state = MutableStateFlow(StatsUiState())

    val libraryChanges =
        callbackFlow {
                val listener =
                    object : MusicRepository.UpdateListener {
                        override fun onMusicChanges(changes: MusicRepository.Changes) {
                            trySend(Unit)
                        }
                    }
                music.addUpdateListener(listener)
                trySend(Unit)
                awaitClose { music.removeUpdateListener(listener) }
            }
            .conflate()

    init {
        viewModelScope.launch {
            // Reevaluate local-date presets at midnight or after a timezone change.
            var day = LocalDate.now() to ZoneId.systemDefault()
            while (true) {
                delay(1000)
                val next = LocalDate.now() to ZoneId.systemDefault()
                if (next != day) {
                    day = next
                    retry()
                }
            }
        }
        viewModelScope.launch {
            combine(period, custom, refresh) { p, c, _ ->
                    if (p == TimePeriod.CUSTOM.name && c.isNotEmpty()) {
                        val dates = c.split('/')
                        StatsDateRange(LocalDate.parse(dates[0]), LocalDate.parse(dates[1]))
                    } else TimePeriod.valueOf(p).range()
                }
                .collectLatest { range ->
                    state.value = state.value.copy(loading = true, error = null)
                    try {
                        combine(repository.observeRecords(), libraryChanges) { records, _ ->
                                val events = records.events
                                val aggregates = records.aggregates
                                val selected = events.filter { range.contains(it.timestamp) }
                                val library = music.library
                                val mapped =
                                    selected.mapNotNull { event ->
                                        library?.findSong(event.songUid)?.let { it to event }
                                    }
                                val resolved =
                                    mapped
                                        .groupBy { it.first.uid }
                                        .values
                                        .map { matches ->
                                            SongStatsInfo(
                                                matches.first().first,
                                                matches.size.toLong(),
                                                matches.sumOf { it.second.listenTimeMs },
                                                matches.maxOf { it.second.timestamp },
                                            )
                                        }
                                val known = mapped.map { it.second }
                                val rankedSongs =
                                    resolved.map {
                                        RankingEntry(it.song, it.playCount, it.totalListenTimeMs)
                                    }
                                val albums =
                                    resolved
                                        .groupBy { it.song.album }
                                        .map { (album, songs) ->
                                            RankingEntry(
                                                album,
                                                songs.sumOf { it.playCount },
                                                songs.sumOf { it.totalListenTimeMs },
                                            )
                                        }
                                val artists =
                                    resolved
                                        .flatMap { info -> info.song.artists.map { it to info } }
                                        .groupBy({ it.first }, { it.second })
                                        .map { (artist, songs) ->
                                            RankingEntry(
                                                artist,
                                                songs.sumOf { it.playCount },
                                                songs.sumOf { it.totalListenTimeMs },
                                            )
                                        }
                                val recorded = events.groupBy { it.songUid }
                                val stored = aggregates.associateBy { it.songUid }
                                val inconsistent =
                                    (recorded.keys + stored.keys).count { uid ->
                                        val rows = recorded[uid].orEmpty()
                                        val old = stored[uid]
                                        (old?.playCount ?: 0) != rows.size.toLong() ||
                                            (old?.totalListenTimeMs ?: 0) !=
                                                rows.sumOf { it.listenTimeMs } ||
                                            (old?.lastPlayedTimestamp ?: 0) !=
                                                (rows.maxOfOrNull { it.timestamp } ?: 0)
                                    }
                                StatsUiState(
                                    false,
                                    range = range,
                                    total =
                                        OverallStats(
                                            selected.size.toLong(),
                                            selected.sumOf { it.listenTimeMs },
                                        ),
                                    unavailable =
                                        OverallStats(
                                            (selected.size - known.size).toLong(),
                                            selected.sumOf { it.listenTimeMs } -
                                                known.sumOf { it.listenTimeMs },
                                        ),
                                    daily =
                                        selected
                                            .groupBy {
                                                Instant.ofEpochMilli(it.timestamp)
                                                    .atZone(ZoneId.systemDefault())
                                                    .toLocalDate()
                                            }
                                            .mapValues { (_, plays) ->
                                                plays.sumOf { it.listenTimeMs }
                                            },
                                    songs = rankedSongs,
                                    albums = albums,
                                    artists = artists,
                                    inconsistentSongs = inconsistent,
                                    days = listeningDays(range, selected.map { it.timestamp }),
                                )
                            }
                            .flowOn(computeDispatcher)
                            .collect { state.value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        state.value =
                            state.value.copy(
                                loading = false,
                                error = e.message ?: "Statistics unavailable",
                            )
                    }
                }
        }
    }

    fun selectPeriod(value: TimePeriod) {
        saved["period"] = value.name
    }

    fun selectRange(first: LocalDate, last: LocalDate) {
        require(!last.isBefore(first))
        saved["custom"] = "$first/${last.plusDays(1)}"
        selectPeriod(TimePeriod.CUSTOM)
    }

    fun selectCategory(value: RankingCategory) {
        saved["category"] = value.name
    }

    fun sortByPlays(value: Boolean) {
        saved["byPlays"] = value
    }

    fun showMonth(value: YearMonth) {
        saved["month"] = value.toString()
    }

    fun retry() {
        refresh.value++
    }
}

enum class RankingCategory {
    SONGS,
    ALBUMS,
    ARTISTS,
}

data class RankingEntry(val music: Music, val plays: Long, val duration: Long)

data class StatsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val range: StatsDateRange = StatsDateRange(null, null),
    val total: OverallStats = OverallStats(0, 0),
    val unavailable: OverallStats = OverallStats(0, 0),
    val daily: Map<LocalDate, Long> = emptyMap(),
    val songs: List<RankingEntry> = emptyList(),
    val albums: List<RankingEntry> = emptyList(),
    val artists: List<RankingEntry> = emptyList(),
    val inconsistentSongs: Int = 0,
    val days: Long = 1,
) {
    fun ranking(category: RankingCategory, byPlays: Boolean) =
        when (category) {
            RankingCategory.SONGS -> songs
            RankingCategory.ALBUMS -> albums
            RankingCategory.ARTISTS -> artists
        }.sortedWith(
            compareByDescending<RankingEntry> { if (byPlays) it.plays else it.duration }
                .thenByDescending { if (byPlays) it.duration else it.plays }
                .thenBy { it.music.uid.toString() }
        )
}

fun listeningDays(
    range: StatsDateRange,
    timestamps: List<Long>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    val first =
        range.first
            ?: timestamps.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: today
    val last =
        range.endExclusive
            ?: maxOf(
                today.plusDays(1),
                timestamps.maxOrNull()?.let {
                    Instant.ofEpochMilli(it).atZone(zone).toLocalDate().plusDays(1)
                } ?: today.plusDays(1),
            )
    return ChronoUnit.DAYS.between(first, last).coerceAtLeast(1)
}
