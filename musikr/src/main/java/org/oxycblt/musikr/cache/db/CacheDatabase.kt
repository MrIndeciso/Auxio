/*
 * Copyright (c) 2023 Auxio Project
 * CacheDatabase.kt is part of Auxio.
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
 
package org.oxycblt.musikr.cache.db

import android.content.Context
import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.oxycblt.musikr.tag.Date
import org.oxycblt.musikr.util.correctWhitespace
import org.oxycblt.musikr.util.splitEscaped

@Database(entities = [CachedFileData::class], version = 71, exportSchema = false)
internal abstract class CacheDatabase : RoomDatabase() {
    abstract fun readDao(): CacheReadDao

    abstract fun writeDao(): CacheWriteDao

    companion object {
        const val PARSER_REVISION = 1

        // Keep the original cache intact as an archive. Reparse active entries with the new
        // parser (including zero R128 gains) while retaining their original added timestamps.
        val MIGRATION_67_71 =
            object : Migration(67, 71) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `CachedSongData` RENAME TO `AuxioLegacyCachedSongData67`"
                    )
                    db.execSQL(CREATE_FILE_CACHE)
                    db.execSQL(
                        "INSERT INTO `CachedFileData` (" +
                            LEGACY_COLUMNS +
                            ") SELECT " +
                            LEGACY_COLUMNS +
                            " FROM `AuxioLegacyCachedSongData67`"
                    )
                }
            }

        val MIGRATION_70_71 =
            object : Migration(70, 71) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `CachedFileData` ADD COLUMN `parserRevision` INTEGER NOT NULL DEFAULT 0"
                    )
                }
            }

        private const val CREATE_FILE_CACHE =
            "CREATE TABLE `CachedFileData` (`uri` TEXT NOT NULL, `modifiedMs` INTEGER NOT NULL, `addedMs` INTEGER NOT NULL, `mimeType` TEXT, `durationMs` INTEGER, `bitrateKbps` INTEGER, `sampleRateHz` INTEGER, `musicBrainzId` TEXT, `name` TEXT, `sortName` TEXT, `track` INTEGER, `disc` INTEGER, `subtitle` TEXT, `date` TEXT, `albumMusicBrainzId` TEXT, `albumName` TEXT, `albumSortName` TEXT, `releaseTypes` TEXT, `artistMusicBrainzIds` TEXT, `artistNames` TEXT, `artistSortNames` TEXT, `albumArtistMusicBrainzIds` TEXT, `albumArtistNames` TEXT, `albumArtistSortNames` TEXT, `genreNames` TEXT, `replayGainTrackAdjustment` REAL, `replayGainAlbumAdjustment` REAL, `coverId` TEXT, `parserRevision` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`uri`))"
        private const val LEGACY_COLUMNS =
            "`uri`, `modifiedMs`, `addedMs`, `mimeType`, `durationMs`, `bitrateKbps`, `sampleRateHz`, `musicBrainzId`, `name`, `sortName`, `track`, `disc`, `subtitle`, `date`, `albumMusicBrainzId`, `albumName`, `albumSortName`, `releaseTypes`, `artistMusicBrainzIds`, `artistNames`, `artistSortNames`, `albumArtistMusicBrainzIds`, `albumArtistNames`, `albumArtistSortNames`, `genreNames`, `replayGainTrackAdjustment`, `replayGainAlbumAdjustment`, `coverId`"

        fun from(context: Context) =
            Room.databaseBuilder(
                    context.applicationContext,
                    CacheDatabase::class.java,
                    "music_cache.db",
                )
                .addMigrations(MIGRATION_67_71, MIGRATION_70_71)
                .build()
    }
}

@Dao
internal interface CacheReadDao {
    @Query("SELECT * FROM CachedFileData") suspend fun selectAllSongs(): List<CachedFileData>
}

@Dao
internal interface CacheWriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun updateSong(data: CachedFileData)

    @Transaction
    suspend fun deleteExcludingUris(uris: Set<String>) {
        val delete = selectAllUris().toSet() - uris
        for (chunk in delete.chunked(999)) {
            deleteExcludingUriChunk(chunk)
        }
    }

    @Query("SELECT uri FROM CachedFileData") suspend fun selectAllUris(): List<String>

    @Query("DELETE FROM CachedFileData WHERE uri IN (:uris)")
    suspend fun deleteExcludingUriChunk(uris: List<String>)
}

@Entity
@TypeConverters(CachedFileData.Converters::class)
internal data class CachedFileData(
    @PrimaryKey val uri: Uri,
    val modifiedMs: Long,
    val addedMs: Long,
    val mimeType: String?,
    val durationMs: Long?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val musicBrainzId: String?,
    val name: String?,
    val sortName: String?,
    val track: Int?,
    val disc: Int?,
    val subtitle: String?,
    val date: Date?,
    val albumMusicBrainzId: String?,
    val albumName: String?,
    val albumSortName: String?,
    val releaseTypes: List<String>?,
    val artistMusicBrainzIds: List<String>?,
    val artistNames: List<String>?,
    val artistSortNames: List<String>?,
    val albumArtistMusicBrainzIds: List<String>?,
    val albumArtistNames: List<String>?,
    val albumArtistSortNames: List<String>?,
    val genreNames: List<String>?,
    val replayGainTrackAdjustment: Float?,
    val replayGainAlbumAdjustment: Float?,
    val coverId: String?,
    @ColumnInfo(defaultValue = "0") val parserRevision: Int = CacheDatabase.PARSER_REVISION,
) {
    object Converters {
        @TypeConverter
        fun fromMultiValue(values: List<String>) =
            values.joinToString(";") { it.replace(";", "\\;") }

        @TypeConverter
        fun toMultiValue(string: String) = string.splitEscaped { it == ';' }.correctWhitespace()

        @TypeConverter fun fromDate(date: Date?) = date?.toString()

        @TypeConverter fun toDate(string: String?) = string?.let(Date::from)

        @TypeConverter fun toUri(string: String) = Uri.parse(string)

        @TypeConverter fun fromUri(uri: Uri) = uri.toString()
    }
}
