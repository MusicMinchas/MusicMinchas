package com.kike.musicplayer.sonos.resample

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Random-access byte source used by the stream demuxers and decoders.
 * Implementations can wrap a local file, an in-memory buffer, or a remote HTTP stream
 * backed by `TrackServer`'s block cache.
 */
interface RandomAccessSource : Closeable {
    val length: Long

    /**
     * Reads up to [len] bytes starting at [offset] into [buf] at [off].
     * Returns the number of bytes actually read, or -1 at EOF.
     */
    fun readAt(offset: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int

    fun readFullyAt(offset: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        var total = 0
        while (total < len) {
            val n = readAt(offset + total, buf, off + total, len - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val length: Long get() = data.size.toLong()

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (offset >= data.size || len <= 0) return if (len <= 0) 0 else -1
        val avail = min(len.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buf, off, avail)
        return avail
    }

    override fun close() {}
}

class FileRandomAccessSource(file: File) : RandomAccessSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    @Synchronized
    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (offset >= length || len <= 0) return if (len <= 0) 0 else -1
        raf.seek(offset)
        return raf.read(buf, off, len)
    }

    override fun close() = raf.close()
}

data class FlacSeekPoint(
    val sampleNumber: Long,
    val streamByteOffset: Long,
    val frameSamples: Int,
)

data class FlacStreamInfo(
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val minFrameSize: Int,
    val maxFrameSize: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val firstFrameOffset: Long,
    val seekPoints: List<FlacSeekPoint> = emptyList(),
) {
    val sharedSeekPoints: java.util.concurrent.CopyOnWriteArrayList<FlacSeekPoint> =
        java.util.concurrent.CopyOnWriteArrayList(seekPoints)
}

/**
 * Parses FLAC `STREAMINFO` and `SEEKTABLE` metadata blocks from the header of a stream.
 */
object FlacProbe {
    /**
     * Quick check on an in-memory prefix (at least 42 bytes) to extract `STREAMINFO` if the file
     * starts directly with `"fLaC"`. Used for fast on-demand probing (e.g. Nebula block 0).
     */
    fun parseHeaderBytes(bytes: ByteArray): FlacStreamInfo? {
        if (bytes.size < 42) return null
        return parse(ByteArraySource(bytes))
    }

