/*
 * Copyright (c) 2026 Auxio Project
 * StatsLayoutTest.kt is part of Auxio.
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
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.kizitonwose.calendar.core.*
import com.kizitonwose.calendar.view.*
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemStatsDashboardBinding
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host layout checks; they do not substitute for TalkBack or signed APK testing on a device. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class StatsLayoutTest {
    @Test
    fun dashboardRecyclerOwnsLayoutBeforeInflatingHeader() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(app, R.style.Theme_Auxio_App)
        val inflater = LayoutInflater.from(context)
        val screen = org.oxycblt.auxio.databinding.FragmentStatsBinding.inflate(inflater)
        // onViewCreated inflates this before it installs the ConcatAdapter. Android asks the
        // RecyclerView to generate LayoutParams even when attachToParent is false.
        val header = ItemStatsDashboardBinding.inflate(inflater, screen.statsRecycler, false)
        assertNotNull(screen.statsRecycler.layoutManager)
        assertTrue(
            header.root.layoutParams is androidx.recyclerview.widget.RecyclerView.LayoutParams
        )
    }

    @Test
    fun dashboardInflatesAndMeasuresInBothThemesAtLargeText() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            for (scale in listOf(1f, 2f)) {
                val config =
                    Configuration(app.resources.configuration).apply {
                        uiMode = night
                        fontScale = scale
                    }
                val context =
                    ContextThemeWrapper(
                        app.createConfigurationContext(config),
                        R.style.Theme_Auxio_App,
                    )
                val binding = ItemStatsDashboardBinding.inflate(LayoutInflater.from(context))
                binding.totalTime.text = "324 h 21 min"
                binding.totalPlays.text = "7,100 plays"
                binding.average.text = "Daily average 1 h 25 min · Weekly average 10 h 1 min"
                binding.calendar.daySize = DaySize.SeventhWidth
                binding.calendar.dayBinder =
                    object : MonthDayBinder<ViewContainer> {
                        override fun create(view: View) = ViewContainer(view)

                        override fun bind(container: ViewContainer, data: CalendarDay) {
                            (container.view as TextView).text = data.date.dayOfMonth.toString()
                        }
                    }
                val month = YearMonth.of(2026, 9)
                binding.calendar.setup(month, month, java.time.DayOfWeek.MONDAY)
                binding.calendar.scrollToMonth(month)
                binding.root.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                binding.root.layout(0, 0, 360, binding.root.measuredHeight)
                assertTrue(binding.root.measuredHeight > 0)
                assertTrue(binding.period.measuredHeight >= 48)
                assertTrue(binding.byPlays.measuredHeight >= 48)
                assertTrue(binding.totalTime.measuredHeight >= binding.totalTime.lineHeight)
                assertTrue(binding.previousMonth.contentDescription.isNotBlank())
                assertEquals(360, binding.root.measuredWidth)
            }
        }
    }
}
