package com.kike.musicplayer.sonos.resample

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decimation plan for converting a hi-res audio stream (> 48 kHz) to the highest sample rate
 * in the same clock family supported by Sonos S2 (44.1 kHz or 48 kHz, 24-bit).
 */
data class ResamplePlan(
    val inputRate: Int,
    val outputRate: Int,
    val ratio: Int,
    val channels: Int,
) {
    /** Total output samples produced from [inputSamples] per channel. */
    fun outputSamples(inputSamples: Long): Long = inputSamples / ratio

    companion object {
        const val MAX_SONOS_SAMPLE_RATE = 48_000

        fun needsDownsampling(sampleRate: Int): Boolean = sampleRate > MAX_SONOS_SAMPLE_RATE

        /**
         * Returns a [ResamplePlan] if [inputRate] > 48 kHz and can be integer-decimated to
         * <= 48 kHz (e.g. 88.2/176.4/352.8 kHz -> 44.1 kHz; 96/192/384 kHz -> 48 kHz),
         * or null when the stream is already <= 48 kHz or unsupported.
         */
        fun forStream(inputRate: Int, channels: Int): ResamplePlan? {
            if (inputRate <= MAX_SONOS_SAMPLE_RATE || channels !in 1..2) return null
            var rate = inputRate
            var ratio = 1
            while (rate > MAX_SONOS_SAMPLE_RATE && rate % 2 == 0) {
                rate /= 2
                ratio *= 2
            }
            if (rate > MAX_SONOS_SAMPLE_RATE || ratio !in setOf(2, 4, 8)) return null
            return ResamplePlan(
                inputRate = inputRate,
                outputRate = rate,
                ratio = ratio,
                channels = channels,
            )
        }
    }
}

/**
 * Symmetric linear-phase 2:1 FIR decimator designed with a Kaiser window (beta = 14.47,
 * stopband attenuation >= 144 dB, passband ripple < 1e-6 dB).
 *
 * Because the filter has odd length (2 * halfLen + 1) and is symmetric around [halfLen],
 * symmetric sample pairs are folded (x[left] + x[right]) before multiplying by the tap
 * coefficient, cutting multiplications in half.
 *
 * When [halfBand] is true (used for wide-transition pre-stages like 192->96 kHz or 176.4->88.2 kHz),
 * every non-center even tap is zero and is skipped completely.
 */