    fun parse(source: RandomAccessSource): FlacStreamInfo? {
        val scratch = ByteArray(42)
        if (source.readFullyAt(0L, scratch, 0, 10) < 10) return null

        var pos = 0L
        // Skip optional leading ID3v2 tag if present.
        if (scratch[0] == 'I'.code.toByte() && scratch[1] == 'D'.code.toByte() && scratch[2] == '3'.code.toByte()) {
            val tagSize = ((scratch[6].toInt() and 0x7F) shl 21) or
                ((scratch[7].toInt() and 0x7F) shl 14) or
                ((scratch[8].toInt() and 0x7F) shl 7) or
                (scratch[9].toInt() and 0x7F)
            pos = 10L + tagSize
            if (source.readFullyAt(pos, scratch, 0, 4) < 4) return null
        }

        if (scratch[0] != 'f'.code.toByte() ||
            scratch[1] != 'L'.code.toByte() ||
            scratch[2] != 'a'.code.toByte() ||
            scratch[3] != 'C'.code.toByte()
        ) {
            return null
        }
        pos += 4L

        var minBlock = 0
        var maxBlock = 0
        var minFrame = 0
        var maxFrame = 0
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var totalSamples = 0L
        var hasStreamInfo = false
        val seekPoints = ArrayList<FlacSeekPoint>()

        while (true) {
            if (source.readFullyAt(pos, scratch, 0, 4) < 4) break
            val headerByte = scratch[0].toInt() and 0xFF
            val isLast = (headerByte and 0x80) != 0
            val type = headerByte and 0x7F
            val length = ((scratch[1].toInt() and 0xFF) shl 16) or
                ((scratch[2].toInt() and 0xFF) shl 8) or
                (scratch[3].toInt() and 0xFF)
            pos += 4L

            if (type == 0 && length >= 34) {
                if (source.readFullyAt(pos, scratch, 0, 34) < 34) return null
                minBlock = ((scratch[0].toInt() and 0xFF) shl 8) or (scratch[1].toInt() and 0xFF)
                maxBlock = ((scratch[2].toInt() and 0xFF) shl 8) or (scratch[3].toInt() and 0xFF)
                minFrame = ((scratch[4].toInt() and 0xFF) shl 16) or ((scratch[5].toInt() and 0xFF) shl 8) or (scratch[6].toInt() and 0xFF)
                maxFrame = ((scratch[7].toInt() and 0xFF) shl 16) or ((scratch[8].toInt() and 0xFF) shl 8) or (scratch[9].toInt() and 0xFF)
                sampleRate = ((scratch[10].toInt() and 0xFF) shl 12) or
                    ((scratch[11].toInt() and 0xFF) shl 4) or
                    ((scratch[12].toInt() and 0xF0) ushr 4)
                channels = ((scratch[12].toInt() ushr 1) and 0x07) + 1
                bitsPerSample = (((scratch[12].toInt() and 0x01) shl 4) or ((scratch[13].toInt() and 0xF0) ushr 4)) + 1
                totalSamples = ((scratch[13].toLong() and 0x0FL) shl 32) or
                    ((scratch[14].toLong() and 0xFFL) shl 24) or
                    ((scratch[15].toLong() and 0xFFL) shl 16) or
                    ((scratch[16].toLong() and 0xFFL) shl 8) or
                    (scratch[17].toLong() and 0xFFL)
                hasStreamInfo = true
            } else if (type == 3 && length >= 18 && length <= 512 * 1024) {
                val tableBytes = ByteArray(length)
                if (source.readFullyAt(pos, tableBytes, 0, length) == length) {
                    val count = length / 18
                    for (i in 0 until count) {
                        val b = i * 18
                        val sampleNum = readBeLong(tableBytes, b)
                        if (sampleNum != -1L) { // 0xFFFFFFFFFFFFFFFF is a placeholder seekpoint
                            val streamOff = readBeLong(tableBytes, b + 8)
                            val fSamples = ((tableBytes[b + 16].toInt() and 0xFF) shl 8) or (tableBytes[b + 17].toInt() and 0xFF)
                            seekPoints.add(FlacSeekPoint(sampleNum, streamOff, fSamples))
                        }
                    }
                }
            }

            pos += length
            if (isLast) break
        }

        if (!hasStreamInfo || sampleRate <= 0 || channels <= 0) return null
        return FlacStreamInfo(
            minBlockSize = minBlock,
            maxBlockSize = maxBlock,
            minFrameSize = minFrame,
            maxFrameSize = maxFrame,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            totalSamples = totalSamples,
            firstFrameOffset = pos,
            seekPoints = seekPoints,
        )
    }

    private fun readBeLong(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFFL) shl 56) or
            ((b[off + 1].toLong() and 0xFFL) shl 48) or
            ((b[off + 2].toLong() and 0xFFL) shl 40) or
            ((b[off + 3].toLong() and 0xFFL) shl 32) or
            ((b[off + 4].toLong() and 0xFFL) shl 24) or
            ((b[off + 5].toLong() and 0xFFL) shl 16) or
            ((b[off + 6].toLong() and 0xFFL) shl 8) or
            (b[off + 7].toLong() and 0xFFL)
}

/**
 * Pure-Kotlin lossless FLAC decoder supporting 16/20/24/32-bit streams, all subframe types
 * (CONSTANT, VERBATIM, FIXED order 0..4, LPC order 1..32), RICE and RICE2 partitions, wasted bits,
 * all stereo decorrelation modes, and sample-accurate seeking (both via `SEEKTABLE` and via
 * interpolated frame-header bisection when a file has no `SEEKTABLE`).
 *
 * Output samples are normalized to signed 24-bit integers (`[-8388608, 8388607]`) interleaved by channel.
 */
