/*
 * Copyright (c) 2026 Auxio Project
 * SearchSettingsMigrationTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.search.SearchSettingsImpl
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SearchSettingsMigrationTest {
    @Test
    fun actualPreferencesAndCustomTabSurviveAllStartupMigrations() {
        val directory = System.getProperty("auxio.migrationFixtures")
        org.junit.Assume.assumeNotNull(directory)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source =
            java.io.File(
                requireNotNull(directory),
                "ce/shared_prefs/org.oxycblt.auxio_preferences.xml",
            )
        val target =
            java.io.File(
                context.applicationInfo.dataDir,
                "shared_prefs/${context.packageName}_preferences.xml",
            )
        target.parentFile!!.mkdirs()
        source.copyTo(target)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val before = prefs.all.toMap()
        check(before.isNotEmpty())
        repeat(2) {
            org.oxycblt.auxio.image.ImageSettingsImpl(context).migrate()
            org.oxycblt.auxio.playback.PlaybackSettingsImpl(context).migrate()
            org.oxycblt.auxio.ui.UISettingsImpl(context).migrate()
            org.oxycblt.auxio.home.HomeSettingsImpl(context).migrate()
            SearchSettingsImpl(context).migrate()
            assertEquals(before, prefs.all)
            check(
                org.oxycblt.auxio.home.HomeSettingsImpl(context).homeTabs.any {
                    it.type == MusicType.STATS
                }
            )
        }
    }

    @Test
    fun oldFilterMigratesOnceAndRetainsOriginalAndUnrelatedPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs
            .edit()
            .putInt("KEY_SEARCH_FILTER", MusicType.ALBUMS.intCode)
            .putString("unrelated-custom-setting", "keep")
            .commit()
        val settings = SearchSettingsImpl(context)
        settings.migrate()
        assertEquals(setOf(MusicType.ALBUMS), settings.filters)
        settings.filters = setOf(MusicType.SONGS, MusicType.ARTISTS)
        SearchSettingsImpl(context).migrate()
        assertEquals(setOf(MusicType.SONGS, MusicType.ARTISTS), SearchSettingsImpl(context).filters)
        assertEquals(MusicType.ALBUMS.intCode, prefs.getInt("KEY_SEARCH_FILTER", 0))
        assertEquals("keep", prefs.getString("unrelated-custom-setting", null))
    }
}
