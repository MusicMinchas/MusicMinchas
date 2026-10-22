package com.kike.musicplayer.data.lyrics

import android.util.Log
import com.kike.musicplayer.data.db.LyricsDao
import com.kike.musicplayer.data.db.StoredLyrics
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.lastfm.TrackMatcher
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.abs

/** One line of synced lyrics, shown from [timeMs]. */
data class LyricLine(val timeMs: Long, val text: String)

/** A song's lyrics: [synced] lines when LRCLIB has timings, else [plain] text. */
data class SongLyrics(val synced: List<LyricLine>?, val plain: String?, val instrumental: Boolean) {
    val isEmpty get() = synced.isNullOrEmpty() && plain.isNullOrBlank() && !instrumental
}

/**
 * Lyrics from LRCLIB (https://lrclib.net: free, no key), looked up once per song and kept in the
 * database. Exact lookup by artist, title, album and length first (LRCLIB allows ±2 s), then a
 * search by artist and title, taking the entry closest in length (within 5 s), synced preferred.
 * Songs without lyrics are tried again after a week.
 */
class LyricsRepository(
    private val dao: LyricsDao,
    private val http: OkHttpClient,
    private val moshi: Moshi,
) {
    /** The lyrics of [track]; null when LRCLIB has none (or can't be reached). */
    suspend fun lyrics(track: Track): SongLyrics? = withContext(Dispatchers.IO) {
        dao.get(track.id)?.let { stored ->
            if (stored.found || System.currentTimeMillis() - stored.fetchedAt < RETRY_MS) return@withContext stored.toLyrics()
        }
        val artist = track.artist ?: track.albumArtist ?: return@withContext null
        val durationSec = (track.durationMs / 1000).takeIf { it > 0 }
        val found = try {
            exact(artist, track.title, track.album, durationSec) ?: search(artist, track.title, durationSec)
        } catch (e: IOException) {
            Log.i(TAG, "LRCLIB unreachable: $e")
            return@withContext null // not stored: try again next time
        }
        val stored = StoredLyrics(
            trackId = track.id,
            synced = found?.get("syncedLyrics") as? String,
            plain = found?.get("plainLyrics") as? String,
            instrumental = found?.get("instrumental") as? Boolean ?: false,
            found = found != null,
            fetchedAt = System.currentTimeMillis(),
        )
        dao.upsert(stored)
        stored.toLyrics()
    }

    private fun StoredLyrics.toLyrics(): SongLyrics? =
        if (!found) null else SongLyrics(synced?.let(::parseLrc)?.takeIf { it.isNotEmpty() }, plain, instrumental)
            .takeIf { !it.isEmpty }

    private suspend fun exact(artist: String, title: String, album: String?, durationSec: Long?): Map<*, *>? {
        if (album == null || durationSec == null) return null
        val url = "$BASE/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .addQueryParameter("album_name", album)
            .addQueryParameter("duration", durationSec.toString())
            .build()
        return request(url.toString()) as? Map<*, *>
    }

    private suspend fun search(artist: String, title: String, durationSec: Long?): Map<*, *>? {
        val url = "$BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            // "(feat. …)", "(Remastered)", "- Remastered" and the like are often missing on LRCLIB.
            .addQueryParameter("track_name", cleanTitle(title))
            .build()
        val results = (request(url.toString()) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { TrackMatcher.looseArtist(it["artistName"] as? String ?: "") == TrackMatcher.looseArtist(artist) }
        fun lengthOff(r: Map<*, *>) = durationSec?.let { d -> (r["duration"] as? Number)?.let { abs(it.toDouble() - d) } } ?: 0.0
        return results.filter { lengthOff(it) <= MAX_LENGTH_DIFF_SEC }
            .sortedWith(compareBy({ it["syncedLyrics"] == null }, { lengthOff(it) }))
            .firstOrNull()
    }

    /** JSON body; null for "not found"; retries when LRCLIB is busy (it answers 503 under load). */
    private suspend fun request(url: String): Any? {
        repeat(ATTEMPTS) { attempt ->
            http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                when (response.code) {
                    200 -> return moshi.adapter(Any::class.java).fromJson(response.body.string())
                    404 -> return null
                    429, 503 -> Unit
                    else -> throw IOException("LRCLIB HTTP ${response.code}")
                }
            }
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
        throw IOException("LRCLIB busy")
    }

    companion object {
        private const val TAG = "Lyrics"
        private const val BASE = "https://lrclib.net/api"
        private const val USER_AGENT = "MusicMinchas (personal Android app)"
        private const val RETRY_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_LENGTH_DIFF_SEC = 5
        private const val ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_500L

        private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

        /** LRC text ("[01:23.45] line", several tags per line allowed) as lines sorted by time. */
        fun parseLrc(lrc: String): List<LyricLine> =
            lrc.lineSequence().flatMap { raw ->
                val tags = TIME_TAG.findAll(raw).toList()
                if (tags.isEmpty() || tags.first().range.first != 0) return@flatMap emptySequence()
                val text = raw.substring(tags.last().range.last + 1).trim()
                tags.asSequence().map { m ->
                    val (min, sec, frac) = m.destructured
                    val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                    LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + ms, text)
                }
            }.sortedBy { it.timeMs }.toList()

        /** Index of the line being sung at [positionMs], or -1 before the first. */
        fun currentLine(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }

        fun cleanTitle(title: String): String =
            title.replace(Regex("\\s*[(\\[][^)\\]]*(feat|remaster|bonus|version|live|deluxe)[^)\\]]*[)\\]]|\\s*-\\s*(remaster|live|deluxe|bonus|version).*", RegexOption.IGNORE_CASE), "").trim()
    }
}
