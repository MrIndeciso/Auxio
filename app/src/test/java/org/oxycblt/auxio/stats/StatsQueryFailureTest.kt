/*
 * Copyright (c) 2026 Auxio Project
 * StatsQueryFailureTest.kt is part of Auxio.
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.music.MusicRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class StatsQueryFailureTest {
    @Test
    fun readFailureRemainsVisibleUntilRetrySucceeds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val repository = mockk<StatsRepository>(relaxed = true)
            val music = mockk<MusicRepository>(relaxed = true)
            every { music.library } returns null
            every { repository.recordingError } returns MutableStateFlow(null)
            var fail = true
            every { repository.observeRecords() } returns
                flow {
                    if (fail) throw IllegalStateException("test read failure")
                    emit(StatsRecords(emptyList(), emptyList()))
                }
            val model = StatsViewModel(repository, music, SavedStateHandle(), dispatcher)
            store.put("stats", model)
            runCurrent()
            assertNotNull(model.state.value.error)
            assertFalse(model.state.value.loading)
            fail = false
            model.retry()
            runCurrent()
            assertNull(model.state.value.error)
            assertFalse(model.state.value.loading)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
