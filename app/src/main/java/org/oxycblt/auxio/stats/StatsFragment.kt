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

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.R as AR
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.CalendarMonth
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.kizitonwose.calendar.view.CalendarView
import com.kizitonwose.calendar.view.MonthDayBinder
import com.kizitonwose.calendar.view.MonthHeaderFooterBinder
import com.kizitonwose.calendar.view.ViewContainer
import dagger.hilt.android.AndroidEntryPoint
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentStatsBinding
import org.oxycblt.auxio.databinding.ItemCalendarDayBinding
import org.oxycblt.auxio.databinding.ItemCalendarHeaderBinding
import org.oxycblt.auxio.databinding.ItemStatAlbumBinding
import org.oxycblt.auxio.databinding.ItemStatArtistBinding
import org.oxycblt.auxio.databinding.ItemStatSongBinding
import org.oxycblt.auxio.home.HomeFragmentDirections
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.systemBarInsetsCompat
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
    private lateinit var timePeriodOptions: List<Pair<TimePeriod, String>>

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

        binding.root.setOnApplyWindowInsetsListener { v, insets ->
            val extraBottom = resources.getDimensionPixelSize(R.dimen.spacing_medium)
            v.updatePadding(bottom = insets.systemBarInsetsCompat.bottom + extraBottom)
            insets
        }

        // Setup time period selector
        timePeriodOptions =
            listOf(
                TimePeriod.ALL_TIME to getString(R.string.lbl_all_time),
                TimePeriod.THIS_YEAR to getString(R.string.lbl_this_year),
                TimePeriod.LAST_YEAR to getString(R.string.lbl_last_year),
                TimePeriod.LAST_12_MONTHS to getString(R.string.lbl_last_12_months),
                TimePeriod.THIS_MONTH to getString(R.string.lbl_this_month),
                TimePeriod.LAST_MONTH to getString(R.string.lbl_last_month),
                TimePeriod.THIS_WEEK to getString(R.string.lbl_this_week),
                TimePeriod.LAST_WEEK to getString(R.string.lbl_last_week))

        val timePeriodAdapter =
            NoFilterArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                timePeriodOptions.map { it.second })
        binding.statsTimePeriodDropdown.setAdapter(timePeriodAdapter)
        binding.statsTimePeriodDropdown.setOnClickListener {
            binding.statsTimePeriodDropdown.showDropDown()
        }

        // Handle selection changes
        binding.statsTimePeriodDropdown.setOnItemClickListener { _, _, position, _ ->
            statsViewModel.setTimePeriod(timePeriodOptions[position].first)
        }

        collectImmediately(statsViewModel.selectedTimePeriod) { period ->
            val label = timePeriodOptions.firstOrNull { it.first == period }?.second ?: return@collectImmediately
            if (binding.statsTimePeriodDropdown.text.toString() != label) {
                binding.statsTimePeriodDropdown.setText(label, false)
            }
        }

        binding.viewHistoryButton.setOnClickListener {
            findNavController().navigateSafe(HomeFragmentDirections.actionHomeFragmentToSongHistoryFragment())
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

    override fun onResume() {
        super.onResume()
        // Refresh stats in case the library finished loading after the initial query.
        statsViewModel.loadStats()
    }

    private fun updateStats(statsData: StatsData?) {
        if (statsData == null) {
            L.d("Stats data is null")
            return
        }

        // Update overall stats
        val totalTimeMs = statsData.overallStats.totalListenTimeMs
        val hours = TimeUnit.MILLISECONDS.toHours(totalTimeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs) % 60
        binding.statsTotalTime.text = getString(R.string.fmt_hours_minutes, hours, minutes)
        binding.statsTotalPlays.text = statsData.overallStats.totalPlayCount.toString()

        // Update recycler views
        (binding.statsTopSongsRecycler.adapter as? SongStatsAdapter)?.submitList(
            statsData.topSongs)
        (binding.statsTopAlbumsRecycler.adapter as? AlbumStatsAdapter)?.submitList(
            statsData.topAlbums)
        (binding.statsTopArtistsRecycler.adapter as? ArtistStatsAdapter)?.submitList(
            statsData.topArtists)

        // Calendar View
        val dailyStats = statsData.dailyStats
        if (dailyStats.isNotEmpty()) {
            setupCalendar(binding.calendarView, dailyStats)

            val totalListenTime = dailyStats.sumOf { it.totalListenTimeMs }
            val firstDate = dailyStats.minOf { it.date }
            val lastDate = dailyStats.maxOf { it.date }
            val totalDays = ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1

            val dailyAverageMs = if (totalDays > 0) totalListenTime / totalDays else 0
            val dailyAvgHours = TimeUnit.MILLISECONDS.toHours(dailyAverageMs)
            val dailyAvgMinutes = TimeUnit.MILLISECONDS.toMinutes(dailyAverageMs) % 60
            binding.statsDailyAverage.text = getString(R.string.fmt_hours_minutes, dailyAvgHours, dailyAvgMinutes)

            val weekFields = WeekFields.of(Locale.getDefault())
            val weeklyBuckets =
                dailyStats.groupBy { it.date.with(weekFields.dayOfWeek(), 1) }
            val weeklyAverageMs =
                weeklyBuckets.values
                    .map { group -> group.sumOf { it.totalListenTimeMs }.toDouble() }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toLong() ?: 0

            val weeklyAvgHours = TimeUnit.MILLISECONDS.toHours(weeklyAverageMs)
            val weeklyAvgMinutes = TimeUnit.MILLISECONDS.toMinutes(weeklyAverageMs) % 60
            binding.statsWeeklyAverage.text =
                getString(R.string.fmt_hours_minutes, weeklyAvgHours, weeklyAvgMinutes)
        } else {
            binding.statsDailyAverage.text = getString(R.string.fmt_hours_minutes, 0, 0)
            binding.statsWeeklyAverage.text = getString(R.string.fmt_hours_minutes, 0, 0)
        }
    }

    private fun setupCalendar(calendarView: CalendarView, dailyStats: List<DailyStatsInfo>) {
        val firstDate = dailyStats.minOfOrNull { it.date } ?: return
        val lastDate = dailyStats.maxOfOrNull { it.date } ?: return

        val firstMonth = YearMonth.from(firstDate)
        val lastMonth = YearMonth.from(lastDate)

        val firstDayOfWeek = firstDayOfWeekFromLocale()
        val statsByDate = dailyStats.associateBy { it.date }
        val maxListenTime = dailyStats.maxOfOrNull { it.totalListenTimeMs }?.coerceAtLeast(1) ?: 1L

        calendarView.setup(firstMonth, lastMonth, firstDayOfWeek)
        calendarView.scrollToMonth(lastMonth)

        calendarView.dayBinder =
            object : MonthDayBinder<DayViewContainer> {
                override fun create(view: View) = DayViewContainer(view)
                override fun bind(container: DayViewContainer, day: CalendarDay) {
                    container.textView.text = day.date.dayOfMonth.toString()
                    val isCurrentMonth = day.position == DayPosition.MonthDate
                    val statsForDay = statsByDate[day.date]
                    val hasData = statsForDay != null && isCurrentMonth

                    val baseText = com.google.android.material.R.attr.colorOnSurface
                    val disabledText = com.google.android.material.R.attr.colorOnSurfaceVariant
                    val textColorAttr = if (isCurrentMonth) baseText else disabledText
                    container.textView.setTextColor(
                        MaterialColors.getColor(container.textView, textColorAttr, 0))

                    if (hasData) {
                        val intensity =
                            (statsForDay!!.totalListenTimeMs.toFloat() / maxListenTime)
                                .coerceIn(0f, 1f)
                        val activeColor =
                            MaterialColors.getColor(
                                container.textView,
                                AR.attr.colorPrimary,
                                0)
                        val surfaceColor =
                            MaterialColors.getColor(
                                container.textView,
                                MR.attr.colorSurfaceVariant,
                                0)
                        val blended =
                            ColorUtils.blendARGB(
                                surfaceColor, activeColor, 0.3f + 0.7f * intensity)
                        container.textView.background =
                            GradientDrawable().apply {
                                cornerRadius = resources.getDimension(R.dimen.spacing_small)
                                setColor(blended)
                            }
                        container.textView.setTextColor(
                            MaterialColors.getColor(
                                container.textView,
                                MR.attr.colorOnPrimary,
                                0))
                    } else {
                        container.textView.background = null
                    }
                }
            }

        calendarView.monthHeaderBinder =
            object : MonthHeaderFooterBinder<MonthViewContainer> {
                override fun create(view: View) = MonthViewContainer(view)
                override fun bind(container: MonthViewContainer, month: CalendarMonth) {
                    val monthName =
                        month.yearMonth.month.getDisplayName(
                            TextStyle.SHORT, Locale.getDefault())
                    container.textView.text = "$monthName ${month.yearMonth.year}"
                }
            }
    }

    inner class DayViewContainer(view: View) : ViewContainer(view) {
        val textView: TextView = ItemCalendarDayBinding.bind(view).calendarDayText
    }

    inner class MonthViewContainer(view: View) : ViewContainer(view) {
        val textView: TextView = ItemCalendarHeaderBinding.bind(view).calendarHeaderText
    }

    private class NoFilterArrayAdapter<T>(
        context: android.content.Context,
        resource: Int,
        private val items: List<T>
    ) : ArrayAdapter<T>(context, resource, items) {
        override fun getFilter() =
            object : android.widget.Filter() {
                override fun performFiltering(constraint: CharSequence?) =
                    android.widget.Filter.FilterResults().apply {
                        values = items
                        count = items.size
                    }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }

                override fun convertResultToString(resultValue: Any?) =
                    resultValue?.toString() ?: ""
            }
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
                binding.statSongName.text = info.song.name.resolve(itemView.context)
                binding.statSongArtist.text = info.song.artists.joinToString { it.name.resolve(itemView.context) }
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
                binding.statAlbumName.text = info.album.name.resolve(itemView.context)
                binding.statAlbumArtist.text = info.album.artists.joinToString { it.name.resolve(itemView.context) }
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
                binding.statArtistName.text = info.artist.name.resolve(itemView.context)
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
