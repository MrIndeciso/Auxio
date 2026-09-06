/*
 * Copyright (c) 2026 Auxio Project
 * ListeningSessionMachine.kt is part of Auxio.
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

import java.util.UUID
import org.oxycblt.musikr.Music

/** A sample is ordered on the player's application looper. Position jumps are separate samples. */
data class PlaybackObservation(
    val elapsedMs: Long,
    val wallTimeMs: Long,
    val positionMs: Long,
    val advancing: Boolean,
    val speed: Float = 1f,
)

data class SessionSnapshot(
    val attemptId: String,
    val songUid: Music.UID,
    val startedAt: Long?,
    val listenedMs: Long,
    val closed: Boolean = false,
)

/** Pure reducer. It never extrapolates across restoration, seeks or a stopped sample. */
class ListeningSessionMachine(private val emit: (SessionSnapshot) -> Unit) {
    var snapshot: SessionSnapshot? = null
        private set

    private var previous: PlaybackObservation? = null
    private var checkpointMs = 0L

    fun select(uid: Music.UID, restored: ListeningSession? = null) {
        finish()
        snapshot =
            if (restored != null && restored.songUid == uid && restored.state == "OPEN") {
                SessionSnapshot(restored.attemptId, uid, restored.startedAt, restored.committedMs)
            } else SessionSnapshot(UUID.randomUUID().toString(), uid, null, 0)
        checkpointMs = snapshot!!.listenedMs
        emit(snapshot!!)
    }

    fun observe(sample: PlaybackObservation) {
        var active = snapshot ?: return
        val old = previous
        if (old != null && old.advancing) {
            val elapsed = (sample.elapsedMs - old.elapsedMs).coerceAtLeast(0)
            val advancement = (sample.positionMs - old.positionMs).coerceAtLeast(0)
            // Listening is wall-clock audible time, capped by actual media advancement.
            val audible = minOf(elapsed, (advancement / old.speed.coerceAtLeast(0.01f)).toLong())
            if (audible > 0) {
                active =
                    active.copy(
                        startedAt = active.startedAt ?: old.wallTimeMs,
                        listenedMs = active.listenedMs + audible,
                    )
                val qualified =
                    active.listenedMs >= QUALIFY_MS && snapshot!!.listenedMs < QUALIFY_MS
                snapshot = active
                if (qualified || active.listenedMs - checkpointMs >= CHECKPOINT_MS) checkpoint()
            }
        }
        previous = sample
        if (old?.advancing == true && !sample.advancing) checkpoint()
    }

    /** The adapter accounts for the old position before rebasing to a seek's new position. */
    fun rebase(sample: PlaybackObservation) {
        previous = sample
    }

    fun checkpoint() {
        snapshot?.let {
            emit(it)
            checkpointMs = it.listenedMs
        }
    }

    fun suspendSession() {
        checkpoint()
        previous = null
    }

    fun finish() {
        snapshot?.let { emit(it.copy(closed = true)) }
        snapshot = null
        previous = null
    }

    companion object {
        const val QUALIFY_MS = 8000L
        const val CHECKPOINT_MS = 5000L
    }
}
