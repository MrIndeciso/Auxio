/*
 * Copyright (c) 2026 Auxio Project
 * PersistedMusicMigrationTest.kt is part of Auxio.
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
 
package org.oxycblt.musikr

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.verification.DatabaseFixture
import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.cache.db.CacheDatabase
import org.oxycblt.musikr.cache.db.DBCache
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.playlist.db.PlaylistDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PersistedMusicMigrationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun actualPlaylistsSurviveMigrationEditsAndReopen() {
        val file = DatabaseFixture.copy(context, "user_music.db")
        val before = DatabaseFixture.original(file)
        repeat(2) {
            val room =
                Room.databaseBuilder(context, PlaylistDatabase::class.java, file.name)
                    .addMigrations(
                        PlaylistDatabase.MIGRATION_30_76,
                        PlaylistDatabase.MIGRATION_75_76,
                    )
                    .allowMainThreadQueries()
                    .build()
            try {
                val db = room.openHelper.writableDatabase
                assertEquals(76, db.version)
                assertEquals(before, DatabaseFixture.dump { db.query(it) })
                DatabaseFixture.integrity { db.query(it) }
                runBlocking {
                    val playlists = room.playlistDao().readRawPlaylists()
                    assertEquals(before.getValue("PlaylistInfo").values.sum(), playlists.size)
                    assertEquals(
                        before.getValue("PlaylistSongCrossRef").values.sum(),
                        playlists.sumOf { it.songs.size },
                    )
                    // Reinsert identical metadata through the public DAO to check UID binding.
                    val info = playlists.first().playlistInfo
                    room.playlistDao().replacePlaylistInfo(info)
                    assertEquals(
                        info,
                        room
                            .playlistDao()
                            .readRawPlaylists()
                            .first { p -> p.playlistInfo.playlistUid == info.playlistUid }
                            .playlistInfo,
                    )
                }
                assertEquals(before, DatabaseFixture.dump { db.query(it) })
            } finally {
                room.close()
            }
        }
    }

    @Test
    fun actualCachePreservedAndReparsedWithOriginalAddedDates() {
        val file = DatabaseFixture.copy(context, "music_cache.db")
        val before = DatabaseFixture.original(file).getValue("CachedSongData")
        repeat(2) {
            val room =
                Room.databaseBuilder(context, CacheDatabase::class.java, file.name)
                    .addMigrations(CacheDatabase.MIGRATION_67_71, CacheDatabase.MIGRATION_70_71)
                    .allowMainThreadQueries()
                    .build()
            try {
                val db = room.openHelper.writableDatabase
                assertEquals(71, db.version)
                val after = DatabaseFixture.dump { db.query(it) }
                assertEquals(before, after.getValue("AuxioLegacyCachedSongData67"))
                val current = after.getValue("CachedFileData")
                assertEquals(
                    before,
                    current.entries.associate { (row, count) -> row.dropLast(1) to count },
                )
                assertTrue(
                    current.keys.all {
                        it.last() == "${android.database.Cursor.FIELD_TYPE_INTEGER}:0"
                    }
                )
                DatabaseFixture.integrity { db.query(it) }
                runBlocking {
                    val record = room.readDao().selectAllSongs().first()
                    val songFile = mockk<File>()
                    every { songFile.uri } returns record.uri
                    every { songFile.modifiedMs } returns record.modifiedMs
                    assertEquals(
                        CacheResult.Stale(songFile, record.addedMs),
                        DBCache.from(room).read(songFile),
                    )
                }
            } finally {
                room.close()
            }
        }
    }

    private fun helper() =
        FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                old: Int,
                                new: Int,
                            ) {}
                        }
                    )
                    .build()
            )

    @Test
    fun uidCollisionRollsBackRatherThanReplacingHistory() {
        helper().use { helper ->
            val db = helper.writableDatabase
            val old = "org.oxycblt.auxio:a10b-00000000-0000-0000-0000-000000000001"
            val compact = "uas00000000-0000-0000-0000-000000000001"
            db.execSQL(
                "CREATE TABLE SongStats(songUid TEXT PRIMARY KEY NOT NULL, playCount INTEGER NOT NULL)"
            )
            db.execSQL("INSERT INTO SongStats VALUES(?, 17)", arrayOf(old))
            db.execSQL("INSERT INTO SongStats VALUES(?, 23)", arrayOf(compact))
            val before = DatabaseFixture.dump { db.query(it) }
            db.beginTransaction()
            try {
                migrateMusicUids(db, "SongStats", "songUid")
                fail("A collision must abort")
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
                // Room wraps migration in this same transaction boundary.
            } finally {
                db.endTransaction()
            }
            assertEquals(before, DatabaseFixture.dump { db.query(it) })
        }
    }

    @Test
    fun invalidUidRollsBackRatherThanDroppingRecords() {
        helper().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE PlayEvent(id INTEGER PRIMARY KEY, songUid TEXT NOT NULL)")
            db.execSQL("INSERT INTO PlayEvent VALUES(1, 'broken-uid')")
            val before = DatabaseFixture.dump { db.query(it) }
            db.beginTransaction()
            try {
                migrateMusicUids(db, "PlayEvent", "songUid")
                fail("An invalid UID must abort")
            } catch (_: IllegalArgumentException) {} finally {
                db.endTransaction()
            }
            assertEquals(before, DatabaseFixture.dump { db.query(it) })
        }
    }
}
