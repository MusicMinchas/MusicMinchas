package com.kike.musicplayer.sonos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.subsonic.SubsonicUrls
import com.kike.musicplayer.data.db.TrackDao
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
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Minimal HTTP server that lets Sonos fetch tracks and covers from the phone. Drive files are
 * relayed with the user's OAuth token (which never leaves the phone); files in device folders are
 * served from the content provider. Range requests are supported, so Sonos can seek and read ahead.
 *
 * URLs carry a random per-run token, so other devices on the network can't browse the library.
 */
class TrackServer(
    private val context: Context,
    private val dao: TrackDao,
    private val driveHttp: OkHttpClient,
    private val serverUrls: SubsonicUrls,
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

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
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
    fun trackUrl(base: String, entryId: Long, track: Track): String =
        "$base/t/$token/$entryId/${encode(track.id)}.${extensionOf(track)}"

    fun artUrl(base: String, track: Track): String? =
        track.artworkUri?.let { "$base/a/$token/${encode(track.id)}" }

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

    private fun serveTrack(out: OutputStream, method: String, trackId: String?, range: String?) {
        val track = trackId?.let { runBlocking { dao.get(it) } } ?: return respondStatus(out, 404)
        val mime = mimeOf(track)
        // Drive and server songs not on the phone are relayed from where they live.
        val remote = when {
            track.localUri != null -> null
            track.source == Track.SOURCE_DRIVE -> track.uri
            track.source == Track.SOURCE_SUBSONIC -> serverUrls.resolve(track.uri) ?: return respondStatus(out, 503)
            else -> null
        }
        if (remote != null) {
            val builder = Request.Builder().url(remote)
            // HEAD: ask Drive for one byte, which still reports the total size in Content-Range.
            if (method == "HEAD") builder.header("Range", "bytes=0-0") else range?.let { builder.header("Range", it) }
            driveHttp.newCall(builder.build()).execute().use { response ->
                if (response.code != 200 && response.code != 206) return respondStatus(out, 502)
                val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                if (method == "HEAD") {
                    return writeHead(out, 200, mime, total ?: track.sizeBytes, null)
                }
                writeHead(out, response.code, mime, response.body.contentLength().takeIf { it >= 0 }, response.header("Content-Range"))
                copy(response.body.byteStream(), out)
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

    private fun serveArt(out: OutputStream, method: String, trackId: String?) {
        val art = trackId?.let { runBlocking { dao.get(it) } }?.artworkUri ?: return respondStatus(out, 404)
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

    private companion object {
        const val TAG = "TrackServer"
        const val PREFERRED_PORT = 8765

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

    fun mimeTypeFor(track: Track) = mimeOf(track)
}
