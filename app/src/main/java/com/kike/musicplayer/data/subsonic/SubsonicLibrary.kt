package com.kike.musicplayer.data.subsonic

import android.util.Log
import androidx.room.withTransaction
import com.kike.musicplayer.Bench
import com.kike.musicplayer.data.DriveSyncMode
import com.kike.musicplayer.data.Settings
import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.delete
import com.kike.musicplayer.data.db.getByIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** What a server sync is doing, for the server card. */
data class ServerSync(
    val running: Boolean = false,
    /** Songs listed so far, and how many the server said it has (null if it doesn't say). */
    val listed: Int = 0,
    val total: Long? = null,
    /** How the last sync went, or why it failed. */
    val message: String? = null,
    val failed: Boolean = false,
)

/**
 * Brings the server's songs into the library as their own source (Track.SOURCE_SUBSONIC, server
 * scope). The whole library is listed page by page (search3 with an empty query); only songs that
 * are new or whose details changed are written, so a re-sync of an unchanged library writes
 * nothing, and the summaries and search tables are only touched for what changed. Songs come with
 * their tags from the server, so they skip the tag reading and online lookups of the own music.
 */
class SubsonicLibrary(
    private val account: SubsonicAccount,
    private val db: AppDatabase,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val dao = db.tracks()
    private var job: Job? = null

    /** Called after each sync of the songs (e.g. to sync favourites and playlists, which may use new songs). */
    var onSynced: (() -> Unit)? = null

    private val _status = MutableStateFlow(ServerSync())
    val status: StateFlow<ServerSync> = _status.asStateFlow()

    /** Syncs at start when the sync setting says so. */
    fun syncIfDue() {
        val due = when (settings.serverSyncMode.value) {
            DriveSyncMode.OFF -> false
            DriveSyncMode.APP_START -> true
            DriveSyncMode.DAILY -> System.currentTimeMillis() - settings.lastServerSync.value >= DAY_MS
        }
        if (due && account.login() != null) sync(force = false)
    }

    /** Lists the server's songs again; without [force], only if the server scanned since the last sync. */
    fun sync(force: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val login = account.login() ?: return@launch
            _status.value = ServerSync(running = true)
            try {
                val scan = runCatching { account.client.scanStatus(login) }.getOrNull()
                if (!force && scan?.lastScan != null && scan.lastScan == settings.serverScanSynced && dao.countSource(Track.SOURCE_SUBSONIC) > 0) {
                    settings.setLastServerSync(System.currentTimeMillis())
                    _status.value = ServerSync(message = "Up to date: nothing changed on the server")
                    onSynced?.invoke()
                    return@launch
                }
                val result = Bench.time("server sync") { syncAll(login, scan?.count) }
                settings.serverScanSynced = scan?.lastScan
                settings.setLastServerSync(System.currentTimeMillis())
                _status.value = ServerSync(message = result)
                onSynced?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Server sync failed", e)
                _status.value = ServerSync(message = "Sync failed: ${e.message ?: e.javaClass.simpleName}", failed = true)
            }
        }
    }

    /** Removes the server's songs from the library (plays, favourites and playlists keep their ids). */
    suspend fun removeAll() = withContext(Dispatchers.IO) {
        job?.cancel()
        dao.deleteSource(Track.SOURCE_SUBSONIC)
        settings.serverScanSynced = null
        settings.setLastServerSync(0)
        _status.value = ServerSync()
    }

    private suspend fun syncAll(login: SubsonicLogin, total: Long?): String = coroutineScope {
        val known = HashMap<String, String?>()
        dao.checksums(Track.SOURCE_SUBSONIC).forEach { known[it.id] = it.checksum }
        val seen = HashSet<String>(maxOf(known.size, total?.toInt() ?: 0) * 2)
        var added = 0
        var changed = 0
        val now = System.currentTimeMillis()

        var offset = 0
        var next = async { account.client.songs(login, offset, PAGE) }
        while (true) {
            val page = next.await()
            if (page.isEmpty()) break
            offset += page.size
            // Fetch the next page while this one is written.
            next = async { account.client.songs(login, offset, PAGE) }

            val fresh = ArrayList<Track>()
            for (song in page) {
                val track = toTrack(song, now)
                if (!seen.add(track.id)) continue // listed twice (paging over a changing library)
                val old = known[track.id]
                if (old == null && track.id !in known) added++ else if (old != track.checksum) changed++ else continue
                fresh += track
            }
            if (fresh.isNotEmpty()) {
                // Songs already here keep what the app added (download, first-seen date).
                val previous = dao.getByIds(fresh.filter { it.id in known }.map { it.id }).associateBy { it.id }
                db.withTransaction {
                    dao.upsertAll(fresh.map { t ->
                        previous[t.id]?.let { p -> t.copy(dateAdded = p.dateAdded, localUri = p.localUri, downloadState = p.downloadState) } ?: t
                    })
                }
            }
            _status.value = ServerSync(running = true, listed = seen.size, total = total)
            if (page.size < PAGE) break
        }
        next.cancel()

        // Songs no longer listed are gone from the server, unless the listing came up short.
        val gone = known.keys - seen
        val complete = total == null || seen.size >= total
        val removed = if (complete && (seen.isNotEmpty() || known.isEmpty())) gone.size.also { dao.delete(gone) } else 0
        if (!complete) Log.w(TAG, "Listing incomplete: ${seen.size} of $total songs; nothing removed")
        Bench.log("server sync: ${seen.size} listed, $added added, $changed changed, $removed removed")
        buildString {
            val n = java.text.NumberFormat.getIntegerInstance()
            append("${n.format(seen.size)} songs on the server")
            val parts = listOfNotNull(
                "${n.format(added)} new".takeIf { added > 0 }, "${n.format(changed)} updated".takeIf { changed > 0 }, "${n.format(removed)} removed".takeIf { removed > 0 },
            )
            append(if (parts.isEmpty()) ", no changes" else ": " + parts.joinToString(", "))
        }
    }

    companion object {
        private const val TAG = "SubsonicLibrary"
        private const val PAGE = 500
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        const val COVER_PREFIX = "subsonic:cover/"

        /** A library row for a server song; [Track.checksum] is a fingerprint of the details. */
        fun toTrack(song: SongJson, now: Long): Track {
            val fileName = song.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "${song.id}.${song.suffix ?: "audio"}"
            val track = Track(
                id = "${Track.SOURCE_SUBSONIC}:${song.id}",
                source = Track.SOURCE_SUBSONIC,
                uri = "${Track.SOURCE_SUBSONIC}:${song.id}",
                fileName = fileName,
                mimeType = song.contentType,
                sizeBytes = song.size,
                title = song.title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
                artist = song.artist?.takeIf { it.isNotBlank() },
                album = song.album?.takeIf { it.isNotBlank() },
                albumArtist = song.displayAlbumArtist?.takeIf { it.isNotBlank() },
                trackNumber = song.track?.takeIf { it > 0 },
                discNumber = song.discNumber?.takeIf { it > 0 },
                year = song.year?.takeIf { it > 0 },
                genre = song.genre?.takeIf { it.isNotBlank() },
                genreRead = true,
                durationMs = (song.duration ?: 0) * 1000L,
                artworkUri = song.coverArt?.let { COVER_PREFIX + it },
                mbRecordingId = song.musicBrainzId?.takeIf { it.isNotBlank() },
                metadataState = Track.META_DONE,
                dateAdded = song.created?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: now,
                scope = Track.SCOPE_SERVER,
            )
            return track.copy(checksum = fingerprint(track))
        }

        private fun fingerprint(t: Track): String = SubsonicClient.md5(
            listOf(
                t.fileName, t.mimeType, t.sizeBytes, t.title, t.artist, t.album, t.albumArtist, t.trackNumber, t.discNumber,
                t.year, t.genre, t.durationMs, t.artworkUri, t.mbRecordingId, t.dateAdded,
            ).joinToString("\u001F")
        )
    }
}
