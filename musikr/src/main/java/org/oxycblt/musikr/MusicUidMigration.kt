/*
 * Copyright (c) 2026 Auxio Project
 * MusicUidMigration.kt is part of Auxio.
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

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Canonicalize stored identifiers inside Room's migration transaction. Updates use ABORT, never
 * REPLACE: invalid identifiers or colliding primary keys must preserve the old database and fail
 * the upgrade, rather than merging or deleting historical records.
 */
fun migrateMusicUids(db: SupportSQLiteDatabase, table: String, vararg columns: String) {
    require(table.matches(Regex("[A-Za-z][A-Za-z0-9_]*")))
    for (column in columns) {
        require(column.matches(Regex("[A-Za-z][A-Za-z0-9_]*")))
        val updates =
            db.query("SELECT rowid, `$column` FROM `$table`").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.isNull(1)) continue
                        val old = cursor.getString(1)
                        val uid =
                            requireNotNull(Music.UID.fromString(old)) {
                                "Cannot migrate an invalid UID in $table.$column; original records retained"
                            }
                        val canonical = uid.toString()
                        if (old != canonical) add(cursor.getLong(0) to canonical)
                    }
                }
            }
        for ((row, canonical) in updates) {
            db.execSQL(
                "UPDATE OR ABORT `$table` SET `$column` = ? WHERE rowid = ?",
                arrayOf<Any>(canonical, row),
            )
        }
    }
}