internal class FirDecimator2x(
    val numTaps: Int,
    cutoffNorm: Double,
    beta: Double = 14.47,
    private val channels: Int,
    private val halfBand: Boolean = false,
) {
    init {
        require(numTaps >= 3 && numTaps % 2 == 1) { "numTaps must be odd" }
    }

    val halfLen: Int = numTaps / 2
    private val centerTap: Double
    private val pairOffsets: IntArray
    private val pairCoeffs: DoubleArray

    // Power-of-two per-channel ring buffer so index wrap is a bitmask.
    private val ringSize: Int = Integer.highestOneBit(numTaps * 2 - 1).shl(1)
    private val ringMask: Int = ringSize - 1
    private val ring = Array(channels) { DoubleArray(ringSize) }
    private var writePos = 0
    private var phase = 0 // 0 or 1: emits one output frame for every 2 input frames

    init {
        val full = designKaiserLowPass(numTaps, cutoffNorm, beta)
        centerTap = full[halfLen]
        val offsets = ArrayList<Int>(halfLen)
        val coeffs = ArrayList<Double>(halfLen)
        for (d in 1..halfLen) {
            val c = full[halfLen + d]
            // In a half-band filter (fc = 0.25), even distances d from the center are zero.
            if (halfBand && d % 2 == 0) continue
            if (kotlin.math.abs(c) > 1e-18) {
                offsets.add(d)
                coeffs.add(c)
            }
        }
        pairOffsets = offsets.toIntArray()
        pairCoeffs = coeffs.toDoubleArray()
    }

    fun reset() {
        for (c in 0 until channels) ring[c].fill(0.0)
        writePos = 0
        phase = 0
    }

    /**
     * Processes [inFrames] interleaved frames from [input] (length >= inFrames * channels)
     * into [output], returning the number of decimated output frames written.
     */
    fun process(input: DoubleArray, inFrames: Int, output: DoubleArray): Int {
        var outFrames = 0
        val offsets = pairOffsets
        val coeffs = pairCoeffs
        val nPairs = offsets.size
        val mask = ringMask
        val mid = halfLen
        val center = centerTap

        for (i in 0 until inFrames) {
            val base = i * channels
            for (c in 0 until channels) {
                ring[c][writePos] = input[base + c]
            }
            if (phase == 1) {
                val centerIdx = (writePos - mid) and mask
                val outBase = outFrames * channels
                for (c in 0 until channels) {
                    val r = ring[c]
                    var acc = center * r[centerIdx]
                    for (k in 0 until nPairs) {
                        val d = offsets[k]
                        acc += coeffs[k] * (r[(centerIdx - d) and mask] + r[(centerIdx + d) and mask])
                    }
                    output[outBase + c] = acc
                }
                outFrames++
                phase = 0
            } else {
                phase = 1
            }
            writePos = (writePos + 1) and mask
        }
        return outFrames
    }

    companion object {
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            val halfX = x * 0.5
            while (true) {
                val ratio = halfX / k
                term *= ratio * ratio
                sum += term
                if (term < sum * 1e-15) break
                k++
            }
            return sum
        }

        fun designKaiserLowPass(numTaps: Int, fcNorm: Double, beta: Double): DoubleArray {
            val mid = numTaps / 2
            val invI0Beta = 1.0 / besselI0(beta)
            val h = DoubleArray(numTaps)
            var dcSum = 0.0
            for (n in 0 until numTaps) {
                val k = n - mid
                val sinc = if (k == 0) {
                    2.0 * fcNorm
                } else {
                    sin(2.0 * PI * fcNorm * k) / (PI * k)
                }
                val r = k.toDouble() / mid.toDouble()
                val win = besselI0(beta * sqrt((1.0 - r * r).coerceAtLeast(0.0))) * invI0Beta
                val v = sinc * win
                h[n] = v
                dcSum += v
            }
            for (n in 0 until numTaps) h[n] /= dcSum
            return h
        }
    }
}

/**
 * Multi-stage 2:1 / 4:1 / 8:1 polyphase FIR resampler with 24-bit TPDF dithering.
 *
 * Input samples are interleaved 24-bit signed integers (in [-8388608, 8388607]).
 * Output samples are interleaved 24-bit signed integers at [plan.outputRate].
 */
class Resampler(val plan: ResamplePlan) {
    private val stages: Array<FirDecimator2x>
    private var bufA = DoubleArray(0)
    private var bufB = DoubleArray(0)
    private var outputSampleCounter = 0L

    /**
     * Total group delay of the cascade in input samples. When seeking to an output sample,
     * feeding [inputPreRollSamples] before the target sample warms up all filter rings completely.
     */
    val groupDelayInputSamples: Int

    /** Number of input samples needed to warm up all filter states before emitting a seek target. */
    val inputPreRollSamples: Int

