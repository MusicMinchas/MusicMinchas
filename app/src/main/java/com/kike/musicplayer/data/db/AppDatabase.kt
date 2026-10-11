package com.kike.musicplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, QueueItem::class, SessionState::class, PlayHistory::class, ArtistInfo::class],
    version = 7,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun queue(): QueueDao
    abstract fun history(): HistoryDao
    abstract fun artistInfo(): ArtistInfoDao

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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "music.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
