package com.kike.musicplayer.playback.alac

import java.nio.ByteBuffer

/**
 * Apple Lossless frame decoder: a Kotlin port of Apple's open-source reference decoder
 * (ALACDecoder.cpp, ag_dec.c, dp_dec.c, matrix_dec.c — Apache License 2.0,
 * https://github.com/macosforge/alac). Output is interleaved little-endian PCM:
 * 16-bit for 16-bit sources, packed 24-bit for 20/24-bit sources, 32-bit for 32-bit sources.
 *
 * We need our own because some vendor decoders (Qualcomm c2.qti.alac.*) reject valid files.
 */
class AlacFrameDecoder(magicCookie: ByteArray) {
    val config = AlacConfig.parse(magicCookie)

    val bytesPerSample: Int = when (config.bitDepth) {
        16 -> 2
        20, 24 -> 3
        32 -> 4
        else -> throw IllegalArgumentException("Unsupported ALAC bit depth ${config.bitDepth}")
    }

    /** Upper bound for one decoded frame, in bytes. */
    val maxOutputBytes: Int = config.frameLength * config.numChannels * bytesPerSample

    private val predictor = IntArray(config.frameLength)
    private val mixU = IntArray(config.frameLength)
    private val mixV = IntArray(config.frameLength)
    private val shiftBuffer = IntArray(config.frameLength * 2)
    private val coefsU = IntArray(32)
    private val coefsV = IntArray(32)
    private var input = ByteArray(0)

    /**
     * Decodes one ALAC packet (as stored in one MP4 sample) into [out], starting at its current
     * position. Returns the number of samples per channel written.
     */
    fun decode(packet: ByteBuffer, out: ByteBuffer): Int {
        val size = packet.remaining()
        // Padding lets the bit reader load 8 bytes at a time without bounds checks.
        if (input.size < size + PADDING) input = ByteArray(size + PADDING)
        packet.duplicate().get(input, 0, size)
        input.fill(0, size, size + PADDING)
        val bits = BitReader(input, size)

        val numChannels = config.numChannels
        val outBase = out.position()
        var channelIndex = 0
        var numSamples = config.frameLength

        while (true) {
            if (bits.bitPos >= size * 8) break
            when (bits.read(3)) {
                ID_SCE, ID_LFE -> {
                    bits.read(4) // element instance tag
                    check(bits.read(12) == 0) { "Bad ALAC header" }
                    val header = bits.read(4)
                    val partialFrame = header ushr 3
                    var bytesShifted = (header ushr 1) and 3
                    check(bytesShifted != 3) { "Bad ALAC shift" }
                    val escape = header and 1
                    val chanBits = config.bitDepth - bytesShifted * 8
                    if (partialFrame != 0) numSamples = bits.read(32)
                    check(numSamples in 1..config.frameLength) { "Bad ALAC frame size $numSamples" }

                    var shiftPos = 0
                    if (escape == 0) {
                        bits.read(8); bits.read(8) // mixBits / mixRes: unused for mono
                        val modeHeader = bits.read(8)
                        val modeU = modeHeader ushr 4
                        val denShiftU = modeHeader and 0xF
                        val pbHeader = bits.read(8)
                        val pbFactorU = pbHeader ushr 5
                        val numU = pbHeader and 0x1F
                        for (i in 0 until numU) coefsU[i] = bits.read(16).toShort().toInt()
                        if (bytesShifted != 0) {
                            shiftPos = bits.bitPos
                            bits.bitPos += bytesShifted * 8 * numSamples
                        }
                        dynDecomp(bits, numSamples, chanBits, (config.pb * pbFactorU) / 4)
                        predict(mixU, numSamples, modeU, coefsU, numU, chanBits, denShiftU)
                    } else {
                        readUncompressed(bits, mixU, null, numSamples, chanBits)
                        bytesShifted = 0
                    }
                    if (bytesShifted != 0) {
                        val shiftBits = BitReader(input, size).also { it.bitPos = shiftPos }
                        for (i in 0 until numSamples) shiftBuffer[i] = shiftBits.read(bytesShifted * 8)
                    }
                    writeMono(out, outBase, channelIndex, numSamples, bytesShifted)
                    channelIndex += 1
                }
                ID_CPE -> {
                    check(channelIndex + 2 <= numChannels) { "Too many ALAC channels" }
                    bits.read(4)
                    check(bits.read(12) == 0) { "Bad ALAC header" }
                    val header = bits.read(4)
                    val partialFrame = header ushr 3
                    var bytesShifted = (header ushr 1) and 3
                    check(bytesShifted != 3) { "Bad ALAC shift" }
                    val escape = header and 1
                    val chanBits = config.bitDepth - bytesShifted * 8 + 1
                    if (partialFrame != 0) numSamples = bits.read(32)
                    check(numSamples in 1..config.frameLength) { "Bad ALAC frame size $numSamples" }

                    var mixBits = 0
                    var mixRes = 0
                    var shiftPos = 0
                    if (escape == 0) {
                        mixBits = bits.read(8)
                        mixRes = bits.read(8).toByte().toInt()
                        var h = bits.read(8)
                        val modeU = h ushr 4
                        val denShiftU = h and 0xF
                        h = bits.read(8)
                        val pbFactorU = h ushr 5
                        val numU = h and 0x1F
                        for (i in 0 until numU) coefsU[i] = bits.read(16).toShort().toInt()
                        h = bits.read(8)
                        val modeV = h ushr 4
                        val denShiftV = h and 0xF
                        h = bits.read(8)
                        val pbFactorV = h ushr 5
                        val numV = h and 0x1F
                        for (i in 0 until numV) coefsV[i] = bits.read(16).toShort().toInt()
                        if (bytesShifted != 0) {
                            shiftPos = bits.bitPos
                            bits.bitPos += bytesShifted * 8 * 2 * numSamples
                        }
                        dynDecomp(bits, numSamples, chanBits, (config.pb * pbFactorU) / 4)
                        predict(mixU, numSamples, modeU, coefsU, numU, chanBits, denShiftU)
                        dynDecomp(bits, numSamples, chanBits, (config.pb * pbFactorV) / 4)
                        predict(mixV, numSamples, modeV, coefsV, numV, chanBits, denShiftV)
                    } else {
                        readUncompressed(bits, mixU, mixV, numSamples, config.bitDepth)
                        bytesShifted = 0
                    }
                    if (bytesShifted != 0) {
                        val shiftBits = BitReader(input, size).also { it.bitPos = shiftPos }
                        for (i in 0 until numSamples * 2) shiftBuffer[i] = shiftBits.read(bytesShifted * 8)
                    }
                    writeStereo(out, outBase, channelIndex, numSamples, mixBits, mixRes, bytesShifted)
                    channelIndex += 2
                }
                ID_DSE -> {
                    bits.read(4)
                    val align = bits.read(1)
                    var count = bits.read(8)
                    if (count == 255) count += bits.read(8)
                    if (align != 0) bits.byteAlign()
                    bits.bitPos += count * 8
                }
                ID_FIL -> {
                    var count = bits.read(4)
                    if (count == 15) count += bits.read(8) - 1
                    bits.bitPos += count * 8
                }
                ID_END -> break
                else -> error("Unsupported ALAC element")
            }
            if (channelIndex >= numChannels) break
        }
        out.position(outBase + numSamples * numChannels * bytesPerSample)
        return numSamples
    }

