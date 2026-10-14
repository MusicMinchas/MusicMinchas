package com.kike.musicplayer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.Favourite
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.Track
import java.io.File
import kotlin.random.Random

/**
 * Debug-only benchmark mode for large libraries: with a file `files/bench` in the app's data
 * (`adb shell run-as com.kike.musicplayer sh -c 'echo 200000 > files/bench'`), the debug build
 * runs on its own database and settings (`bench.db`, `settings-bench`, `lastfm-bench`) filled
 * with synthetic songs, so the real library, plays and accounts are never touched. Remove the
 * file to go back to the real data.
 */
object Bench {
    private const val TAG = "Perf"

    fun size(context: Context): Int? {
        if (!BuildConfig.DEBUG) return null
        val file = File(context.filesDir, "bench")
        if (!file.exists()) return null
        return file.readText().trim().substringBefore(' ').toIntOrNull() ?: 200_000
    }

    /**
     * "200000 indexonly" in the bench file: the screens get no songs, to measure the indexer
     * alone while the screens still group the whole library in memory.
     */
    fun indexOnly(context: Context): Boolean =
        size(context) != null && "indexonly" in File(context.filesDir, "bench").readText()

    /** Runs [block] and logs how long it took (debug builds only; release builds just run it). */
    inline fun <T> time(label: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val start = SystemClock.elapsedRealtime()
        return block().also { log("$label: ${SystemClock.elapsedRealtime() - start} ms") }
    }

    fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    /**
     * Fills an empty benchmark database: [count] songs by count/20 artists on count/10 albums
     * (10 songs each), 40 genres, 20 000 plays over two years and 500 favourites. Fixed seed, so
     * every run measures the same library.
     */
    suspend fun seed(db: AppDatabase, count: Int) {
        if (db.tracks().countAll() > 0) return
        val random = Random(42)
        val genres = List(40) { "Genre ${('A' + it % 26)}${it / 26} Metal" }
        val artists = count / 20
        val words = listOf("Shadow", "Fire", "Night", "Dream", "Storm", "Silent", "Burning", "Eternal", "Lost", "Crimson", "Winter", "Heart")
        fun phrase(n: Int) = List(n) { words[random.nextInt(words.size)] }.joinToString(" ")
        time("seed $count tracks") {
            db.withTransaction {
                val batch = ArrayList<Track>(1000)
                for (i in 0 until count) {
                    val album = i / 10
                    val artist = album % artists
                    batch += Track(
                        id = "bench:$i", source = "bench", uri = "https://example.invalid/rest/stream?id=$i",
                        fileName = "$i.flac", mimeType = "audio/flac", sizeBytes = 30_000_000L + random.nextInt(20_000_000),
                        title = "${phrase(1 + random.nextInt(4))} $i", artist = "Bench Artist $artist",
                        album = "${words[album % words.size]} ${words[album / words.size % words.size]} Album $album",
                        albumArtist = "Bench Artist $artist",
                        trackNumber = i % 10 + 1, discNumber = 1, year = 1960 + album % 66,
                        genre = genres[artist % genres.size], genreRead = true,
                        durationMs = 120_000L + random.nextInt(360_000),
                        artworkUri = null, metadataState = Track.META_DONE,
                        dateAdded = 1_700_000_000_000L + i * 1000L,
                    )
                    if (batch.size == 1000) { db.tracks().upsertAll(batch); batch.clear() }
                }
                if (batch.isNotEmpty()) db.tracks().upsertAll(batch)
                val now = System.currentTimeMillis()
                val played = List(5000) { random.nextInt(count) }
                db.history().insertAll(List(20_000) {
                    PlayHistory(
                        trackId = "bench:${played[random.nextInt(played.size)]}",
                        playedAt = now - random.nextLong(730L * 24 * 3600 * 1000),
                        listenedMs = 200_000, counted = true,
                    )
                })
                db.favourites().insertAll(List(500) { Favourite("bench:${played[it]}", now) })
            }
        }
    }
}

/**
 * Debug check while the screens still group the library in memory: compares the indexer's
 * album, artist and genre summaries with the in-memory ones and logs every difference.
 */
object IndexCheck {
    suspend fun compare(
        db: AppDatabase,
        albums: List<com.kike.musicplayer.ui.Album>,
        artists: List<com.kike.musicplayer.ui.Artist>,
        genres: List<com.kike.musicplayer.ui.Genre>,
    ) {
        if (!BuildConfig.DEBUG) return
        val dao = db.libraryIndex()
        val problems = mutableListOf<String>()
        val ia = dao.allAlbums().associateBy { it.key }
        for (a in albums) {
            val i = ia[a.key]
            if (i == null) problems += "album missing: ${a.title}"
            else if (i.title != a.title || i.artist != a.artist || i.year != a.year || i.artworkUri != a.artworkUri || i.trackCount != a.tracks.size) {
                problems += "album differs: ${a.title} / ${i.title} ${i.artist}/${a.artist} ${i.year}/${a.year} ${i.trackCount}/${a.tracks.size}"
            }
        }
        if (ia.size != albums.size) problems += "albums: ${ia.size} indexed, ${albums.size} in memory"
        val ir = dao.allArtists().associateBy { it.name }
        for (a in artists) {
            val i = ir[a.name]
            if (i == null) problems += "artist missing: ${a.name}"
            else if (i.trackCount != a.tracks.size || i.albumCount != a.albumCount) problems += "artist differs: ${a.name} ${i.trackCount}/${a.tracks.size} ${i.albumCount}/${a.albumCount}"
        }
        if (ir.size != artists.size) problems += "artists: ${ir.size} indexed, ${artists.size} in memory"
        val ig = dao.allGenres().associateBy { it.key }
        for (g in genres) {
            val i = ig[g.key]
            if (i == null) problems += "genre missing: ${g.name}"
            else if (i.songCount != g.songCount || i.artistCount != g.artistCount || i.name != g.name) {
                problems += "genre differs: ${g.name}/${i.name} ${i.songCount}/${g.songCount} ${i.artistCount}/${g.artistCount}"
            }
        }
        if (ig.size != genres.size) problems += "genres: ${ig.size} indexed, ${genres.size} in memory"
        Bench.log("index check: ${albums.size} albums, ${artists.size} artists, ${genres.size} genres, ${problems.size} differences")
        problems.take(30).forEach { Bench.log("  $it") }
    }
}
