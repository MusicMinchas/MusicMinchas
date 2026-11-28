package com.kike.musicplayer.sonos

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class SonosHttpResponseTest {

    @Test
    fun readHttpResponseReturnsImmediatelyOnContentLengthWithoutWaitingForEof() {
        val body = """<s:Envelope><CurrentVolume>35</CurrentVolume></s:Envelope>"""
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "CONTENT-LENGTH: ${body.toByteArray().size}\r\n" +
            "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n\r\n" +
            body).toByteArray(Charsets.UTF_8)

        // If readHttpResponse attempts to read past Content-Length waiting for TCP FIN, fail the test.
        val noEofStream = object : InputStream() {
            private var pos = 0
            override fun read(): Int {
                if (pos >= raw.size) throw IOException("Blocked waiting for EOF after Content-Length")
                return raw[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= raw.size) throw IOException("Blocked waiting for EOF after Content-Length")
                val n = minOf(len, raw.size - pos)
                System.arraycopy(raw, pos, b, off, n)
                pos += n
                return n
            }
        }

        val result = readHttpResponse(noEofStream)
        assertEquals(String(raw, Charsets.UTF_8), String(result, Charsets.UTF_8))
    }

    @Test
    fun readHttpResponseHandlesSplitTcpPackets() {
        val body = """<s:Envelope><u:GetGroupVolumeResponse><CurrentVolume>42</CurrentVolume></u:GetGroupVolumeResponse></s:Envelope>"""
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" +
            body).toByteArray(Charsets.UTF_8)

        val tinyChunkStream = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < raw.size) raw[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= raw.size) throw IOException("Read past Content-Length")
                val n = minOf(len, 7, raw.size - pos)
                System.arraycopy(raw, pos, b, off, n)
                pos += n
                return n
            }
        }

        val result = readHttpResponse(tinyChunkStream)
        assertEquals(String(raw, Charsets.UTF_8), String(result, Charsets.UTF_8))
    }

    @Test
    fun readHttpResponseFallsBackToEofForChunkedResponse() {
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n" +
            "5\r\nhello\r\n0\r\n\r\n").toByteArray(Charsets.UTF_8)

        val result = readHttpResponse(ByteArrayInputStream(raw))
        assertEquals(String(raw, Charsets.UTF_8), String(result, Charsets.UTF_8))
    }
}