class FlacStreamDecoder(
    private val source: RandomAccessSource,
    val info: FlacStreamInfo,
    fallbackTotalSamples: Long = 0L,
) {
    private val reader = BufferedBitReader(source, info.firstFrameOffset)
    private val maxBlock = info.maxBlockSize.coerceAtLeast(65536)
    private val subframes = Array(info.channels) { LongArray(maxBlock) }
    private val lpcCoeffs = IntArray(32)
    private val effectiveTotalSamples: Long = if (info.totalSamples > 0L) info.totalSamples else fallbackTotalSamples
    private val dynamicSeekPoints = info.sharedSeekPoints

    /** Sample number of the first sample of the most recently decoded frame. */
    var lastFrameFirstSample: Long = 0L
        private set

    /**
     * Seeks so that the next decoded frames start at or just before [targetSample].
     * Uses `SEEKTABLE` when available and falls back to fast interpolated byte-offset bisection
     * when `SEEKTABLE` is absent or sparse, avoiding full-stream scans on large hi-res files.
     */
    fun seekToSample(targetSample: Long) {
        if (targetSample <= 0L) {
            reader.reset(info.firstFrameOffset)
            lastFrameFirstSample = 0L
            return
        }

        var best: FlacSeekPoint? = null
        var next: FlacSeekPoint? = null
        for (p in dynamicSeekPoints) {
            if (p.sampleNumber <= targetSample) {
                if (best == null || p.sampleNumber > best.sampleNumber) best = p
            } else {
                if (next == null || p.sampleNumber < next.sampleNumber) next = p
            }
        }

        val closeThreshold = info.sampleRate.toLong() * 3L // Within 3 seconds before targetSample
        if (best != null && targetSample - best.sampleNumber <= closeThreshold) {
            reader.reset(info.firstFrameOffset + best.streamByteOffset)
            lastFrameFirstSample = best.sampleNumber
            return
        }

        val fileLen = source.length
        if (effectiveTotalSamples > 0L && fileLen > info.firstFrameOffset + 32768L && fileLen < Long.MAX_VALUE / 2) {
            var lowByte = if (best != null) info.firstFrameOffset + best.streamByteOffset else info.firstFrameOffset
            var lowSample = best?.sampleNumber ?: 0L
            var highByte = if (next != null) info.firstFrameOffset + next.streamByteOffset else fileLen
            var highSample = next?.sampleNumber ?: effectiveTotalSamples

            val preRollAim = (info.sampleRate / 2).toLong()
            for (iter in 0 until 5) {
                if (targetSample - lowSample <= closeThreshold || highByte - lowByte <= 65536L) break
                val aimSample = (targetSample - preRollAim).coerceAtLeast(lowSample)
                val sampleSpan = (highSample - lowSample).coerceAtLeast(1L)
                val byteSpan = (highByte - lowByte).coerceAtLeast(1L)
                val frac = ((aimSample - lowSample).toDouble() / sampleSpan.toDouble()).coerceIn(0.0, 0.98)
                val safetyBytes = if (iter == 0) 32768L else 16384L
                val guessByte = (lowByte + (byteSpan * frac).toLong() - safetyBytes)
                    .coerceIn(lowByte, (highByte - 8192L).coerceAtLeast(lowByte))

                val probed = probeValidFrameAt(guessByte, maxScanBytes = 192 * 1024)
                if (probed == null) {
                    highByte = guessByte
                    continue
                }
                recordSeekPoint(probed.sampleNumber, probed.streamByteOffset, probed.frameSamples)
                val frameBytePos = info.firstFrameOffset + probed.streamByteOffset
                if (probed.sampleNumber <= targetSample) {
                    if (frameBytePos <= lowByte) break
                    lowByte = frameBytePos
                    lowSample = probed.sampleNumber
                    if (targetSample - lowSample <= closeThreshold) break
                } else {
                    if (frameBytePos >= highByte) break
                    highByte = frameBytePos
                    highSample = probed.sampleNumber
                }
            }

            reader.reset(lowByte)
            lastFrameFirstSample = lowSample
            return
        }

        if (best != null) {
            reader.reset(info.firstFrameOffset + best.streamByteOffset)
            lastFrameFirstSample = best.sampleNumber
            return
        }

        reader.reset(info.firstFrameOffset)
        lastFrameFirstSample = 0L
    }

    /**
     * Scans forward from [startByteOffset] up to [maxScanBytes] to find a genuine FLAC frame
     * verified by header fields, header CRC-8, full subframe decoding, and footer CRC-16.
     */
    private fun probeValidFrameAt(startByteOffset: Long, maxScanBytes: Int): FlacSeekPoint? {
        reader.reset(startByteOffset)
        val limitByte = startByteOffset + maxScanBytes
        while (!reader.isEof && reader.bytePosition < limitByte) {
            if (!syncToNextFrame(limitByte)) return null
            val frameStartBytePos = reader.bytePosition
            if (frameStartBytePos >= limitByte) return null
            val decoded = tryDecodeFrameAtCurrentPos(outInterleaved = null)
            if (decoded > 0) {
                return FlacSeekPoint(
                    sampleNumber = lastFrameFirstSample,
                    streamByteOffset = frameStartBytePos - info.firstFrameOffset,
                    frameSamples = decoded,
                )
            }
            reader.reset(frameStartBytePos + 1)
        }
        return null
    }

    /**
     * Decodes the next FLAC frame into [outInterleaved] (which must have capacity >= `maxBlockSize * channels`).
     * Returns the number of samples per channel decoded, or -1 at EOF.
     */
    fun decodeNextFrame(outInterleaved: IntArray): Int {
        while (!reader.isEof) {
            if (!syncToNextFrame(Long.MAX_VALUE)) return -1
            val frameStartBytePos = reader.bytePosition
            val decoded = tryDecodeFrameAtCurrentPos(outInterleaved)
            if (decoded > 0) {
                recordSeekPoint(lastFrameFirstSample, frameStartBytePos - info.firstFrameOffset, decoded)
                return decoded
            }
            if (reader.isEof) return -1
            // False sync word in mid-stream data: advance 1 byte in buffer and continue syncing.
            reader.reset(frameStartBytePos + 1)
        }
        return -1
    }

    private fun tryDecodeFrameAtCurrentPos(outInterleaved: IntArray?): Int {
        reader.alignToByte()
        val frameStartBytePos = reader.bytePosition
        reader.pinFrameStart(frameStartBytePos)
        try {
            return tryDecodePinnedFrame(frameStartBytePos, outInterleaved)
        } finally {
            reader.unpinFrameStart()
        }
    }

    private fun tryDecodePinnedFrame(frameStartBytePos: Long, outInterleaved: IntArray?): Int {
        val sync = reader.readBits(15).toInt() // 14-bit sync 0x3FFE + 1-bit reserved (0) = 0x7FFC
        if (sync != 0x7FFC) return -1
        val blockingStrategy = reader.readBits(1).toInt()
        val blockSizeCode = reader.readBits(4).toInt()
        val sampleRateCode = reader.readBits(4).toInt()
        val channelAssignment = reader.readBits(4).toInt()
        val sampleSizeCode = reader.readBits(3).toInt()
        val reservedBit = reader.readBits(1).toInt()
        if (reservedBit != 0 || blockSizeCode == 0 || sampleRateCode == 15) return -1

        val chCount = when (channelAssignment) {
            in 0..7 -> channelAssignment + 1
            8, 9, 10 -> 2
            else -> return -1
        }
        if (chCount != info.channels) return -1

        val frameBps = when (sampleSizeCode) {
            0 -> info.bitsPerSample
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            7 -> 32
            else -> return -1
        }
        if (frameBps != info.bitsPerSample) return -1

        val codedNumber = reader.readUtf8()
        if (codedNumber < 0L) return -1

        val blockSamples = when (blockSizeCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockSizeCode - 2)
            6 -> reader.readBits(8).toInt() + 1
            7 -> reader.readBits(16).toInt() + 1
            in 8..15 -> 256 shl (blockSizeCode - 8)
            else -> return -1
        }
        if (blockSamples <= 0 || blockSamples > maxBlock) return -1

        when (sampleRateCode) {
            12 -> if (reader.readBits(8) < 0) return -1
            13, 14 -> if (reader.readBits(16) < 0) return -1
        }

        val headerCrc8 = reader.readBits(8).toInt()
        if (headerCrc8 < 0) return -1
        val headerLen = (reader.bytePosition - frameStartBytePos - 1).toInt()
        if (headerLen <= 0 || reader.computeCrc8From(frameStartBytePos, headerLen) != headerCrc8) {
            return -1
        }

        val candidateFirstSample = if (blockingStrategy == 0) {
            codedNumber * info.minBlockSize.toLong()
        } else {
            codedNumber
        }
        if (effectiveTotalSamples > 0L && candidateFirstSample >= effectiveTotalSamples + maxBlock) {
            return -1
        }

        for (c in 0 until chCount) {
            // Side channel in left-side (ch 1), side-right (ch 0), or mid-side (ch 1) has +1 bit depth!
            val extraBit = (channelAssignment == 8 && c == 1) ||
                (channelAssignment == 9 && c == 0) ||
                (channelAssignment == 10 && c == 1)
            val subBps = frameBps + if (extraBit) 1 else 0
            if (!decodeSubframe(subframes[c], blockSamples, subBps)) return -1
        }

        reader.alignToByte()
        val bodyByteLen = (reader.bytePosition - frameStartBytePos).toInt()
        val footerCrc16 = reader.readBits(16).toInt()
        if (footerCrc16 < 0 || bodyByteLen <= headerLen) return -1
        if (reader.computeCrc16From(frameStartBytePos, bodyByteLen) != footerCrc16) {
            return -1
        }

        lastFrameFirstSample = candidateFirstSample
        if (outInterleaved == null) return blockSamples

        // Channel decorrelation
        when (channelAssignment) {
            8 -> { // Left-side
                val left = subframes[0]
                val side = subframes[1]
                for (i in 0 until blockSamples) side[i] = left[i] - side[i]
            }
            9 -> { // Side-right
                val side = subframes[0]
                val right = subframes[1]
                for (i in 0 until blockSamples) side[i] = side[i] + right[i]
            }
            10 -> { // Mid-side
                val mid = subframes[0]
                val side = subframes[1]
                for (i in 0 until blockSamples) {
                    val s = side[i]
                    val m = (mid[i] shl 1) or (s and 1L)
                    mid[i] = (m + s) shr 1
                    side[i] = (m - s) shr 1
                }
            }
        }

        // Normalize to 24-bit signed integer scale and interleave
        val shiftTo24 = 24 - frameBps
        var outIdx = 0
        if (shiftTo24 > 0) {
            for (i in 0 until blockSamples) {
                for (c in 0 until chCount) {
                    outInterleaved[outIdx++] = (subframes[c][i].toInt() shl shiftTo24)
                }
            }
        } else if (shiftTo24 == 0) {
            for (i in 0 until blockSamples) {
                for (c in 0 until chCount) {
                    outInterleaved[outIdx++] = subframes[c][i].toInt()
                }
            }
        } else {
            val rshift = -shiftTo24
            for (i in 0 until blockSamples) {
                for (c in 0 until chCount) {
                    outInterleaved[outIdx++] = (subframes[c][i] shr rshift).toInt()
                }
            }
        }

        return blockSamples
    }

    private fun recordSeekPoint(sampleNumber: Long, streamByteOffset: Long, frameSamples: Int) {
        if (sampleNumber < 0L || streamByteOffset < 0L) return
        val minSpacing = info.sampleRate.toLong() // At most 1 dynamic seek point per second
        for (existing in dynamicSeekPoints) {
            if (kotlin.math.abs(existing.sampleNumber - sampleNumber) < minSpacing) return
        }
        synchronized(dynamicSeekPoints) {
            var insertIdx = 0
            for (i in dynamicSeekPoints.indices) {
                val existing = dynamicSeekPoints[i]
                if (kotlin.math.abs(existing.sampleNumber - sampleNumber) < minSpacing) return
                if (existing.sampleNumber < sampleNumber) insertIdx = i + 1
            }
            dynamicSeekPoints.add(insertIdx, FlacSeekPoint(sampleNumber, streamByteOffset, frameSamples))
        }
    }

    private fun decodeSubframe(dst: LongArray, blockSamples: Int, subframeBps: Int): Boolean {
        val zeroBit = reader.readBits(1).toInt()
        if (zeroBit != 0) return false
        val typeCode = reader.readBits(6).toInt()
        val hasWasted = reader.readBits(1).toInt()
        if (hasWasted < 0) return false
        var wasted = 0
        if (hasWasted != 0) {
            wasted = 1 + reader.readUnary(maxZeros = 32)
            if (wasted > 32) return false
        }
        val bps = subframeBps - wasted
        if (bps !in 1..33) return false

        when {
            typeCode == 0 -> { // SUBFRAME_CONSTANT
                val v = reader.readSignedBits(bps)
                dst.fill(v, 0, blockSamples)
            }
            typeCode == 1 -> { // SUBFRAME_VERBATIM
                for (i in 0 until blockSamples) {
                    dst[i] = reader.readSignedBits(bps)
                }
            }
            typeCode in 8..12 -> { // SUBFRAME_FIXED
                val order = typeCode and 0x07
                if (blockSamples <= order) return false
                for (i in 0 until order) {
                    dst[i] = reader.readSignedBits(bps)
                }
                if (!decodeResidual(dst, order, blockSamples)) return false
                when (order) {
                    0 -> {}
                    1 -> for (i in 1 until blockSamples) dst[i] += dst[i - 1]
                    2 -> for (i in 2 until blockSamples) dst[i] += 2L * dst[i - 1] - dst[i - 2]
                    3 -> for (i in 3 until blockSamples) dst[i] += 3L * dst[i - 1] - 3L * dst[i - 2] + dst[i - 3]
                    4 -> for (i in 4 until blockSamples) dst[i] += 4L * dst[i - 1] - 6L * dst[i - 2] + 4L * dst[i - 3] - dst[i - 4]
                }
            }
            typeCode >= 32 -> { // SUBFRAME_LPC
                val order = (typeCode and 0x1F) + 1
                if (blockSamples <= order) return false
                for (i in 0 until order) {
                    dst[i] = reader.readSignedBits(bps)
                }
                val qlpPrec = reader.readBits(4).toInt() + 1
                if (qlpPrec <= 0 || qlpPrec == 16) return false
                val qlpShift = reader.readSignedBits(5).toInt()
                if (qlpShift < 0) return false
                for (i in 0 until order) {
                    lpcCoeffs[i] = reader.readSignedBits(qlpPrec).toInt()
                }
                if (!decodeResidual(dst, order, blockSamples)) return false
                for (i in order until blockSamples) {
                    var sum = 0L
                    for (j in 0 until order) {
                        sum += lpcCoeffs[j].toLong() * dst[i - 1 - j]
                    }
                    dst[i] += sum shr qlpShift
                }
            }
            else -> return false
        }

        if (wasted > 0) {
            for (i in 0 until blockSamples) {
                dst[i] = dst[i] shl wasted
            }
        }
        return true
    }

    private fun decodeResidual(dst: LongArray, predictorOrder: Int, blockSamples: Int): Boolean {
        val method = reader.readBits(2).toInt()
        val paramBits = when (method) {
            0 -> 4
            1 -> 5
            else -> return false
        }
        val escapeCode = (1 shl paramBits) - 1
        val partitionOrder = reader.readBits(4).toInt()
        if (partitionOrder < 0) return false
        val numPartitions = 1 shl partitionOrder
        if ((blockSamples and (numPartitions - 1)) != 0) return false
        val partSamples = blockSamples ushr partitionOrder
        if (partSamples < predictorOrder) return false

        var sampleIdx = predictorOrder
        for (p in 0 until numPartitions) {
            val count = if (p == 0) partSamples - predictorOrder else partSamples
            val riceParam = reader.readBits(paramBits).toInt()
            if (riceParam < 0) return false
            if (riceParam == escapeCode) {
                val rawBits = reader.readBits(5).toInt()
                if (rawBits < 0) return false
                if (rawBits == 0) {
                    for (i in 0 until count) dst[sampleIdx++] = 0L
                } else {
                    for (i in 0 until count) {
                        dst[sampleIdx++] = reader.readSignedBits(rawBits)
                    }
                }
            } else {
                for (i in 0 until count) {
                    val q = reader.readUnary(maxZeros = 65536)
                    if (q < 0) return false
                    val r = if (riceParam > 0) {
                        val rv = reader.readBits(riceParam)
                        if (rv < 0L) return false
                        rv
                    } else {
                        0L
                    }
                    val u = (q.toLong() shl riceParam) or r
                    dst[sampleIdx++] = (u ushr 1) xor -(u and 1L)
                }
            }
        }
        return true
    }

    private fun syncToNextFrame(limitByte: Long): Boolean {
        reader.alignToByte()
        while (!reader.isEof && reader.bytePosition < limitByte) {
            val b0 = reader.peekByte(0)
            if (b0 == 0xFF) {
                val b1 = reader.peekByte(1)
                if (b1 >= 0 && (b1 and 0xFC) == 0xF8) {
                    return true
                }
            }
            reader.skipByte()
        }
        return false
    }
}