    private fun readUncompressed(bits: BitReader, u: IntArray, v: IntArray?, numSamples: Int, chanBits: Int) {
        val shift = 32 - chanBits
        fun one(): Int = if (chanBits <= 16) {
            (bits.read(chanBits) shl shift) shr shift
        } else {
            ((bits.read(16) shl 16) shr shift) or bits.read(chanBits - 16)
        }
        for (i in 0 until numSamples) {
            u[i] = one()
            if (v != null) v[i] = one()
        }
    }

    /** Runs the predictor ("mode" 0 = one pass; otherwise a first-order pre-pass). */
    private fun predict(out: IntArray, numSamples: Int, mode: Int, coefs: IntArray, numActive: Int, chanBits: Int, denShift: Int) {
        if (mode != 0) unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0)
        unpcBlock(predictor, out, numSamples, coefs, numActive, chanBits, denShift)
    }

    // ---- ag_dec.c: adaptive Golomb decoding into [predictor] ----

    private fun dynDecomp(bits: BitReader, numSamples: Int, maxSize: Int, pb: Int) {
        val kb = config.kb
        val wb = (1 shl kb) - 1
        var mb = config.mb.toLong() // uint32 semantics
        var zero = 0L
        var c = 0
        val maxPos = bits.byteSize * 8
        while (c < numSamples) {
            check(bits.bitPos < maxPos) { "ALAC bitstream overrun" }
            val m0 = (mb ushr QBSHIFT).toInt()
            var k = 31 - Integer.numberOfLeadingZeros(m0 + 3)
            if (k > kb) k = kb
            val m = (1 shl k) - 1
            val n = dynGet32(bits, m, k, maxSize).toLong() and MASK32

            val ndecode = (n + zero) and MASK32
            val magnitude = ((ndecode + 1) and MASK32) ushr 1
            predictor[c++] = (if (ndecode and 1L != 0L) -magnitude else magnitude).toInt()

            mb = ((pb * (n + zero)) and MASK32) + mb - (((pb * mb) and MASK32) ushr QBSHIFT)
            mb = mb and MASK32
            if (n > N_MAX_MEAN_CLAMP) mb = N_MEAN_CLAMP_VAL
            zero = 0

            if (((mb shl MMULSHIFT) and MASK32) < QB && c < numSamples) {
                zero = 1
                val kz = Integer.numberOfLeadingZeros(mb.toInt()) - BITOFF + ((mb.toInt() + MOFF) shr MDENSHIFT)
                val mz = ((1 shl kz) - 1) and wb
                val run = dynGet(bits, mz, kz)
                check(c + run <= numSamples) { "ALAC zero run overflow" }
                repeat(run) { predictor[c++] = 0 }
                if (run >= 65535) zero = 0
                mb = 0
            }
        }
    }

    private fun dynGet(bits: BitReader, m: Int, k: Int): Int {
        var pos = bits.bitPos
        var stream = bits.peek32(pos)
        var pre = Integer.numberOfLeadingZeros(stream.inv())
        val result: Int
        if (pre >= MAX_PREFIX_16) {
            pre = MAX_PREFIX_16
            pos += pre
            stream = stream shl pre
            result = stream ushr (32 - MAX_DATATYPE_BITS_16)
            pos += MAX_DATATYPE_BITS_16
        } else {
            pos += pre + 1
            stream = stream shl (pre + 1)
            val v = if (k == 0) 0 else stream ushr (32 - k)
            pos += k
            var r = pre * m + v - 1
            if (v < 2) {
                r -= v - 1
                pos -= 1
            }
            result = r
        }
        bits.bitPos = pos
        return result
    }

    private fun dynGet32(bits: BitReader, m: Int, k: Int, maxBits: Int): Int {
        var pos = bits.bitPos
        var stream = bits.peek32(pos)
        var result = Integer.numberOfLeadingZeros(stream.inv())
        if (result >= MAX_PREFIX_32) {
            result = bits.readAt(pos + MAX_PREFIX_32, maxBits)
            pos += MAX_PREFIX_32 + maxBits
        } else {
            pos += result + 1
            if (k != 1) {
                stream = stream shl (result + 1)
                val v = stream ushr (32 - k)
                pos += k - 1
                result *= m
                if (v >= 2) {
                    result += v - 1
                    pos += 1
                }
            }
        }
        bits.bitPos = pos
        return result
    }

    // ---- dp_dec.c: adaptive FIR predictor ----

    private fun unpcBlock(pc: IntArray, out: IntArray, num: Int, coefs: IntArray?, numActive: Int, chanBits: Int, denShift: Int) {
        val chanShift = 32 - chanBits
        out[0] = pc[0]
        if (numActive == 0) {
            if (num > 1 && pc !== out) System.arraycopy(pc, 1, out, 1, num - 1)
            return
        }
        if (numActive == 31) {
            var prev = out[0]
            for (j in 1 until num) {
                val del = pc[j] + prev
                prev = (del shl chanShift) shr chanShift
                out[j] = prev
            }
            return
        }
        coefs!!
        for (j in 1..numActive) {
            val del = pc[j] + out[j - 1]
            out[j] = (del shl chanShift) shr chanShift
        }
        val lim = numActive + 1
        val denHalf = 1 shl (denShift - 1)
        for (j in lim until num) {
            var sum = 0
            val top = out[j - lim]
            // pout[-k] == out[j - 1 - k]
            for (k in 0 until numActive) sum += coefs[k] * (out[j - 1 - k] - top)
            var del = pc[j]
            var del0 = del
            val sg = signOf(del)
            del += top + ((sum + denHalf) shr denShift)
            out[j] = (del shl chanShift) shr chanShift
            if (sg > 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[j - 1 - k]
                    val sgn = signOf(dd)
                    coefs[k] = (coefs[k] - sgn).toShort().toInt()
                    del0 -= (numActive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                }
            } else if (sg < 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[j - 1 - k]
                    val sgn = signOf(dd)
                    coefs[k] = (coefs[k] + sgn).toShort().toInt()
                    del0 -= (numActive - k) * ((-sgn * dd) shr denShift)
                    if (del0 >= 0) break
                }
            }
        }
    }

    private fun signOf(i: Int): Int = ((-i) ushr 31) or (i shr 31)

    // ---- matrix_dec.c: un-mixing and output ----

    private fun writeMono(out: ByteBuffer, base: Int, ch: Int, numSamples: Int, bytesShifted: Int) {
        val stride = config.numChannels
        val shift = bytesShifted * 8
        for (i in 0 until numSamples) {
            var v = mixU[i]
            if (bytesShifted != 0) v = (v shl shift) or shiftBuffer[i]
            putSample(out, base + (i * stride + ch) * bytesPerSample, v)
        }
    }

    private fun writeStereo(out: ByteBuffer, base: Int, ch: Int, numSamples: Int, mixBits: Int, mixRes: Int, bytesShifted: Int) {
        val stride = config.numChannels
        val shift = bytesShifted * 8
        for (j in 0 until numSamples) {
            var l: Int
            var r: Int
            if (mixRes != 0) {
                l = mixU[j] + mixV[j] - ((mixRes * mixV[j]) shr mixBits)
                r = l - mixV[j]
            } else {
                l = mixU[j]
                r = mixV[j]
            }
            if (bytesShifted != 0) {
                l = (l shl shift) or shiftBuffer[j * 2]
                r = (r shl shift) or shiftBuffer[j * 2 + 1]
            }
            val pos = base + (j * stride + ch) * bytesPerSample
            putSample(out, pos, l)
            putSample(out, pos + bytesPerSample, r)
        }
    }

    private fun putSample(out: ByteBuffer, pos: Int, value: Int) {
        when (config.bitDepth) {
            16 -> {
                out.put(pos, value.toByte())
                out.put(pos + 1, (value shr 8).toByte())
            }
            20, 24 -> {
                val v = if (config.bitDepth == 20) value shl 4 else value
                out.put(pos, v.toByte())
                out.put(pos + 1, (v shr 8).toByte())
                out.put(pos + 2, (v shr 16).toByte())
            }
            else -> {
                out.put(pos, value.toByte())
                out.put(pos + 1, (value shr 8).toByte())
                out.put(pos + 2, (value shr 16).toByte())
                out.put(pos + 3, (value shr 24).toByte())
            }
        }
    }

    /** MSB-first bit reader over a zero-padded array. */
    private class BitReader(private val buf: ByteArray, val byteSize: Int) {
        var bitPos = 0

        fun peek32(pos: Int): Int {
            val i = pos ushr 3
            val word = ((buf[i].toInt() and 0xFF) shl 24) or ((buf[i + 1].toInt() and 0xFF) shl 16) or
                ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)
            return word shl (pos and 7)
        }

        /** Reads [n] (0..32) bits at an absolute position without moving. */
        fun readAt(pos: Int, n: Int): Int {
            if (n == 0) return 0
            val i = pos ushr 3
            var v = 0L
            for (b in 0 until 8) v = (v shl 8) or (buf[i + b].toLong() and 0xFF)
            return ((v shl (pos and 7)) ushr (64 - n)).toInt()
        }

        fun read(n: Int): Int = readAt(bitPos, n).also { bitPos += n }

        fun byteAlign() {
            bitPos = (bitPos + 7) and 7.inv()
        }
    }

    private companion object {
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_LFE = 3
        const val ID_DSE = 4
        const val ID_FIL = 6
        const val ID_END = 7

        const val PADDING = 16
        const val MASK32 = 0xFFFFFFFFL
        const val QBSHIFT = 9
        const val QB = 1L shl QBSHIFT
        const val MMULSHIFT = 2
        const val MDENSHIFT = QBSHIFT - MMULSHIFT - 1
        const val MOFF = 1 shl (MDENSHIFT - 2)
        const val BITOFF = 24
        const val MAX_PREFIX_16 = 9
        const val MAX_PREFIX_32 = 9
        const val MAX_DATATYPE_BITS_16 = 16
        const val N_MAX_MEAN_CLAMP = 0xFFFFL
        const val N_MEAN_CLAMP_VAL = 0xFFFFL
    }
}

