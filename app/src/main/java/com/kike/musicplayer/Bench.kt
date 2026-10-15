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
