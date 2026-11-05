package com.kike.musicplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.kike.musicplayer.data.db.Track
import org.junit.Assert.assertEquals
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
            description = "Direct USB Audio (Bit-Perfect)",
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
    }

    @Test
    fun testStandardCdLossless() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .build()
        val track = Track(
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
            description = "Internal Device Stereo Speakers",
        )

        val quality = resolveAudioQuality(format, track, output)

        assertEquals(AudioTier.LOSSLESS, quality.tier)
        assertEquals("FLAC", quality.codec)
        assertEquals(16, quality.bitDepth)
        assertEquals(44100, quality.sampleRateHz)
        assertEquals("Lossless", quality.badgeLabel)
        assertEquals("16-bit / 44.1 kHz", quality.resolutionLabel)
        assertEquals("Local Device Storage", quality.sourceDescription)
        assertEquals("Media3 FLAC Decoder", quality.decoderDescription)
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
            description = "Wireless Bluetooth A2DP / LE Sink",
        )

        val quality = resolveAudioQuality(format, track, output)

        assertEquals(AudioTier.HIGH_QUALITY, quality.tier)
        assertEquals("MP3", quality.codec)
        assertEquals(320, quality.bitrateKbps)
        assertEquals("320 kbps", quality.badgeLabel)
        assertEquals("MP3 320 kbps", quality.resolutionLabel)
    }

    @Test
    fun testSonosCastRelay() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .build()
        val track = Track(
            id = "saf:test4",
            source = Track.SOURCE_SAF,
            uri = "content://saf/test4",
            fileName = "Track04.flac",
            mimeType = "audio/flac",
            sizeBytes = 30_000_000L,
            durationMs = 200_000L,
            title = "Test Sonos Cast",
        )
        val output = AudioOutputInfo(
            routeType = OutputRouteType.SONOS_CAST,
            displayName = "Sonos (Kitchen)",
            description = "Lossless Bit-Perfect UPnP Relay",
        )

        val quality = resolveAudioQuality(format, track, output, isSonosDownsample = false)

        assertEquals(AudioTier.LOSSLESS, quality.tier)
        assertEquals(OutputRouteType.SONOS_CAST, quality.outputInfo.routeType)
        assertTrue(quality.signalPathDescription.contains("UPnP Lossless HTTP Relay"))
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
