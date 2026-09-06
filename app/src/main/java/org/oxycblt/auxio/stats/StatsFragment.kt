/*
 * Copyright (c) 2026 Auxio Project
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
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.kizitonwose.calendar.view.MonthDayBinder
import com.kizitonwose.calendar.view.ViewContainer
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentStatsBinding
import org.oxycblt.auxio.databinding.ItemStatsDashboardBinding
import org.oxycblt.auxio.databinding.ItemStatsRankingBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Song

/** One RecyclerView owns dashboard, calendar and ranking scrolling. */
@AndroidEntryPoint
class StatsFragment : Fragment() {
    private var binding: FragmentStatsBinding? = null
    private val model: StatsViewModel by activityViewModels()
    private val all: Boolean
        get() = arguments?.getBoolean("allRankings") == true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentStatsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = binding!!.statsRecycler
        val header = ItemStatsDashboardBinding.inflate(layoutInflater, recycler, false)
        val rankings = RankingAdapter { entry ->
            val (destination, key) =
                when (entry.music) {
                    is Song -> R.id.song_detail_dialog to "songUid"
                    is Album -> R.id.album_detail_fragment to "albumUid"
                    else -> R.id.artist_detail_fragment to "artistUid"
                }
            findNavController().navigate(destination, bundleOf(key to entry.music.uid))
        }
        val more =
            MaterialButton(requireContext()).apply {
                setText(R.string.stats_see_all)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener { findNavController().navigate(R.id.statsRankingFragment) }
            }
        recycler.adapter =
            ConcatAdapter(SingleViewAdapter(header.root), rankings, SingleViewAdapter(more))
        recycler.setOnApplyWindowInsetsListener { v, insets ->
            v.updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
            insets
        }
        androidx.core.view.ViewCompat.setAccessibilityHeading(header.month, true)
        header.summary.isVisible = !all
        header.calendarSection.isVisible = !all
        val periods = TimePeriod.entries
        val labels =
            listOf(
                R.string.lbl_all_time,
                R.string.stats_today,
                R.string.lbl_this_year,
                R.string.lbl_last_year,
                R.string.lbl_last_12_months,
                R.string.lbl_this_month,
                R.string.lbl_last_month,
                R.string.lbl_this_week,
                R.string.lbl_last_week,
                R.string.stats_custom,
            )
        header.period.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lbl_stats)
                .setSingleChoiceItems(
                    labels.map { getString(it) }.toTypedArray(),
                    periods.indexOf(TimePeriod.valueOf(model.period.value)),
                ) { dialog, which ->
                    dialog.dismiss()
                    if (periods[which] == TimePeriod.CUSTOM) {
                        val picker = MaterialDatePicker.Builder.dateRangePicker().build()
                        picker.addOnPositiveButtonClickListener { selection ->
                            model.selectRange(
                                Instant.ofEpochMilli(selection.first)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate(),
                                Instant.ofEpochMilli(selection.second)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate(),
                            )
                        }
                        picker.show(parentFragmentManager, "stats-date-range")
                    } else model.selectPeriod(periods[which])
                }
                .show()
        }
        val categories = listOf(R.string.lbl_songs, R.string.lbl_albums, R.string.lbl_artists)
        header.category.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setSingleChoiceItems(
                    categories.map { getString(it) }.toTypedArray(),
                    RankingCategory.valueOf(model.category.value).ordinal,
                ) { dialog, which ->
                    model.selectCategory(RankingCategory.entries[which])
                    dialog.dismiss()
                }
                .show()
        }
        header.byPlays.setOnCheckedChangeListener { _, checked -> model.sortByPlays(checked) }
        header.history.setOnClickListener { history(model.state.value.range) }
        header.unavailable.setOnClickListener {
            history(model.state.value.range, unavailable = true)
        }
        header.retry.setOnClickListener { model.retry() }
        val firstWeekday = firstDayOfWeekFromLocale()
        repeat(7) { index ->
            header.weekdays.addView(
                TextView(requireContext()).apply {
                    text =
                        firstWeekday
                            .plus(index.toLong())
                            .getDisplayName(TextStyle.NARROW, Locale.getDefault())
                    gravity = android.view.Gravity.CENTER
                    layoutParams =
                        android.widget.LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                }
            )
        }
        header.calendar.dayBinder =
            object : MonthDayBinder<DayContainer> {
                override fun create(view: View) = DayContainer(view)

                override fun bind(container: DayContainer, day: CalendarDay) {
                    val text = container.view as TextView
                    val current = day.position == DayPosition.MonthDate
                    val duration = model.state.value.daily[day.date] ?: 0
                    text.text = if (current) day.date.dayOfMonth.toString() else ""
                    text.isClickable = current
                    text.isFocusable = current
                    text.contentDescription =
                        getString(
                            R.string.stats_day,
                            day.date.format(
                                DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)
                            ),
                            formatStatsDuration(duration),
                        )
                    text.setTextColor(
                        MaterialColors.getColor(
                            text,
                            if (duration > 0 && current)
                                com.google.android.material.R.attr.colorOnPrimaryContainer
                            else com.google.android.material.R.attr.colorOnSurface,
                        )
                    )
                    text.setBackgroundColor(
                        MaterialColors.getColor(
                            text,
                            if (duration > 0 && current)
                                com.google.android.material.R.attr.colorPrimaryContainer
                            else com.google.android.material.R.attr.colorSurface,
                        )
                    )
                    text.setOnClickListener {
                        if (current) history(StatsDateRange(day.date, day.date.plusDays(1)))
                    }
                    text.isClickable = current
                    text.importantForAccessibility =
                        if (current) View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        else View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
        header.calendar.daySize = com.kizitonwose.calendar.view.DaySize.SeventhWidth
        header.calendar.setup(YearMonth.of(1900, 1), YearMonth.of(2200, 12), firstWeekday)
        header.calendar.scrollToMonth(YearMonth.parse(model.month.value))
        header.calendar.monthScrollListener = { model.showMonth(it.yearMonth) }
        header.previousMonth.setOnClickListener {
            model.showMonth(
                YearMonth.parse(model.month.value)
                    .minusMonths(1)
                    .coerceAtLeast(YearMonth.of(1900, 1))
            )
        }
        header.nextMonth.setOnClickListener {
            model.showMonth(
                YearMonth.parse(model.month.value)
                    .plusMonths(1)
                    .coerceAtMost(YearMonth.of(2200, 12))
            )
        }
        collectImmediately(model.month) {
            val month = YearMonth.parse(it)
            header.month.text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
            if (header.calendar.findFirstVisibleMonth()?.yearMonth != month)
                header.calendar.scrollToMonth(month)
        }
        var restored = false
        collectImmediately(
            combine(
                    model.state,
                    model.category,
                    model.byPlays,
                    model.period,
                    model.recordingError,
                ) { state, category, plays, period, recordingError ->
                    DashboardRender(
                        state,
                        RankingCategory.valueOf(category),
                        plays,
                        period,
                        recordingError,
                    )
                }
                .stateIn(
                    viewLifecycleOwner.lifecycleScope,
                    SharingStarted.Eagerly,
                    DashboardRender(
                        model.state.value,
                        RankingCategory.valueOf(model.category.value),
                        model.byPlays.value,
                        model.period.value,
                        model.recordingError.value,
                    ),
                )
        ) { render ->
            val state = render.state
            val entries = state.ranking(render.category, render.plays)
            header.category.setText(categories[render.category.ordinal])
            header.byPlays.isChecked = render.plays
            header.period.text =
                if (render.period == TimePeriod.CUSTOM.name)
                    "${state.range.first} – ${state.range.endExclusive?.minusDays(1)}"
                else getString(labels[periods.indexOf(TimePeriod.valueOf(render.period))])
            header.totalTime.text = formatStatsDuration(state.total.totalListenTimeMs)
            header.totalPlays.text = getString(R.string.fmt_play_count, state.total.totalPlayCount)
            header.average.text =
                getString(
                    R.string.stats_average,
                    formatStatsDuration(state.total.totalListenTimeMs / state.days),
                    formatStatsDuration(
                        (state.total.totalListenTimeMs.toDouble() * 7 / state.days).toLong()
                    ),
                )
            header.unavailable.isVisible = state.unavailable.totalPlayCount > 0
            header.unavailable.text =
                getString(
                    R.string.stats_unavailable,
                    state.unavailable.totalPlayCount,
                    formatStatsDuration(state.unavailable.totalListenTimeMs),
                )
            header.loading.isVisible = state.loading
            val messages = buildList {
                state.error?.let { add(it) }
                render.recordingError?.let { add(it) }
                if (!state.loading && state.total.totalPlayCount == 0L)
                    add(getString(R.string.stats_empty))
                else if (!state.loading && entries.isEmpty())
                    add(getString(R.string.stats_empty_ranking))
                if (state.inconsistentSongs > 0)
                    add(
                        resources.getQuantityString(
                            R.plurals.stats_inconsistent,
                            state.inconsistentSongs,
                            state.inconsistentSongs,
                        )
                    )
            }
            header.status.text = messages.joinToString("\n")
            header.status.isVisible = messages.isNotEmpty()
            header.retry.isVisible = state.error != null
            header.calendar.notifyCalendarChanged()
            more.isVisible = !all && entries.size > 10
            rankings.submitList(if (all) entries else entries.take(10)) {
                if (!restored && !state.loading) {
                    recycler.layoutManager?.onRestoreInstanceState(
                        if (all) model.rankingScroll else model.scroll
                    )
                    restored = true
                }
            }
        }
    }

    private fun history(range: StatsDateRange, unavailable: Boolean = false) {
        val (start, end) = range.timestamps()
        findNavController()
            .navigate(
                R.id.songHistoryFragment,
                bundleOf("start" to start, "end" to end, "unavailable" to unavailable),
            )
    }

    override fun onPause() {
        val scroll = binding?.statsRecycler?.layoutManager?.onSaveInstanceState()
        if (all) model.rankingScroll = scroll else model.scroll = scroll
        super.onPause()
    }

    override fun onDestroyView() {
        binding?.statsRecycler?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private class DayContainer(view: View) : ViewContainer(view)

    private data class DashboardRender(
        val state: StatsUiState,
        val category: RankingCategory,
        val plays: Boolean,
        val period: String,
        val recordingError: String?,
    )
}

