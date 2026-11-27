package com.kike.musicplayer.sonos

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Okio
import okio.buffer
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class TrackServerRelayTest {

    private fun testPayload(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + 17) and 0xFF).toByte() }

    private fun streamResponse(
        url: String,
        code: Int,
        stream: InputStream,
        contentLength: Long,
        contentRange: String? = null,
    ): Response {
        val body = object : ResponseBody() {
            override fun contentType() = "audio/flac".toMediaType()
            override fun contentLength() = contentLength
            override fun source(): BufferedSource = stream.source().buffer()
        }
        val builder = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 206) "Partial Content" else "OK")
            .body(body)
        if (contentRange != null) builder.header("Content-Range", contentRange)
        return builder.build()
    }

    /** InputStream that yields [bytes] and then throws [IOException] to simulate an upstream CDN drop. */
    private class FailingAfterBytesInputStream(private val bytes: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos >= bytes.size) throw IOException("Simulated upstream connection reset")
            return bytes[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) throw IOException("Simulated upstream connection reset")
            val n = minOf(len, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun relayResumesAfterMidStreamIoException() {
        val payload = testPayload(300_000)
        val dropAt = 102_400
        val url = "https://cdn.example.com/track.flac"

        val initial = streamResponse(
            url = url,
            code = 200,
            stream = FailingAfterBytesInputStream(payload.copyOfRange(0, dropAt)),
            contentLength = payload.size.toLong(),
        )

        val seenRanges = mutableListOf<String>()
        val out = ByteArrayOutputStream()

        relayRemoteWithResume(
            initialResponse = initial,
            initialRemote = url,
            startOffset = 0L,
            expectedLength = payload.size.toLong(),
            out = out,
            openRange = { reqUrl, rangeHeader ->
                seenRanges += rangeHeader
                assertEquals(url, reqUrl)
                val slice = payload.copyOfRange(dropAt, payload.size)
                streamResponse(
                    url = reqUrl,
                    code = 206,
                    stream = ByteArrayInputStream(slice),
                    contentLength = slice.size.toLong(),
                    contentRange = "bytes $dropAt-${payload.size - 1}/${payload.size}",
                )
            },
        )

        assertEquals(listOf("bytes=$dropAt-${payload.size - 1}"), seenRanges)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun relayResumesAfterPrematureEof() {
        val payload = testPayload(200_000)
        val cutAt = 50_000
        val url = "https://cdn.example.com/track.flac"

        val initial = streamResponse(
            url = url,
            code = 200,
            stream = ByteArrayInputStream(payload.copyOfRange(0, cutAt)),
            contentLength = payload.size.toLong(),
        )

        val out = ByteArrayOutputStream()
        var resumeCount = 0

        relayRemoteWithResume(
            initialResponse = initial,
            initialRemote = url,
            startOffset = 0L,
            expectedLength = payload.size.toLong(),
            out = out,
            openRange = { reqUrl, rangeHeader ->
                resumeCount++
                assertEquals("bytes=$cutAt-${payload.size - 1}", rangeHeader)
                val slice = payload.copyOfRange(cutAt, payload.size)
                streamResponse(
                    url = reqUrl,
                    code = 206,
                    stream = ByteArrayInputStream(slice),
                    contentLength = slice.size.toLong(),
                    contentRange = "bytes $cutAt-${payload.size - 1}/${payload.size}",
                )
            },
        )

        assertEquals(1, resumeCount)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun relayRefreshesNebulaUrlWhenReconnectFails() {
        val payload = testPayload(256_000)
        val dropAt = 65_536
        val staleUrl = "https://cdn1.torbox.app/expired.flac"
        val freshUrl = "https://cdn2.torbox.app/fresh.flac"

        val initial = streamResponse(
            url = staleUrl,
            code = 200,
            stream = FailingAfterBytesInputStream(payload.copyOfRange(0, dropAt)),
            contentLength = payload.size.toLong(),
        )

        val out = ByteArrayOutputStream()
        val refreshedUrls = mutableListOf<String>()

        relayRemoteWithResume(
            initialResponse = initial,
            initialRemote = staleUrl,
            startOffset = 0L,
            expectedLength = payload.size.toLong(),
            out = out,
            openRange = { reqUrl, rangeHeader ->
                if (reqUrl == staleUrl) {
                    null // e.g., expired URL returned 403/404
                } else {
                    assertEquals(freshUrl, reqUrl)
                    assertEquals("bytes=$dropAt-${payload.size - 1}", rangeHeader)
                    val slice = payload.copyOfRange(dropAt, payload.size)
                    streamResponse(
                        url = reqUrl,
                        code = 206,
                        stream = ByteArrayInputStream(slice),
                        contentLength = slice.size.toLong(),
                        contentRange = "bytes $dropAt-${payload.size - 1}/${payload.size}",
                    )
                }
            },
            refreshUrl = { failed ->
                refreshedUrls += failed
                freshUrl
            },
        )

        assertEquals(listOf(staleUrl), refreshedUrls)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun relayStopsImmediatelyWhenDownstreamSocketCloses() {
        val payload = testPayload(200_000)
        val url = "https://cdn.example.com/track.flac"

        val initial = streamResponse(
            url = url,
            code = 200,
            stream = ByteArrayInputStream(payload),
            contentLength = payload.size.toLong(),
        )

        val failingSonosOut = object : OutputStream() {
            var written = 0
            override fun write(b: Int) {
                throw IOException("Broken pipe")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written >= 32_768) throw IOException("Broken pipe")
                written += len
            }
        }

        var upstreamRetries = 0
        var urlRefreshes = 0

        try {
            relayRemoteWithResume(
                initialResponse = initial,
                initialRemote = url,
                startOffset = 0L,
                expectedLength = payload.size.toLong(),
                out = failingSonosOut,
                openRange = { _, _ ->
                    upstreamRetries++
                    null
                },
                refreshUrl = {
                    urlRefreshes++
                    null
                },
            )
            fail("Expected downstream IOException to propagate")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Broken pipe") == true)
        }

        assertEquals(0, upstreamRetries)
        assertEquals(0, urlRefreshes)
    }

    @Test
    fun relayAbortsOnMisalignedRangeResponse() {
        val payload = testPayload(128_000)
        val dropAt = 65_536
        val url = "https://cdn.example.com/track.flac"

        val initial = streamResponse(
            url = url,
            code = 200,
            stream = FailingAfterBytesInputStream(payload.copyOfRange(0, dropAt)),
            contentLength = payload.size.toLong(),
        )

        val out = ByteArrayOutputStream()

        relayRemoteWithResume(
            initialResponse = initial,
            initialRemote = url,
            startOffset = 0L,
            expectedLength = payload.size.toLong(),
            out = out,
            openRange = { reqUrl, _ ->
                // Misbehaving server ignores Range and replies 200 OK from byte 0
                streamResponse(
                    url = reqUrl,
                    code = 200,
                    stream = ByteArrayInputStream(payload),
                    contentLength = payload.size.toLong(),
                )
            },
        )

        // Must stop cleanly at dropAt instead of writing duplicate bytes from offset 0
        assertEquals(dropAt, out.size())
        assertArrayEquals(payload.copyOfRange(0, dropAt), out.toByteArray())
    }

    @Test
    fun parseContentRangeHelpers() {
        assertEquals(1024L, parseContentRangeStart("bytes 1024-2047/4096"))
        assertEquals(1024L, parseContentRangeLength("bytes 1024-2047/4096"))
        assertEquals(0L, parseContentRangeStart("bytes 0-0/*"))
        assertEquals(1L, parseContentRangeLength("bytes 0-0/*"))
        assertNull(parseContentRangeStart(null))
        assertNull(parseContentRangeLength("invalid"))
    }
}
