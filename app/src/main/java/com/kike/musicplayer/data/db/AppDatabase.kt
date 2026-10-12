package com.kike.musicplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, QueueItem::class, SessionState::class, PlayHistory::class, ArtistInfo::class, Favourite::class, LastFmOutboxItem::class, LastFmScrobble::class, ReplacedTrack::class],
    version = 12,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun queue(): QueueDao
    abstract fun history(): HistoryDao
    abstract fun artistInfo(): ArtistInfoDao
    abstract fun favourites(): FavouriteDao
    abstract fun lastFmOutbox(): LastFmOutboxDao
    abstract fun lastFmScrobbles(): LastFmScrobbleDao
    abstract fun replacements(): ReplacementDao

    companion object {
        /** Adds the play history. Real migrations keep the scanned library (tags, covers) intact. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`trackId` TEXT NOT NULL, `playedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_playedAt` ON `play_history` (`playedAt`)")
            }
        }

        /** Listening time and the 50% "counted" flag per play; artist genres for the recommender. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `play_history` ADD COLUMN `listenedMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `play_history` ADD COLUMN `counted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_info` (`name` TEXT NOT NULL, `mbid` TEXT, `tags` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`name`))"
                )
            }
        }

        /** The genre tag from the files; existing tracks get it from a background backfill. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `genre` TEXT")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `genreRead` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `queue_items` ADD COLUMN `shufflePosition` INTEGER")
            }
        }

        /** MP3s with ID3v2.2 tags had their genre missed (TCO frame): read their genres again. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `tracks` SET `genreRead` = 0 WHERE `genre` IS NULL AND `mimeType` = 'audio/mpeg'")
            }
        }

        /** ...and again, now that ID3v1 genre numbers above 79 ("(91)" = Gothic Rock) are known. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `tracks` SET `genreRead` = 0 WHERE `genre` IS NULL AND `mimeType` = 'audio/mpeg'")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favourites` (`trackId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lastfm_outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`action` TEXT NOT NULL, `artist` TEXT NOT NULL, `track` TEXT NOT NULL, `album` TEXT, " +
                        "`albumArtist` TEXT, `durationSec` INTEGER, `timestamp` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lastfm_scrobbles` (`user` TEXT NOT NULL, `uts` INTEGER NOT NULL, " +
                        "`artist` TEXT NOT NULL, `track` TEXT NOT NULL, `album` TEXT, `mbid` TEXT, `trackId` TEXT, " +
                        "PRIMARY KEY(`user`, `uts`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lastfm_scrobbles_trackId` ON `lastfm_scrobbles` (`trackId`)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `localUri` TEXT")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadState` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `checksum` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `replaced_tracks` (`oldId` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`artist` TEXT, `albumArtist` TEXT, `album` TEXT, `title` TEXT NOT NULL, `trackNumber` INTEGER, " +
                        "`discNumber` INTEGER, `localUri` TEXT, `removedAt` INTEGER NOT NULL, PRIMARY KEY(`oldId`))"
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "music.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .build()
    }
}
