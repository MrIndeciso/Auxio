/*
 * Copyright (c) 2026 Auxio Project
 * DatabaseFixture.kt is part of Auxio.
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
 
package org.oxycblt.auxio.verification

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.oxycblt.musikr.Music

/**
 * Copies the private fixture before opening anything. The master backup is never a database path.
 */
object DatabaseFixture {
    fun copy(context: Context, name: String): File {
        val directory = System.getProperty("auxio.migrationFixtures")
        assumeNotNull(directory)
        val source = File(requireNotNull(directory), "ce/databases/$name")
        check(source.isFile) { "Missing required migration fixture: $name" }
        val destination = context.getDatabasePath("migration-${UUID.randomUUID()}-$name")
        destination.parentFile!!.mkdirs()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val input = File(source.path + suffix)
            if (input.exists()) input.copyTo(File(destination.path + suffix), overwrite = false)
        }
        return destination
    }

    fun original(file: File): Map<String, Map<List<String>, Int>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            dump(normalizeUids = true) { db.rawQuery(it, null) }
        }

    /** Compare complete row multisets, preserving duplicates and types as well as row counts. */
    fun dump(
        normalizeUids: Boolean = false,
        query: (String) -> Cursor,
    ): Map<String, Map<List<String>, Int>> {
        val tables =
            query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','android_metadata')"
                )
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table ->
            query("SELECT * FROM `${table.replace("`", "``")}`").use { c ->
                buildList {
                        while (c.moveToNext()) {
                            add(
                                (0 until c.columnCount).map { index ->
                                    val type = c.getType(index)
                                    val value =
                                        when (type) {
                                            Cursor.FIELD_TYPE_NULL -> ""
                                            Cursor.FIELD_TYPE_BLOB ->
                                                Base64.encodeToString(
                                                    c.getBlob(index),
                                                    Base64.NO_WRAP,
                                                )
                                            else -> c.getString(index)
                                        }
                                    val column = c.getColumnName(index)
                                    val canonical =
                                        if (
                                            normalizeUids &&
                                                type != Cursor.FIELD_TYPE_NULL &&
                                                (column == "uid" || column.endsWith("Uid"))
                                        ) {
                                            requireNotNull(Music.UID.fromString(value)).toString()
                                        } else value
                                    "$type:$canonical"
                                }
                            )
                        }
                    }
                    .groupingBy { it }
                    .eachCount()
            }
        }
    }

    fun integrity(query: (String) -> Cursor) {
        query("PRAGMA integrity_check").use { c ->
            check(c.moveToFirst())
            assertEquals("ok", c.getString(0))
            assertEquals(false, c.moveToNext())
        }
        query("PRAGMA foreign_key_check").use { c -> assertEquals(false, c.moveToNext()) }
    }
}
