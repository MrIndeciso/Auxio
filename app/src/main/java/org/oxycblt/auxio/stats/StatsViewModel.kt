/*
 * Copyright (c) 2024 Auxio Project
 * StatsViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber as L

/**
 * ViewModel for stats data.
 *
 * @author Auxio Project
 */
@HiltViewModel
class StatsViewModel @Inject constructor(private val statsRepository: StatsRepository) :
    ViewModel() {
    private val _statsData = MutableStateFlow<StatsData?>(null)
    val statsData: StateFlow<StatsData?> = _statsData

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val songStats = statsRepository.getAllSongStats()
                val albumStats = statsRepository.getAlbumStats()
                val artistStats = statsRepository.getArtistStats()
                val overallStats = statsRepository.getOverallStats()

                _statsData.value =
                    StatsData(
                        topSongs = songStats.take(10),
                        topAlbums = albumStats.take(10),
                        topArtists = artistStats.take(10),
                        overallStats = overallStats)
            } catch (e: Exception) {
                L.e("Failed to load stats")
                L.e(e.stackTraceToString())
            } finally {
                _isLoading.value = false
            }
        }
    }
}

/**
 * Consolidated data for the stats screen.
 *
 * @param topSongs Top songs by play count.
 * @param topAlbums Top albums by play count.
 * @param topArtists Top artists by play count.
 * @param overallStats Overall listening statistics.
 */
data class StatsData(
    val topSongs: List<SongStatsInfo>,
    val topAlbums: List<AlbumStatsInfo>,
    val topArtists: List<ArtistStatsInfo>,
    val overallStats: OverallStats
)