/** ALACSpecificConfig (the 24-byte "magic cookie"), optionally wrapped in 'frma' / 'alac' atoms. */
data class AlacConfig(
    val frameLength: Int,
    val bitDepth: Int,
    val pb: Int,
    val mb: Int,
    val kb: Int,
    val numChannels: Int,
    val maxRun: Int,
    val sampleRate: Int,
) {
    companion object {
        fun parse(cookie: ByteArray): AlacConfig {
            var b = ByteBuffer.wrap(cookie)
            fun atomAt(offset: Int, type: String) =
                b.remaining() >= offset + 8 && String(cookie, b.position() + offset + 4, 4, Charsets.US_ASCII) == type
            if (atomAt(0, "frma")) b.position(b.position() + 12)
            if (atomAt(0, "alac")) b.position(b.position() + 12)
            require(b.remaining() >= 24) { "ALAC magic cookie too short" }
            b = b.slice()
            return AlacConfig(
                frameLength = b.getInt(0),
                bitDepth = b.get(5).toInt() and 0xFF,
                pb = b.get(6).toInt() and 0xFF,
                mb = b.get(7).toInt() and 0xFF,
                kb = b.get(8).toInt() and 0xFF,
                numChannels = b.get(9).toInt() and 0xFF,
                maxRun = b.getShort(10).toInt() and 0xFFFF,
                sampleRate = b.getInt(20),
            )
        }
    }
}