/**
 * Buffered bit reader over a [RandomAccessSource] with in-memory seek reuse, byte-level peek,
 * and CRC-8 / CRC-16 support.
 */
internal class BufferedBitReader(
    private val source: RandomAccessSource,
    startOffset: Long,
) {
    private val buf = ByteArray(BUFFER_SIZE)
    private var bufStartOffset = startOffset
    private var bufValid = 0
    private var bufPos = 0
    private var pinnedOffset = -1L

    private var bitAcc = 0L
    private var bitsAvailable = 0

    init {
        reset(startOffset)
    }

    val bytePosition: Long
        get() = bufStartOffset + bufPos - (bitsAvailable ushr 3)

    val isEof: Boolean
        get() {
            if (bitsAvailable > 0 || bufPos < bufValid) return false
            return !refillAppend()
        }

    fun pinFrameStart(offset: Long) {
        pinnedOffset = offset
    }

    fun unpinFrameStart() {
        pinnedOffset = -1L
    }

    fun reset(offset: Long) {
        val rel = offset - bufStartOffset
        if (bufValid > 0 && rel >= 0L && rel < bufValid.toLong()) {
            bufPos = rel.toInt()
            bitAcc = 0L
            bitsAvailable = 0
            return
        }
        bufStartOffset = offset
        bufValid = 0
        bufPos = 0
        bitAcc = 0L
        bitsAvailable = 0
        refillAppend()
    }

    fun alignToByte() {
        val rem = bitsAvailable and 7
        if (rem > 0) {
            bitsAvailable -= rem
        }
    }

    fun peekByte(ahead: Int): Int {
        alignToByte()
        val targetOffset = bytePosition + ahead
        while (targetOffset >= bufStartOffset + bufValid) {
            if (!refillAppend()) return -1
        }
        val rel = (targetOffset - bufStartOffset).toInt()
        return if (rel in 0 until bufValid) buf[rel].toInt() and 0xFF else -1
    }

    fun skipByte() {
        readBits(8)
    }

    fun readBits(n: Int): Long {
        if (n == 0) return 0L
        while (bitsAvailable < n) {
            if (bufPos >= bufValid) {
                if (!refillAppend()) return -1L
            }
            bitAcc = (bitAcc shl 8) or (buf[bufPos++].toLong() and 0xFFL)
            bitsAvailable += 8
        }
        bitsAvailable -= n
        val mask = if (n == 64) -1L else (1L shl n) - 1L
        return (bitAcc ushr bitsAvailable) and mask
    }

    fun readSignedBits(n: Int): Long {
        val u = readBits(n)
        val shift = 64 - n
        return (u shl shift) shr shift
    }

    fun readUnary(maxZeros: Int = Int.MAX_VALUE): Int {
        var zeros = 0
        while (true) {
            if (bitsAvailable == 0) {
                if (bufPos >= bufValid) {
                    if (!refillAppend()) return -1
                }
                bitAcc = buf[bufPos++].toLong() and 0xFFL
                bitsAvailable = 8
            }
            val top = (bitAcc shl (64 - bitsAvailable))
            if (top == 0L) {
                zeros += bitsAvailable
                bitsAvailable = 0
                if (zeros > maxZeros) return -1
            } else {
                val lz = java.lang.Long.numberOfLeadingZeros(top)
                zeros += lz
                bitsAvailable -= (lz + 1)
                return if (zeros > maxZeros) -1 else zeros
            }
        }
    }

    fun readUtf8(): Long {
        val first = readBits(8).toInt()
        if (first < 0) return -1L
        if ((first and 0x80) == 0) return first.toLong()
        val n = when {
            (first and 0xE0) == 0xC0 -> 2
            (first and 0xF0) == 0xE0 -> 3
            (first and 0xF8) == 0xF0 -> 4
            (first and 0xFC) == 0xF8 -> 5
            (first and 0xFE) == 0xFC -> 6
            first == 0xFE -> 7
            else -> return -1L
        }
        var v = if (n == 7) 0L else (first and ((1 shl (7 - n)) - 1)).toLong()
        for (i in 1 until n) {
            val b = readBits(8).toInt()
            if (b < 0 || (b and 0xC0) != 0x80) return -1L
            v = (v shl 6) or (b and 0x3F).toLong()
        }
        return v
    }

    fun computeCrc8From(startByteOffset: Long, len: Int): Int {
        val rel = (startByteOffset - bufStartOffset).toInt()
        if (rel >= 0 && rel + len <= bufValid) {
            return FlacCrc.crc8(buf, rel, len)
        }
        return -1
    }

    fun computeCrc16From(startByteOffset: Long, len: Int): Int {
        val rel = (startByteOffset - bufStartOffset).toInt()
        if (rel >= 0 && rel + len <= bufValid) {
            return FlacCrc.crc16(buf, rel, len)
        }
        return -1
    }

    /**
     * Appends more bytes from [source] at `bufStartOffset + bufValid` (strictly sequential!)
     * while preserving [pinnedOffset] and a [BACK_WINDOW] behind [bytePosition] in RAM.
     */
    private fun refillAppend(): Boolean {
        val curBytePos = bytePosition
        val minKeepOffset = if (pinnedOffset >= bufStartOffset) {
            minOf(pinnedOffset, (curBytePos - BACK_WINDOW).coerceAtLeast(bufStartOffset))
        } else {
            (curBytePos - BACK_WINDOW).coerceAtLeast(bufStartOffset)
        }
        val keepStartIdx = (minKeepOffset - bufStartOffset).toInt().coerceIn(0, bufValid)
        if (keepStartIdx > 0 && (bufValid >= buf.size - MIN_READ_SPACE || keepStartIdx >= COMPACT_THRESHOLD)) {
            val rem = bufValid - keepStartIdx
            if (rem > 0) {
                System.arraycopy(buf, keepStartIdx, buf, 0, rem)
            }
            bufStartOffset += keepStartIdx
            bufPos -= keepStartIdx
            bufValid = rem
        }
        val space = buf.size - bufValid
        if (space <= 0) return false
        val readTarget = minOf(space, CHUNK_READ_SIZE)
        val n = source.readAt(bufStartOffset + bufValid, buf, bufValid, readTarget)
        if (n <= 0) return false
        bufValid += n
        return true
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val CHUNK_READ_SIZE = 64 * 1024
        private const val MIN_READ_SPACE = 32 * 1024
        private const val COMPACT_THRESHOLD = 64 * 1024
        private const val BACK_WINDOW = 8 * 1024L
    }
}
