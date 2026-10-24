package com.kike.musicplayer.sonos.resample

import com.kike.musicplayer.playback.alac.AlacConfig
import com.kike.musicplayer.playback.alac.AlacFrameDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AlacMp4Info(
    val config: AlacConfig,
    val magicCookie: ByteArray,
    val totalSamples: Long,
    val packetOffsets: LongArray,
    val packetSizes: IntArray,
    val packetFirstSamples: LongArray,
) {
    val sampleRate: Int get() = config.sampleRate
    val channels: Int get() = config.numChannels
    val bitsPerSample: Int get() = config.bitDepth
    val maxBlockSize: Int get() = config.frameLength
}

/**
 * Parses MP4/M4A `moov` sample tables (`stsd`, `stts`, `stsc`, `stsz`, `stco`/`co64`) to locate
 * the ALAC configuration and every packet's exact byte offset and sample index.
 */
object AlacMp4Probe {
    fun parse(source: RandomAccessSource): AlacMp4Info? {
        val len = source.length
        if (len < 16) return null

        // Locate top-level 'moov' box (can be at start or after 'mdat' at end of file).
        var pos = 0L
        val hdr = ByteArray(16)
        while (pos + 8 <= len) {
            val n = source.readFullyAt(pos, hdr, 0, 16.coerceAtMost((len - pos).toInt()))
            if (n < 8) break
            val size32 = readBeUInt(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            val headerLen = if (size32 == 1L) 16 else 8
            val boxSize = when (size32) {
                1L -> if (n >= 16) readBeLong(hdr, 8) else return null
                0L -> len - pos
                else -> size32
            }
            if (boxSize < headerLen || pos + boxSize > len) break
            if (type == "moov") {
                val payloadSize = (boxSize - headerLen).toInt()
                if (payloadSize <= 0 || payloadSize > 32 * 1024 * 1024) return null
                val moovBytes = ByteArray(payloadSize)
                if (source.readFullyAt(pos + headerLen, moovBytes, 0, payloadSize) != payloadSize) return null
                return parseMoov(moovBytes)
            }
            pos += boxSize
        }
        return null
    }

    private fun parseMoov(moov: ByteArray): AlacMp4Info? {
        forEachBox(moov, 0, moov.size) { trakType, trakStart, trakEnd ->
            if (trakType == "trak") {
                parseTrak(moov, trakStart, trakEnd)?.let { return it }
            }
        }
        return null
    }

    private fun parseTrak(buf: ByteArray, start: Int, end: Int): AlacMp4Info? {
        forEachBox(buf, start, end) { t1, s1, e1 ->
            if (t1 == "mdia") {
                forEachBox(buf, s1, e1) { t2, s2, e2 ->
                    if (t2 == "minf") {
                        forEachBox(buf, s2, e2) { t3, s3, e3 ->
                            if (t3 == "stbl") {
                                parseStbl(buf, s3, e3)?.let { return it }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun parseStbl(buf: ByteArray, start: Int, end: Int): AlacMp4Info? {
        var magicCookie: ByteArray? = null
        var config: AlacConfig? = null
        var sttsCounts = IntArray(0)
        var sttsDeltas = IntArray(0)
        var stscFirstChunk = IntArray(0)
        var stscSamplesPerChunk = IntArray(0)
        var stszDefaultSize = 0
        var stszCount = 0
        var stszSizes = IntArray(0)
        var chunkOffsets = LongArray(0)

        forEachBox(buf, start, end) { type, s, e ->
            when (type) {
                "stsd" -> {
                    parseAlacStsd(buf, s, e)?.let { (cookie, cfg) ->
                        magicCookie = cookie
                        config = cfg
                    }
                }
                "stts" -> {
                    if (s + 8 <= e) {
                        val entries = readBeInt(buf, s + 4)
                        if (entries >= 0 && s + 8 + entries * 8 <= e) {
                            sttsCounts = IntArray(entries)
                            sttsDeltas = IntArray(entries)
                            var p = s + 8
                            for (i in 0 until entries) {
                                sttsCounts[i] = readBeInt(buf, p)
                                sttsDeltas[i] = readBeInt(buf, p + 4)
                                p += 8
                            }
                        }
                    }
                }
                "stsc" -> {
                    if (s + 8 <= e) {
                        val entries = readBeInt(buf, s + 4)
                        if (entries >= 0 && s + 8 + entries * 12 <= e) {
                            stscFirstChunk = IntArray(entries)
                            stscSamplesPerChunk = IntArray(entries)
                            var p = s + 8
                            for (i in 0 until entries) {
                                stscFirstChunk[i] = readBeInt(buf, p)
                                stscSamplesPerChunk[i] = readBeInt(buf, p + 4)
                                p += 12
                            }
                        }
                    }
                }
                "stsz" -> {
                    if (s + 12 <= e) {
                        stszDefaultSize = readBeInt(buf, s + 4)
                        stszCount = readBeInt(buf, s + 8)
                        if (stszDefaultSize == 0 && stszCount >= 0 && s + 12 + stszCount * 4 <= e) {
                            stszSizes = IntArray(stszCount)
                            var p = s + 12
                            for (i in 0 until stszCount) {
                                stszSizes[i] = readBeInt(buf, p)
                                p += 4
                            }
                        }
                    }
                }
                "stco" -> {
                    if (s + 8 <= e) {
                        val entries = readBeInt(buf, s + 4)
                        if (entries >= 0 && s + 8 + entries * 4 <= e) {
                            chunkOffsets = LongArray(entries)
                            var p = s + 8
                            for (i in 0 until entries) {
                                chunkOffsets[i] = readBeUInt(buf, p)
                                p += 4
                            }
                        }
                    }
                }
                "co64" -> {
                    if (s + 8 <= e) {
                        val entries = readBeInt(buf, s + 4)
                        if (entries >= 0 && s + 8 + entries * 8 <= e) {
                            chunkOffsets = LongArray(entries)
                            var p = s + 8
                            for (i in 0 until entries) {
                                chunkOffsets[i] = readBeLong(buf, p)
                                p += 8
                            }
                        }
                    }
                }
            }
        }

        val cfg = config ?: return null
        val cookie = magicCookie ?: return null
        if (stszCount <= 0 || chunkOffsets.isEmpty() || stscFirstChunk.isEmpty()) return null

        val packetSizes = if (stszDefaultSize > 0) {
            IntArray(stszCount) { stszDefaultSize }
        } else {
            stszSizes
        }
        if (packetSizes.size != stszCount) return null

        // Build per-packet byte offsets from chunkOffsets + stsc
        val packetOffsets = LongArray(stszCount)
        var packetIdx = 0
        val numChunks = chunkOffsets.size
        var stscIdx = 0
        for (chunkZeroBased in 0 until numChunks) {
            val chunkOneBased = chunkZeroBased + 1
            while (stscIdx + 1 < stscFirstChunk.size && stscFirstChunk[stscIdx + 1] <= chunkOneBased) {
                stscIdx++
            }
            val samplesInChunk = stscSamplesPerChunk[stscIdx]
            var offset = chunkOffsets[chunkZeroBased]
            for (k in 0 until samplesInChunk) {
                if (packetIdx >= stszCount) break
                packetOffsets[packetIdx] = offset
                offset += packetSizes[packetIdx]
                packetIdx++
            }
        }
        if (packetIdx == 0) return null

        // Build per-packet first-sample index and totalSamples from stts
        val packetFirstSamples = LongArray(packetIdx)
        var accSamples = 0L
        var pkt = 0
        for (i in sttsCounts.indices) {
            val count = sttsCounts[i]
            val delta = sttsDeltas[i].toLong()
            for (k in 0 until count) {
                if (pkt < packetIdx) {
                    packetFirstSamples[pkt++] = accSamples
                }
                accSamples += delta
            }
        }
        while (pkt < packetIdx) {
            packetFirstSamples[pkt++] = accSamples
            accSamples += cfg.frameLength
        }

        return AlacMp4Info(
            config = cfg,
            magicCookie = cookie,
            totalSamples = accSamples,
            packetOffsets = if (packetIdx == stszCount) packetOffsets else packetOffsets.copyOf(packetIdx),
            packetSizes = if (packetIdx == stszCount) packetSizes else packetSizes.copyOf(packetIdx),
            packetFirstSamples = packetFirstSamples,
        )
    }

    private fun parseAlacStsd(buf: ByteArray, start: Int, end: Int): Pair<ByteArray, AlacConfig>? {
        if (start + 8 > end) return null
        forEachBox(buf, start + 8, end) { entryType, es, ee ->
            if (entryType == "alac" && ee - es >= 28) {
                // AudioSampleEntry has 28 bytes of header (or 44 for QuickTime v1); scan for child 'alac' box.
                val version = ((buf[es + 8].toInt() and 0xFF) shl 8) or (buf[es + 9].toInt() and 0xFF)
                val childOffset = when (version) {
                    1 -> 44
                    2 -> 64
                    else -> 28
                }
                if (es + childOffset < ee) {
                    forEachBox(buf, es + childOffset, ee) { childType, cs, ce ->
                        if (childType == "alac" && ce - cs >= 28) {
                            // FullBox 'alac': 4 bytes version/flags + 24-byte ALACSpecificConfig
                            val cookie = buf.copyOfRange(cs + 4, ce)
                            val cfg = runCatching { AlacConfig.parse(cookie) }.getOrNull()
                            if (cfg != null) return cookie to cfg
                        }
                    }
                }
            }
        }
        return null
    }

    private inline fun forEachBox(buf: ByteArray, start: Int, end: Int, block: (String, Int, Int) -> Unit) {
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readBeUInt(buf, pos)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            val headerLen = if (size32 == 1L) 16 else 8
            val boxSize = when (size32) {
                1L -> if (pos + 16 <= end) readBeLong(buf, pos + 8) else break
                0L -> (end - pos).toLong()
                else -> size32
            }
            if (boxSize < headerLen || pos + boxSize > end) break
            block(type, pos + headerLen, (pos + boxSize).toInt())
            pos += boxSize.toInt()
        }
    }

    private fun readBeInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun readBeUInt(b: ByteArray, off: Int): Long =
        readBeInt(b, off).toLong() and 0xFFFFFFFFL

    private fun readBeLong(b: ByteArray, off: Int): Long =
        (readBeUInt(b, off) shl 32) or readBeUInt(b, off + 4)
}

/**
 * Decodes ALAC packets from an MP4/M4A [RandomAccessSource] into 24-bit signed interleaved PCM.
 */
class AlacStreamDecoder(
    private val source: RandomAccessSource,
    val info: AlacMp4Info,
) {
    private val frameDecoder = AlacFrameDecoder(info.magicCookie)
    private var packetBuf = ByteArray(info.packetSizes.maxOrNull()?.coerceAtLeast(4096) ?: 4096)
    private val pcmByteBuf = ByteBuffer.allocate(frameDecoder.maxOutputBytes).order(ByteOrder.LITTLE_ENDIAN)
    private var currentPacketIndex = 0

    var lastFrameFirstSample: Long = 0L
        private set

    fun seekToSample(targetSample: Long) {
        if (targetSample <= 0L || info.packetFirstSamples.isEmpty()) {
            currentPacketIndex = 0
            lastFrameFirstSample = 0L
            return
        }
        val firsts = info.packetFirstSamples
        var lo = 0
        var hi = firsts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (firsts[mid] <= targetSample) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        currentPacketIndex = lo
        lastFrameFirstSample = firsts[lo]
    }

    fun decodeNextFrame(outInterleaved: IntArray): Int {
        if (currentPacketIndex >= info.packetOffsets.size) return -1
        val idx = currentPacketIndex++
        val offset = info.packetOffsets[idx]
        val size = info.packetSizes[idx]
        lastFrameFirstSample = info.packetFirstSamples[idx]

        if (packetBuf.size < size) packetBuf = ByteArray(size)
        if (source.readFullyAt(offset, packetBuf, 0, size) < size) return -1

        pcmByteBuf.clear()
        val samples = frameDecoder.decode(ByteBuffer.wrap(packetBuf, 0, size), pcmByteBuf)
        val totalValues = samples * info.channels
        val bps = frameDecoder.bytesPerSample
        val raw = pcmByteBuf.array()
        var bPos = 0

        when (bps) {
            2 -> { // 16-bit LE -> shift left by 8 to 24-bit scale
                for (i in 0 until totalValues) {
                    val s16 = (raw[bPos].toInt() and 0xFF) or (raw[bPos + 1].toInt() shl 8)
                    outInterleaved[i] = s16 shl 8
                    bPos += 2
                }
            }
            3 -> { // 20/24-bit packed 3-byte LE -> sign-extend to 24-bit
                for (i in 0 until totalValues) {
                    val s24 = (raw[bPos].toInt() and 0xFF) or
                        ((raw[bPos + 1].toInt() and 0xFF) shl 8) or
                        (raw[bPos + 2].toInt() shl 16)
                    outInterleaved[i] = s24
                    bPos += 3
                }
            }
            4 -> { // 32-bit LE -> shift right by 8 to 24-bit scale
                for (i in 0 until totalValues) {
                    val s32 = (raw[bPos].toInt() and 0xFF) or
                        ((raw[bPos + 1].toInt() and 0xFF) shl 8) or
                        ((raw[bPos + 2].toInt() and 0xFF) shl 16) or
                        (raw[bPos + 3].toInt() shl 24)
                    outInterleaved[i] = s32 shr 8
                    bPos += 4
                }
            }
        }
        return samples
    }
}
