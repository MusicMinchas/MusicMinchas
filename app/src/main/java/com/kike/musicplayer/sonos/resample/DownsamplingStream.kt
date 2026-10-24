package com.kike.musicplayer.sonos.resample

import java.io.OutputStream
import kotlin.math.min

/**
 * Common interface over [FlacStreamDecoder] and [AlacStreamDecoder] yielding 24-bit normalized
 * signed interleaved PCM frames and supporting sample-accurate seeking.
 */
interface LosslessInputDecoder {
    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    val totalSamples: Long
    val maxBlockSamples: Int
    val lastFrameFirstSample: Long

    fun seekToSample(targetSample: Long)
    fun decodeNextFrame(outInterleaved: IntArray): Int
}

class FlacInputDecoder(
    source: RandomAccessSource,
    val info: FlacStreamInfo,
     fallbackTotalSamples: Long = 0L,
) : LosslessInputDecoder {
    private val decoder = FlacStreamDecoder(source, info)
    override val sampleRate: Int get() = info.sampleRate
    override val channels: Int get() = info.channels
    override val bitsPerSample: Int get() = info.bitsPerSample
    override val totalSamples: Long = if (info.totalSamples > 0L) info.totalSamples else fallbackTotalSamples
    override val maxBlockSamples: Int get() = info.maxBlockSize.coerceAtLeast(65536)
    override val lastFrameFirstSample: Long get() = decoder.lastFrameFirstSample

    override fun seekToSample(targetSample: Long) = decoder.seekToSample(targetSample)
    override fun decodeNextFrame(outInterleaved: IntArray): Int = decoder.decodeNextFrame(outInterleaved)
}

class AlacInputDecoder(
    source: RandomAccessSource,
    val info: AlacMp4Info,
) : LosslessInputDecoder {
    private val decoder = AlacStreamDecoder(source, info)
    override val sampleRate: Int get() = info.sampleRate
    override val channels: Int get() = info.channels
    override val bitsPerSample: Int get() = info.bitsPerSample
    override val totalSamples: Long get() = info.totalSamples
    override val maxBlockSamples: Int get() = info.maxBlockSize.coerceAtLeast(16384)
    override val lastFrameFirstSample: Long get() = decoder.lastFrameFirstSample

    override fun seekToSample(targetSample: Long) = decoder.seekToSample(targetSample)
    override fun decodeNextFrame(outInterleaved: IntArray): Int = decoder.decodeNextFrame(outInterleaved)
}

/**
 * Streams any byte range `[startByte..endByte]` of a downsampled 24-bit verbatim FLAC stream
 * on the fly from a [LosslessInputDecoder].
 *
 * Seeking to any mid-stream byte offset warms up the FIR decimator cascade using the exact
 * pre-roll of input samples prior to the target frame, guaranteeing that every frame is
 * bit-for-bit identical regardless of where the range request starts.
 */
