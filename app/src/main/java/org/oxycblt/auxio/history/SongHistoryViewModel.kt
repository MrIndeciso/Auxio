/*
 * Copyright (c) 2024 Auxio Project
 * SongHistoryViewModel.kt is part of Auxio.
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

package org.oxycblt.auxio.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.musikr.Music
import org.oxycblt.auxio.stats.PlayEvent
import org.oxycblt.auxio.stats.StatsRepository

@HiltViewModel
class SongHistoryViewModel @Inject constructor(private val statsRepository: StatsRepository) :
    ViewModel() {
    private val _history = MutableStateFlow<List<PlayEvent>>(emptyList())
    val history: StateFlow<List<PlayEvent>> = _history

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = statsRepository.getSongHistory()
        }
    }

    fun updateEvent(id: Long, songUid: Music.UID, timestamp: Long, listenTimeMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            statsRepository.updatePlayEvent(id, songUid, timestamp, listenTimeMs)
            _history.value = statsRepository.getSongHistory()
        }
    }

    fun deleteEvent(id: Long, songUid: Music.UID) {
        viewModelScope.launch(Dispatchers.IO) {
            statsRepository.deletePlayEvent(id, songUid)
            _history.value = statsRepository.getSongHistory()
        }
    }
}
