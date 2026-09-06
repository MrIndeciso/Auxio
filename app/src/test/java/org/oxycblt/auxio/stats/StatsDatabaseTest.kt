/*
 * Copyright (c) 2026 Auxio Project
 * StatsDatabaseTest.kt is part of Auxio.
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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.musikr.Music
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StatsDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db =
        Room.inMemoryDatabaseBuilder(context, StatsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val dao = db.statsDao()
    private val uid =
        requireNotNull(Music.UID.fromString("uas00000000-0000-0000-0000-000000000001"))

    private fun snapshot(duration: Long = 8000) =
        SessionSnapshot(UUID.randomUUID().toString(), uid, 123456, duration)

    @After
    fun close() {
        db.close()
    }

    @Test
    fun retriesNeverDuplicateEventAndAggregate() = runBlocking {
        val first = snapshot()
        repeat(5) { dao.checkpoint(first) }
        repeat(5) { dao.checkpoint(first.copy(listenedMs = 13000)) }
        dao.checkpoint(first.copy(listenedMs = 5000))
        assertEquals(1, dao.getAllPlayEvents().size)
        assertEquals(13000L, dao.getAllPlayEvents().single().listenTimeMs)
        assertEquals(SongStats(uid, 1, 13000, 123456), dao.getSongStats(uid))
    }

    @Test
    fun qualificationAndEventInsertionRollbackTogetherOnFailure() = runBlocking {
        val first = snapshot()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stats BEFORE INSERT ON SongStats BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        try {
            dao.checkpoint(first)
            fail("Expected failure")
        } catch (_: android.database.sqlite.SQLiteException) {}
        assertTrue(dao.getAllPlayEvents().isEmpty())
        assertNull(dao.session(first.attemptId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_stats")
        dao.checkpoint(first)
        assertEquals(1, dao.getAllPlayEvents().size)
    }

    @Test
    fun activeEditKeepsTimestampAndAddsOnlyFutureListening() = runBlocking {
        val first = snapshot()
        dao.checkpoint(first)
        val id = dao.getAllPlayEvents().single().id
        dao.edit(id, 42, 1000)
        dao.checkpoint(first.copy(listenedMs = 13000))
        val event = dao.getAllPlayEvents().single()
        assertEquals(42L, event.timestamp)
        assertEquals(6000L, event.listenTimeMs)
        assertEquals(SongStats(uid, 1, 6000, 42), dao.getSongStats(uid))
    }

    @Test
    fun activeDeleteSurvivesRetriesAndReopen() = runBlocking {
        val name = "sessions-${UUID.randomUUID()}"
        var room = Room.databaseBuilder(context, StatsDatabase::class.java, name).build()
        val first = snapshot()
        room.statsDao().checkpoint(first)
        room.statsDao().delete(room.statsDao().getAllPlayEvents().single().id)
        room.close()
        room = Room.databaseBuilder(context, StatsDatabase::class.java, name).build()
        room.statsDao().checkpoint(first.copy(listenedMs = 18000))
        assertTrue(room.statsDao().getAllPlayEvents().isEmpty())
        assertTrue(room.statsDao().session(first.attemptId)!!.suppressed)
        room.close()
    }

    @Test
    fun concurrentCheckpointsAndEditRemainConsistent() = runBlocking {
        val first = snapshot()
        dao.checkpoint(first)
        val id = dao.getAllPlayEvents().single().id
        coroutineScope {
            repeat(10) { n ->
                launch(Dispatchers.IO) { dao.checkpoint(first.copy(listenedMs = 9000 + n * 1000L)) }
            }
            launch(Dispatchers.IO) { dao.edit(id, 42, 2000) }
        }
        val event = dao.getAllPlayEvents().single()
        assertEquals(SongStats(uid, 1, event.listenTimeMs, 42), dao.getSongStats(uid))
    }

    @Test
    fun preexistingInconsistenciesArePreservedAsDeltas() = runBlocking {
        dao.insertOrUpdateStats(SongStats(uid, 9, 99999, 1))
        dao.checkpoint(snapshot())
        assertEquals(10L, dao.getSongStats(uid)!!.playCount)
        assertEquals(107999L, dao.getSongStats(uid)!!.totalListenTimeMs)
        assertEquals(1, dao.getAllPlayEvents().size)
    }

    @Test
    fun historyUsesStableOrderExclusiveRangeAndIncrementalLimit() = runBlocking {
        repeat(60) {
            dao.insertPlayEvent(PlayEvent(songUid = uid, timestamp = 100, listenTimeMs = 8000))
        }
        dao.insertPlayEvent(PlayEvent(songUid = uid, timestamp = 200, listenTimeMs = 8000))
        val page = dao.observeHistory(100, 200, null, false, "", 51).first()
        assertEquals(51, page.size)
        assertEquals(60L, page.first().id)
        assertTrue(dao.observeHistory(100, 200, null, true, ",$uid,", 51).first().isEmpty())
    }
}
