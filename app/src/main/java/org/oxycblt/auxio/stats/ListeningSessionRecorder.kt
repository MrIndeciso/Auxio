/*
 * Copyright (c) 2026 Auxio Project
 * ListeningSessionRecorder.kt is part of Auxio.
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
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.oxycblt.musikr.Music

/** Application-wide ordered worker: service recreation cannot overtake a shutdown checkpoint. */
@Singleton
class ListeningSessionRecorder
internal constructor(private val repository: StatsRepository, scope: CoroutineScope) {
    @Inject
    constructor(
        repository: StatsRepository
    ) : this(repository, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val pending = ArrayDeque<SessionSnapshot>()
    private val machine = ListeningSessionMachine { pending.addLast(it) }

    init {
        scope.launch {
            for (command in commands) {
                var completed = false
                while (!completed) {
                    try {
                        command()
                        completed = true
                    } catch (e: Exception) {
                        repository.reportRecordingFailure()
                        delay(5000)
                    }
                }
                var saved = false
                while (!saved) {
                    try {
                        flush()
                        saved = true
                    } catch (e: Exception) {
                        repository.reportRecordingFailure()
                        delay(5000)
                    }
                }
            }
        }
    }

    private suspend fun flush() {
        while (pending.isNotEmpty()) {
            repository.checkpoint(pending.first())
            pending.removeFirst()
        }
    }

    private fun enqueue(command: suspend () -> Unit) {
        check(commands.trySend(command).isSuccess)
    }

    fun observe(sample: PlaybackObservation) = enqueue { machine.observe(sample) }

    fun rebase(sample: PlaybackObservation) = enqueue {
        machine.rebase(sample)
        machine.checkpoint()
    }

    fun suspendSession() = enqueue { machine.suspendSession() }

    fun finish() = enqueue { machine.finish() }

    fun select(uid: Music.UID?, restore: Boolean, sample: PlaybackObservation) = enqueue {
        if (uid == null) machine.finish()
        else if (restore && machine.snapshot?.songUid == uid) machine.rebase(sample)
        else {
            machine.finish()
            flush()
            val recovered = if (restore) repository.recover(uid) else null
            if (recovered == null) repository.closeUnfinished()
            machine.select(uid, recovered)
        }
        machine.rebase(sample)
    }
}
