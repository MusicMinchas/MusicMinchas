package com.kike.musicplayer.sonos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.subsonic.SubsonicUrls
import com.kike.musicplayer.data.nebula.NebulaUrls
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.getByIds
import com.kike.musicplayer.sonos.resample.AlacInputDecoder
import com.kike.musicplayer.sonos.resample.AlacMp4Info
import com.kike.musicplayer.sonos.resample.AlacMp4Probe
import com.kike.musicplayer.sonos.resample.DownsamplingStream
import com.kike.musicplayer.sonos.resample.FlacInputDecoder
import com.kike.musicplayer.sonos.resample.FlacProbe
import com.kike.musicplayer.sonos.resample.FlacStreamInfo
import com.kike.musicplayer.sonos.resample.RandomAccessSource
import com.kike.musicplayer.sonos.resample.ResamplePlan
import com.kike.musicplayer.sonos.resample.VerbatimFlacWriter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Minimal HTTP server that lets Sonos fetch tracks and covers from the phone. Drive files are
 * relayed with the user's OAuth token (which never leaves the phone); files in device folders are
 * served from the content provider. Range requests are supported, so Sonos can seek and read ahead.
 *
 * When [downsampleEnabled] is on, hi-res FLAC and ALAC streams (> 48 kHz) from any source
 * (Local, Drive, Navidrome, or Nebula) are downsampled on the fly to 24-bit / 48 or 44.1 kHz
 * verbatim FLAC with full HTTP Range seeking support; streams <= 48 kHz are relayed untouched.
 *
 * URLs carry a random per-run token, so other devices on the network can't browse the library.
 */
