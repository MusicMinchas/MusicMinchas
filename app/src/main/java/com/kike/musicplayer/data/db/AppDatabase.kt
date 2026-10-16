package com.kike.musicplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, QueueItem::class, SessionState::class, PlayHistory::class, ArtistInfo::class, Favourite::class, LastFmOutboxItem::class, LastFmScrobble::class, ReplacedTrack::class, ArtistImage::class, StoredLyrics::class, UserPlaylist::class, PlaylistTrack::class, FollowedArtist::class, DiscoverItem::class,
        AlbumIndex::class, ArtistIndex::class, GenreIndex::class, IndexDirty::class,
        TrackFts::class, AlbumFts::class, ArtistFts::class],
    version = 19,
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
    abstract fun artistImages(): ArtistImageDao
    abstract fun lyrics(): LyricsDao
    abstract fun playlists(): PlaylistDao
    abstract fun discover(): DiscoverDao
    abstract fun libraryIndex(): LibraryIndexDao
    abstract fun browse(): BrowseDao

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

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_images` (`name` TEXT NOT NULL, `url` TEXT, " +
                        "`checkedAt` INTEGER NOT NULL, PRIMARY KEY(`name`))"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lyrics` (`trackId` TEXT NOT NULL, `synced` TEXT, `plain` TEXT, " +
                        "`instrumental` INTEGER NOT NULL, `found` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))"
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "`trackId` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `position`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_trackId` ON `playlist_tracks` (`trackId`)")
            }
        }

        /** Followed artists and what the Discover tab found for them. */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `followed_artists` (`name` TEXT NOT NULL, `followedAt` INTEGER NOT NULL, " +
                        "`deezerId` INTEGER, `ticketmasterId` TEXT, PRIMARY KEY(`name`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `discover_items` (`id` TEXT NOT NULL, `kind` INTEGER NOT NULL, `artist` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `detail` TEXT, `date` INTEGER NOT NULL, `imageUrl` TEXT, `url` TEXT, " +
                        "`upcoming` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_discover_items_artist` ON `discover_items` (`artist`)")
            }
        }

        /**
         * Album, artist and genre summaries for large libraries: index columns on tracks, the
         * summary tables, and every song queued for LibraryIndexer to fill them in.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("indexAlbum", "indexArtist", "indexGenre", "indexTitle")) {
                    db.execSQL("ALTER TABLE `tracks` ADD COLUMN `$column` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_$column` ON `tracks` (`$column`)")
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_index` (`key` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                        "`year` INTEGER, `artworkUri` TEXT, `trackCount` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`sortKey` TEXT NOT NULL, PRIMARY KEY(`key`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_index_sortKey` ON `album_index` (`sortKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_index_artist` ON `album_index` (`artist`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_index` (`name` TEXT NOT NULL, `trackCount` INTEGER NOT NULL, " +
                        "`albumCount` INTEGER NOT NULL, `sortKey` TEXT NOT NULL, PRIMARY KEY(`name`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_index_sortKey` ON `artist_index` (`sortKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `genre_index` (`key` TEXT NOT NULL, `name` TEXT NOT NULL, `songCount` INTEGER NOT NULL, " +
                        "`artistCount` INTEGER NOT NULL, `albumCount` INTEGER NOT NULL, `sortKey` TEXT NOT NULL, `artworkUri` TEXT, PRIMARY KEY(`key`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_genre_index_sortKey` ON `genre_index` (`sortKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `index_dirty` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT, " +
                        "`oldAlbum` TEXT, `oldArtist` TEXT, `oldGenre` TEXT)"
                )
                for ((table, column) in listOf("tracks_fts" to "title", "album_fts" to "title", "artist_fts" to "name")) {
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `$table` USING FTS4(`$column` TEXT NOT NULL, tokenize=unicode61)")
                }
                createIndexTriggers(db)
                db.execSQL("INSERT INTO index_dirty(trackId) SELECT id FROM tracks")
                db.execSQL("INSERT INTO tracks_fts(docid, title) SELECT rowid, title FROM tracks")
            }
        }

        /**
         * Songs of a Subsonic server are kept apart from the user's own (scope): the summaries get
         * a scope in their key and are rebuilt from scratch, which takes seconds for a phone library.
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `scope` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_scope_indexTitle` ON `tracks` (`scope`, `indexTitle`)")
                for (table in listOf("album_index", "artist_index", "genre_index")) db.execSQL("DROP TABLE IF EXISTS `$table`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_index` (`key` TEXT NOT NULL, `scope` INTEGER NOT NULL DEFAULT 0, `title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, `year` INTEGER, `artworkUri` TEXT, `trackCount` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`sortKey` TEXT NOT NULL, PRIMARY KEY(`key`, `scope`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_index_sortKey` ON `album_index` (`sortKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_index_artist` ON `album_index` (`artist`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_index` (`name` TEXT NOT NULL, `scope` INTEGER NOT NULL DEFAULT 0, " +
                        "`trackCount` INTEGER NOT NULL, `albumCount` INTEGER NOT NULL, `sortKey` TEXT NOT NULL, PRIMARY KEY(`name`, `scope`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_index_sortKey` ON `artist_index` (`sortKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `genre_index` (`key` TEXT NOT NULL, `scope` INTEGER NOT NULL DEFAULT 0, `name` TEXT NOT NULL, " +
                        "`songCount` INTEGER NOT NULL, `artistCount` INTEGER NOT NULL, `albumCount` INTEGER NOT NULL, `sortKey` TEXT NOT NULL, " +
                        "`artworkUri` TEXT, PRIMARY KEY(`key`, `scope`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_genre_index_sortKey` ON `genre_index` (`sortKey`)")
                // The summaries' search rows went with them; the triggers refill them as they're rebuilt.
                db.execSQL("DELETE FROM album_fts")
                db.execSQL("DELETE FROM artist_fts")
                createIndexTriggers(db)
                db.execSQL("INSERT INTO index_dirty(trackId) SELECT id FROM tracks")
            }
        }

        /** The songs' search table also holds artist and album, so a search can mix them with the title. */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (t in listOf("insert", "update", "delete")) db.execSQL("DROP TRIGGER IF EXISTS tracks_fts_$t")
                db.execSQL("DROP TABLE IF EXISTS tracks_fts")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `tracks_fts` USING FTS4(`title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                        "`album` TEXT NOT NULL, tokenize=unicode61)"
                )
                db.execSQL(
                    "INSERT INTO tracks_fts(docid, title, artist, album) SELECT rowid, title, " +
                        "TRIM(IFNULL(artist, '') || ' ' || IFNULL(albumArtist, '')), IFNULL(album, '') FROM tracks"
                )
                createIndexTriggers(db)
            }
        }

        /**
         * Queue every added, changed (in a column the summaries use) or removed song for
         * LibraryIndexer, whatever code changed it. Not part of Room's schema, so created on every open.
         */
        private fun createIndexTriggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS index_track_insert AFTER INSERT ON tracks BEGIN " +
                    "INSERT INTO index_dirty(trackId) VALUES (NEW.id); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS index_track_update AFTER UPDATE OF title, album, albumArtist, artist, genre, year, " +
                    "artworkUri, trackNumber, discNumber, durationMs ON tracks BEGIN " +
                    "INSERT INTO index_dirty(trackId, oldAlbum, oldArtist, oldGenre) " +
                    "VALUES (NEW.id, OLD.indexAlbum, OLD.indexArtist, OLD.indexGenre); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS index_track_delete AFTER DELETE ON tracks BEGIN " +
                    "INSERT INTO index_dirty(oldAlbum, oldArtist, oldGenre) " +
                    "VALUES (OLD.indexAlbum, OLD.indexArtist, OLD.indexGenre); END"
            )
            // The songs' search table: title, artist (with album artist) and album.
            val songRow = "NEW.rowid, NEW.title, TRIM(IFNULL(NEW.artist, '') || ' ' || IFNULL(NEW.albumArtist, '')), IFNULL(NEW.album, '')"
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS tracks_fts_insert AFTER INSERT ON tracks BEGIN " +
                    "INSERT INTO tracks_fts(docid, title, artist, album) VALUES ($songRow); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS tracks_fts_update AFTER UPDATE OF title, artist, albumArtist, album ON tracks BEGIN " +
                    "DELETE FROM tracks_fts WHERE docid = OLD.rowid; INSERT INTO tracks_fts(docid, title, artist, album) VALUES ($songRow); END"
            )
            db.execSQL("CREATE TRIGGER IF NOT EXISTS tracks_fts_delete AFTER DELETE ON tracks BEGIN DELETE FROM tracks_fts WHERE docid = OLD.rowid; END")
            // The summaries' search tables follow album titles and artist names.
            for ((table, fts, column) in listOf(
                Triple("album_index", "album_fts", "title"), Triple("artist_index", "artist_fts", "name"),
            )) {
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS ${fts}_insert AFTER INSERT ON $table BEGIN " +
                        "INSERT INTO $fts(docid, $column) VALUES (NEW.rowid, NEW.$column); END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS ${fts}_update AFTER UPDATE OF $column ON $table BEGIN " +
                        "DELETE FROM $fts WHERE docid = OLD.rowid; INSERT INTO $fts(docid, $column) VALUES (NEW.rowid, NEW.$column); END"
                )
                db.execSQL("CREATE TRIGGER IF NOT EXISTS ${fts}_delete AFTER DELETE ON $table BEGIN DELETE FROM $fts WHERE docid = OLD.rowid; END")
            }
        }

        fun create(context: Context, name: String = "music.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) = createIndexTriggers(db)
                })
                .build()
    }
}
