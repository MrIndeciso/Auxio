/*
 * Copyright (c) 2024 Auxio Project
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

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Component that tracks playback and records listening statistics.
 *
 * @author Auxio Project
 */
class StatsTracker
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val statsRepository: StatsRepository
) : PlaybackStateManager.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentSong: Song? = null
    private var playbackStartTime: Long = 0L
    private var isPlaying = false

    fun attach() {
        L.d("Attaching StatsTracker")
        playbackManager.addListener(this)
        seedCurrentSong()
    }

    fun detach() {
        L.d("Detaching StatsTracker")
        recordCurrentSession()
        playbackManager.removeListener(this)
    }

    override fun onIndexMoved(index: Int) {
        // Song changed, record the previous session
        recordCurrentSession()
        currentSong = playbackManager.currentSong
        playbackStartTime = android.os.SystemClock.elapsedRealtime()
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        // Queue changed with different song, record the previous session
        recordCurrentSession()
        currentSong = playbackManager.currentSong
        playbackStartTime = android.os.SystemClock.elapsedRealtime()
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean
    ) {
        // New playback started
        recordCurrentSession()
        currentSong = playbackManager.currentSong
        playbackStartTime = android.os.SystemClock.elapsedRealtime()
        isPlaying = playbackManager.progression.isPlaying
    }

    override fun onProgressionChanged(progression: Progression) {
        val wasPlaying = isPlaying
        isPlaying = progression.isPlaying

        // If playback stopped, record the session
        if (wasPlaying && !isPlaying) {
            recordCurrentSession()
        }

        // If playback started, reset the timer
        if (!wasPlaying && isPlaying) {
            playbackStartTime = android.os.SystemClock.elapsedRealtime()
        }
    }

    override fun onSessionEnded() {
        recordCurrentSession()
        currentSong = null
        isPlaying = false
    }

    private fun recordCurrentSession() {
        val song = currentSong ?: return
        if (playbackStartTime == 0L) return

        val listenTimeMs = android.os.SystemClock.elapsedRealtime() - playbackStartTime
        // Only count if the song was played for at least 3 seconds
        if (listenTimeMs >= 3000) {
            scope.launch {
                try {
                    statsRepository.recordPlay(song, listenTimeMs)
                } catch (e: Exception) {
                    L.e("Failed to record playback session")
                    L.e(e.stackTraceToString())
                }
            }
        }

        // Reset the timer
        playbackStartTime = 0L
    }

    private fun seedCurrentSong() {
        val progression = playbackManager.progression
        val song = playbackManager.currentSong ?: return
        if (progression.isPlaying) {
            currentSong = song
            playbackStartTime = android.os.SystemClock.elapsedRealtime()
            isPlaying = true
        }
    }
}
