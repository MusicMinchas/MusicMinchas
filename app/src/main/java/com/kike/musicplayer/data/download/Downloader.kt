package com.kike.musicplayer.data.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The download in progress: [done] of [total] bytes ([total] 0 when unknown). */
data class DownloadProgress(val trackId: String, val done: Long, val total: Long, val queued: Int)

/**
 * Downloads Google Drive songs to the phone, into Music/MusicMinchas/Artist/Album/, through
 * MediaStore (no storage permission; other apps see them too). A downloaded track keeps its
 * identity (plays, favourites) and gains [Track.localUri], which playback and Sonos prefer.
 * The queue lives in the database ([Track.downloadState]), so it survives the app being killed;
 * [DownloadService] works through it in the foreground.
 */
class Downloader(
    private val context: Context,
    private val dao: TrackDao,
    private val driveHttp: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    /** Queues the Drive songs among [tracks] that aren't on the phone yet, and starts downloading. */
    fun download(tracks: List<Track>) {
        val ids = tracks.filter { it.source == Track.SOURCE_DRIVE && it.downloadState != Track.DOWNLOADED && it.downloadState != Track.DOWNLOAD_QUEUED }
            .map { it.id }
        if (ids.isEmpty()) return
        scope.launch {
            dao.setDownloadState(ids, Track.DOWNLOAD_QUEUED)
            startService()
        }
    }

    /** Deletes a downloaded file (e.g. of a song replaced by a new file). */
    fun deleteFile(localUri: String) {
        runCatching { context.contentResolver.delete(Uri.parse(localUri), null, null) }
            .onFailure { Log.w(TAG, "Couldn't delete $localUri", it) }
    }

    /** Deletes the phone copy; the song plays from Drive again. */
    fun remove(track: Track) {
        scope.launch(Dispatchers.IO) {
            track.localUri?.let { uri -> runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) } }
            dao.setLocal(track.id, null, Track.NOT_DOWNLOADED)
        }
    }

    val totals = dao.observeDownloadTotals()

    /** Deletes every downloaded song from the phone (and drops what's still queued); they play from Drive again. */
    fun removeAll() {
        scope.launch(Dispatchers.IO) {
            dao.cancelQueuedDownloads()
            for (t in dao.downloaded()) {
                t.localUri?.let(::deleteFile)
                dao.setLocal(t.id, null, Track.NOT_DOWNLOADED)
            }
        }
    }

    /** Resumes an interrupted queue (call while the app is in front: it starts a foreground service). */
    fun resume() {
        scope.launch { if (dao.countDownloadQueued() > 0) startService() }
    }

    /** Forgets downloads whose file was deleted outside the app. */
    fun verify() {
        scope.launch(Dispatchers.IO) {
            for (t in dao.downloaded()) {
                val exists = runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(t.localUri), "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (!exists) dao.setLocal(t.id, null, Track.NOT_DOWNLOADED)
            }
        }
    }

    private fun startService() {
        // Not allowed while the app is in the background; the queue then resumes when it's opened.
        runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
            .onFailure { Log.i(TAG, "Downloads wait until the app is opened: $it") }
    }

    /** Downloads everything queued, one song at a time; [onProgress] feeds the notification. */
    suspend fun runQueue(onProgress: (DownloadProgress, Track) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            var failures = 0
            while (true) {
                val queued = dao.downloadQueued()
                val track = queued.firstOrNull() ?: break
                try {
                    val uri = downloadOne(track, queued.size) { onProgress(it, track) }
                    dao.setLocal(track.id, uri.toString(), Track.DOWNLOADED)
                    // A changed file was downloaded again: drop the old copy if it had another name.
                    track.localUri?.takeIf { it != uri.toString() }?.let(::deleteFile)
                    failures = 0
                } catch (e: Exception) {
                    Log.w(TAG, "Download of ${track.title} failed", e)
                    dao.setDownloadState(listOf(track.id), Track.DOWNLOAD_FAILED)
                    // Several in a row: probably offline or signed out of Drive; stop and leave the rest queued.
                    if (++failures >= MAX_FAILURES_IN_A_ROW) break
                }
            }
            _progress.value = null
        }
    }

    private fun downloadOne(track: Track, queued: Int, onProgress: (DownloadProgress) -> Unit): Uri {
        val resolver = context.contentResolver
        val folder = "${RELATIVE_ROOT}/${safe(track.albumArtist ?: track.artist ?: "Unknown artist")}/${safe(track.album ?: "Unknown album")}/"
        val name = fileName(track)
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // Already there (e.g. downloaded before the app's data was reset): reuse it when complete,
        // unless this download replaces an older version of the file.
        val replacing = track.localUri != null
        resolver.query(
            collection, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(folder, name), null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val existing = Uri.withAppendedPath(collection, c.getLong(0).toString())
                if (!replacing && (track.sizeBytes == null || c.getLong(1) == track.sizeBytes)) return existing
                resolver.delete(existing, null, null)
            }
        }

        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
                put(MediaStore.Audio.Media.MIME_TYPE, track.mimeType ?: "audio/mp4")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ) ?: throw IOException("MediaStore refused $folder$name")
        try {
            driveHttp.newCall(Request.Builder().url(track.uri).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Drive HTTP ${response.code}")
                val total = response.body.contentLength().takeIf { it > 0 } ?: track.sizeBytes ?: 0
                resolver.openOutputStream(uri, "w")!!.use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        var done = 0L
                        var reported = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (done - reported >= REPORT_EVERY || done == total) {
                                reported = done
                                DownloadProgress(track.id, done, total, queued).also { _progress.value = it; onProgress(it) }
                            }
                        }
                    }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    companion object {
        private const val TAG = "Downloader"
        const val RELATIVE_ROOT = "Music/MusicMinchas"
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 512 * 1024L
        private const val MAX_FAILURES_IN_A_ROW = 3

        /** "01 Title.m4a"; "2-01 Title.m4a" on a second disc, as iTunes names them. */
        fun fileName(track: Track): String {
            val ext = track.fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }
                ?: when (track.mimeType) {
                    "audio/flac" -> "flac"
                    "audio/mpeg" -> "mp3"
                    "audio/wav", "audio/x-wav" -> "wav"
                    else -> "m4a"
                }
            val number = track.trackNumber?.let { n ->
                val disc = track.discNumber?.takeIf { it > 1 }
                (if (disc != null) "$disc-" else "") + n.toString().padStart(2, '0') + " "
            } ?: ""
            return safe("$number${track.title}") + "." + ext.lowercase()
        }

        /** Folder- and file-name safe: no path separators or reserved characters, not too long. */
        fun safe(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(100).ifEmpty { "_" }
    }
}
