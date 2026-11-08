package com.kike.musicplayer.playback

import android.bluetooth.BluetoothCodecConfig
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQualityTest {

    @Test
    fun testFormatSampleRate() {
        assertEquals("44.1 kHz", formatSampleRate(44100))
        assertEquals("48 kHz", formatSampleRate(48000))
        assertEquals("88.2 kHz", formatSampleRate(88200))
        assertEquals("96 kHz", formatSampleRate(96000))
        assertEquals("176.4 kHz", formatSampleRate(176400))
        assertEquals("192 kHz", formatSampleRate(192000))
    }

    @Test
    fun testHiResLosslessResolution() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_ALAC)
            .setSampleRate(96000)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .build()
        val track = Track(
            id = "drive:test1",
            source = Track.SOURCE_DRIVE,
            uri = "https://drive.google.com/test",
            fileName = "Track01.m4a",
            mimeType = "audio/mp4",
            sizeBytes = 72_000_000L,
            durationMs = 200_000L,
            title = "Test Hi-Res",
            sampleRate = 96000,
            bitsPerSample = 24,
        )
        val output = AudioOutputInfo(
            routeType = OutputRouteType.USB_DAC,
            displayName = "USB DAC (DragonFly Cobalt)",
            description = "Direct USB Audio",
        )

        val quality = resolveAudioQuality(format, track, output)

        assertEquals(AudioTier.HI_RES_LOSSLESS, quality.tier)
        assertEquals("ALAC", quality.codec)
        assertEquals(24, quality.bitDepth)
        assertEquals(96000, quality.sampleRateHz)
        assertEquals(2880, quality.bitrateKbps) // 72MB * 8 / 200s = 2880 kbps
        assertEquals("Hi-Res Lossless", quality.badgeLabel)
        assertEquals("24-bit / 96 kHz", quality.resolutionLabel)
        assertEquals("Google Drive", quality.sourceDescription)
        assertEquals("Custom In-App Bit-Exact ALAC Decoder", quality.decoderDescription)
        assertTrue(quality.signalPathDescription.contains("Bit-Perfect"))
        assertEquals("Bit-Perfect (No DSP / Normalization)", quality.integrityDescription)
    }

    @Test
    fun testPhoneSpeakerMixerResamplingTruthfulness() {
        // 1. 44.1 kHz track playing on Phone Speaker (48 kHz native Android AudioMixer)
        val format44 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .build()
        val track44 = Track(
            id = "saf:test2",
            source = Track.SOURCE_SAF,
            uri = "content://saf/test2",
            fileName = "Track02.flac",
            mimeType = "audio/flac",
            sizeBytes = 25_000_000L,
            durationMs = 240_000L,
            title = "Test CD Quality",
            sampleRate = 44100,
            bitsPerSample = 16,
        )
        val output = AudioOutputInfo(
            routeType = OutputRouteType.PHONE_SPEAKER,
            displayName = "Phone Speaker",
            description = "Internal Device Stereo Speakers (48 kHz System Mixer)",
            nativeMixerRateHz = 48000,
        )

        val quality44 = resolveAudioQuality(format44, track44, output)

        assertEquals(AudioTier.LOSSLESS, quality44.tier)
        assertEquals("FLAC", quality44.codec)
        assertEquals(16, quality44.bitDepth)
        assertEquals(44100, quality44.sampleRateHz)
        assertEquals("Android AudioMixer (32-bit Float · Resampled to 48 kHz)", quality44.signalPathDescription)
        assertEquals("OS Mixer Resampled (44.1 kHz → 48 kHz — Not Bit-Perfect)", quality44.integrityDescription)

        // 2. 48 kHz track playing on Phone Speaker (matches 48 kHz native mixer rate, no rate conversion)
        val format48 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(48000)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .build()
        val quality48 = resolveAudioQuality(format48, track44.copy(sampleRate = 48000, bitsPerSample = 24), output)
        assertEquals("Android AudioMixer (32-bit Float · Native 48 kHz)", quality48.signalPathDescription)
        assertEquals("Lossless In-App Decode · Shared OS Mixer (48 kHz)", quality48.integrityDescription)
    }

    @Test
    fun testMp3HighQuality() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MPEG)
            .setSampleRate(44100)
            .setAverageBitrate(320_000)
            .setChannelCount(2)
            .build()
        val track = Track(
            id = "saf:test3",
            source = Track.SOURCE_SAF,
            uri = "content://saf/test3",
            fileName = "Track03.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 9_600_000L,
            durationMs = 240_000L,
            title = "Test MP3 320",
        )
        val output = AudioOutputInfo(
            routeType = OutputRouteType.BLUETOOTH,
            displayName = "Bluetooth (WH-1000XM4)",
            description = "Wireless Bluetooth A2DP Sink",
            bluetoothCodec = BluetoothCodecDetails(
                codecName = "LDAC",
                sampleRateHz = 96000,
                bitDepth = 24,
                channelMode = "Stereo",
                bitrateInfo = "990 kbps",
            ),
        )

        val quality = resolveAudioQuality(format, track, output)

        assertEquals(AudioTier.HIGH_QUALITY, quality.tier)
        assertEquals("MP3", quality.codec)
        assertEquals(320, quality.bitrateKbps)
        assertEquals("320 kbps", quality.badgeLabel)
        assertEquals("MP3 320 kbps", quality.resolutionLabel)
        assertEquals("Bluetooth Encoding (LDAC · 24-bit / 96 kHz)", quality.signalPathDescription)
        assertEquals("Lossy Bluetooth Re-encoding (LDAC — Not Bit-Perfect)", quality.integrityDescription)
    }

    @Test
    fun testSonosCastDownsampleVsBitPerfectRelay() {
        val output = AudioOutputInfo(
            routeType = OutputRouteType.SONOS_CAST,
            displayName = "Sonos (Kitchen)",
            description = "Lossless Bit-Perfect UPnP Relay",
        )

        // 1. 44.1 kHz track with Sonos Downsampling setting ON -> must NOT say Downsampled!
        val format44 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .build()
        val track44 = Track(
            id = "saf:test4",
            source = Track.SOURCE_SAF,
            uri = "content://saf/test4",
            fileName = "Track04.flac",
            mimeType = "audio/flac",
            sizeBytes = 30_000_000L,
            durationMs = 200_000L,
            title = "Test Sonos 44.1",
            sampleRate = 44100,
            bitsPerSample = 16,
        )
        val quality44 = resolveAudioQuality(format44, track44, output, isSonosDownsample = true)
        assertFalse(quality44.isDownsampledForSonos)
        assertNull(quality44.sonosTargetSampleRateHz)
        assertEquals("Bit-Perfect UPnP Lossless HTTP Relay (localhost:8765)", quality44.signalPathDescription)
        assertEquals("Lossless Bit-Perfect UPnP Relay", quality44.outputInfo.description)
        assertEquals("Bit-Perfect (Untouched Original Stream)", quality44.integrityDescription)

        // 2. 48 kHz / 24-bit track with Sonos Downsampling setting ON -> must NOT say Downsampled!
        val format48 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_ALAC)
            .setSampleRate(48000)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .build()
        val track48 = track44.copy(fileName = "Track48.m4a", sampleRate = 48000, bitsPerSample = 24)
        val quality48 = resolveAudioQuality(format48, track48, output, isSonosDownsample = true)
        assertFalse(quality48.isDownsampledForSonos)
        assertNull(quality48.sonosTargetSampleRateHz)
        assertEquals("Bit-Perfect UPnP Lossless HTTP Relay (localhost:8765)", quality48.signalPathDescription)
        assertEquals("Lossless Bit-Perfect UPnP Relay", quality48.outputInfo.description)
        assertEquals("Bit-Perfect (Untouched Original Stream)", quality48.integrityDescription)

        // 3. 96 kHz / 24-bit track with Sonos Downsampling setting ON -> Downsampled to 48 kHz
        val format96 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(96000)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .build()
        val track96 = track44.copy(sampleRate = 96000, bitsPerSample = 24)
        val quality96 = resolveAudioQuality(format96, track96, output, isSonosDownsample = true)
        assertTrue(quality96.isDownsampledForSonos)
        assertEquals(48000, quality96.sonosTargetSampleRateHz)
        assertEquals("Downsampled to 24-bit / 48 kHz FLAC for Sonos S2", quality96.signalPathDescription)
        assertEquals("Lossless UPnP Relay (Downsampled to 48 kHz)", quality96.outputInfo.description)

        // 4. 88.2 kHz / 24-bit track with Sonos Downsampling setting ON -> Downsampled to 44.1 kHz
        val format88 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(88200)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .build()
        val track88 = track44.copy(sampleRate = 88200, bitsPerSample = 24)
        val quality88 = resolveAudioQuality(format88, track88, output, isSonosDownsample = true)
        assertTrue(quality88.isDownsampledForSonos)
        assertEquals(44100, quality88.sonosTargetSampleRateHz)
        assertEquals("Downsampled to 24-bit / 44.1 kHz FLAC for Sonos S2", quality88.signalPathDescription)
        assertEquals("Lossless UPnP Relay (Downsampled to 44.1 kHz)", quality88.outputInfo.description)
    }

    @Test
    fun testParseBluetoothCodecConfig() {
        val ldac = parseBluetoothCodecConfig(
            codecType = 4, // LDAC
            sampleRateFlag = BluetoothCodecConfig.SAMPLE_RATE_96000,
            bitsPerSampleFlag = BluetoothCodecConfig.BITS_PER_SAMPLE_24,
            channelModeFlag = BluetoothCodecConfig.CHANNEL_MODE_STEREO,
            codecSpecific1 = 1000L,
        )
        assertEquals("LDAC", ldac.codecName)
        assertEquals(96000, ldac.sampleRateHz)
        assertEquals(24, ldac.bitDepth)
        assertEquals("Stereo", ldac.channelMode)
        assertEquals("990 kbps", ldac.bitrateInfo)
        assertEquals("LDAC (990 kbps)", ldac.codecSummary)
        assertEquals("24-bit / 96 kHz", ldac.formatSummary)

        val aac = parseBluetoothCodecConfig(
            codecType = 1, // AAC
            sampleRateFlag = BluetoothCodecConfig.SAMPLE_RATE_44100,
            bitsPerSampleFlag = BluetoothCodecConfig.BITS_PER_SAMPLE_16,
            channelModeFlag = BluetoothCodecConfig.CHANNEL_MODE_STEREO,
        )
        assertEquals("AAC", aac.codecName)
        assertEquals(44100, aac.sampleRateHz)
        assertEquals(16, aac.bitDepth)
        assertEquals("AAC (~256 kbps)", aac.codecSummary)
        assertEquals("16-bit / 44.1 kHz", aac.formatSummary)
    }

    @Test
    fun testAddonSourceNames() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .build()
        val output = AudioOutputInfo(
            routeType = OutputRouteType.PHONE_SPEAKER,
            displayName = "Phone Speaker",
            description = "Internal Device Stereo Speakers",
        )

        // 1. Explicit addonName provided
        val trackAero = Track(
            id = "nebula:a1_12345",
            source = Track.SOURCE_NEBULA,
            uri = "nebula:stream?artist=Manowar&title=Sign&id=a1_12345&provider=comtorboxapple-music-addon",
            fileName = "Sign.flac",
            mimeType = "audio/flac",
            sizeBytes = null,
            title = "Sign",
        )
        val qualityAero = resolveAudioQuality(format, trackAero, output, addonName = "Aero")
        assertEquals("Aero Stream", qualityAero.sourceDescription)

        // 2. Inferred from provider param
        val trackNullbeat = Track(
            id = "nebula:dz:998877",
            source = Track.SOURCE_NEBULA,
            uri = "nebula:stream?artist=Test&title=Song&id=dz:998877&provider=nullbeat",
            fileName = "Song.flac",
            mimeType = "audio/flac",
            sizeBytes = null,
            title = "Song",
        )
        val qualityNullbeat = resolveAudioQuality(format, trackNullbeat, output)
        assertEquals("NullBeat Stream", qualityNullbeat.sourceDescription)

        // 3. Inferred from track ID
        val trackAeroInferred = Track(
            id = "nebula:a1_abcde",
            source = Track.SOURCE_NEBULA,
            uri = "nebula:stream?artist=Test&title=Song&id=a1_abcde",
            fileName = "Song.flac",
            mimeType = "audio/flac",
            sizeBytes = null,
            title = "Song",
        )
        val qualityAeroInferred = resolveAudioQuality(format, trackAeroInferred, output)
        assertEquals("Aero Stream", qualityAeroInferred.sourceDescription)

        // 4. Inferred from tidal ID
        val trackNebulaInferred = Track(
            id = "nebula:tidal:track:112233",
            source = Track.SOURCE_NEBULA,
            uri = "nebula:stream?artist=Test&title=Song&id=tidal:track:112233",
            fileName = "Song.flac",
            mimeType = "audio/flac",
            sizeBytes = null,
            title = "Song",
        )
        val qualityNebulaInferred = resolveAudioQuality(format, trackNebulaInferred, output)
        assertEquals("Nebula Stream", qualityNebulaInferred.sourceDescription)
    }
}