class TrackServer(
    private val context: Context,
    private val dao: TrackDao,
    private val driveHttp: OkHttpClient,
    private val serverUrls: SubsonicUrls,
    private val nebulaUrls: NebulaUrls? = null,
    private val downsampleEnabled: () -> Boolean = { false },
) {
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null

    val isRunning get() = server != null

    @Synchronized
    fun start() {
        if (server != null) return
        // A fixed port keeps queued URLs valid if the server restarts; fall back to any free port.
        val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PREFERRED_PORT)) } }
            .getOrElse { ServerSocket(0) }
        server = socket
        thread(name = "track-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "track-server-client", isDaemon = true) { serve(client) }
            }
        }
        Log.i(TAG, "Serving on port ${socket.localPort}")
    }

    private val cachedTracks = ConcurrentHashMap<String, Track>()
    private val probedFormats = ConcurrentHashMap<String, ProbedTrackFormat>()
    private val downsamplerPool = ArrayDeque<CachedDownsampler>()
    private val activeDownsamplers = java.util.concurrent.CopyOnWriteArrayList<CachedDownsampler>()
    private val prefetchGeneration = AtomicLong(0L)
    private val prefetchExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "track-server-prefetch").apply { isDaemon = true }
    }

    @Synchronized
    fun stop() {
        prefetchGeneration.incrementAndGet()
        runCatching { server?.close() }
        server = null
        cachedTracks.clear()
        probedFormats.clear()
        for (session in activeDownsamplers) {
            session.isCancelled = true
            runCatching { session.source.close() }
        }
        activeDownsamplers.clear()
        synchronized(downsamplerPool) {
            downsamplerPool.forEach { runCatching { it.source.close() } }
            downsamplerPool.clear()
        }
    }

    /** Base URL ("http://192.168.0.41:8765") reachable from the speakers, or null when not on a LAN. */
    fun baseUrl(): String? {
        val port = server?.localPort ?: return null
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val address = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?: return null
        return "http://${address.hostAddress}:$port"
    }

    /** URL Sonos plays for [track]. [entryId] identifies the queue entry, so the player can map what Sonos plays back to it. */
    fun trackUrl(base: String, entryId: Long, track: Track): String {
        cachedTracks[track.id] = track
        val ext = if (willDownsampleAtQueueTime(track)) "flac" else extensionOf(track)
        return "$base/t/$token/$entryId/${encode(track.id)}.$ext"
    }

    fun artUrl(base: String, track: Track): String? {
        cachedTracks[track.id] = track
        return track.artworkUri?.let { "$base/a/$token/${encode(track.id)}" }
    }

    private fun trackOf(trackId: String?): Track? {
        val id = trackId ?: return null
        return cachedTracks[id] ?: runBlocking { dao.get(id) }?.also { cachedTracks[id] = it }
    }

    /** The queue entry ID in a URL made by [trackUrl], if it is one of ours. */
    fun entryIdOf(url: String?): Long? {
        val path = url?.let { Uri.parse(it).path } ?: return null
        val parts = path.split('/')
        return if (parts.size >= 5 && parts[1] == "t" && parts[2] == token) parts[3].toLongOrNull() else null
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 30_000
                val input = socket.getInputStream()
                val requestLine = readLine(input) ?: return
                val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
                    .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }
                    .toMap()
                val (method, target) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                Log.i(TAG, "--> Sonos Request: $method $target, range=${headers["range"]}")
                traceLog("REQ $method $target range=${headers["range"]}")
                val parts = target.substringBefore('?').split('/')
                val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                if (parts.size < 4 || parts[2] != token || method !in setOf("GET", "HEAD")) {
                    return respondStatus(out, 404)
                }
                when (parts[1]) {
                    "t" -> serveTrack(out, method, decode(parts.getOrNull(4)?.substringBeforeLast('.')), headers["range"])
                    "a" -> serveArt(out, method, decode(parts[3]))
                    else -> respondStatus(out, 404)
                }
            } catch (_: IOException) {
                // Sonos routinely closes a connection mid-transfer when it seeks or has buffered enough.
            }
        }
    }

    private fun cancelDownsamplersExcept(currentTrackId: String?) {
        for (session in activeDownsamplers) {
            if (currentTrackId == null || session.trackId != currentTrackId) {
                session.isCancelled = true
            }
        }
    }

    private fun serveTrack(out: OutputStream, method: String, trackId: String?, range: String?) {
        val track = trackOf(trackId) ?: return respondStatus(out, 404)
        val mime = mimeOf(track)
        Log.i(TAG, "serveTrack: id=$trackId, title='${track.title}', source=${track.source}")
        cancelDownsamplersExcept(track.id)
        // Drive and server songs not on the phone are relayed from where they live.
        val remote = when {
            track.localUri != null -> null
            track.source == Track.SOURCE_DRIVE -> track.uri
            track.source == Track.SOURCE_SUBSONIC -> serverUrls.resolve(track.uri) ?: return respondStatus(out, 503)
            track.source == Track.SOURCE_NEBULA -> nebulaUrls?.resolve(track.uri) ?: return respondStatus(out, 503)
            else -> null
        }
        if (downsampleEnabled() && serveDownsampledIfNeeded(out, method, track, remote, range)) return
        if (remote != null) {
            // Sonos probes a file in many small pieces before playing (start, then the index at the
            // end); fetching each from Drive would cost about a second. Serve those from blocks.
            if (method == "GET" && serveFromBlocks(out, track, remote, mime, range)) return
            fun buildRemoteRequest(url: String, reqRange: String?): Request {
                val builder = Request.Builder().url(url)
                // HEAD: ask Drive for one byte, which still reports the total size in Content-Range.
                if (method == "HEAD") builder.header("Range", "bytes=0-0") else reqRange?.let { builder.header("Range", it) }
                return builder.build()
            }
            var activeRemote = if (track.source == Track.SOURCE_NEBULA) nebulaUrls?.resolve(track.uri) ?: remote else remote
            var response = executeRemote(buildRemoteRequest(activeRemote, range), ok200 = true)
            if (response == null && track.source == Track.SOURCE_NEBULA) {
                nebulaUrls?.invalidate(track.uri, failedUrl = activeRemote)
                val freshRemote = nebulaUrls?.resolve(track.uri)
                if (freshRemote != null && freshRemote != activeRemote) {
                    activeRemote = freshRemote
                    response = executeRemote(buildRemoteRequest(activeRemote, range), ok200 = true)
                }
            }
            if (response == null) return respondStatus(out, 502)
            val contentRange = response.header("Content-Range")
            val total = contentRange?.substringAfter('/')?.toLongOrNull()
                ?: response.body.contentLength().takeIf { it > 0 }
            if (total != null && track.sizeBytes == null) {
                cachedTracks[track.id] = track.copy(sizeBytes = total)
            }
            if (method == "HEAD") {
                response.use {
                    return writeHead(out, 200, mime, total ?: track.sizeBytes, null)
                }
            }
            val startOffset = parseContentRangeStart(contentRange) ?: 0L
            val expectedLength = parseContentRangeLength(contentRange)
                ?: response.body.contentLength().takeIf { it >= 0 }
            Log.i(TAG, "serveTrack streaming to Sonos: remoteCode=${response.code}, len=$expectedLength, range=$contentRange")
            writeHead(out, response.code, mime, expectedLength, contentRange)
            val refreshUrl: ((String) -> String?)? = if (track.source == Track.SOURCE_NEBULA) {
                { failedUrl ->
                    nebulaUrls?.invalidate(track.uri, failedUrl = failedUrl)
                    nebulaUrls?.resolve(track.uri)
                }
            } else null
            relayRemoteWithResume(
                initialResponse = response,
                initialRemote = activeRemote,
                startOffset = startOffset,
                expectedLength = expectedLength,
                out = out,
                openRange = { url, rangeHeader ->
                    executeRemote(Request.Builder().url(url).header("Range", rangeHeader).build(), ok200 = false)
                },
                refreshUrl = refreshUrl,
                onLog = { msg ->
                    Log.w(TAG, "${track.title}: $msg")
                    traceLog("RELAY '${track.title}': $msg")
                },
            )
        } else {
            val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(track.localUri ?: track.uri), "r")
                ?: return respondStatus(out, 404)
            afd.use {
                val size = afd.length.takeIf { it >= 0 } ?: track.sizeBytes ?: return respondStatus(out, 500)
                val (start, end) = parseRange(range, size) ?: (0L to size - 1)
                val partial = range != null
                writeHead(out, if (partial) 206 else 200, mime, end - start + 1, if (partial) "bytes $start-$end/$size" else null)
                if (method == "HEAD") return out.flush()
                FileInputStream(afd.fileDescriptor).use { stream ->
                    stream.channel.position(afd.startOffset + start)
                    copy(stream, out, limit = end - start + 1)
                }
            }
        }
    }

    /**
     * Answers a small closed range ("bytes=a-b", within one [BLOCK]), or an open one ("bytes=a-")
     * within the file's last block, from a cached block of the remote file, fetching the block once.
     * Returns false for anything else (streamed as is).
     */
    private fun serveFromBlocks(out: OutputStream, track: Track, remote: String, mime: String, range: String?): Boolean {
        val match = range?.let { CLOSED_RANGE.matchEntire(it.trim()) }
        // "From here to the end" inside the file's last block (Sonos reading a file's index): also from the block.
        val open = if (match == null) range?.let { OPEN_RANGE.matchEntire(it.trim()) } ?: return false else null
        val start = (match ?: open)!!.groupValues[1].toLong()
        val size = track.sizeBytes
        val end = when {
            match != null -> match.groupValues[2].toLong()
            size != null && start / BLOCK == (size - 1) / BLOCK -> size - 1
            else -> return false
        }
        if (end < start || start / BLOCK != end / BLOCK) return false
        val index = start / BLOCK
        val (block, total) = getOrFetchBlock(track, remote, index) ?: return false
        val from = (start - index * BLOCK).toInt()
        if (from >= block.size) return false
        if (open != null && index * BLOCK + block.size != total) return false // the file isn't as the library says
        val to = minOf(end - index * BLOCK, block.size - 1L).toInt()
        writeHead(out, 206, mime, (to - from + 1).toLong(), "bytes $start-${index * BLOCK + to}/$total")
        out.write(block, from, to - from + 1)
        out.flush()
        return true
    }

    /** Caches transient or library tracks so [prefetch] and [warmUpTrack] can find them before queue URLs are built. */
    fun cacheTracks(tracks: List<Track>) {
        for (t in tracks) {
            cachedTracks[t.id] = t
        }
    }

    /**
     * Pre-resolves the remote URL, fetches Block 0, and probes the audio header for [track] so that
     * when Sonos issues its initial HTTP GET on Play, the stream URL, format, and initial audio
     * bytes are already warm in memory.
     */
    fun warmUpTrack(track: Track) {
        prefetchGeneration.incrementAndGet()
        cachedTracks[track.id] = track
        if (track.localUri != null) return
        val remote = when (track.source) {
            Track.SOURCE_DRIVE -> track.uri
            Track.SOURCE_SUBSONIC -> serverUrls.resolve(track.uri)
            Track.SOURCE_NEBULA -> nebulaUrls?.resolve(track.uri)
            else -> null
        } ?: return
        getOrFetchBlock(track, remote, 0L)
        val current = cachedTracks[track.id] ?: track
        val activeRemote = if (current.source == Track.SOURCE_NEBULA) nebulaUrls?.resolve(current.uri) ?: remote else remote
        if (downsampleEnabled() && extensionOf(current) in RESAMPLE_CANDIDATE_EXTS && !probedFormats.containsKey(current.id)) {
            val fmt = probeTrackFormat(current, activeRemote)
            if (fmt is ProbedTrackFormat.FlacHiRes || fmt is ProbedTrackFormat.AlacHiRes) {
                val gen = prefetchGeneration.get()
                prefetchExecutor.execute {
                    if (prefetchGeneration.get() != gen || !isRunning) return@execute
                    runCatching { getOrFetchBlock(cachedTracks[current.id] ?: current, activeRemote, 1L) }
                }
            }
        }
    }

    /**
     * Fetches, before Sonos asks, the blocks it reads first from a remote song: Block 0, and
     * (for containers that keep their index at EOF, such as MP4/M4A) the trailing blocks.
     * Bounded to [prefetchExecutor] and cancelled via [prefetchGeneration] whenever a newer track
     * starts or casting stops, so rapid skips never pile up concurrent CDN requests.
     */
    fun prefetch(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val gen = prefetchGeneration.incrementAndGet()
        prefetchExecutor.execute {
            if (prefetchGeneration.get() != gen || !isRunning) return@execute
            val missing = trackIds.filter { !cachedTracks.containsKey(it) }.toSet()
            if (missing.isNotEmpty()) {
                runBlocking { dao.getByIds(missing) }.forEach { cachedTracks[it.id] = it }
            }
            val tracks = trackIds.mapNotNull { cachedTracks[it] }
            for (track in tracks) {
                if (prefetchGeneration.get() != gen || !isRunning) return@execute
                if (track.localUri != null) continue
                val remote = when (track.source) {
                    Track.SOURCE_DRIVE -> track.uri
                    Track.SOURCE_SUBSONIC -> serverUrls.resolve(track.uri)
                    Track.SOURCE_NEBULA -> nebulaUrls?.resolve(track.uri)
                    else -> null
                } ?: continue
                if (prefetchGeneration.get() != gen || !isRunning) return@execute
                val firstPair = runCatching { getOrFetchBlock(track, remote, 0L) }.getOrNull()
                if (prefetchGeneration.get() != gen || !isRunning) return@execute
                val totalSize = firstPair?.second?.takeIf { it > 0 } ?: track.sizeBytes
                val updatedTrack = cachedTracks[track.id] ?: track
                val activeRemote = if (updatedTrack.source == Track.SOURCE_NEBULA) {
                    nebulaUrls?.resolve(updatedTrack.uri) ?: remote
                } else {
                    remote
                }
                if (downsampleEnabled() && extensionOf(updatedTrack) in RESAMPLE_CANDIDATE_EXTS && !probedFormats.containsKey(updatedTrack.id)) {
                    val fmt = runCatching { probeTrackFormat(updatedTrack, activeRemote) }.getOrNull()
                    if (fmt is ProbedTrackFormat.FlacHiRes || fmt is ProbedTrackFormat.AlacHiRes) {
                        if (prefetchGeneration.get() == gen && isRunning) {
                            runCatching { getOrFetchBlock(updatedTrack, activeRemote, 1L) }
                        }
                        continue
                    }
                }
                // FLAC STREAMINFO and SEEKTABLE are always at byte 0 (Block 0); fetching trailing
                // blocks is only useful for containers like MP4/M4A whose moov index can sit at EOF.
                if (extensionOf(updatedTrack) == "flac") continue
                val size = totalSize ?: continue
                val last = (size - 1) / BLOCK
                for (index in listOf(last, last - 1).filter { it > 0 }.distinct()) {
                    if (prefetchGeneration.get() != gen || !isRunning) return@execute
                    runCatching {
                        getOrFetchBlock(updatedTrack, activeRemote, index)
                    }.onFailure { Log.i(TAG, "Prefetch of ${track.title} failed: $it") }
                }
            }
        }
    }

    /** Block [index] of the remote file and the file's total size, or null if it can't be fetched. */
    private fun getOrFetchBlock(track: Track, remote: String, index: Long): Pair<ByteArray, Long>? {
        val key = "${track.id}#$index"
        val pair = synchronized(blockLocks.computeIfAbsent(key) { Any() }) {
            blocks.get(key) ?: fetchBlock(track, remote, index)?.also { blocks.put(key, it) }
        } ?: return null
        val (_, total) = pair
        if (total > 0 && track.sizeBytes == null) {
            cachedTracks.computeIfPresent(track.id) { _, t -> if (t.sizeBytes == null) t.copy(sizeBytes = total) else t }
        }
        return pair
    }

    /**
     * Bounded upstream read timeout for streaming relays: detects stalled CDN sockets within 25 s
     * so [relayRemoteWithResume] can reconnect via `Range` before Sonos drains its buffer.
     * When Sonos pauses, backpressure blocks on the downstream socket write (not upstream read),
     * and if the upstream closes while paused, the next read seamlessly resumes via `Range`.
     */
    private val streamHttp = driveHttp.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val blockHttp = driveHttp.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun fetchBlock(track: Track, remote: String, index: Long): Pair<ByteArray, Long>? {
        fun buildReq(url: String) = Request.Builder()
            .url(url)
            .header("Range", "bytes=${index * BLOCK}-${(index + 1) * BLOCK - 1}")
            .build()

        var response = executeRemote(buildReq(remote), ok200 = true, useBlockHttp = true)
        if (response == null && track.source == Track.SOURCE_NEBULA) {
            nebulaUrls?.invalidate(track.uri, failedUrl = remote)
            val freshRemote = nebulaUrls?.resolve(track.uri)
            if (freshRemote != null && freshRemote != remote) {
                response = executeRemote(buildReq(freshRemote), ok200 = true, useBlockHttp = true)
            }
        }
        if (response == null) return null
        return response.use { res ->
            val total = res.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: res.body.contentLength().takeIf { res.code == 200 && it > 0 }
                ?: return null
            runCatching {
                val bytes = if (res.code == 206) {
                    res.body.bytes()
                } else {
                    val stream = res.body.byteStream()
                    val toSkip = index * BLOCK
                    var skipped = 0L
                    while (skipped < toSkip) {
                        val s = stream.skip(toSkip - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    val buf = ByteArray(BLOCK.toInt())
                    var read = 0
                    while (read < buf.size) {
                        val n = stream.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read == buf.size) buf else buf.copyOf(read)
                }
                bytes to total
            }.getOrNull()
        }
    }

    /**
     * Runs a remote track request with short retries on transient network errors or 5xx/429
     * responses. Fails fast on 403/404/410 so expired signed CDN URLs can be invalidated and
     * re-resolved immediately instead of burning Sonos's timeout window.
     */
    private fun executeRemote(request: Request, ok200: Boolean, useBlockHttp: Boolean = false): okhttp3.Response? {
        val client = if (useBlockHttp) blockHttp else streamHttp
        for (attempt in 0 until REMOTE_ATTEMPTS) {
            if (attempt > 0) runCatching { Thread.sleep(RETRY_DELAY_MS * attempt) }
            val res = runCatching { client.newCall(request).execute() }
                .onFailure { Log.w(TAG, "Remote request failed (attempt ${attempt + 1}): $it") }
                .getOrNull()
            if (res != null && (res.code == 206 || (ok200 && res.code == 200))) return res
            if (res != null) {
                val code = res.code
                Log.w(TAG, "Remote request returned HTTP $code (attempt ${attempt + 1})")
                res.close()
                if (code == 403 || code == 404 || code == 410) return null
            }
        }
        return null
    }

    private val blocks = BlockCache(MAX_BLOCKS)
    private val blockLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** The blocks used last, least recently used dropped first. */
    private class BlockCache(private val max: Int) {
        private val map = object : LinkedHashMap<String, Pair<ByteArray, Long>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ByteArray, Long>>) = size > max
        }

        @Synchronized fun get(key: String) = map[key]
        @Synchronized fun put(key: String, value: Pair<ByteArray, Long>) { map[key] = value }
    }

    private fun serveArt(out: OutputStream, method: String, trackId: String?) {
        val art = trackOf(trackId)?.artworkUri ?: return respondStatus(out, 404)
        val bytes = if (art.startsWith("file:")) {
            File(Uri.parse(art).path ?: return respondStatus(out, 404)).takeIf { it.exists() }?.readBytes()
        } else {
            driveHttp.newCall(Request.Builder().url(serverUrls.resolve(art) ?: art).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
        } ?: return respondStatus(out, 404)
        val isPng = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        writeHead(out, 200, if (isPng) "image/png" else "image/jpeg", bytes.size.toLong(), null)
        if (method == "GET") out.write(bytes)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, mime: String, length: Long?, contentRange: String?) {
        val reason = when (code) { 200 -> "OK"; 206 -> "Partial Content"; else -> "Status" }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $mime\r\n")
            append("Accept-Ranges: bytes\r\n")
            length?.let { append("Content-Length: $it\r\n") }
            contentRange?.let { append("Content-Range: $it\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun respondStatus(out: OutputStream, code: Int) {
        out.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun copy(input: InputStream, out: OutputStream, limit: Long = Long.MAX_VALUE) {
        val buf = ByteArray(64 * 1024)
        var left = limit
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun willDownsampleAtQueueTime(track: Track): Boolean {
        if (!downsampleEnabled()) return false
        val ext = extensionOf(track)
        if (ext !in RESAMPLE_CANDIDATE_EXTS) return false
        val rate = track.sampleRate ?: probedFormats[track.id]?.sampleRate
        if (rate != null) return ResamplePlan.needsDownsampling(rate)
        if (ext in ALAC_EXTS && (track.localUri != null || track.source == Track.SOURCE_SAF)) {
            val probed = probeTrackFormat(track, remote = null)
            return probed is ProbedTrackFormat.AlacHiRes
        }
        return false
    }

    private fun serveDownsampledIfNeeded(
        out: OutputStream,
        method: String,
        track: Track,
        remote: String?,
        range: String?,
    ): Boolean {
        val knownRate = track.sampleRate
        if (knownRate != null && !ResamplePlan.needsDownsampling(knownRate)) return false
        val ext = extensionOf(track)
        if (ext !in RESAMPLE_CANDIDATE_EXTS) return false

        val format = probedFormats[track.id] ?: probeTrackFormat(track, remote) ?: return false
        val plan = when (format) {
            is ProbedTrackFormat.Passthrough -> return false
            is ProbedTrackFormat.FlacHiRes -> format.plan
            is ProbedTrackFormat.AlacHiRes -> format.plan
        }

        val headerWriter = when (format) {
            is ProbedTrackFormat.FlacHiRes -> format.headerWriter
            is ProbedTrackFormat.AlacHiRes -> format.headerWriter
            is ProbedTrackFormat.Passthrough -> return false
        }
        val headerBytes = when (format) {
            is ProbedTrackFormat.FlacHiRes -> format.headerBytes
            is ProbedTrackFormat.AlacHiRes -> format.headerBytes
            is ProbedTrackFormat.Passthrough -> return false
        }
        val size = headerWriter.totalStreamBytes
        val (start, end) = parseRange(range, size) ?: (0L to size - 1)
        val partial = range != null

        if (method == "HEAD") {
            writeHead(out, 200, "audio/flac", end - start + 1, if (partial) "bytes $start-$end/$size" else null)
            return true
        }

        // Fast-path: When Sonos seeks, it first sends a quick range=null probe to read the 1.8 KB FLAC header,
        // and immediately closes the socket after ~24 ms. Serving the header without locking a downsampler
        // prevents contention when the actual seek range request arrives 9 ms later.
        val headerSize = headerBytes.size.toLong()
        if (end < headerSize) {
            writeHead(out, if (partial) 206 else 200, "audio/flac", end - start + 1, if (partial) "bytes $start-$end/$size" else null)
            out.write(headerBytes, start.toInt(), (end - start + 1).toInt())
            out.flush()
            traceLog("DS HEADER-ONLY '${track.title}' range=$start-$end/${headerBytes.size}")
            return true
        }

        // Write the HTTP status and any leading FLAC header bytes immediately BEFORE acquiring a downsampler:
        // when Sonos opens a range=null connection just to inspect the 1.8 KB SEEKTABLE before seeking,
        // it gets the header in <1 ms instead of waiting on decoder setup.
        writeHead(
            out = out,
            code = if (partial) 206 else 200,
            mime = "audio/flac",
            length = end - start + 1,
            contentRange = if (partial) "bytes $start-$end/$size" else null,
        )
        val bodyStart = if (start < headerSize) {
            val hOff = start.toInt()
            val hLen = headerBytes.size - hOff
            out.write(headerBytes, hOff, hLen)
            out.flush()
            headerSize
        } else {
            start
        }

        val session = acquireDownsampler(track, remote, format) ?: return true
        try {
            val stream = session.stream
            val t0 = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "Downsampling '${track.title}' (${plan.inputRate} Hz -> ${plan.outputRate} Hz / 24-bit FLAC, range=$start-$end/$size)")
            traceLog("DS START '${track.title}' ${plan.inputRate}->${plan.outputRate} range=$start-$end/$size")
            try {
                stream.writeRange(bodyStart, end, out, shouldCancel = { session.isCancelled })
                if (session.isCancelled) {
                    traceLog("DS CANCEL '${track.title}' range=$start-$end after ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                } else {
                    traceLog("DS DONE  '${track.title}' range=$start-$end (${android.os.SystemClock.elapsedRealtime() - t0} ms)")
                }
            } catch (e: IOException) {
                traceLog("DS CLOSE '${track.title}' range=$start-$end after ${android.os.SystemClock.elapsedRealtime() - t0} ms: $e")
                throw e
            }
            return true
        } finally {
            releaseDownsampler(session)
        }
    }

    private fun probeTrackFormat(track: Track, remote: String?): ProbedTrackFormat? {
        val ext = extensionOf(track)
        val source = openRandomAccessSource(track, remote) ?: return null
        val format = try {
            if (ext == "flac") {
                val info = FlacProbe.parse(source) ?: return null
                traceLog("PROBE FLAC '${track.title}' rate=${info.sampleRate} bps=${info.bitsPerSample} samples=${info.totalSamples} seekPts=${info.seekPoints.size} firstOff=${info.firstFrameOffset}")
                val plan = ResamplePlan.forStream(info.sampleRate, info.channels)
                if (plan != null) {
                    val fallbackSamples = if (track.durationMs > 0) (track.durationMs * info.sampleRate) / 1000L else 0L
                    val totalInput = if (info.totalSamples > 0L) info.totalSamples else fallbackSamples
                    val outputSamples = plan.outputSamples(totalInput)
                    val writer = VerbatimFlacWriter(plan.outputRate, plan.channels, outputSamples)
                    ProbedTrackFormat.FlacHiRes(info, plan, writer)
                } else {
                    ProbedTrackFormat.Passthrough(info.sampleRate, info.bitsPerSample)
                }
            } else if (ext in ALAC_EXTS) {
                val info = AlacMp4Probe.parse(source) ?: return null
                traceLog("PROBE ALAC '${track.title}' rate=${info.sampleRate} bps=${info.bitsPerSample} samples=${info.totalSamples} packets=${info.packetSizes.size}")
                val plan = ResamplePlan.forStream(info.sampleRate, info.channels)
                if (plan != null) {
                    val outputSamples = plan.outputSamples(info.totalSamples)
                    val writer = VerbatimFlacWriter(plan.outputRate, plan.channels, outputSamples)
                    ProbedTrackFormat.AlacHiRes(info, plan, writer)
                } else {
                    ProbedTrackFormat.Passthrough(info.sampleRate, info.bitsPerSample)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Header probe failed for ${track.title}: $e")
            traceLog("PROBE ERR '${track.title}': $e")
            null
        } finally {
            runCatching { source.close() }
        } ?: return null

        probedFormats[track.id] = format
        if (track.sampleRate == null) {
            val updated = track.copy(sampleRate = format.sampleRate, bitsPerSample = format.bitsPerSample)
            cachedTracks[track.id] = updated
            thread(name = "save-track-format", isDaemon = true) {
                runCatching { runBlocking { dao.setAudioFormat(track.id, format.sampleRate, format.bitsPerSample) } }
            }
        }
        return format
    }

    private fun acquireDownsampler(
        track: Track,
        remote: String?,
        format: ProbedTrackFormat,
    ): CachedDownsampler? {
        synchronized(downsamplerPool) {
            val it = downsamplerPool.iterator()
            while (it.hasNext()) {
                val s = it.next()
                if (s.isCancelled) {
                    it.remove()
                    activeDownsamplers.remove(s)
                    runCatching { s.source.close() }
                }
            }
            val idx = downsamplerPool.indexOfFirst { it.trackId == track.id && !it.isCancelled && it.lock.tryLock() }
            if (idx >= 0) {
                return downsamplerPool.removeAt(idx)
            }
        }
        val source = openRandomAccessSource(track, remote) ?: return null
        val fallbackSamples = if (track.durationMs > 0) (track.durationMs * format.sampleRate) / 1000L else 0L
        val stream = when (format) {
            is ProbedTrackFormat.FlacHiRes -> DownsamplingStream(FlacInputDecoder(source, format.info, fallbackSamples), format.plan)
            is ProbedTrackFormat.AlacHiRes -> DownsamplingStream(AlacInputDecoder(source, format.info), format.plan)
            is ProbedTrackFormat.Passthrough -> {
                runCatching { source.close() }
                return null
            }
        }
        val sameTrackActive = activeDownsamplers.filter { it.trackId == track.id && !it.isCancelled }
        if (sameTrackActive.size >= MAX_DOWNSAMPLERS) {
            sameTrackActive.firstOrNull()?.isCancelled = true
        }
        val session = CachedDownsampler(track.id, source, stream).also { it.lock.lock() }
        activeDownsamplers.add(session)
        return session
    }

    private fun releaseDownsampler(session: CachedDownsampler) {
        if (session.isCancelled) {
            activeDownsamplers.remove(session)
            runCatching { session.source.close() }
            if (session.lock.isHeldByCurrentThread) session.lock.unlock()
            return
        }
        var evicted: CachedDownsampler? = null
        synchronized(downsamplerPool) {
            while (downsamplerPool.size >= MAX_DOWNSAMPLERS) {
                val victimIdx = downsamplerPool.indexOfFirst { !it.lock.isLocked }
                if (victimIdx < 0) break
                evicted = downsamplerPool.removeAt(victimIdx)
                activeDownsamplers.remove(evicted)
            }
            downsamplerPool.addLast(session)
            if (session.lock.isHeldByCurrentThread) session.lock.unlock()
        }
        evicted?.let { runCatching { it.source.close() } }
    }

    private fun openRandomAccessSource(track: Track, remote: String?): RandomAccessSource? = try {
        if (remote != null) {
            RemoteRandomAccessSource(track, remote)
        } else {
            LocalRandomAccessSource(Uri.parse(track.localUri ?: track.uri), track.sizeBytes)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to open source for ${track.title}: $e")
        null
    }

    private sealed interface ProbedTrackFormat {
        val sampleRate: Int
        val bitsPerSample: Int

        data class Passthrough(override val sampleRate: Int, override val bitsPerSample: Int) : ProbedTrackFormat
        data class FlacHiRes(
            val info: FlacStreamInfo,
            val plan: ResamplePlan,
            val headerWriter: VerbatimFlacWriter,
            val headerBytes: ByteArray = headerWriter.writeStreamHeader(),
        ) : ProbedTrackFormat {
            override val sampleRate: Int get() = info.sampleRate
            override val bitsPerSample: Int get() = info.bitsPerSample
        }
        data class AlacHiRes(
            val info: AlacMp4Info,
            val plan: ResamplePlan,
            val headerWriter: VerbatimFlacWriter,
            val headerBytes: ByteArray = headerWriter.writeStreamHeader(),
        ) : ProbedTrackFormat {
            override val sampleRate: Int get() = info.sampleRate
            override val bitsPerSample: Int get() = info.bitsPerSample
        }
    }

    private class CachedDownsampler(
        val trackId: String,
        val source: RandomAccessSource,
        val stream: DownsamplingStream,
        val lock: ReentrantLock = ReentrantLock(),
        @Volatile var isCancelled: Boolean = false,
    )

    @Synchronized
    private fun traceLog(line: String) {
        runCatching {
            val f = File(context.cacheDir, "sonos_trace.log")
            if (f.exists() && f.length() > 256 * 1024) f.writeText("")
            f.appendText("${System.currentTimeMillis() % 1_000_000} $line\n")
        }
    }

    private inner class LocalRandomAccessSource(uri: Uri, fallbackSize: Long?) : RandomAccessSource {
        private val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open $uri")
        private val fis = FileInputStream(afd.fileDescriptor)
        private val ch = fis.channel
        private val baseOffset = afd.startOffset
        override val length: Long = afd.length.takeIf { it >= 0 }
            ?: fallbackSize
            ?: runCatching { ch.size() - baseOffset }.getOrDefault(0L)

        @Synchronized
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (offset >= length || len <= 0) return if (len <= 0) 0 else -1
            val maxRead = minOf(len.toLong(), length - offset).toInt()
            val bb = ByteBuffer.wrap(buf, off, maxRead)
            var total = 0
            while (total < maxRead) {
                val n = ch.read(bb, baseOffset + offset + total)
                if (n <= 0) break
                total += n
            }
            return if (total == 0) -1 else total
        }

        override fun close() {
            runCatching { fis.close() }
            runCatching { afd.close() }
        }
    }

    private inner class RemoteRandomAccessSource(
        private val track: Track,
        initialRemote: String,
    ) : RandomAccessSource {
        private var currentRemote: String = initialRemote
        private var knownLength: Long = track.sizeBytes ?: -1L
        private var activeResponse: okhttp3.Response? = null
        private var activeStream: InputStream? = null
        private var streamOffset: Long = -1L

        override val length: Long
            get() {
                if (knownLength < 0L) {
                    getOrFetchBlock(track, currentRemote, 0L)?.let { (_, total) ->
                        if (total > 0L) knownLength = total
                    }
                }
                return if (knownLength >= 0L) knownLength else Long.MAX_VALUE
            }

        @Synchronized
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            if (knownLength in 0L..offset) return -1

            var totalRead = 0
            while (totalRead < len) {
                val curOffset = offset + totalRead
                if (knownLength in 0L..curOffset) break
                val blockIdx = curOffset / BLOCK
                val key = "${track.id}#$blockIdx"

                // Serve from the RAM block cache when already cached, or fetch Block 0 once for fast header probes.
                val cachedPair = blocks.get(key)
                    ?: if (blockIdx == 0L && activeStream == null) getOrFetchBlock(track, currentRemote, 0L) else null
                if (cachedPair != null) {
                    val (bData, total) = cachedPair
                    if (total > 0L && knownLength < 0L) {
                        knownLength = total
                        if (track.sizeBytes == null) cachedTracks[track.id] = track.copy(sizeBytes = total)
                    }
                    val rel = (curOffset - blockIdx * BLOCK).toInt()
                    if (rel in 0 until bData.size) {
                        val avail = minOf(len - totalRead, bData.size - rel)
                        System.arraycopy(bData, rel, buf, off + totalRead, avail)
                        totalRead += avail
                        continue
                    }
                }

                // Beyond cached blocks: use a continuous HTTP stream instead of synchronous 512 KB range requests,
                // avoiding per-block CDN TTFB stalls during hi-res FLAC/ALAC decoding.
                val needed = if (knownLength > 0L) {
                    minOf((len - totalRead).toLong(), knownLength - curOffset).toInt()
                } else {
                    len - totalRead
                }
                if (needed <= 0) break
                val n = readFromContinuousStream(curOffset, buf, off + totalRead, needed)
                if (n <= 0) break
                totalRead += n
            }
            return if (totalRead == 0) -1 else totalRead
        }

        private fun readFromContinuousStream(targetOffset: Long, buf: ByteArray, off: Int, len: Int): Int {
            for (attempt in 0 until 2) {
                try {
                    if (!ensureStreamAt(targetOffset)) break
                    val stream = activeStream ?: break
                    val n = stream.read(buf, off, len)
                    if (n > 0) {
                        streamOffset += n
                        return n
                    }
                    if (n < 0) {
                        closeStream()
                        // Premature EOF before knownLength: retry with a fresh Range connection.
                        if (knownLength > 0L && targetOffset < knownLength && attempt == 0) continue
                        return -1
                    }
                } catch (_: IOException) {
                    closeStream()
                }
            }
            // Fallback to a single block fetch if streaming failed.
            val blockIdx = targetOffset / BLOCK
            val (bData, total) = getOrFetchBlock(track, currentRemote, blockIdx) ?: return -1
            if (total > 0L && knownLength < 0L) {
                knownLength = total
                if (track.sizeBytes == null) cachedTracks[track.id] = track.copy(sizeBytes = total)
            }
            val rel = (targetOffset - blockIdx * BLOCK).toInt()
            if (rel !in 0 until bData.size) return -1
            val avail = minOf(len, bData.size - rel)
            System.arraycopy(bData, rel, buf, off, avail)
            return avail
        }

        private fun ensureStreamAt(targetOffset: Long): Boolean {
            val current = activeStream
            if (current != null) {
                if (targetOffset == streamOffset) return true
                if (targetOffset > streamOffset && targetOffset - streamOffset <= MAX_STREAM_SKIP_BYTES) {
                    val toSkip = targetOffset - streamOffset
                    if (skipFully(current, toSkip)) {
                        streamOffset = targetOffset
                        return true
                    }
                }
                closeStream()
            }
            fun openAt(url: String): okhttp3.Response? = executeRemote(
                Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$targetOffset-")
                    .build(),
                ok200 = true,
            )
            var res = openAt(currentRemote)
            if (res == null && track.source == Track.SOURCE_NEBULA) {
                nebulaUrls?.invalidate(track.uri, failedUrl = currentRemote)
                val fresh = nebulaUrls?.resolve(track.uri)
                if (fresh != null && fresh != currentRemote) {
                    currentRemote = fresh
                    res = openAt(currentRemote)
                }
            }
            if (res == null) return false
            val total = res.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: res.body.contentLength().takeIf { res.code == 200 && it > 0 }
            if (total != null && total > 0L && knownLength < 0L) {
                knownLength = total
                if (track.sizeBytes == null) cachedTracks[track.id] = track.copy(sizeBytes = total)
            }
            val buffered = BufferedInputStream(res.body.byteStream(), 256 * 1024)
            if (res.code == 200 && targetOffset > 0L) {
                if (!skipFully(buffered, targetOffset)) {
                    runCatching { res.close() }
                    return false
                }
            }
            activeResponse = res
            activeStream = buffered
            streamOffset = targetOffset
            return true
        }

        private fun skipFully(stream: InputStream, bytesToSkip: Long): Boolean {
            var remaining = bytesToSkip
            val discard = ByteArray(16 * 1024)
            while (remaining > 0L) {
                val s = stream.skip(remaining)
                if (s > 0L) {
                    remaining -= s
                } else {
                    val n = stream.read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
                    if (n < 0) return false
                    remaining -= n
                }
            }
            return true
        }

        private fun closeStream() {
            runCatching { activeStream?.close() }
            runCatching { activeResponse?.close() }
            activeStream = null
            activeResponse = null
            streamOffset = -1L
        }

        @Synchronized
        override fun close() {
            closeStream()
        }
    }

    private companion object {
        const val TAG = "TrackServer"
        const val PREFERRED_PORT = 8765
        /** Sonos probes in 6 KB pieces; one block covers dozens of them. */
        const val BLOCK = 512 * 1024L
        /** 16 MB at most: blocks cached for fast seeking and probe reuse. */
        const val MAX_BLOCKS = 32
        const val MAX_DOWNSAMPLERS = 2
        const val MAX_STREAM_SKIP_BYTES = 256 * 1024L
        const val REMOTE_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
        val CLOSED_RANGE = Regex("""bytes=(\d+)-(\d+)""")
        val OPEN_RANGE = Regex("""bytes=(\d+)-""")
        val ALAC_EXTS = setOf("m4a", "mp4", "alac")
        val RESAMPLE_CANDIDATE_EXTS = setOf("flac", "m4a", "mp4", "alac")

        fun encode(id: String): String =
            Base64.encodeToString(id.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun decode(s: String?): String? = s?.let { runCatching { String(Base64.decode(it, Base64.URL_SAFE)) }.getOrNull() }

        /** "bytes=START-[END]" -> inclusive range, or null if absent or unusable. */
        fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(header ?: return null) ?: return null
            val (a, b) = m.destructured
            return when {
                a.isNotEmpty() -> a.toLong() to (b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1))
                b.isNotEmpty() -> (size - b.toLong()).coerceAtLeast(0) to size - 1 // suffix range
                else -> null
            }?.takeIf { it.first <= it.second }
        }

        fun extensionOf(track: Track) = track.fileName.substringAfterLast('.', "audio").lowercase()

        /** The MIME types Sonos expects, whatever the source reported (Drive says e.g. audio/x-m4a). */
        fun mimeOf(track: Track): String = when (extensionOf(track)) {
            "m4a", "mp4", "aac", "alac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> track.mimeType ?: "application/octet-stream"
        }
    }

    fun mimeTypeFor(track: Track): String = if (willDownsampleAtQueueTime(track)) "audio/flac" else mimeOf(track)
}

private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

internal fun parseContentRangeStart(contentRange: String?): Long? =
    contentRange?.let { CONTENT_RANGE_PATTERN.find(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()

internal fun parseContentRangeLength(contentRange: String?): Long? {
    val m = contentRange?.let { CONTENT_RANGE_PATTERN.find(it.trim()) } ?: return null
    val start = m.groupValues[1].toLongOrNull() ?: return null
    val end = m.groupValues[2].toLongOrNull() ?: return null
    return if (end >= start) end - start + 1 else null
}

/**
 * Copies a remote HTTP stream into [out], transparently reconnecting with HTTP `Range` requests
 * if the upstream connection drops, stalls, or ends prematurely before [expectedLength].
 *
 * Downstream errors (writing to [out], e.g. Sonos closing the socket on seek/skip/stop) propagate
 * immediately without retrying upstream.
 */
internal fun relayRemoteWithResume(
    initialResponse: okhttp3.Response,
    initialRemote: String,
    startOffset: Long,
    expectedLength: Long?,
    out: OutputStream,
    openRange: (url: String, rangeHeader: String) -> okhttp3.Response?,
    refreshUrl: ((failedUrl: String) -> String?)? = null,
    maxResumes: Int = 5,
    onLog: (String) -> Unit = {},
) {
    var currentRes: okhttp3.Response? = initialResponse
    var currentRemote = initialRemote
    var sent = 0L
    var resumes = 0
    val buf = ByteArray(64 * 1024)

    try {
        while (true) {
            val res = currentRes ?: break
            val input = res.body.byteStream()
            var upstreamError = false
            var eof = false

            while (true) {
                val remaining = expectedLength?.let { it - sent } ?: Long.MAX_VALUE
                if (remaining <= 0L) {
                    out.flush()
                    return
                }
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = try {
                    input.read(buf, 0, toRead)
                } catch (e: IOException) {
                    onLog("Upstream read error at byte ${startOffset + sent}: $e")
                    upstreamError = true
                    -2
                }
                if (n == -2) break
                if (n < 0) {
                    eof = true
                    break
                }
                if (n > 0) {
                    // Downstream write stays outside the upstream catch so a closed Sonos socket
                    // aborts immediately without issuing upstream reconnects.
                    out.write(buf, 0, n)
                    sent += n
                }
            }

            runCatching { res.close() }
            currentRes = null

            val finished = (!upstreamError && eof && (expectedLength == null || sent >= expectedLength)) ||
                (expectedLength != null && sent >= expectedLength)
            if (finished) {
                out.flush()
                return
            }

            // Flush buffered bytes to Sonos now; also immediately detects if Sonos closed the socket.
            out.flush()

            if (resumes >= maxResumes) {
                onLog("Exhausted $maxResumes mid-stream resumes at byte ${startOffset + sent}")
                return
            }
            resumes++

            val nextOffset = startOffset + sent
            val rangeHeader = if (expectedLength != null) {
                "bytes=$nextOffset-${startOffset + expectedLength - 1}"
            } else {
                "bytes=$nextOffset-"
            }

            var nextRes = openRange(currentRemote, rangeHeader)
            if (nextRes == null && refreshUrl != null) {
                val fresh = refreshUrl(currentRemote)
                if (fresh != null) {
                    currentRemote = fresh
                    nextRes = openRange(currentRemote, rangeHeader)
                }
            }
            if (nextRes == null) {
                onLog("Resume #$resumes failed to reconnect at byte $nextOffset")
                return
            }

            val validOffset = when {
                nextRes.code == 206 -> parseContentRangeStart(nextRes.header("Content-Range")) == nextOffset
                nextRes.code == 200 && nextOffset == 0L -> true
                else -> false
            }
            if (!validOffset) {
                onLog("Resume #$resumes got misaligned response HTTP ${nextRes.code} (${nextRes.header("Content-Range")}) for byte $nextOffset")
                runCatching { nextRes.close() }
                return
            }

            onLog("Resumed upstream stream at byte $nextOffset (resume #$resumes)")
            currentRes = nextRes
        }
    } finally {
        runCatching { currentRes?.close() }
    }
}