class DownsamplingStream(
    private val decoder: LosslessInputDecoder,
    val plan: ResamplePlan,
) {
    private val resampler = Resampler(plan)
    val outputTotalSamples: Long = plan.outputSamples(decoder.totalSamples)
    val writer = VerbatimFlacWriter(
        sampleRate = plan.outputRate,
        channels = plan.channels,
        totalSamples = outputTotalSamples,
    )

    val totalStreamBytes: Long get() = writer.totalStreamBytes

    private val decodeBuf = IntArray(decoder.maxBlockSamples * plan.channels)
    private val resampledBuf = IntArray((decoder.maxBlockSamples / plan.ratio + 64) * plan.channels)
    private val framePcmBuf = IntArray(writer.blockSamples * plan.channels)
    private var frameByteBuf = ByteArray(writer.fixedMaxFrameBytes())

    // Cursor state so consecutive HTTP range requests (e.g. Sonos probe followed by main stream)
    // can continue without re-seeking if already positioned at the next frame.
    private var nextOutputFrameIndex = -1L
    private var nextInputSample = 0L
    private var pendingOutSamples = IntArray(writer.blockSamples * 4 * plan.channels)
    private var pendingOutRead = 0
    private var pendingOutWrite = 0

    /**
     * Writes the exact byte slice `[startByte..endByte]` (inclusive) of the downsampled FLAC
     * stream to [out].
     */
    fun writeRange(startByte: Long, endByte: Long, out: OutputStream) {
        val total = totalStreamBytes
        if (startByte < 0L || endByte < startByte || startByte >= total) return
        val clampedEnd = endByte.coerceAtMost(total - 1)
        var pos = startByte

        // 1. Write any requested portion of the 42-byte FLAC stream header.
        if (pos < VerbatimFlacWriter.HEADER_SIZE) {
            val header = writer.writeStreamHeader()
            val hStart = pos.toInt()
            val hEnd = min(clampedEnd + 1, VerbatimFlacWriter.HEADER_SIZE.toLong()).toInt()
            val hLen = hEnd - hStart
            out.write(header, hStart, hLen)
            pos += hLen
        }

        if (pos > clampedEnd || writer.totalFrames == 0L) {
            out.flush()
            return
        }

        // 2. Locate the first output FLAC frame covering [pos].
        var frameIdx = writer.frameIndexForByteOffset(pos)
        if (frameIdx != nextOutputFrameIndex) {
            seekToOutputFrame(frameIdx)
        }

        // 3. Generate and write frames until [clampedEnd] is satisfied.
        while (pos <= clampedEnd && frameIdx < writer.totalFrames) {
            val frameStartOffset = writer.frameByteOffset(frameIdx)
            val neededSamples = writer.frameSamples(frameIdx)
            fillFrameSamples(neededSamples)
            nextOutputFrameIndex = frameIdx + 1

            val frameLen = writer.encodeFrameInto(frameIdx, framePcmBuf, neededSamples, frameByteBuf)
            val sliceStart = (pos - frameStartOffset).toInt()
            val sliceEnd = min(clampedEnd - frameStartOffset + 1, frameLen.toLong()).toInt()
            val sliceLen = sliceEnd - sliceStart
            if (sliceLen > 0) {
                out.write(frameByteBuf, sliceStart, sliceLen)
                pos += sliceLen
            }
            frameIdx++
        }
        out.flush()
    }

    private fun seekToOutputFrame(frameIdx: Long) {
        val ch = plan.channels
        val ratio = plan.ratio
        val targetOutSample = frameIdx * writer.blockSamples
        val preRollOut = min(targetOutSample, (resampler.inputPreRollSamples / ratio).toLong())
        val startOutSample = targetOutSample - preRollOut
        val startInSample = startOutSample * ratio

        resampler.reset(startOutputSample = startOutSample)
        pendingOutRead = 0
        pendingOutWrite = 0

        decoder.seekToSample(startInSample)
        nextInputSample = startInSample

        // Decode and skip input samples until reaching startInSample, then feed preRollOut * ratio
        // input samples into the resampler and discard the preRollOut output samples.
        var discardOutRemaining = preRollOut.toInt()
        var positionedAtStart = false

        while (discardOutRemaining > 0 || !positionedAtStart) {
            val decoded = decoder.decodeNextFrame(decodeBuf)
            if (decoded <= 0) break
            val frameFirstIn = decoder.lastFrameFirstSample
            val frameEndIn = frameFirstIn + decoded
            if (!positionedAtStart) {
                if (frameEndIn <= startInSample) continue
                val skipIn = (startInSample - frameFirstIn).coerceAtLeast(0L).toInt()
                val availIn = decoded - skipIn
                positionedAtStart = true
                if (availIn > 0) {
                    feedInputSamples(decodeBuf, skipIn, availIn)
                }
            } else {
                feedInputSamples(decodeBuf, 0, decoded)
            }

            val availOut = (pendingOutWrite - pendingOutRead) / ch
            if (availOut > 0 && discardOutRemaining > 0) {
                val drop = min(availOut, discardOutRemaining)
                pendingOutRead += drop * ch
                discardOutRemaining -= drop
                if (pendingOutRead == pendingOutWrite) {
                    pendingOutRead = 0
                    pendingOutWrite = 0
                }
            }
        }
        nextOutputFrameIndex = frameIdx
    }

    private fun fillFrameSamples(neededSamples: Int) {
        val ch = plan.channels
        val neededInts = neededSamples * ch
        var writtenInts = 0

        while (writtenInts < neededInts) {
            val availInts = pendingOutWrite - pendingOutRead
            if (availInts > 0) {
                val take = min(availInts, neededInts - writtenInts)
                System.arraycopy(pendingOutSamples, pendingOutRead, framePcmBuf, writtenInts, take)
                pendingOutRead += take
                writtenInts += take
                if (pendingOutRead == pendingOutWrite) {
                    pendingOutRead = 0
                    pendingOutWrite = 0
                }
                continue
            }

            val decoded = decoder.decodeNextFrame(decodeBuf)
            if (decoded <= 0) {
                // Pad any remaining tail with zeros so the stream length is always exact.
                framePcmBuf.fill(0, writtenInts, neededInts)
                break
            }
            feedInputSamples(decodeBuf, 0, decoded)
        }
    }

    private fun feedInputSamples(src: IntArray, startFrame: Int, frameCount: Int) {
        val ch = plan.channels
        val inputSlice = if (startFrame == 0) {
            src
        } else {
            val len = frameCount * ch
            src.copyOfRange(startFrame * ch, startFrame * ch + len)
        }
        val outFrames = resampler.process(inputSlice, frameCount, resampledBuf)
        if (outFrames <= 0) return
        val outInts = outFrames * ch
        ensurePendingCapacity(outInts)
        System.arraycopy(resampledBuf, 0, pendingOutSamples, pendingOutWrite, outInts)
        pendingOutWrite += outInts
    }

    private fun ensurePendingCapacity(additionalInts: Int) {
        if (pendingOutRead > 0) {
            val rem = pendingOutWrite - pendingOutRead
            if (rem > 0) {
                System.arraycopy(pendingOutSamples, pendingOutRead, pendingOutSamples, 0, rem)
            }
            pendingOutRead = 0
            pendingOutWrite = rem
        }
        if (pendingOutWrite + additionalInts > pendingOutSamples.size) {
            var newSize = pendingOutSamples.size * 2
            while (newSize < pendingOutWrite + additionalInts) newSize *= 2
            pendingOutSamples = pendingOutSamples.copyOf(newSize)
        }
    }

    private fun VerbatimFlacWriter.fixedMaxFrameBytes(): Int =
        9 + channels + 3 * channels * blockSamples + 7
}
