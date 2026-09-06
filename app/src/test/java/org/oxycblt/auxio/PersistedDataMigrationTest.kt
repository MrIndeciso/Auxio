/*
 * Copyright (c) 2026 Auxio Project
 * PersistedDataMigrationTest.kt is part of Auxio.
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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.playback.persist.PersistenceDatabase
import org.oxycblt.auxio.stats.StatsDatabase
import org.oxycblt.auxio.verification.DatabaseFixture
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PersistedDataMigrationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun actualHistorySurvivesMigrationAndReopen() {
        val file = DatabaseFixture.copy(context, "stats.db")
        val before = DatabaseFixture.original(file)
        repeat(2) {
            val room =
                Room.databaseBuilder(context, StatsDatabase::class.java, file.name)
                    .addMigrations(StatsDatabase.MIGRATION_2_3)
                    .allowMainThreadQueries()
                    .build()
            try {
                val db = room.openHelper.writableDatabase
                assertEquals(3, db.version)
                assertEquals(before, DatabaseFixture.dump { db.query(it) })
                DatabaseFixture.integrity { db.query(it) }
                // Exercise parameter binding used by history editing, not only full-table reads.
                db.query("SELECT songUid FROM SongStats LIMIT 1").use { cursor ->
                    check(cursor.moveToFirst())
                    val uid =
                        requireNotNull(org.oxycblt.musikr.Music.UID.fromString(cursor.getString(0)))
                    kotlinx.coroutines.runBlocking {
                        check(room.statsDao().getSongStats(uid) != null)
                        check(room.statsDao().getAllPlayEventsForSong(uid).isNotEmpty())
                    }
                }
            } finally {
                room.close()
            }
        }
    }

    @Test
    fun actualPlaybackAndQueueSurviveMigrationAndReopen() {
        val file = DatabaseFixture.copy(context, "playback_persistence.db")
        val before = DatabaseFixture.original(file)
        repeat(2) {
            val room =
                Room.databaseBuilder(context, PersistenceDatabase::class.java, file.name)
                    .addMigrations(PersistenceDatabase.MIGRATION_38_39)
                    .allowMainThreadQueries()
                    .build()
            try {
                val db = room.openHelper.writableDatabase
                assertEquals(39, db.version)
                assertEquals(before, DatabaseFixture.dump { db.query(it) })
                DatabaseFixture.integrity { db.query(it) }
            } finally {
                room.close()
            }
        }
    }
}
