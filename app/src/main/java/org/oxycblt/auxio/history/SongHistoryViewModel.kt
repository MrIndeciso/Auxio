/*
 * Copyright (c) 2026 Auxio Project
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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.stats.*
import org.oxycblt.musikr.Music

@HiltViewModel
class SongHistoryViewModel
@Inject
constructor(
    private val repository: StatsRepository,
    private val music: MusicRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val limit = saved.getStateFlow("limit", 50)
    private val unavailable = saved.getStateFlow("unavailable", false)
    private val refresh = MutableStateFlow(0)
    val rangeLabel: String
        get() {
            val zone = java.time.ZoneId.systemDefault()
            val first =
                saved
                    .get<Long>("start")
                    ?.takeIf { it != Long.MIN_VALUE }
                    ?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            val last =
                saved
                    .get<Long>("end")
                    ?.takeIf { it != Long.MAX_VALUE }
                    ?.let {
                        java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().minusDays(1)
                    }
            return if (first == null || last == null) ""
            else if (first == last) first.toString() else "$first – $last"
        }

    var scroll: android.os.Parcelable?
        get() = saved["scroll"]
        set(value) {
            saved["scroll"] = value
        }

    val state = MutableStateFlow(HistoryUiState())
    private var pending: (suspend () -> Unit)? = null
    private val libraryChanges = callbackFlow {
        val listener =
            object : MusicRepository.UpdateListener {
                override fun onMusicChanges(changes: MusicRepository.Changes) {
                    trySend(Unit)
                }
            }
        music.addUpdateListener(listener)
        trySend(Unit)
        awaitClose { music.removeUpdateListener(listener) }
    }

    init {
        viewModelScope.launch {
            combine(limit, unavailable, refresh, libraryChanges) { count, missing, _, _ ->
                    HistoryFilter(
                        saved["start"] ?: Long.MIN_VALUE,
                        saved["end"] ?: Long.MAX_VALUE,
                        saved["songUid"],
                        missing,
                    ) to count
                }
                .collectLatest { (filter, count) ->
                    state.value = state.value.copy(loading = true)
                    try {
                        repository.observeHistory(filter, count + 1).collect { rows ->
                            state.value =
                                HistoryUiState(
                                    rows.take(count),
                                    false,
                                    rows.size > count,
                                    unavailable = filter.unavailable,
                                )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        state.value =
                            state.value.copy(
                                loading = false,
                                error = e.message ?: "History unavailable",
                            )
                    }
                }
        }
    }

    fun more() {
        saved["limit"] = limit.value + 50
    }

    fun filterUnavailable(value: Boolean) {
        saved["unavailable"] = value
        saved["limit"] = 50
    }

    fun retry() {
        viewModelScope.launch {
            val operation = pending
            if (operation != null) write(operation)
            refresh.value++
        }
    }

    private suspend fun write(operation: suspend () -> Unit): Boolean =
        try {
            operation()
            pending = null
            state.value = state.value.copy(error = null)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pending = operation
            state.value = state.value.copy(error = e.message ?: "History could not be saved")
            false
        }

    suspend fun updateEvent(id: Long, uid: Music.UID, timestamp: Long, duration: Long) = write {
        repository.updatePlayEvent(id, uid, timestamp, duration)
    }

    fun deleteEvent(id: Long, uid: Music.UID) {
        viewModelScope.launch { write { repository.deletePlayEvent(id, uid) } }
    }
}

data class HistoryUiState(
    val events: List<PlayEvent> = emptyList(),
    val loading: Boolean = true,
    val more: Boolean = false,
    val error: String? = null,
    val unavailable: Boolean = false,
)
