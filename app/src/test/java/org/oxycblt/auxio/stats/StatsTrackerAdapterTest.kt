/*
 * Copyright (c) 2026 Auxio Project
 * StatsTrackerAdapterTest.kt is part of Auxio.
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

import android.app.Application
import android.os.Looper
import androidx.media3.common.*
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StatsTrackerAdapterTest {
    private val uid =
        requireNotNull(Music.UID.fromString("uas00000000-0000-0000-0000-000000000001"))
    private val song = mockk<Song> { every { uid } returns this@StatsTrackerAdapterTest.uid }
    private var window: Any = "first"
    private var position = 0L
    private var playing = true
    private val timeline =
        mockk<Timeline> {
            every { isEmpty } returns false
            every { getWindow(any(), any()) } answers
                {
                    secondArg<Timeline.Window>().apply { uid = window }
                }
        }
    private val player =
        mockk<Player> {
            every { applicationLooper } returns Looper.getMainLooper()
            every { currentTimeline } returns timeline
            every { currentMediaItemIndex } returns 0
            every { currentMediaItem } returns
                MediaItem.Builder().setUri("file:///test").setTag(song).build()
            every { currentPosition } answers { position }
            every { isPlaying } answers { playing }
            every { playbackParameters } returns PlaybackParameters.DEFAULT
        }
    private val recorder = mockk<ListeningSessionRecorder>(relaxed = true)
    private val tracker = StatsTracker(recorder, player)
    private val events = Player.Events(FlagSet.Builder().add(Player.EVENT_TIMELINE_CHANGED).build())

    private fun info(token: Any, position: Long) =
        Player.PositionInfo(token, 0, null, 0, position, position, -1, -1)

    @Test
    fun queueAddReorderAndShuffleKeepOccurrence() {
        tracker.endChange()
        repeat(10) { tracker.onEvents(player, events) }
        verify(exactly = 1) { recorder.select(uid, false, any()) }
    }

    @Test
    fun sameSongInDifferentQueueOccurrencesStartsAnotherAttempt() {
        tracker.endChange()
        window = "second"
        tracker.onPositionDiscontinuity(
            info("first", 9000),
            info("second", 0),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        tracker.onEvents(player, events)
        verify(exactly = 2) { recorder.select(uid, false, any()) }
    }

    @Test
    fun repeatOneAndDuplicateCallbacksCreateExactlyOneNewAttempt() {
        tracker.endChange()
        repeat(2) {
            tracker.onPositionDiscontinuity(
                info("first", 9000),
                info("first", 0),
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            )
            tracker.onEvents(player, events)
        }
        verify(exactly = 2) { recorder.select(uid, false, any()) }
    }

    @Test
    fun seekAndPreviousRewindAccountOldPositionThenRebase() {
        tracker.endChange()
        position = 120000
        tracker.onPositionDiscontinuity(
            info("first", 4000),
            info("first", position),
            Player.DISCONTINUITY_REASON_SEEK,
        )
        position = 0
        tracker.onPositionDiscontinuity(
            info("first", 124000),
            info("first", position),
            Player.DISCONTINUITY_REASON_SEEK,
        )
        verify(exactly = 1) { recorder.select(uid, false, any()) }
        verifyOrder {
            recorder.observe(match { it.positionMs == 4000L })
            recorder.rebase(match { it.positionMs == 120000L })
            recorder.observe(match { it.positionMs == 124000L })
            recorder.rebase(match { it.positionMs == 0L })
        }
    }

    @Test
    fun explicitSameSongSelectionSplitsAndRestoreIsExplicit() {
        tracker.beginChange()
        tracker.endChange()
        tracker.beginChange()
        tracker.endChange()
        tracker.beginChange()
        tracker.endChange(restore = true)
        verify(atLeast = 2) { recorder.select(uid, false, any()) }
        verify(exactly = 1) { recorder.select(uid, true, any()) }
    }

    @Test
    fun finalTrackCompletionSamplesBeforeFinishing() {
        tracker.endChange()
        position = 9999
        playing = false
        tracker.onPlaybackStateChanged(Player.STATE_ENDED)
        verifyOrder {
            recorder.observe(match { it.positionMs == 9999L && !it.advancing })
            recorder.finish()
        }
    }
}
