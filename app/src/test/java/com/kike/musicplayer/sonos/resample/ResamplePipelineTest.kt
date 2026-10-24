package com.kike.musicplayer.sonos.resample

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplePipelineTest {

    @Test
    fun `resample plan maps standard and hi-res sample rates accurately`() {
        assertNull(ResamplePlan.forStream(44_100, 2))
        assertNull(ResamplePlan.forStream(48_000, 2))

        val p88 = ResamplePlan.forStream(88_200, 2)!!
        assertEquals(44_100, p88.outputRate)
        assertEquals(2, p88.ratio)

        val p176 = ResamplePlan.forStream(176_400, 2)!!
        assertEquals(44_100, p176.outputRate)
        assertEquals(4, p176.ratio)

        val p352 = ResamplePlan.forStream(352_800, 2)!!
        assertEquals(44_100, p352.outputRate)
        assertEquals(8, p352.ratio)

        val p96 = ResamplePlan.forStream(96_000, 2)!!
        assertEquals(48_000, p96.outputRate)
        assertEquals(2, p96.ratio)

        val p192 = ResamplePlan.forStream(192_000, 2)!!
        assertEquals(48_000, p192.outputRate)
        assertEquals(4, p192.ratio)

        val p384 = ResamplePlan.forStream(384_000, 2)!!
        assertEquals(48_000, p384.outputRate)
        assertEquals(8, p384.ratio)
    }

    @Test
    fun `resampler preserves passband flatness and rejects ultrasonic stopband above -135 dB`() {
        // 1. Test 96 kHz -> 48 kHz passband (1 kHz and 19.5 kHz) and stopband (26 kHz and 35 kHz)
        val plan96 = ResamplePlan.forStream(96_000, 2)!!
        assertGainDb(plan96, freqHz = 1_000.0, expectedDb = 0.0, toleranceDb = 0.002)
        assertGainDb(plan96, freqHz = 19_500.0, expectedDb = 0.0, toleranceDb = 0.005)
        assertStopbandBelowDb(plan96, freqHz = 25_000.0, maxDb = -135.0)
        assertStopbandBelowDb(plan96, freqHz = 35_000.0, maxDb = -135.0)

        // 2. Test 88.2 kHz -> 44.1 kHz passband (1 kHz and 19 kHz) and stopband (23 kHz)
        val plan88 = ResamplePlan.forStream(88_200, 2)!!
        assertGainDb(plan88, freqHz = 1_000.0, expectedDb = 0.0, toleranceDb = 0.002)
        assertGainDb(plan88, freqHz = 19_000.0, expectedDb = 0.0, toleranceDb = 0.005)
        assertStopbandBelowDb(plan88, freqHz = 23_000.0, maxDb = -135.0)

        // 3. Test 192 kHz -> 48 kHz (4:1 cascade)
        val plan192 = ResamplePlan.forStream(192_000, 2)!!
        assertGainDb(plan192, freqHz = 10_000.0, expectedDb = 0.0, toleranceDb = 0.002)
        assertStopbandBelowDb(plan192, freqHz = 26_000.0, maxDb = -135.0)
        assertStopbandBelowDb(plan192, freqHz = 75_000.0, maxDb = -135.0)
    }

    @Test
    fun `verbatim flac writer offset math and roundtrip decode are bit-exact`() {
        // Test across > 130 frames to exercise 1-byte and 2-byte UTF-8 frame numbers
        val totalSamples = VerbatimFlacWriter.DEFAULT_BLOCK_SAMPLES.toLong() * 135 + 234L
        val writer = VerbatimFlacWriter(sampleRate = 48_000, channels = 2, totalSamples = totalSamples)
        assertEquals(136L, writer.totalFrames)

        var expectedOffset = writer.headerSize.toLong()
        for (f in 0 until writer.totalFrames) {
            assertEquals("Frame $f offset", expectedOffset, writer.frameByteOffset(f))
            assertEquals("Frame index at start of $f", f, writer.frameIndexForByteOffset(expectedOffset))
            val size = writer.frameSizeBytes(f)
            assertEquals("Frame index at end of $f", f, writer.frameIndexForByteOffset(expectedOffset + size - 1))
            expectedOffset += size
        }
        assertEquals(expectedOffset, writer.totalStreamBytes)

        // Roundtrip encode -> FlacStreamDecoder decode on 5 frames
        val shortSamples = 4096L * 4 + 517L
        val shortWriter = VerbatimFlacWriter(sampleRate = 44_100, channels = 2, totalSamples = shortSamples)
        val bos = ByteArrayOutputStream()
        bos.write(shortWriter.writeStreamHeader())
        val originalPcm = IntArray(shortSamples.toInt() * 2) { i ->
            ((i * 198491317L) % 16_777_216L - 8_388_608L).toInt()
        }
        for (f in 0 until shortWriter.totalFrames) {
            val n = shortWriter.frameSamples(f)
            val startInt = (f * shortWriter.blockSamples.toLong() * 2).toInt()
            val slice = originalPcm.copyOfRange(startInt, startInt + n * 2)
            bos.write(shortWriter.encodeFrame(f, slice, n))
        }
        val flacBytes = bos.toByteArray()
        assertEquals(shortWriter.totalStreamBytes, flacBytes.size.toLong())

        val source = ByteArraySource(flacBytes)
        val info = FlacProbe.parse(source)!!
        assertEquals(44_100, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(24, info.bitsPerSample)
        assertEquals(shortSamples, info.totalSamples)

        val decoder = FlacStreamDecoder(source, info)
        val decodedPcm = IntArray(shortSamples.toInt() * 2)
        val frameBuf = IntArray(4096 * 2)
        var pos = 0
        while (true) {
            val n = decoder.decodeNextFrame(frameBuf)
            if (n < 0) break
            System.arraycopy(frameBuf, 0, decodedPcm, pos, n * 2)
            pos += n * 2
        }
        assertEquals(originalPcm.size, pos)
        assertArrayEquals(originalPcm, decodedPcm)
    }

    @Test
    fun `end-to-end ffmpeg flac and alac decode, downsample, and random range seeking`() {
        val ffmpeg = File("C:/Tools/ffmpeg/bin/ffmpeg.exe")
        if (!ffmpeg.exists()) return

        val tmpDir = File(System.getProperty("java.io.tmpdir"), "sonos_resample_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // Generate 3 seconds of 24-bit 96 kHz stereo test signal (1 kHz left, 3 kHz + 7 kHz right, plus 30 kHz ultrasonic)
            val inRate = 96_000
            val durationSec = 3
            val inSamples = inRate * durationSec
            val raw24Le = ByteArray(inSamples * 2 * 3)
            val expectedIn24 = IntArray(inSamples * 2)
            val amp = 4_000_000.0 // ~ -6 dBFS
            val ultraAmp = 2_000_000.0 // ultrasonic tone at 30 kHz that must be filtered out
            var bIdx = 0
            for (n in 0 until inSamples) {
                val t = n.toDouble() / inRate
                val sL = (amp * sin(2.0 * PI * 1000.0 * t) + ultraAmp * sin(2.0 * PI * 30_000.0 * t)).roundToInt()
                val sR = (amp * 0.5 * sin(2.0 * PI * 3000.0 * t) + amp * 0.5 * sin(2.0 * PI * 7000.0 * t)).roundToInt()
                expectedIn24[n * 2] = sL
                expectedIn24[n * 2 + 1] = sR
                for (s in intArrayOf(sL, sR)) {
                    raw24Le[bIdx++] = s.toByte()
                    raw24Le[bIdx++] = (s ushr 8).toByte()
                    raw24Le[bIdx++] = (s ushr 16).toByte()
                }
            }
            val rawFile = File(tmpDir, "input.s24le").apply { writeBytes(raw24Le) }
            val flac96File = File(tmpDir, "input96.flac")
            val alac96File = File(tmpDir, "input96.m4a")

            // Encode 24/96 FLAC and 24/96 ALAC with ffmpeg
            runCmd(ffmpeg.absolutePath, "-y", "-f", "s24le", "-ar", "96000", "-ac", "2", "-i", rawFile.absolutePath, flac96File.absolutePath)
            runCmd(ffmpeg.absolutePath, "-y", "-f", "s24le", "-ar", "96000", "-ac", "2", "-i", rawFile.absolutePath, "-c:a", "alac", alac96File.absolutePath)

            // 1. Verify FlacStreamDecoder is 100% bit-exact against original 24-bit PCM
            FileRandomAccessSource(flac96File).use { src ->
                val info = FlacProbe.parse(src)!!
                assertEquals(96_000, info.sampleRate)
                assertEquals(24, info.bitsPerSample)
                assertEquals(inSamples.toLong(), info.totalSamples)

                // Wrap in a small-packet (1379-byte) strictly-sequential source to verify BufferedBitReader
                // never issues backward reads across TLS/TCP packet boundaries during decoding.
                val packetSource = object : RandomAccessSource {
                    var expectedNextOffset = info.firstFrameOffset
                    override val length: Long get() = src.length
                    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
                        assertEquals("Decoder must read strictly sequentially without backward seeks", expectedNextOffset, offset)
                        val capped = minOf(len, 1379)
                        val n = src.readAt(offset, buf, off, capped)
                        if (n > 0) expectedNextOffset += n
                        return n
                    }
                    override fun close() {}
                }
                val dec = FlacStreamDecoder(packetSource, info)
                val out = IntArray(inSamples * 2)
                val buf = IntArray(info.maxBlockSize * 2)
                var written = 0
                while (true) {
                    val n = dec.decodeNextFrame(buf)
                    if (n < 0) break
                    System.arraycopy(buf, 0, out, written, n * 2)
                    written += n * 2
                }
                assertEquals(expectedIn24.size, written)
                assertArrayEquals("FLAC decode must be 100% bit-exact", expectedIn24, out)
            }

            // 2. Verify AlacStreamDecoder is 100% bit-exact against original 24-bit PCM
            FileRandomAccessSource(alac96File).use { src ->
                val info = AlacMp4Probe.parse(src)!!
                assertEquals(96_000, info.sampleRate)
                assertEquals(24, info.bitsPerSample)
                assertEquals(inSamples.toLong(), info.totalSamples)

                val dec = AlacStreamDecoder(src, info)
                val out = IntArray(inSamples * 2)
                val buf = IntArray(info.maxBlockSize * 2)
                var written = 0
                while (true) {
                    val n = dec.decodeNextFrame(buf)
                    if (n < 0) break
                    System.arraycopy(buf, 0, out, written, n * 2)
                    written += n * 2
                }
                assertEquals(expectedIn24.size, written)
                assertArrayEquals("ALAC decode must be 100% bit-exact", expectedIn24, out)
            }

            // 3. Downsample 96 kHz FLAC -> 48 kHz FLAC and verify random HTTP Range equivalence + ffmpeg decode
            FileRandomAccessSource(flac96File).use { src ->
                val info = FlacProbe.parse(src)!!
                val plan = ResamplePlan.forStream(info.sampleRate, info.channels)!!
                val stream = DownsamplingStream(FlacInputDecoder(src, info), plan)

                val fullOut = ByteArrayOutputStream()
                stream.writeRange(0L, stream.totalStreamBytes - 1, fullOut)
                val fullBytes = fullOut.toByteArray()
                assertEquals(stream.totalStreamBytes, fullBytes.size.toLong())

                // Decode generated 48 kHz FLAC with ffmpeg to verify valid format & CRCs
                val outFlacFile = File(tmpDir, "downsampled48.flac").apply { writeBytes(fullBytes) }
                val decodedRaw48 = File(tmpDir, "decoded48.s24le")
                runCmd(ffmpeg.absolutePath, "-y", "-v", "error", "-i", outFlacFile.absolutePath, "-f", "s24le", decodedRaw48.absolutePath)
                assertEquals((inSamples / 2) * 2 * 3L, decodedRaw48.length())

                // Verify VerbatimFlacWriter's embedded SEEKTABLE metadata block is parsed back cleanly
                val outInfo = FlacProbe.parse(ByteArraySource(fullBytes))!!
                assertTrue("Generated FLAC must include SEEKTABLE", outInfo.seekPoints.isNotEmpty())
                assertEquals(stream.writer.headerSize.toLong(), outInfo.firstFrameOffset)

                // Test non-sequential Range requests (both with SEEKTABLE and with seekPoints stripped to test interpolated bisection)
                val infoWithoutSeekTable = info.copy(seekPoints = emptyList())
                val ranges = listOf(
                    0L to 6143L,
                    stream.totalStreamBytes - 8192L to stream.totalStreamBytes - 1L,
                    300_000L to 420_000L,
                    6144L to 120_000L,
                    0L to 41L,
                )
                for ((start, end) in ranges) {
                    for (testInfo in listOf(info, infoWithoutSeekTable)) {
                        val rangeStream = DownsamplingStream(FlacInputDecoder(src, testInfo), plan)
                        val sliceOut = ByteArrayOutputStream()
                        rangeStream.writeRange(start, end, sliceOut)
                        val sliceBytes = sliceOut.toByteArray()
                        val expectedSlice = fullBytes.copyOfRange(start.toInt(), end.toInt() + 1)
                        assertArrayEquals("Range $start..$end must be bit-identical to full stream", expectedSlice, sliceBytes)
                    }
                }

                // 4. Downsample 96 kHz ALAC (.m4a) -> 48 kHz FLAC and verify identical output & random Range seeks
                FileRandomAccessSource(alac96File).use { alacSrc ->
                    val alacInfo = AlacMp4Probe.parse(alacSrc)!!
                    val alacStream = DownsamplingStream(AlacInputDecoder(alacSrc, alacInfo), plan)
                    val alacFullOut = ByteArrayOutputStream()
                    alacStream.writeRange(0L, alacStream.totalStreamBytes - 1, alacFullOut)
                    val alacFullBytes = alacFullOut.toByteArray()
                    // Since both 96 kHz FLAC and 96 kHz ALAC are lossless encodings of the exact same input PCM,
                    // their downsampled 48 kHz FLAC streams must be 100% byte-for-byte identical!
                    assertArrayEquals("Downsampled ALAC must match downsampled FLAC byte-for-byte", fullBytes, alacFullBytes)

                    val midRangeStream = DownsamplingStream(AlacInputDecoder(alacSrc, alacInfo), plan)
                    val midSliceOut = ByteArrayOutputStream()
                    midRangeStream.writeRange(300_000L, 420_000L, midSliceOut)
                    assertArrayEquals(
                        "Mid-track ALAC range seek must be bit-identical",
                        fullBytes.copyOfRange(300_000, 420_001),
                        midSliceOut.toByteArray(),
                    )
                }
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `subsonic song mapping preserves samplingRate and bitDepth`() {
        val song = com.kike.musicplayer.data.subsonic.SongJson(
            id = "s1",
            title = "Hi-Res Track",
            album = "Hi-Res Album",
            albumId = "a1",
            artist = "Artist",
            artistId = "ar1",
            track = 1,
            discNumber = 1,
            year = 2026,
            genre = "Rock",
            coverArt = null,
            size = 50_000_000L,
            contentType = "audio/flac",
            suffix = "flac",
            duration = 240,
            bitRate = 3000,
            path = "Artist/Hi-Res Album/01 Hi-Res Track.flac",
            created = null,
            starred = null,
            musicBrainzId = null,
            displayAlbumArtist = "Artist",
            samplingRate = 96_000,
            bitDepth = 24,
        )
        val track = com.kike.musicplayer.data.subsonic.SubsonicLibrary.toTrack(song, 1000L)
        assertEquals(96_000, track.sampleRate)
        assertEquals(24, track.bitsPerSample)
    }

    @Test
    fun `benchmark 96 kHz and 192 kHz resampling throughput`() {
        // Benchmark 10 seconds of stereo 96->48 and 192->48
        for (rate in intArrayOf(96_000, 192_000)) {
            val plan = ResamplePlan.forStream(rate, 2)!!
            val resampler = Resampler(plan)
            val blockIn = 4096
            val inBuf = IntArray(blockIn * 2) { (it * 12345) and 0x7FFFFF }
            val outBuf = IntArray(blockIn * 2)

            // Warmup
            repeat(50) { resampler.process(inBuf, blockIn, outBuf) }

            val secondsSimulated = 30
            val iterations = (rate * secondsSimulated) / blockIn
            val t0 = System.nanoTime()
            repeat(iterations) {
                resampler.process(inBuf, blockIn, outBuf)
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            val realtimeFactor = (secondsSimulated * 1000.0) / elapsedMs
            println("Benchmark $rate Hz -> ${plan.outputRate} Hz: ${secondsSimulated}s audio processed in ${"%.1f".format(elapsedMs)} ms (${"%.1f".format(realtimeFactor)}x realtime)")
            assertTrue("Expected at least 10x realtime, got ${realtimeFactor}x", realtimeFactor > 10.0)
        }
    }

    private fun assertGainDb(plan: ResamplePlan, freqHz: Double, expectedDb: Double, toleranceDb: Double) {
        val resampler = Resampler(plan)
        val inFrames = plan.inputRate / 2 // 0.5 s
        val amp = 4_000_000.0
        val input = IntArray(inFrames * plan.channels)
        for (i in 0 until inFrames) {
            val s = (amp * sin(2.0 * PI * freqHz * i / plan.inputRate)).roundToInt()
            for (c in 0 until plan.channels) input[i * plan.channels + c] = s
        }
        val output = IntArray((inFrames / plan.ratio + 16) * plan.channels)
        val outFrames = resampler.process(input, inFrames, output)

        // Skip transient pre-roll at start and measure RMS over steady-state region
        val skip = 1000
        var sumSq = 0.0
        val count = outFrames - skip
        for (i in skip until outFrames) {
            val v = output[i * plan.channels].toDouble()
            sumSq += v * v
        }
        val rmsOut = sqrt(sumSq / count)
        val rmsIn = amp / sqrt(2.0)
        val gainDb = 20.0 * log10(rmsOut / rmsIn)
        assertTrue(
            "Gain at $freqHz Hz (${plan.inputRate}->${plan.outputRate}) was $gainDb dB, expected $expectedDb +/- $toleranceDb dB",
            abs(gainDb - expectedDb) <= toleranceDb,
        )
    }

    private fun assertStopbandBelowDb(plan: ResamplePlan, freqHz: Double, maxDb: Double) {
        val resampler = Resampler(plan)
        val inFrames = plan.inputRate / 2
        val amp = 8_000_000.0
        val input = IntArray(inFrames * plan.channels)
        for (i in 0 until inFrames) {
            val s = (amp * sin(2.0 * PI * freqHz * i / plan.inputRate)).roundToInt()
            for (c in 0 until plan.channels) input[i * plan.channels + c] = s
        }
        val output = IntArray((inFrames / plan.ratio + 16) * plan.channels)
        val outFrames = resampler.process(input, inFrames, output)

        val skip = 1000
        var sumSq = 0.0
        val count = outFrames - skip
        for (i in skip until outFrames) {
            val v = output[i * plan.channels].toDouble()
            sumSq += v * v
        }
        val rmsOut = sqrt(sumSq / count).coerceAtLeast(1e-9)
        val rmsIn = amp / sqrt(2.0)
        val gainDb = 20.0 * log10(rmsOut / rmsIn)
        assertTrue(
            "Stopband at $freqHz Hz (${plan.inputRate}->${plan.outputRate}) was $gainDb dB, expected <= $maxDb dB",
            gainDb <= maxDb,
        )
    }

    private fun runCmd(vararg args: String) {
        val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw RuntimeException("Command failed ($code): ${args.joinToString(" ")}\n$out")
    }
}
