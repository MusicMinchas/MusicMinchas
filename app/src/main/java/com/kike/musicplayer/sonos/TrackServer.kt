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

    @Synchronized
    fun stop() {
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
            val builder = Request.Builder().url(remote)
            // HEAD: ask Drive for one byte, which still reports the total size in Content-Range.
            if (method == "HEAD") builder.header("Range", "bytes=0-0") else range?.let { builder.header("Range", it) }
            val request = builder.build()
            val response = executeRemote(request, ok200 = true) ?: return respondStatus(out, 502)
            response.use { res ->
                val total = res.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: res.body.contentLength().takeIf { it > 0 }
                if (total != null && track.sizeBytes == null) {
                    cachedTracks[track.id] = track.copy(sizeBytes = total)
                }
                if (method == "HEAD") {
                    return writeHead(out, 200, mime, total ?: track.sizeBytes, null)
                }
                Log.i(TAG, "serveTrack streaming to Sonos: remoteCode=${res.code}, len=${res.body.contentLength()}, range=${res.header("Content-Range")}")
                writeHead(out, res.code, mime, res.body.contentLength().takeIf { it >= 0 }, res.header("Content-Range"))
                copy(res.body.byteStream(), out)
            }
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

    /**
     * Fetches, in parallel and before Sonos asks, the blocks it reads first from a Drive or Navidrome
     * song: the start, and the end where many files keep their index. Each Drive fetch takes about a
     * second, and Sonos asks for them one after the other.
     */
    fun prefetch(trackIds: List<String>) {
        Thread {
            val missing = trackIds.filter { !cachedTracks.containsKey(it) }.toSet()
            if (missing.isNotEmpty()) {
                runBlocking { dao.getByIds(missing) }.forEach { cachedTracks[it.id] = it }
            }
            val tracks = trackIds.mapNotNull { cachedTracks[it] }
            for (track in tracks) {
                if (track.localUri != null) continue
                val remote = when (track.source) {
                    Track.SOURCE_DRIVE -> track.uri
                    Track.SOURCE_SUBSONIC -> serverUrls.resolve(track.uri)
                    else -> null
                } ?: continue
                val size = track.sizeBytes ?: continue
                val last = (size - 1) / BLOCK
                for (index in listOf(0L, last, last - 1).filter { it >= 0 }.distinct()) {
                    Thread {
                        val key = "${track.id}#$index"
                        runCatching {
                            synchronized(blockLocks.computeIfAbsent(key) { Any() }) {
                                if (blocks.get(key) == null) fetchBlock(remote, index)?.let { blocks.put(key, it) }
                            }
                        }.onFailure { Log.i(TAG, "Prefetch of ${track.title} failed: $it") }
                    }.start()
                }
            }
        }.start()
    }

    /** Block [index] of the remote file and the file's total size, or null if it can't be fetched. */
    private fun getOrFetchBlock(track: Track, remote: String, index: Long): Pair<ByteArray, Long>? {
        val key = "${track.id}#$index"
        val pair = synchronized(blockLocks.computeIfAbsent(key) { Any() }) {
            blocks.get(key) ?: fetchBlock(remote, index)?.also { blocks.put(key, it) }
        } ?: return null
        val (_, total) = pair
        if (total > 0 && track.sizeBytes == null) {
            cachedTracks.computeIfPresent(track.id) { _, t -> if (t.sizeBytes == null) t.copy(sizeBytes = total) else t }
        }
        return pair
    }

    private val blockHttp = driveHttp.newBuilder()
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun fetchBlock(remote: String, index: Long): Pair<ByteArray, Long>? {
        val request = Request.Builder().url(remote).header("Range", "bytes=${index * BLOCK}-${(index + 1) * BLOCK - 1}").build()
        val response = executeRemote(request, ok200 = true, useBlockHttp = true) ?: return null
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
     * Runs a remote track request with short retries on network errors or non-206 (or non-200 when
     * [ok200] is true) responses, so a momentary Drive glitch or token refresh doesn't make Sonos
     * drop the current track and skip ahead.
     */
    private fun executeRemote(request: Request, ok200: Boolean, useBlockHttp: Boolean = false): okhttp3.Response? {
        val client = if (useBlockHttp) blockHttp else driveHttp
        for (attempt in 0 until REMOTE_ATTEMPTS) {
            if (attempt > 0) runCatching { Thread.sleep(RETRY_DELAY_MS * attempt) }
            val res = runCatching { client.newCall(request).execute() }
                .onFailure { Log.w(TAG, "Remote request failed (attempt ${attempt + 1}): $it") }
                .getOrNull()
            if (res != null && (res.code == 206 || (ok200 && res.code == 200))) return res
            if (res != null) {
                Log.w(TAG, "Remote request returned HTTP ${res.code} (attempt ${attempt + 1})")
                res.close()
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
        if (end < headerBytes.size.toLong()) {
            writeHead(out, if (partial) 206 else 200, "audio/flac", end - start + 1, if (partial) "bytes $start-$end/$size" else null)
            out.write(headerBytes, start.toInt(), (end - start + 1).toInt())
            out.flush()
            traceLog("DS HEADER-ONLY '${track.title}' range=$start-$end/${headerBytes.size}")
            return true
        }

        val session = acquireDownsampler(track, remote, format) ?: return false
        try {
            val stream = session.stream
            writeHead(
                out = out,
                code = if (partial) 206 else 200,
                mime = "audio/flac",
                length = end - start + 1,
                contentRange = if (partial) "bytes $start-$end/$size" else null,
            )
            val t0 = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "Downsampling '${track.title}' (${plan.inputRate} Hz -> ${plan.outputRate} Hz / 24-bit FLAC, range=$start-$end/$size)")
            traceLog("DS START '${track.title}' ${plan.inputRate}->${plan.outputRate} range=$start-$end/$size")
            try {
                stream.writeRange(start, end, out, shouldCancel = { session.isCancelled })
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
        for (old in activeDownsamplers) {
            if (old.trackId == track.id) {
                old.isCancelled = true
            }
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
        private val remote: String,
    ) : RandomAccessSource {
        private var knownLength: Long = track.sizeBytes ?: -1L

        override val length: Long
            get() {
                if (knownLength < 0L) {
                    getOrFetchBlock(track, remote, 0L)?.let { (_, total) ->
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
                val pair = getOrFetchBlock(track, remote, blockIdx) ?: break
                val (bData, total) = pair
                if (total > 0L && knownLength < 0L) {
                    knownLength = total
                    if (track.sizeBytes == null) cachedTracks[track.id] = track.copy(sizeBytes = total)
                }
                val rel = (curOffset - blockIdx * BLOCK).toInt()
                if (rel !in 0 until bData.size) break
                val avail = minOf(len - totalRead, bData.size - rel)
                System.arraycopy(bData, rel, buf, off + totalRead, avail)
                totalRead += avail
            }
            return if (totalRead == 0) -1 else totalRead
        }

        override fun close() {
            // Blocks are held in TrackServer's LRU cache; no persistent stream to abort.
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
