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
import java.text.Normalizer

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
    fun start(indexedVersion: Int, onReindexQueued: (Int) -> Unit) {
        scope.launch {
            if (indexedVersion < VERSION) {
                dao.markAllDirty()
                onReindexQueued(VERSION)
            }
        }
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

    /**
     * Indexes everything queued; returns when the queue is empty. First every queued song gets its
     * keys (batch by batch), then each album, artist and genre they touch is summarised once: a big
     * queue (a whole server library, songs in no particular order) would otherwise rebuild the same
     * album in many batches. Songs leave the queue only once their summaries are rebuilt, so an
     * interrupted run is simply redone.
     */
    suspend fun runQueue() = mutex.withLock {
        withContext(Dispatchers.IO) {
            _busy.value = true
            try {
                Bench.time("index queue") {
                    val tags = artistTagMap(db.artistInfo().all())
                    while (true) {
                        val albums = HashSet<String>()
                        val artists = HashSet<String>()
                        val genres = HashSet<String>()
                        var lastSeq = 0L
                        var total = 0
                        Bench.time("index keys") {
                            while (true) {
                                val batch = dao.dirty(lastSeq, BATCH)
                                if (batch.isEmpty()) break
                                for (d in batch) {
                                    d.oldAlbum?.let(albums::add)
                                    d.oldArtist?.let(artists::add)
                                    d.oldGenre?.let(genres::add)
                                }
                                db.withTransaction {
                                    for (ids in batch.mapNotNull { it.trackId }.distinct().chunked(CHUNK)) {
                                        for (t in dao.tracks(ids)) {
                                            if (t.scope == Track.SCOPE_STREAM) continue
                                            val (album, artist, genre) = keysOf(t, tags)
                                            val title = if (album.isEmpty()) "" else sortText(t.title)
                                            val scope = Track.scopeOf(t.source)
                                            if (album != t.indexAlbum || artist != t.indexArtist || genre != t.indexGenre || title != t.indexTitle || scope != t.scope) {
                                                dao.setIndex(t.id, album, artist, genre, title, scope)
                                            }
                                            albums += album
                                            artists += artist
                                            genres += genre
                                        }
                                    }
                                }
                                lastSeq = batch.last().seq
                                total += batch.size
                            }
                        }
                        if (total == 0) break
                        Bench.time("index summaries (${albums.size} albums, ${artists.size} artists, ${genres.size} genres)") {
                            refreshAlbums(albums - "")
                            refreshArtists(artists - "")
                            refreshGenres(genres - "")
                        }
                        dao.clearDirty(lastSeq)
                        Bench.log("indexed $total queued songs")
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    // Each summary is kept per scope (own music, server); a key's rows in a scope it has no songs in go.

    private suspend fun refreshAlbums(keys: Set<String>) {
        for (chunk in keys.chunked(CHUNK)) db.withTransaction {
            val byAlbum = dao.tracksOfAlbums(chunk).groupBy { it.scope to it.indexAlbum }
            for (scope in SCOPES) {
                val gone = chunk.filter { (scope to it) !in byAlbum }
                if (gone.isNotEmpty()) dao.deleteAlbums(gone, scope)
            }
            dao.upsertAlbums(byAlbum.map { (key, tracks) -> albumOf(key.second, tracks) })
        }
    }

    private suspend fun refreshArtists(names: Set<String>) {
        for (chunk in names.chunked(CHUNK)) db.withTransaction {
            val counts = dao.artistCounts(chunk)
            for (scope in SCOPES) {
                val present = counts.filter { it.scope == scope }.mapTo(HashSet()) { it.name }
                val gone = chunk.filter { it !in present }
                if (gone.isNotEmpty()) dao.deleteArtists(gone, scope)
            }
            dao.upsertArtists(counts.map { ArtistIndex(it.name, it.scope, it.trackCount, it.albumCount, sortKey(it.name, UNKNOWN_ARTIST)) })
        }
    }

    private suspend fun refreshGenres(keys: Set<String>) {
        for (chunk in keys.chunked(CHUNK)) db.withTransaction {
            val counts = dao.genreCounts(chunk)
            for (scope in SCOPES) {
                val present = counts.filter { it.scope == scope }.mapTo(HashSet()) { it.key }
                val gone = chunk.filter { it !in present }
                if (gone.isNotEmpty()) dao.deleteGenres(gone, scope)
            }
            dao.upsertGenres(counts.map { c ->
                val name = genreName(c.key, dao.genreSpelling(c.key))
                GenreIndex(
                    c.key, c.scope, name, c.songCount, c.artistCount, c.albumCount,
                    sortKey = (if (c.key == UNKNOWN_GENRE) "1" else "0") + sortText(name),
                    artworkUri = dao.genreCover(c.key, c.scope),
                )
            })
        }
    }

    companion object {
        private const val TAG = "LibraryIndexer"
        private const val BATCH = 5000
        /** Below SQLite's limit of bound variables per statement. */
        private const val CHUNK = 500
        private const val DEBOUNCE_MS = 500L
        private val SCOPES = Track.SCOPES
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
                scope = first.scope,
                title = title,
                artist = first.albumArtist ?: first.artist ?: UNKNOWN_ARTIST,
                year = sorted.firstNotNullOfOrNull { it.year },
                artworkUri = sorted.firstNotNullOfOrNull { it.artworkUri },
                trackCount = tracks.size,
                durationMs = tracks.sumOf { it.durationMs.coerceAtLeast(0) },
                sortKey = sortKey(title, UNKNOWN_ALBUM),
            )
        }

        /** Names like "Unknown album" sort last, the rest by [sortText]. */
        fun sortKey(name: String, unknown: String) = (if (name == unknown) "1" else "0") + sortText(name)

        private val MARKS = Regex("""\p{Mn}+""")
        /** Leading spaces, invisible characters and punctuation: ' WATA!' and '"Heroes"' sort by their first word. */
        private val LEADING = Regex("""^[\p{Z}\p{Cc}\p{Cf}\p{P}\s]+""")

        /**
         * For sorting and the A–Z bar: lower case without accents, so "Ángel" sorts with A, and
         * without leading spaces or punctuation (unless that's all there is).
         */
        fun sortText(s: String): String {
            val plain = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
            return plain.replace(LEADING, "").ifEmpty { plain }
        }

        /** Raised when [sortText] or the keys change, so everything is indexed again once. */
        const val VERSION = 5

        fun genreName(key: String, spelling: String?): String = when {
            key == UNKNOWN_GENRE -> "Unknown genre"
            spelling != null -> spelling
            else -> key.split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::titlecase) }
        }
    }
}
