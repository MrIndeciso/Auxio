/*
 * Copyright (c) 2024 Auxio Project
 * StatsFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentStatsBinding
import org.oxycblt.auxio.databinding.ItemStatAlbumBinding
import org.oxycblt.auxio.databinding.ItemStatArtistBinding
import org.oxycblt.auxio.databinding.ItemStatSongBinding
import org.oxycblt.auxio.util.collectImmediately
import timber.log.Timber as L

/**
 * Fragment that displays listening statistics.
 *
 * @author Auxio Project
 */
@AndroidEntryPoint
class StatsFragment : Fragment() {
    private var _binding: FragmentStatsBinding? = null
    private val binding
        get() = _binding!!
    private val statsViewModel: StatsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup time period selector
        val timePeriodLabels =
            mapOf(
                TimePeriod.ALL_TIME to getString(R.string.lbl_all_time),
                TimePeriod.THIS_YEAR to getString(R.string.lbl_this_year),
                TimePeriod.LAST_YEAR to getString(R.string.lbl_last_year),
                TimePeriod.LAST_12_MONTHS to getString(R.string.lbl_last_12_months),
                TimePeriod.THIS_MONTH to getString(R.string.lbl_this_month),
                TimePeriod.LAST_MONTH to getString(R.string.lbl_last_month),
                TimePeriod.THIS_WEEK to getString(R.string.lbl_this_week),
                TimePeriod.LAST_WEEK to getString(R.string.lbl_last_week))

        val adapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                timePeriodLabels.values.toList())
        binding.statsTimePeriodDropdown.setAdapter(adapter)

        // Set default selection
        binding.statsTimePeriodDropdown.setText(
            timePeriodLabels[TimePeriod.ALL_TIME], false)

        // Handle selection changes
        binding.statsTimePeriodDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedPeriod = timePeriodLabels.keys.toList()[position]
            statsViewModel.setTimePeriod(selectedPeriod)
        }

        binding.statsTopSongsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = SongStatsAdapter()
        }

        binding.statsTopAlbumsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = AlbumStatsAdapter()
        }

        binding.statsTopArtistsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ArtistStatsAdapter()
        }

        collectImmediately(statsViewModel.statsData, ::updateStats)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateStats(statsData: StatsData?) {
        if (statsData == null) {
            L.d("Stats data is null")
            return
        }

        // Update overall stats
        val totalTimeMs = statsData.overallStats.totalListenTimeMs
        val hours = totalTimeMs / (1000 * 60 * 60)
        val minutes = (totalTimeMs / (1000 * 60)) % 60
        binding.statsTotalTime.text = getString(R.string.fmt_hours_minutes, hours, minutes)
        binding.statsTotalPlays.text = statsData.overallStats.totalPlayCount.toString()

        // Update recycler views
        (binding.statsTopSongsRecycler.adapter as? SongStatsAdapter)?.submitList(
            statsData.topSongs)
        (binding.statsTopAlbumsRecycler.adapter as? AlbumStatsAdapter)?.submitList(
            statsData.topAlbums)
        (binding.statsTopArtistsRecycler.adapter as? ArtistStatsAdapter)?.submitList(
            statsData.topArtists)
    }

    private inner class SongStatsAdapter :
        RecyclerView.Adapter<SongStatsAdapter.ViewHolder>() {
        private var items = listOf<SongStatsInfo>()

        fun submitList(newItems: List<SongStatsInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                ItemStatSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position + 1)
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val binding: ItemStatSongBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(info: SongStatsInfo, rank: Int) {
                binding.statRank.text = rank.toString()
                binding.statSongName.text = info.song.name.raw
                binding.statSongArtist.text = info.song.artists.joinToString { it.name.raw }
                binding.statPlayCount.text =
                    getString(R.string.fmt_play_count, info.playCount)
                
                val timeMs = info.totalListenTimeMs
                val hours = timeMs / (1000 * 60 * 60)
                val minutes = (timeMs / (1000 * 60)) % 60
                if (hours > 0) {
                    binding.statListenTime.text =
                        getString(R.string.fmt_hours_minutes, hours, minutes)
                } else {
                    val seconds = (timeMs / 1000) % 60
                    binding.statListenTime.text =
                        getString(R.string.fmt_minutes_seconds, minutes, seconds)
                }
            }
        }
    }

    private inner class AlbumStatsAdapter :
        RecyclerView.Adapter<AlbumStatsAdapter.ViewHolder>() {
        private var items = listOf<AlbumStatsInfo>()

        fun submitList(newItems: List<AlbumStatsInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                ItemStatAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position + 1)
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val binding: ItemStatAlbumBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(info: AlbumStatsInfo, rank: Int) {
                binding.statRank.text = rank.toString()
                binding.statAlbumName.text = info.album.name.raw
                binding.statAlbumArtist.text = info.album.artist.name.raw
                binding.statPlayCount.text =
                    getString(R.string.fmt_play_count, info.totalPlayCount)
                
                val timeMs = info.totalListenTimeMs
                val hours = timeMs / (1000 * 60 * 60)
                val minutes = (timeMs / (1000 * 60)) % 60
                if (hours > 0) {
                    binding.statListenTime.text =
                        getString(R.string.fmt_hours_minutes, hours, minutes)
                } else {
                    val seconds = (timeMs / 1000) % 60
                    binding.statListenTime.text =
                        getString(R.string.fmt_minutes_seconds, minutes, seconds)
                }
            }
        }
    }

    private inner class ArtistStatsAdapter :
        RecyclerView.Adapter<ArtistStatsAdapter.ViewHolder>() {
        private var items = listOf<ArtistStatsInfo>()

        fun submitList(newItems: List<ArtistStatsInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                ItemStatArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position + 1)
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val binding: ItemStatArtistBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(info: ArtistStatsInfo, rank: Int) {
                binding.statRank.text = rank.toString()
                binding.statArtistName.text = info.artist.name.raw
                binding.statPlayCount.text =
                    getString(R.string.fmt_play_count, info.totalPlayCount)
                
                val timeMs = info.totalListenTimeMs
                val hours = timeMs / (1000 * 60 * 60)
                val minutes = (timeMs / (1000 * 60)) % 60
                if (hours > 0) {
                    binding.statListenTime.text =
                        getString(R.string.fmt_hours_minutes, hours, minutes)
                } else {
                    val seconds = (timeMs / 1000) % 60
                    binding.statListenTime.text =
                        getString(R.string.fmt_minutes_seconds, minutes, seconds)
                }
            }
        }
    }
}