internal class SingleViewAdapter(private val view: View) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        object : RecyclerView.ViewHolder(view) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
}

private class RankingAdapter(private val onClick: (RankingEntry) -> Unit) :
    ListAdapter<RankingEntry, RankingAdapter.Holder>(
        object : DiffUtil.ItemCallback<RankingEntry>() {
            override fun areItemsTheSame(oldItem: RankingEntry, newItem: RankingEntry) =
                oldItem.music.uid == newItem.music.uid

            override fun areContentsTheSame(oldItem: RankingEntry, newItem: RankingEntry) =
                oldItem == newItem
        }
    ) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemStatsRankingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), position + 1)
    }

    inner class Holder(private val binding: ItemStatsRankingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: RankingEntry, rank: Int) {
            binding.rank.text = java.text.NumberFormat.getIntegerInstance().format(rank)
            binding.title.text = entry.music.name.resolve(itemView.context)
            binding.subtitle.text =
                when (val music = entry.music) {
                    is Song -> {
                        binding.cover.bind(music)
                        music.artists.joinToString { it.name.resolve(itemView.context) }
                    }
                    is Album -> {
                        binding.cover.bind(music)
                        music.artists.joinToString { it.name.resolve(itemView.context) }
                    }
                    is Artist -> {
                        binding.cover.bind(music)
                        ""
                    }
                    else -> ""
                }
            binding.subtitle.isVisible = binding.subtitle.text.isNotEmpty()
            binding.metrics.text =
                itemView.context.getString(
                    R.string.stats_metrics,
                    entry.plays,
                    itemView.context.formatStatsDuration(entry.duration),
                )
            itemView.setOnClickListener { onClick(entry) }
        }
    }
}

internal fun android.content.Context.formatStatsDuration(ms: Long): String =
    if (ms >= 3600000) getString(R.string.fmt_hours_minutes, ms / 3600000, ms / 60000 % 60)
    else getString(R.string.fmt_minutes_seconds, ms / 60000, ms / 1000 % 60)

internal fun Fragment.formatStatsDuration(ms: Long) = requireContext().formatStatsDuration(ms)
