/*
 * Copyright (c) 2026 Auxio Project
 * StatsTracker.kt is part of Auxio.
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

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import org.oxycblt.musikr.Song

/** Ordered adapter at the player boundary. All player access stays on its application looper. */
@androidx.annotation.OptIn(UnstableApi::class)
class StatsTracker(private val recorder: ListeningSessionRecorder, private val player: Player) :
    Player.Listener {
    private val handler = Handler(player.applicationLooper)
    private var occurrence: Any? = null
    private var discontinuity: List<Any?>? = null
    private var boundaryPosition = 0L
    private var changing = false
    private var attached = false
    private val tick =
        object : Runnable {
            override fun run() {
                if (!attached) return
                sample()
                scheduleTick()
            }
        }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        if (attached && player.isPlaying) handler.postDelayed(tick, 250)
    }

    private fun token(): Any? =
        if (player.currentTimeline.isEmpty) null
        else player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).uid

    private fun observation(position: Long = player.currentPosition) =
        PlaybackObservation(
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis(),
            position,
            player.isPlaying,
            player.playbackParameters.speed,
        )

    fun attach() {
        attached = true
        player.addListener(this)
        scheduleTick()
    }

    fun release() {
        sample()
        recorder.suspendSession()
        attached = false
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    fun beginChange() {
        sample()
        changing = true
    }

    fun endChange(restore: Boolean = false) {
        changing = false
        select(restore)
    }

    private fun select(restore: Boolean = false) {
        occurrence = token()
        val song = player.currentMediaItem?.localConfiguration?.tag as? Song
        val sample = observation()
        recorder.select(song?.uid, restore, sample)
    }

    private fun sample() {
        if (changing) return
        if (token() != occurrence) select()
        val sample = observation()
        if (sample.positionMs != boundaryPosition) discontinuity = null
        recorder.observe(sample)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (changing) return
        val signature =
            listOf(
                oldPosition.windowUid,
                newPosition.windowUid,
                oldPosition.positionMs,
                newPosition.positionMs,
                reason,
            )
        if (signature == discontinuity) return
        discontinuity = signature
        boundaryPosition = newPosition.positionMs
        val old = observation(oldPosition.positionMs)
        recorder.observe(old)
        val nextOccurrence = token()
        if (nextOccurrence != occurrence || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
            select()
        val new = observation(newPosition.positionMs)
        recorder.rebase(new)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (changing) return
        if (playbackState == Player.STATE_ENDED) {
            sample()
            recorder.finish()
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        sample()
        scheduleTick()
    }
}
