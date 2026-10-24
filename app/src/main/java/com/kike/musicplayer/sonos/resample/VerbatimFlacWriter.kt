package com.kike.musicplayer.sonos.resample

/**
 * Deterministic 24-bit uncompressed ("verbatim") FLAC stream writer with exact O(1)
 * byte-offset <-> frame-index mapping.
 *
 * Every frame uses a fixed structure:
 * - Frame header: 4 fixed bytes + UTF-8(frameIndex) + 2-byte (blockSamples - 1) + 1-byte CRC-8
 * - [channels] independent verbatim subframes: 1-byte subframe header (0x02) + 3 * blockSamples bytes
 * - Frame footer: 2-byte CRC-16
 *
 * Because all fields are byte-aligned and every frame's byte length is a deterministic function
 * of [frameIndex] and [blockSamples], the total file size and the exact byte offset of any frame
 * are known in O(1) time without encoding prior frames. This lets `TrackServer` answer HTTP
 * `HEAD` and `Range` requests (probing, read-ahead, and `REL_TIME` seeks) accurately.
 */
class VerbatimFlacWriter(
    val sampleRate: Int,
    val channels: Int,
    val totalSamples: Long,
    val blockSamples: Int = DEFAULT_BLOCK_SAMPLES,
) {
    init {
        require(channels in 1..8) { "Unsupported channel count $channels" }
        require(blockSamples in 16..65535) { "Unsupported block size $blockSamples" }
        require(totalSamples >= 0) { "totalSamples must be non-negative" }
    }

    private val sampleRateCode: Int = when (sampleRate) {
        88_200 -> 1; 176_400 -> 2; 192_000 -> 3; 8_000 -> 4; 16_000 -> 5; 22_050 -> 6
        24_000 -> 7; 32_000 -> 8; 44_100 -> 9; 48_000 -> 10; 96_000 -> 11
        else -> 0
    }

    /** Total number of FLAC frames in the stream. */
    val totalFrames: Long = if (totalSamples == 0L) 0L else (totalSamples + blockSamples - 1) / blockSamples

    /** Exact total byte length of the complete FLAC stream (42-byte header + all frames). */
    val totalStreamBytes: Long = computeTotalStreamBytes()

    /** Number of samples per channel in frame [frameIndex] (0 until [totalFrames]). */
    fun frameSamples(frameIndex: Long): Int {
        require(frameIndex in 0 until totalFrames) { "frameIndex $frameIndex out of 0 until $totalFrames" }
        return if (frameIndex < totalFrames - 1) {
            blockSamples
        } else {
            val rem = (totalSamples - frameIndex * blockSamples).toInt()
            if (rem > 0) rem else blockSamples
        }
    }

    /** Exact byte size of frame [frameIndex]. */
    fun frameSizeBytes(frameIndex: Long): Int {
        val n = frameSamples(frameIndex)
        return fixedFramePayloadBytes(n) + utf8Len(frameIndex)
    }

    /** Exact byte offset in the FLAC stream where frame [frameIndex] begins. */
    fun frameByteOffset(frameIndex: Long): Long {
        require(frameIndex in 0..totalFrames) { "frameIndex $frameIndex out of 0..$totalFrames" }
        if (frameIndex == 0L) return HEADER_SIZE.toLong()
        if (frameIndex == totalFrames) return totalStreamBytes
        val fullFrameBase = fixedFramePayloadBytes(blockSamples).toLong()
        return HEADER_SIZE + frameIndex * fullFrameBase + sumUtf8Lens(frameIndex)
    }

    /**
     * Returns the index of the frame (`0 until totalFrames`) that contains [byteOffset]
     * (where `byteOffset >= HEADER_SIZE` and `byteOffset < totalStreamBytes`).
     */
    fun frameIndexForByteOffset(byteOffset: Long): Long {
        require(byteOffset in HEADER_SIZE.toLong() until totalStreamBytes) {
            "byteOffset $byteOffset out of $HEADER_SIZE until $totalStreamBytes"
        }
        val rel = byteOffset - HEADER_SIZE
        val minFullFrame = fixedFramePayloadBytes(blockSamples) + 1
        val maxFullFrame = fixedFramePayloadBytes(blockSamples) + 7
        var lo = (rel / maxFullFrame).coerceAtLeast(0L)
        var hi = (rel / minFullFrame + 2).coerceAtMost(totalFrames - 1)
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (frameByteOffset(mid) <= byteOffset) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return lo
    }

    /** Generates the 42-byte `fLaC` + `STREAMINFO` header. */
    fun writeStreamHeader(): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        out[0] = 'f'.code.toByte()
        out[1] = 'L'.code.toByte()
        out[2] = 'a'.code.toByte()
        out[3] = 'C'.code.toByte()
        // Last metadata block flag (0x80) | block type 0 (STREAMINFO), length = 34 bytes
        out[4] = 0x80.toByte()
        out[5] = 0
        out[6] = 0
        out[7] = 34

        // min block size (16 bits) & max block size (16 bits)
        out[8] = (blockSamples ushr 8).toByte()
        out[9] = blockSamples.toByte()
        out[10] = (blockSamples ushr 8).toByte()
        out[11] = blockSamples.toByte()

        // min frame size (24 bits)
        val minFrame = if (totalFrames > 0) frameSizeBytes(totalFrames - 1) else 0
        out[12] = (minFrame ushr 16).toByte()
        out[13] = (minFrame ushr 8).toByte()
        out[14] = minFrame.toByte()

        // max frame size (24 bits)
        val maxFrame = if (totalFrames > 0) fixedFramePayloadBytes(blockSamples) + utf8Len(totalFrames - 1) else 0
        out[15] = (maxFrame ushr 16).toByte()
        out[16] = (maxFrame ushr 8).toByte()
        out[17] = maxFrame.toByte()

        // 20 bits sampleRate, 3 bits (channels - 1), 5 bits (bitsPerSample - 1 = 23), 36 bits totalSamples
        val bpsMinus1 = BITS_PER_SAMPLE - 1
        val chMinus1 = channels - 1
        out[18] = (sampleRate ushr 12).toByte()
        out[19] = (sampleRate ushr 4).toByte()
        out[20] = (((sampleRate and 0xF) shl 4) or (chMinus1 shl 1) or (bpsMinus1 ushr 4)).toByte()
        out[21] = (((bpsMinus1 and 0xF) shl 4) or ((totalSamples ushr 32) and 0xFL).toInt()).toByte()
        out[22] = (totalSamples ushr 24).toByte()
        out[23] = (totalSamples ushr 16).toByte()
        out[24] = (totalSamples ushr 8).toByte()
        out[25] = totalSamples.toByte()
        // Bytes 26..41 are the 16-byte MD5 signature (all zeros = unset, valid per FLAC spec).
        return out
    }

    /**
     * Encodes [frames] interleaved 24-bit signed samples from [pcm] into frame [frameIndex],
     * returning the frame bytes (exact length == `frameSizeBytes(frameIndex)`).
     */
    fun encodeFrame(frameIndex: Long, pcm: IntArray, frames: Int): ByteArray {
        val size = fixedFramePayloadBytes(frames) + utf8Len(frameIndex)
        val buf = ByteArray(size)
        encodeFrameInto(frameIndex, pcm, frames, buf)
        return buf
    }

    /**
     * Encodes [frames] interleaved 24-bit signed samples from [pcm] into [dest] starting at offset 0.
     * Returns the number of bytes written.
     */
    fun encodeFrameInto(frameIndex: Long, pcm: IntArray, frames: Int, dest: ByteArray): Int {
        var pos = 0
        // Sync code 0xFFF8 (fixed-blocksize stream)
        dest[pos++] = 0xFF.toByte()
        dest[pos++] = 0xF8.toByte()
        // Block size = 0b0111 (16-bit (frames - 1) at end of header), sampleRateCode
        dest[pos++] = ((0b0111 shl 4) or sampleRateCode).toByte()
        // Channel assignment = independent (channels - 1), sample size = 24-bit (0b110), reserved = 0
        dest[pos++] = (((channels - 1) shl 4) or (0b110 shl 1)).toByte()

        // UTF-8 coded frameIndex
        pos = writeUtf8(dest, pos, frameIndex)

        // 16-bit (frames - 1)
        val fm1 = frames - 1
        dest[pos++] = (fm1 ushr 8).toByte()
        dest[pos++] = fm1.toByte()

        // Header CRC-8
        val crc8 = FlacCrc.crc8(dest, 0, pos)
        dest[pos++] = crc8.toByte()

        // Independent verbatim subframes (1 per channel)
        val chCount = channels
        for (c in 0 until chCount) {
            // Subframe header: 0 (1 bit), TYPE_VERBATIM = 000001 (6 bits), wasted = 0 (1 bit) -> 0x02
            dest[pos++] = 0x02
            var idx = c
            for (i in 0 until frames) {
                val s = pcm[idx]
                dest[pos++] = (s ushr 16).toByte()
                dest[pos++] = (s ushr 8).toByte()
                dest[pos++] = s.toByte()
                idx += chCount
            }
        }

        // Footer CRC-16
        val crc16 = FlacCrc.crc16(dest, 0, pos)
        dest[pos++] = (crc16 ushr 8).toByte()
        dest[pos++] = crc16.toByte()
        return pos
    }

    private fun fixedFramePayloadBytes(frames: Int): Int =
        9 + channels + 3 * channels * frames

    private fun computeTotalStreamBytes(): Long {
        if (totalFrames == 0L) return HEADER_SIZE.toLong()
        val fullFrames = totalFrames - 1
        val fullBytes = fullFrames * fixedFramePayloadBytes(blockSamples).toLong()
        val lastSamples = frameSamples(totalFrames - 1)
        val lastBytes = fixedFramePayloadBytes(lastSamples).toLong()
        return HEADER_SIZE + fullBytes + lastBytes + sumUtf8Lens(totalFrames)
    }

    companion object {
        const val HEADER_SIZE = 42
        const val DEFAULT_BLOCK_SAMPLES = 4096
        const val BITS_PER_SAMPLE = 24

        /** Number of bytes required to encode [v] in FLAC's UTF-8 variable-length format. */
        fun utf8Len(v: Long): Int = when {
            v < 0x80L -> 1
            v < 0x800L -> 2
            v < 0x10000L -> 3
            v < 0x200000L -> 4
            v < 0x4000000L -> 5
            v < 0x80000000L -> 6
            else -> 7
        }

        /** Closed-form O(1) sum of `utf8Len(i)` for `i in 0 until count`. */
        fun sumUtf8Lens(count: Long): Long {
            if (count <= 0L) return 0L
            var sum = count
            if (count > 0x80L) sum += count - 0x80L
            if (count > 0x800L) sum += count - 0x800L
            if (count > 0x10000L) sum += count - 0x10000L
            if (count > 0x200000L) sum += count - 0x200000L
            if (count > 0x4000000L) sum += count - 0x4000000L
            if (count > 0x80000000L) sum += count - 0x80000000L
            return sum
        }

        private fun writeUtf8(buf: ByteArray, offset: Int, v: Long): Int {
            var pos = offset
            val n = utf8Len(v)
            if (n == 1) {
                buf[pos++] = v.toByte()
                return pos
            }
            val lead = if (n == 7) 0xFE else ((0xFF00 shr n) and 0xFF) or (v ushr (6 * (n - 1))).toInt()
            buf[pos++] = lead.toByte()
            for (i in n - 2 downTo 0) {
                buf[pos++] = (0x80 or ((v ushr (6 * i)) and 0x3F).toInt()).toByte()
            }
            return pos
        }
    }
}

internal object FlacCrc {
    private val table8 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
        c and 0xFF
    }
    private val table16 = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
        c and 0xFFFF
    }

    fun crc8(b: ByteArray, off: Int, len: Int): Int {
        var c = 0
        for (i in off until off + len) c = table8[c xor (b[i].toInt() and 0xFF)]
        return c
    }

    fun crc16(b: ByteArray, off: Int, len: Int): Int {
        var c = 0
        for (i in off until off + len) c = ((c shl 8) xor table16[(c ushr 8) xor (b[i].toInt() and 0xFF)]) and 0xFFFF
        return c
    }
}
