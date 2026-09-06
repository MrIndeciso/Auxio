/*
 * Copyright (c) 2026 Auxio Project
 * ListeningSessionRecorderTest.kt is part of Auxio.
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

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.oxycblt.musikr.Music

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningSessionRecorderTest {
    private val uid =
        requireNotNull(Music.UID.fromString("uas00000000-0000-0000-0000-000000000001"))

    private fun sample(time: Long, position: Long = time) =
        PlaybackObservation(time, time + 42, position, true)

    @Test
    fun failureRetriesBeforeTransitionAndShutdownWithoutDuplicatingAttempts() = runTest {
        val repository = mockk<StatsRepository>(relaxed = true)
        val writes = mutableListOf<SessionSnapshot>()
        var failed = false
        coEvery { repository.checkpoint(any()) } coAnswers
            {
                val snapshot = firstArg<SessionSnapshot>()
                if (snapshot.listenedMs == 8000L && !failed) {
                    failed = true
                    throw IllegalStateException("disk full")
                }
                writes.add(snapshot)
            }
        val recorder = ListeningSessionRecorder(repository, backgroundScope)
        recorder.select(uid, false, sample(0))
        recorder.observe(sample(8000))
        recorder.suspendSession()
        // A replacement service restores immediately, before the failed writer catches up.
        recorder.select(uid, true, sample(100000, 8000))
        recorder.observe(sample(105000, 13000))
        recorder.finish()
        runCurrent()
        verify { repository.reportRecordingFailure() }
        assertFalse(writes.any { it.listenedMs >= 8000 })
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(13000L, writes.last().listenedMs)
        assertTrue(writes.last().closed)
        assertEquals(1, writes.map { it.attemptId }.distinct().size)
    }

    @Test
    fun explicitRestorationRecoversJournalFreshSelectionDoesNot() = runTest {
        val repository = mockk<StatsRepository>(relaxed = true)
        val writes = mutableListOf<SessionSnapshot>()
        coEvery { repository.recover(uid) } returns
            ListeningSession("recovered", uid, 1, 5000, null, "OPEN", false)
        coEvery { repository.checkpoint(any()) } coAnswers { writes.add(firstArg()) }
        val recorder = ListeningSessionRecorder(repository, backgroundScope)
        recorder.select(uid, true, sample(100000, 9000))
        recorder.observe(sample(103000, 12000))
        recorder.select(uid, false, sample(104000, 0))
        recorder.observe(sample(112000, 8000))
        runCurrent()
        assertEquals(2, writes.map { it.attemptId }.distinct().size)
        assertEquals(8000L, writes.last { it.attemptId == "recovered" }.listenedMs)
        assertNotEquals("recovered", writes.last().attemptId)
    }
}
