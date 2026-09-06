/*
 * Copyright (c) 2026 Auxio Project
 * StatsDashboardTest.kt is part of Auxio.
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

import androidx.lifecycle.SavedStateHandle
import io.mockk.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.oxycblt.auxio.history.HistoryInput
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class StatsDashboardTest {
    private val uid =
        requireNotNull(Music.UID.fromString("uas00000000-0000-0000-0000-000000000001"))

    @Test
    fun dstRangesAreLocalDatesAndExclusive() {
        val zone = ZoneId.of("America/Chicago")
        val day = LocalDate.of(2026, 3, 8)
        val range = TimePeriod.TODAY.range(day)
        val (start, end) = range.timestamps(zone)
        assertEquals(23 * 3600000L, end - start)
        assertTrue(range.contains(start, zone))
        assertFalse(range.contains(end, zone))
        assertEquals(
            25 * 3600000L,
            TimePeriod.TODAY.range(LocalDate.of(2026, 11, 1)).timestamps(zone).let {
                it.second - it.first
            },
        )
    }

    @Test
    fun lastWeekAcrossYearBoundaryAndInactiveDays() {
        val range = TimePeriod.LAST_WEEK.range(LocalDate.of(2026, 1, 2), Locale.US)
        assertEquals(LocalDate.of(2025, 12, 21), range.first)
        assertEquals(LocalDate.of(2025, 12, 28), range.endExclusive)
        assertEquals(7, listeningDays(range, emptyList()).toInt())
        assertEquals(
            31,
            listeningDays(TimePeriod.LAST_MONTH.range(LocalDate.of(2026, 2, 10)), emptyList())
                .toInt(),
        )
    }

    @Test
    fun allTimeAverageExtendsThroughToday() {
        val zone = ZoneId.of("UTC")
        val start = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(
            10L,
            listeningDays(
                StatsDateRange(null, null),
                listOf(start),
                LocalDate.of(2026, 1, 10),
                zone,
            ),
        )
    }

    @Test
    fun invalidEditsRejectTrailingTextOverflowAndInvalidComponents() {
        val format =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
        assertNull(HistoryInput.timestamp("2026-02-30 12:00", format))
        assertNull(HistoryInput.timestamp("2026-02-01 12:00 junk", format))
        for (raw in listOf("-1", "1:60", "1:-1", "9223372036854775807", "1:2:3:4", "0")) assertNull(
            raw,
            HistoryInput.duration(raw),
        )
        assertEquals(3723000L, HistoryInput.duration("1:02:03"))
    }

    @Test
    fun unavailableTotalsRapidFiltersAndSavedNavigationState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<StatsRepository>(relaxed = true)
            val music = mockk<MusicRepository>(relaxed = true)
            every { music.library } returns null
            val listener = slot<MusicRepository.UpdateListener>()
            every { music.addUpdateListener(capture(listener)) } just Runs
            val missingUid =
                requireNotNull(Music.UID.fromString("uas00000000-0000-0000-0000-000000000002"))
            val today = LocalDate.now()
            val timestamp = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val rows =
                MutableStateFlow(
                    StatsRecords(
                        List(7100) {
                            PlayEvent(it + 1L, if (it < 6934) uid else missingUid, timestamp, 8000)
                        },
                        listOf(SongStats(uid, 7100, 56800000, timestamp)),
                    )
                )
            every { repository.observeRecords() } returns rows
            every { repository.recordingError } returns MutableStateFlow(null)
            val saved = SavedStateHandle()
            val model = StatsViewModel(repository, music, saved, dispatcher)
            runCurrent()
            assertEquals(7100L, model.state.value.total.totalPlayCount)
            assertEquals(7100L, model.state.value.unavailable.totalPlayCount)
            assertTrue(model.state.value.songs.isEmpty())
            val library = mockk<org.oxycblt.musikr.Library>()
            val album = mockk<org.oxycblt.musikr.Album>(relaxed = true)
            val song = mockk<org.oxycblt.musikr.Song>(relaxed = true)
            every { song.uid } returns uid
            every { song.album } returns album
            every { song.artists } returns emptyList()
            every { library.findSong(uid) } returns song
            every { library.findSong(missingUid) } returns null
            every { music.library } returns library
            listener.captured.onMusicChanges(MusicRepository.Changes(true, false))
            runCurrent()
            assertEquals(7100L, model.state.value.total.totalPlayCount)
            assertEquals(6934L, model.state.value.songs.single().plays)
            assertEquals(166L, model.state.value.unavailable.totalPlayCount)
            model.selectPeriod(TimePeriod.TODAY)
            model.selectPeriod(TimePeriod.LAST_YEAR)
            model.selectRange(today, today)
            model.showMonth(java.time.YearMonth.of(2025, 2))
            model.selectCategory(RankingCategory.ARTISTS)
            model.sortByPlays(true)
            runCurrent()
            assertEquals(7100L, model.state.value.total.totalPlayCount)
            assertEquals(1L, model.state.value.days)
            assertEquals("2025-02", saved.get<String>("month"))
            assertEquals("ARTISTS", saved.get<String>("category"))
            assertEquals(true, saved.get<Boolean>("byPlays"))
            rows.value = StatsRecords(emptyList(), emptyList())
            runCurrent()
            assertEquals(0L, model.state.value.total.totalPlayCount)
            assertFalse(model.state.value.loading)
            // Clear the scope containing the midnight watcher.
            val store = androidx.lifecycle.ViewModelStore()
            store.put("stats", model)
            store.clear()
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
