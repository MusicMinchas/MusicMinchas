package com.kike.musicplayer.data.metadata

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.kike.musicplayer.data.Settings
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.drive.DriveAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.security.MessageDigest

/**
 * Two-tier metadata pipeline:
 *  1. Local: embedded ID3 / Vorbis / MP4 tags and artwork via MediaMetadataRetriever.
 *  2. Online fallback for tracks still missing artist, album or artwork: MusicBrainz recording
 *     search for the MBIDs, then the Cover Art Archive for the album front cover.
 * Results are cached in Room, so each track is processed once.
 */
class MetadataEnricher(
    private val context: Context,
    private val dao: TrackDao,
    private val settings: Settings,
    private val driveAuth: DriveAuth,
    private val musicBrainz: MusicBrainzApi,
    private val coverArt: CoverArtApi,
) {
    data class Progress(val stage: String, val done: Int, val total: Int)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val mutex = Mutex()
    private val artworkDir = File(context.filesDir, "artwork").apply { mkdirs() }
    private var lastMbCall = 0L

    /** Processes everything pending. Concurrent calls wait for the running pass and then re-check. */
    suspend fun run() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                localPass()
                if (settings.onlineMetadata.value) onlinePass()
            } finally {
                _progress.value = null
            }
        }
    }

    private suspend fun localPass() {
        val ids = dao.idsWithState(Track.META_NEW)
        ids.forEachIndexed { i, id ->
            _progress.value = Progress("Reading tags", i, ids.size)
            val track = dao.get(id) ?: return@forEachIndexed
            if (track.source == Track.SOURCE_DRIVE && driveAuth.account.value == null) return@forEachIndexed
            val updated = runCatching { extractLocal(track) }
                .onFailure { Log.w(TAG, "Tag extraction failed for ${track.fileName}", it) }
                .getOrDefault(track)
            dao.upsert(updated.copy(metadataState = if (updated.isComplete()) Track.META_DONE else Track.META_LOCAL_DONE))
        }
    }

    private suspend fun onlinePass() {
        val ids = dao.idsWithState(Track.META_LOCAL_DONE)
        ids.forEachIndexed { i, id ->
            if (!settings.onlineMetadata.value) return
            _progress.value = Progress("Looking up MusicBrainz", i, ids.size)
            val track = dao.get(id) ?: return@forEachIndexed
            val updated = try {
                lookupOnline(track)
            } catch (e: Exception) {
                if (e is java.io.IOException) {
                    // Offline or MusicBrainz unreachable: keep the track pending and stop for now.
                    Log.w(TAG, "Online lookup stopped: $e")
                    return
                }
                Log.w(TAG, "Online lookup failed for ${track.fileName}", e)
                track
            }
            dao.upsert(updated.copy(metadataState = Track.META_DONE))
        }
    }

    private fun Track.isComplete() = artist != null && album != null && artworkUri != null

    // ---- Tier 1: tags embedded in the file ----

    private fun extractLocal(track: Track): Track {
        val mmr = MediaMetadataRetriever()
        try {
            if (track.source == Track.SOURCE_DRIVE) {
                val token = driveAuth.tokenBlocking() ?: return track
                mmr.setDataSource(track.uri, mapOf("Authorization" to "Bearer $token"))
            } else {
                mmr.setDataSource(context, Uri.parse(track.uri))
            }
            fun tag(key: Int) = mmr.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

            val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: track.artist
            val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: track.album
            val albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: track.albumArtist
            var artwork = track.artworkUri
            if (artwork == null) {
                mmr.embeddedPicture?.let { bytes ->
                    // One file per album when we know the album, so a 20-track album stores one image.
                    val key = if (album != null) Track.albumKeyOf(album, albumArtist ?: artist) else track.id
                    val file = File(artworkDir, sha1(key) + ".img")
                    if (!file.exists()) file.writeBytes(bytes)
                    artwork = Uri.fromFile(file).toString()
                }
            }
            return track.copy(
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: track.title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                trackNumber = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.leadingInt() ?: track.trackNumber,
                discNumber = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.leadingInt() ?: track.discNumber,
                year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR)?.leadingInt()
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.toIntOrNull() ?: track.year,
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: track.durationMs,
                artworkUri = artwork,
            )
        } finally {
            mmr.release()
        }
    }

    // ---- Tier 2: MusicBrainz + Cover Art Archive ----

    private suspend fun lookupOnline(track: Track): Track {
        // Untagged files are often named "Artist - Title"; use that when tags are missing.
        val fromName = track.fileName.substringBeforeLast('.').replace(Regex("^\\d+[ .\\-_]+"), "")
            .split(" - ", limit = 2).takeIf { it.size == 2 }
        val artist = track.artist ?: track.albumArtist ?: fromName?.get(0)?.trim()
        val title = if (track.artist == null && fromName != null) fromName[1].trim() else track.title
        if (artist.isNullOrBlank() || title.isBlank()) return track

        val query = buildString {
            append("recording:").append(quote(title)).append(" AND artist:").append(quote(artist))
            track.album?.let { append(" AND release:").append(quote(it)) }
        }
        var recordings = mbCall { musicBrainz.searchRecordings(query).recordings }
        if (recordings.isEmpty() && track.album != null) {
            // The album name in the tags may not match MusicBrainz; retry without it.
            recordings = mbCall {
                musicBrainz.searchRecordings("recording:${quote(title)} AND artist:${quote(artist)}").recordings
            }
        }
        val recording = recordings.firstOrNull { (it.score ?: 0) >= MIN_SCORE } ?: return track
        val release = pickRelease(recording, track.album)

        val artwork = track.artworkUri ?: release?.let { findCover(it) }
        return track.copy(
            title = if (track.artist == null) recording.title else track.title,
            artist = track.artist ?: recording.artistName,
            album = track.album ?: release?.title,
            albumArtist = track.albumArtist ?: release?.artistCredit?.joinToString("") { it.name + (it.joinphrase ?: "") },
            year = track.year ?: release?.date?.take(4)?.toIntOrNull(),
            durationMs = if (track.durationMs > 0) track.durationMs else recording.length ?: 0,
            artworkUri = artwork,
            mbRecordingId = recording.id,
            mbReleaseId = release?.id,
        )
    }

    private fun pickRelease(recording: MbRecording, knownAlbum: String?): MbRelease? {
        val releases = recording.releases.orEmpty()
        return releases.firstOrNull { knownAlbum != null && it.title.equals(knownAlbum, ignoreCase = true) }
            ?: releases.firstOrNull { it.status == "Official" && it.releaseGroup?.primaryType == "Album" }
            ?: releases.firstOrNull { it.status == "Official" }
            ?: releases.firstOrNull()
    }

    private suspend fun findCover(release: MbRelease): String? {
        suspend fun front(fetch: suspend () -> CaaListing): String? = try {
            fetch().images.let { images -> images.firstOrNull { it.front } ?: images.firstOrNull() }?.bestUrl
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        }
        return front { coverArt.release(release.id) }
            ?: release.releaseGroup?.let { rg -> front { coverArt.releaseGroup(rg.id) } }
    }

    /** MusicBrainz allows one request per second per client. */
    private suspend fun <T> mbCall(block: suspend () -> T): T {
        val wait = lastMbCall + MB_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            return block()
        } catch (e: HttpException) {
            if (e.code() != 503) throw e
            delay(5_000) // rate limited: back off and try once more
            return block()
        } finally {
            lastMbCall = System.currentTimeMillis()
        }
    }

    companion object {
        private const val TAG = "MetadataEnricher"
        private const val MIN_SCORE = 90
        private const val MB_INTERVAL_MS = 1_100L

        /** Lucene phrase with special characters escaped. */
        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun String.leadingInt() = Regex("^\\d+").find(this)?.value?.toIntOrNull()

        private fun sha1(s: String) =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
