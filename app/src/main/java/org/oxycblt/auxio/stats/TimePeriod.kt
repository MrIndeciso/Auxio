/*
 * Copyright (c) 2026 Auxio Project
 * TimePeriod.kt is part of Auxio.
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

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** Half-open local-date ranges use calendar arithmetic, including DST and year boundaries. */
data class StatsDateRange(val first: LocalDate?, val endExclusive: LocalDate?) {
    fun timestamps(zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        (first?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE) to
            (endExclusive?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE)

    fun contains(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val (start, end) = timestamps(zone)
        return (first == null || timestamp >= start) && (endExclusive == null || timestamp < end)
    }
}

enum class TimePeriod {
    ALL_TIME,
    TODAY,
    THIS_YEAR,
    LAST_YEAR,
    LAST_12_MONTHS,
    THIS_MONTH,
    LAST_MONTH,
    THIS_WEEK,
    LAST_WEEK,
    CUSTOM;

    fun range(
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
    ): StatsDateRange {
        val month = today.withDayOfMonth(1)
        val year = today.withDayOfYear(1)
        val week = today.with(WeekFields.of(locale).dayOfWeek(), 1)
        return when (this) {
            ALL_TIME,
            CUSTOM -> StatsDateRange(null, null)
            TODAY -> StatsDateRange(today, today.plusDays(1))
            THIS_YEAR -> StatsDateRange(year, today.plusDays(1))
            LAST_YEAR -> StatsDateRange(year.minusYears(1), year)
            LAST_12_MONTHS -> StatsDateRange(today.minusMonths(12), today.plusDays(1))
            THIS_MONTH -> StatsDateRange(month, today.plusDays(1))
            LAST_MONTH -> StatsDateRange(month.minusMonths(1), month)
            THIS_WEEK -> StatsDateRange(week, today.plusDays(1))
            LAST_WEEK -> StatsDateRange(week.minusWeeks(1), week)
        }
    }

    /** Compatibility with the older inclusive DAO API. */
    fun getTimeRange(): Pair<Long, Long> =
        range().timestamps().let { (start, end) ->
            start to if (end == Long.MAX_VALUE) end else end - 1
        }
}
