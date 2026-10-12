package com.kike.musicplayer.data.metadata

import android.util.Log
import com.kike.musicplayer.data.db.ArtistImage
import com.kike.musicplayer.data.db.ArtistImageDao
import com.kike.musicplayer.data.db.ArtistInfoDao
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale

/**
 * Artist photos for the artist page, looked up once and remembered: Deezer first (no key; square
 * press photos, found for 102 of 106 artists in this library), then Wikidata's image of the
 * artist's MusicBrainz entry (Wikimedia Commons, free licence). Last.fm no longer serves artist
 * photos. Artists without one are tried again after a month.
 */
class ArtistImages(
    private val dao: ArtistImageDao,
    private val artistInfo: ArtistInfoDao,
    private val http: OkHttpClient,
    private val moshi: Moshi,
) {
    private val mutex = Mutex()

    /** The image URL for [artist], or null when none is known. */
    suspend fun imageFor(artist: String): String? = withContext(Dispatchers.IO) {
        val key = artist.lowercase(Locale.ROOT)
        dao.get(key)?.let { cached ->
            if (cached.url != null || System.currentTimeMillis() - cached.checkedAt < RETRY_MS) return@withContext cached.url
        }
        mutex.withLock {
            val url = runCatching { deezer(artist) }.onFailure { Log.i(TAG, "Deezer: $it") }.getOrNull()
                ?: runCatching { wikidata(key) }.onFailure { Log.i(TAG, "Wikidata: $it") }.getOrNull()
            dao.upsert(ArtistImage(key, url, System.currentTimeMillis()))
            url
        }
    }

    /** Deezer's artist search; only an exact name match, and not Deezer's empty default picture. */
    private fun deezer(artist: String): String? {
        val url = "https://api.deezer.com/search/artist".toHttpUrl().newBuilder()
            .addQueryParameter("q", artist).addQueryParameter("limit", "5").build()
        val data = json(url.toString())["data"] as? List<*> ?: return null
        return data.asSequence().mapNotNull { it as? Map<*, *> }
            .firstOrNull { comparable(it["name"] as? String ?: "") == comparable(artist) }
            ?.let { it["picture_xl"] as? String }
            ?.takeIf { "/artist//" !in it }
    }

    /** The image (P18) of the Wikidata item with this MusicBrainz artist ID (P434), scaled by Commons. */
    private suspend fun wikidata(key: String): String? {
        val mbid = artistInfo.get(key)?.mbid ?: return null
        val query = "SELECT ?img WHERE { ?a wdt:P434 \"$mbid\" . ?a wdt:P18 ?img } LIMIT 1"
        val url = "https://query.wikidata.org/sparql".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("format", "json").build()
        val bindings = (json(url.toString())["results"] as? Map<*, *>)?.get("bindings") as? List<*> ?: return null
        val img = ((bindings.firstOrNull() as? Map<*, *>)?.get("img") as? Map<*, *>)?.get("value") as? String ?: return null
        return img.replace("http://", "https://") + "?width=1000"
    }

    private fun json(url: String): Map<*, *> =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            moshi.adapter(Map::class.java).fromJson(response.body.string()) ?: emptyMap<Any, Any>()
        }

    private fun comparable(s: String) =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")

    private companion object {
        const val TAG = "ArtistImages"
        const val RETRY_MS = 30L * 24 * 60 * 60 * 1000
    }
}
