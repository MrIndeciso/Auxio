/*
 * Copyright (c) 2026 Auxio Project
 * ListeningSessionMachineTest.kt is part of Auxio.
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

import org.junit.Assert.*
import org.junit.Test
import org.oxycblt.musikr.Music

class ListeningSessionMachineTest {
    private val uid =
        requireNotNull(Music.UID.fromString("uas00000000-0000-0000-0000-000000000001"))
    private val emitted = mutableListOf<SessionSnapshot>()
    private val machine = ListeningSessionMachine { emitted.add(it) }

    private fun sample(
        time: Long,
        position: Long = time,
        playing: Boolean = true,
        speed: Float = 1f,
    ) = PlaybackObservation(time, 100000 + time, position, playing, speed)

    private fun start() {
        machine.select(uid)
        machine.observe(sample(0))
    }

    @Test
    fun exactThresholdAndCheckpoints() {
        start()
        machine.observe(sample(4999))
        assertEquals(1, emitted.size)
        machine.observe(sample(5000))
        assertEquals(5000L, emitted.last().listenedMs)
        machine.observe(sample(7999))
        assertFalse(emitted.any { it.listenedMs >= 8000 })
        machine.observe(sample(8000))
        assertEquals(8000L, emitted.last().listenedMs)
        machine.observe(sample(13000))
        assertEquals(13000L, emitted.last().listenedMs)
        assertEquals(100000L, emitted.last().startedAt)
        assertEquals(1, emitted.map { it.attemptId }.distinct().size)
    }

    @Test
    fun pauseResumeRetainsAttemptAndExcludesAbsentTime() {
        start()
        val id = machine.snapshot!!.attemptId
        machine.observe(sample(4000, playing = false))
        machine.observe(sample(100000, 4000, false))
        machine.observe(sample(200000, 4000))
        machine.observe(sample(204000, 8000))
        assertEquals(8000L, machine.snapshot!!.listenedMs)
        assertEquals(id, machine.snapshot!!.attemptId)
    }

    @Test
    fun bufferingAndFocusSuppressionDoNotAdvance() {
        start()
        machine.observe(sample(3000, playing = false))
        machine.observe(sample(13000, 3000, false))
        machine.observe(sample(14000, 3000))
        machine.observe(sample(19000, 8000))
        assertEquals(8000L, machine.snapshot!!.listenedMs)
    }

    @Test
    fun forwardAndBackwardSeeksAndPreviousRewindRetainAttempt() {
        start()
        machine.observe(sample(3000))
        machine.rebase(sample(3000, 120000))
        machine.observe(sample(6000, 123000))
        machine.rebase(sample(6000, 0))
        machine.observe(sample(8000, 2000))
        assertEquals(8000L, machine.snapshot!!.listenedMs)
        assertEquals(1, emitted.map { it.attemptId }.distinct().size)
    }

    @Test
    fun rapidSkipsRemainBelowThreshold() {
        repeat(20) {
            start()
            machine.observe(sample(7000))
            machine.finish()
        }
        assertFalse(emitted.any { it.listenedMs >= 8000 })
        assertEquals(20, emitted.map { it.attemptId }.distinct().size)
    }

    @Test
    fun consecutiveSameSongsAndNaturalRepeatsAreDistinct() {
        repeat(3) {
            start()
            machine.observe(sample(8000))
            machine.finish()
        }
        assertEquals(
            3,
            emitted
                .filter { it.closed && it.listenedMs == 8000L }
                .map { it.attemptId }
                .distinct()
                .size,
        )
    }

    @Test
    fun duplicateObservationsAndFinishAreIdempotent() {
        start()
        repeat(5) { machine.observe(sample(8000)) }
        machine.finish()
        machine.finish()
        assertEquals(1, emitted.count { it.closed })
        assertEquals(8000L, emitted.last().listenedMs)
    }

    @Test
    fun recoverOnlyCommittedProgressAndKeepOriginalStart() {
        val journal = ListeningSession("stable", uid, 42, 5000, null, "OPEN", false)
        machine.select(uid, journal)
        machine.observe(sample(1000000, 9000))
        machine.observe(sample(1003000, 12000))
        assertEquals(8000L, machine.snapshot!!.listenedMs)
        assertEquals(42L, machine.snapshot!!.startedAt)
        assertEquals("stable", machine.snapshot!!.attemptId)
    }

    @Test
    fun stalledPositionAndPlaybackSpeedBoundAudibleTime() {
        start()
        machine.observe(sample(10000, 0))
        assertEquals(0L, machine.snapshot!!.listenedMs)
        machine.rebase(sample(10000, 0, speed = 2f))
        machine.observe(sample(18000, 16000, speed = 2f))
        assertEquals(8000L, machine.snapshot!!.listenedMs)
    }

    @Test
    fun shutdownCheckpointKeepsAttemptOpenForExplicitRestore() {
        start()
        machine.observe(sample(9000))
        machine.suspendSession()
        assertFalse(emitted.last().closed)
        machine.observe(sample(100000, 9500))
        machine.observe(sample(101000, 10500))
        assertEquals(10000L, machine.snapshot!!.listenedMs)
    }
}