    init {
        val ch = plan.channels
        // Final 2:1 stage has the steepest transition band (20 kHz -> output Nyquist).
        val finalStage = if (plan.outputRate == 44_100) {
            // 88.2 -> 44.1 kHz: passband 19.8 kHz, stopband 22.05 kHz, fc = 20.925 kHz
            FirDecimator2x(numTaps = 639, cutoffNorm = 20_925.0 / 88_200.0, beta = 14.47, channels = ch, halfBand = false)
        } else {
            // 96 -> 48 kHz: passband 20.0 kHz, stopband 24.0 kHz, fc = 22.0 kHz
            FirDecimator2x(numTaps = 447, cutoffNorm = 22_000.0 / 96_000.0, beta = 14.47, channels = ch, halfBand = false)
        }

        stages = when (plan.ratio) {
            2 -> arrayOf(finalStage)
            4 -> arrayOf(
                // 192->96 or 176.4->88.2: wide transition band, 63-tap half-band gives > 145 dB alias rejection
                FirDecimator2x(numTaps = 63, cutoffNorm = 0.25, beta = 14.47, channels = ch, halfBand = true),
                finalStage,
            )
            8 -> arrayOf(
                // 384->192 or 352.8->176.4: ultra-wide transition band, 31-tap half-band
                FirDecimator2x(numTaps = 31, cutoffNorm = 0.25, beta = 14.47, channels = ch, halfBand = true),
                FirDecimator2x(numTaps = 63, cutoffNorm = 0.25, beta = 14.47, channels = ch, halfBand = true),
                finalStage,
            )
            else -> throw IllegalArgumentException("Unsupported decimation ratio ${plan.ratio}")
        }

        // Compute total group delay and pre-roll in input samples.
        var delay = 0
        var span = 0
        var factor = 1
        for (s in stages) {
            delay += s.halfLen * factor
            span += (s.numTaps - 1) * factor
            factor *= 2
        }
        groupDelayInputSamples = delay
        // Round pre-roll up to a multiple of ratio.
        inputPreRollSamples = ((span + plan.ratio - 1) / plan.ratio) * plan.ratio
    }

    fun reset(startOutputSample: Long = 0L) {
        for (s in stages) s.reset()
        outputSampleCounter = startOutputSample
    }

    /**
     * Filters [inFrames] interleaved 24-bit integer samples from [input] and writes
     * decimated 24-bit integer samples to [output]. Returns the number of output frames written.
     */
    fun process(input: IntArray, inFrames: Int, output: IntArray): Int {
        val ch = plan.channels
        val needed = inFrames * ch
        if (bufA.size < needed) bufA = DoubleArray(needed)
        if (bufB.size < needed) bufB = DoubleArray(needed)

        for (i in 0 until needed) {
            bufA[i] = input[i].toDouble()
        }

        var frames = inFrames
        var src = bufA
        var dst = bufB
        for (stage in stages) {
            frames = stage.process(src, frames, dst)
            val tmp = src
            src = dst
            dst = tmp
        }

        // Quantize to 24-bit signed integer with deterministic TPDF dither at +/- 0.5 LSB.
        for (f in 0 until frames) {
            val sampleIdx = outputSampleCounter++
            val base = f * ch
            for (c in 0 until ch) {
                val dither = tpdfDither(sampleIdx, c)
                val q = (src[base + c] + dither).roundToLong()
                output[base + c] = q.coerceIn(MIN_INT24, MAX_INT24).toInt()
            }
        }
        return frames
    }

    companion object {
        private const val MIN_INT24 = -8_388_608L
        private const val MAX_INT24 = 8_388_607L
        private const val INV_2_POW_53 = 1.0 / (1L shl 53).toDouble()

        /**
         * Stateless, deterministic TPDF dither in [-1.0, 1.0] LSB constructed from two
         * independent SplitMix64 hashes of (outputSampleIndex, channel). Because it depends
         * only on the output sample coordinate, seeking to any position yields bit-identical output.
         */
        internal fun tpdfDither(sampleIndex: Long, channel: Int): Double {
            var z1 = sampleIndex * -0x61c8864680b583ebL + (channel + 1) * 0x9e3779b97f4a7c15u.toLong()
            z1 = (z1 xor (z1 ushr 30)) * -0x40a7b892e31b1a47L
            z1 = (z1 xor (z1 ushr 27)) * -0x6b2fb644ecceee15L
            z1 = z1 xor (z1 ushr 31)

            var z2 = z1 + 0x9e3779b97f4a7c15u.toLong()
            z2 = (z2 xor (z2 ushr 30)) * -0x40a7b892e31b1a47L
            z2 = (z2 xor (z2 ushr 27)) * -0x6b2fb644ecceee15L
            z2 = z2 xor (z2 ushr 31)

            val u1 = (z1 ushr 11).toDouble() * INV_2_POW_53 // [0, 1)
            val u2 = (z2 ushr 11).toDouble() * INV_2_POW_53 // [0, 1)
            return (u1 - u2) * 0.5
        }
    }
}
