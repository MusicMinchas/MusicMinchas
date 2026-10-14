package com.kike.musicplayer.data

import android.util.Log
import androidx.room.withTransaction
import com.kike.musicplayer.Bench
import com.kike.musicplayer.data.db.AlbumIndex
import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.ArtistIndex
import com.kike.musicplayer.data.db.GenreIndex
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.playback.albumTrackOrder
import com.kike.musicplayer.recommend.UNKNOWN_GENRE
import com.kike.musicplayer.recommend.artistTagMap
import com.kike.musicplayer.recommend.effectiveGenreKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the album, artist and genre summaries (album_index, artist_index, genre_index) in step with
 * the songs, so screens can page through them instead of grouping the whole library in memory.
 *
 * Database triggers queue every song that is added, changed or removed (index_dirty), whichever
 * code changed it. This works through the queue in batches: it works out each queued song's album
 * key, listing artist and genre key (in Kotlin, like the rest of the app, so non-English names
 * group the same way), stores them on the song, and rebuilds the summaries those songs belong to
 * now or belonged to before. Songs without a genre of their own are queued again when artist
 * genre tags arrive from MusicBrainz.
 */
class LibraryIndexer(private val db: AppDatabase, private val scope: CoroutineScope) {
    private val dao = db.libraryIndex()
    private val mutex = Mutex()

    private val _busy = MutableStateFlow(false)
    /** True while queued songs are being indexed. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            dao.observeDirtyCount().filter { it > 0 }.debounce(DEBOUNCE_MS).collect {
                runCatching { runQueue() }.onFailure { Log.w(TAG, "Indexing failed", it) }
            }
        }
        scope.launch {
            db.artistInfo().observeAll().map { artistTagMap(it) }.distinctUntilChanged().drop(1).collect {
                dao.markUntaggedDirty()
            }
        }
    }

    /** Indexes everything queued; returns when the queue is empty. */
    suspend fun runQueue() = mutex.withLock {
        withContext(Dispatchers.IO) {
            _busy.value = true
            try {
                var total = 0
                Bench.time("index queue") {
                    val tags = artistTagMap(db.artistInfo().all())
                    while (true) {
                        val batch = dao.dirty(BATCH)
                        if (batch.isEmpty()) break
                        val albums = HashSet<String>()
                        val artists = HashSet<String>()
                        val genres = HashSet<String>()
                        for (d in batch) {
                            d.oldAlbum?.let(albums::add)
                            d.oldArtist?.let(artists::add)
                            d.oldGenre?.let(genres::add)
                        }
                        db.withTransaction {
                            for (ids in batch.mapNotNull { it.trackId }.distinct().chunked(CHUNK)) {
                                for (t in dao.tracks(ids)) {
                                    val (album, artist, genre) = keysOf(t, tags)
                                    if (album != t.indexAlbum || artist != t.indexArtist || genre != t.indexGenre) {
                                        dao.setIndex(t.id, album, artist, genre)
                                    }
                                    albums += album
                                    artists += artist
                                    genres += genre
                                }
                            }
                            refreshAlbums(albums - "")
                            refreshArtists(artists - "")
                            refreshGenres(genres - "")
                            dao.clearDirty(batch.last().seq)
                        }
                        total += batch.size
                    }
                }
                if (total > 0) Bench.log("indexed $total queued songs")
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun refreshAlbums(keys: Set<String>) {
        for (chunk in keys.chunked(CHUNK)) {
            val byAlbum = dao.tracksOfAlbums(chunk).groupBy { it.indexAlbum }
            val gone = chunk.filter { it !in byAlbum }
            if (gone.isNotEmpty()) dao.deleteAlbums(gone)
            dao.upsertAlbums(byAlbum.map { (key, tracks) -> albumOf(key, tracks) })
        }
    }

    private suspend fun refreshArtists(names: Set<String>) {
        for (chunk in names.chunked(CHUNK)) {
            val counts = dao.artistCounts(chunk).associateBy { it.name }
            val gone = chunk.filter { it !in counts }
            if (gone.isNotEmpty()) dao.deleteArtists(gone)
            dao.upsertArtists(counts.values.map { ArtistIndex(it.name, it.trackCount, it.albumCount, sortKey(it.name, UNKNOWN_ARTIST)) })
        }
    }

    private suspend fun refreshGenres(keys: Set<String>) {
        for (chunk in keys.chunked(CHUNK)) {
            val counts = dao.genreCounts(chunk).associateBy { it.key }
            val gone = chunk.filter { it !in counts }
            if (gone.isNotEmpty()) dao.deleteGenres(gone)
            dao.upsertGenres(counts.values.map { c ->
                val name = genreName(c.key, dao.genreSpelling(c.key))
                GenreIndex(c.key, name, c.songCount, c.artistCount, c.albumCount, (if (c.key == UNKNOWN_GENRE) "1" else "0") + name.lowercase())
            })
        }
    }

    companion object {
        private const val TAG = "LibraryIndexer"
        private const val BATCH = 5000
        /** Below SQLite's limit of bound variables per statement. */
        private const val CHUNK = 500
        private const val DEBOUNCE_MS = 500L
        const val UNKNOWN_ALBUM = "Unknown album"
        const val UNKNOWN_ARTIST = "Unknown artist"

        /**
         * The album key, listing artist and genre key of a song, as the library screens group
         * them; all empty for songs that can't be played (they aren't listed).
         */
        fun keysOf(t: Track, tags: Map<String, List<String>>): Triple<String, String, String> =
            if (t.durationMs < 0) Triple("", "", "")
            else Triple(t.albumKey, t.albumArtist ?: t.artist ?: UNKNOWN_ARTIST, effectiveGenreKey(t, tags) ?: UNKNOWN_GENRE)

        /** An album summary from its songs, as the Albums tab shows it. */
        fun albumOf(key: String, tracks: List<Track>): AlbumIndex {
            val sorted = tracks.sortedWith(albumTrackOrder)
            val first = sorted.first()
            val title = first.album ?: UNKNOWN_ALBUM
            return AlbumIndex(
                key = key,
                title = title,
                artist = first.albumArtist ?: first.artist ?: UNKNOWN_ARTIST,
                year = sorted.firstNotNullOfOrNull { it.year },
                artworkUri = sorted.firstNotNullOfOrNull { it.artworkUri },
                trackCount = tracks.size,
                durationMs = tracks.sumOf { it.durationMs.coerceAtLeast(0) },
                sortKey = sortKey(title, UNKNOWN_ALBUM),
            )
        }

        /** Names like "Unknown album" sort last, the rest by their lower-cased name. */
        fun sortKey(name: String, unknown: String) = (if (name == unknown) "1" else "0") + name.lowercase()

        fun genreName(key: String, spelling: String?): String = when {
            key == UNKNOWN_GENRE -> "Unknown genre"
            spelling != null -> spelling
            else -> key.split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::titlecase) }
        }
    }
}
