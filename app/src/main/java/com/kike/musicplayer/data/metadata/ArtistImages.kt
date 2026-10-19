package com.kike.musicplayer.data.metadata

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.kike.musicplayer.data.Settings
import com.kike.musicplayer.data.catalog.CatalogClient
import com.kike.musicplayer.data.db.ArtistImage
import com.kike.musicplayer.data.db.ArtistImageDao
import com.kike.musicplayer.data.db.ArtistInfoDao
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Artist photos for the artist page and Artists tab, looked up dynamically as artists come into
 * view and remembered: Deezer first (no key; square press photos, when enabled in Settings), then
 * Wikidata's image of the artist's MusicBrainz entry (Wikimedia Commons, when MusicBrainz is
 * enabled). Artists without one are tried again after a month.
 */
class ArtistImages(
    private val context: Context,
    private val dao: ArtistImageDao,
    private val artistInfo: ArtistInfoDao,
    private val settings: Settings,
    private val catalog: CatalogClient,
    private val http: OkHttpClient,
    private val moshi: Moshi,
) {
    private val mutex = Mutex()
    private var prefetchJob: Job? = null

    /** Photo URLs by lower-cased artist name, as found so far. */
    val urls: Flow<Map<String, String>> = dao.observeAll()
        .map { list -> list.mapNotNull { i -> i.url?.let { i.name to it } }.toMap() }

    /**
     * Looks up the photos of [artists] not looked up yet and downloads them into the image cache.
     */
    fun prefetch(artists: List<String>, scope: CoroutineScope) {
        if (prefetchJob?.isActive == true || artists.any { it.startsWith("Bench Artist ") }) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            val loader = SingletonImageLoader.get(context)
            for (name in artists) {
                val cached = dao.get(name.lowercase(Locale.ROOT))
                val fresh = cached == null || (cached.url == null && System.currentTimeMillis() - cached.checkedAt >= RETRY_MS)
                val url = if (fresh) imageFor(name).also { delay(PREFETCH_INTERVAL_MS) } else cached?.url
                url?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
            }
        }
    }

    /** The image URL for [artist], or null when none is known. */
    suspend fun imageFor(artist: String): String? = withContext(Dispatchers.IO) {
        if (artist.startsWith("Bench Artist ")) return@withContext null // debug benchmark library
        val key = artist.lowercase(Locale.ROOT)
        dao.get(key)?.let { cached ->
            if (cached.url != null || System.currentTimeMillis() - cached.checkedAt < RETRY_MS) return@withContext cached.url
        }
        val useDeezer = settings.metaDeezer.value
        val useMb = settings.metaMusicBrainz.value
        if (!useDeezer && !useMb) return@withContext null

        mutex.withLock {
            dao.get(key)?.let { cached ->
                if (cached.url != null || System.currentTimeMillis() - cached.checkedAt < RETRY_MS) return@withLock cached.url
            }
            val url = (if (useDeezer) runCatching { catalog.deezerArtistPhoto(artist) }.onFailure { Log.i(TAG, "Deezer: $it") }.getOrNull() else null)
                ?: (if (useMb) runCatching { wikidata(key) }.onFailure { Log.i(TAG, "Wikidata: $it") }.getOrNull() else null)
            dao.upsert(ArtistImage(key, url, System.currentTimeMillis()))
            url
        }
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

    private companion object {
        const val TAG = "ArtistImages"
        const val RETRY_MS = 30L * 24 * 60 * 60 * 1000
        const val PREFETCH_INTERVAL_MS = 150L
    }
}
