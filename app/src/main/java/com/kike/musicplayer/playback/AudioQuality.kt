package com.kike.musicplayer.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.sonos.SonosGroup

enum class AudioTier {
    HI_RES_LOSSLESS,
    LOSSLESS,
    HIGH_QUALITY,
    STANDARD,
}

enum class OutputRouteType {
    SONOS_CAST,
    USB_DAC,
    WIRED_HEADPHONES,
    BLUETOOTH,
    PHONE_SPEAKER,
}

data class AudioOutputInfo(
    val routeType: OutputRouteType,
    val displayName: String,
    val description: String,
    val hardwareRates: List<Int> = emptyList(),
    val maxHardwareRateHz: Int? = null,
)

data class AudioQualityDetails(
    val tier: AudioTier,
    val codec: String,
    val bitDepth: Int?,
    val sampleRateHz: Int?,
    val bitrateKbps: Int?,
    val channelCount: Int = 2,
    val sourceDescription: String,
    val outputInfo: AudioOutputInfo,
    val decoderDescription: String,
    val signalPathDescription: String,
    val integrityDescription: String? = null,
    val isDownsampledForSonos: Boolean = false,
    val isDspActive: Boolean = false,
) {
    /** Short label for badge pill, e.g. "Hi-Res Lossless", "Lossless", "320 kbps". */
    val badgeLabel: String
        get() = when (tier) {
            AudioTier.HI_RES_LOSSLESS -> "Hi-Res Lossless"
            AudioTier.LOSSLESS -> "Lossless"
            AudioTier.HIGH_QUALITY, AudioTier.STANDARD -> if (bitrateKbps != null && bitrateKbps > 0) "$bitrateKbps kbps" else codec
        }

    /** Formatted resolution string, e.g. "24-bit / 96 kHz" or "16-bit / 44.1 kHz" or "MP3 320 kbps". */
    val isLossless: Boolean
        get() = tier == AudioTier.HI_RES_LOSSLESS || tier == AudioTier.LOSSLESS

    /** Formatted resolution string, e.g. "24-bit / 96 kHz" or "16-bit / 44.1 kHz" or "MP3 320 kbps". */
    val resolutionLabel: String
        get() {
            if (!isLossless) {
                return if (bitrateKbps != null && bitrateKbps > 0) "$codec $bitrateKbps kbps" else codec
            }
            val rateStr = sampleRateHz?.let { formatSampleRate(it) }
            val depthStr = bitDepth?.let { "$it-bit" }
            return when {
                depthStr != null && rateStr != null -> "$depthStr / $rateStr"
                rateStr != null -> rateStr
                depthStr != null -> "$depthStr"
                bitrateKbps != null && bitrateKbps > 0 -> "$codec $bitrateKbps kbps"
                else -> codec
            }
        }
}

fun formatSampleRate(hz: Int): String {
    val khz = hz / 1000.0
    return if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(khz).replace(".0 ", " ")
}

fun detectAudioOutput(context: Context, castGroup: SonosGroup?, isDownsamplingSonos: Boolean): AudioOutputInfo {
    if (castGroup != null) {
        return AudioOutputInfo(
            routeType = OutputRouteType.SONOS_CAST,
            displayName = "Sonos (${castGroup.name})",
            description = if (isDownsamplingSonos) "Lossless UPnP Relay (Downsampled to 48 kHz)" else "Lossless Bit-Perfect UPnP Relay",
        )
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return AudioOutputInfo(OutputRouteType.PHONE_SPEAKER, "Phone Speaker", "Internal Device Speaker")

    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    // 1. USB DAC
    val usb = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }
    if (usb != null) {
        val prodName = usb.productName?.toString()?.trim().orEmpty()
        val displayName = if (prodName.isNotEmpty() && !prodName.equals("Android", ignoreCase = true)) {
            "USB DAC ($prodName)"
        } else {
            "USB DAC"
        }
        val rates = usb.sampleRates.filter { it > 0 }
        val maxRate = rates.maxOrNull()
        return AudioOutputInfo(
            routeType = OutputRouteType.USB_DAC,
            displayName = displayName,
            description = "Direct USB Audio",
            hardwareRates = rates,
            maxHardwareRateHz = maxRate,
        )
    }

    // 2. Bluetooth
    val bt = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }
    if (bt != null) {
        val prodName = bt.productName?.toString()?.trim().orEmpty()
        val displayName = if (prodName.isNotEmpty() && !prodName.equals("Android", ignoreCase = true)) {
            "Bluetooth ($prodName)"
        } else {
            "Bluetooth Audio"
        }
        return AudioOutputInfo(
            routeType = OutputRouteType.BLUETOOTH,
            displayName = displayName,
            description = "Wireless Bluetooth A2DP / LE Sink",
        )
    }

    // 3. Wired 3.5mm / Analog line out
    val wired = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
        it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL
    }
    if (wired != null) {
        return AudioOutputInfo(
            routeType = OutputRouteType.WIRED_HEADPHONES,
            displayName = "Wired Headphones",
            description = "Direct 3.5mm / Analog Audio Output",
        )
    }

    // 4. Default phone speaker
    return AudioOutputInfo(
        routeType = OutputRouteType.PHONE_SPEAKER,
        displayName = "Phone Speaker",
        description = "Internal Device Stereo Speakers",
    )
}

fun resolveAudioQuality(
    format: Format?,
    track: Track?,
    outputInfo: AudioOutputInfo,
    isSonosDownsample: Boolean = false,
    isDspActive: Boolean = false,
): AudioQualityDetails {
    val codec = when (format?.sampleMimeType) {
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_VORBIS -> "OGG"
        MimeTypes.AUDIO_OPUS -> "OPUS"
        MimeTypes.AUDIO_RAW -> "WAV"
        else -> {
            val ext = track?.fileName?.substringAfterLast('.', "")?.uppercase()
            when (ext) {
                "FLAC" -> "FLAC"
                "M4A" -> "ALAC"
                "MP3" -> "MP3"
                "AAC" -> "AAC"
                "OGG" -> "OGG"
                "WAV" -> "WAV"
                else -> track?.mimeType?.substringAfter('/')?.uppercase() ?: "AUDIO"
            }
        }
    }

    val sampleRateHz = format?.sampleRate?.takeIf { it > 0 }
        ?: track?.sampleRate?.takeIf { it > 0 }
        ?: if (codec in listOf("FLAC", "ALAC", "WAV")) 44100 else null

    val bitDepth = when (format?.pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> track?.bitsPerSample?.takeIf { it > 0 } ?: if (codec in listOf("FLAC", "ALAC", "WAV")) 16 else null
    }

    val bitrateKbps = format?.bitrate?.takeIf { it > 0 }?.let { it / 1000 }
        ?: if (track != null && track.sizeBytes != null && track.durationMs > 0) {
            (track.sizeBytes * 8L / track.durationMs).toInt()
        } else if (bitDepth != null && sampleRateHz != null && (format?.channelCount ?: 2) > 0) {
            ((sampleRateHz.toLong() * bitDepth * (format?.channelCount ?: 2)) / 1000L).toInt()
        } else null

    val channels = format?.channelCount?.takeIf { it > 0 } ?: 2

    val isLossless = codec in listOf("FLAC", "ALAC", "WAV", "AIFF", "PCM")
    val isHiRes = isLossless && ((sampleRateHz ?: 0) > 48000 || (bitDepth ?: 0) > 16)

    val tier = when {
        isHiRes -> AudioTier.HI_RES_LOSSLESS
        isLossless -> AudioTier.LOSSLESS
        (bitrateKbps ?: 0) >= 256 -> AudioTier.HIGH_QUALITY
        else -> AudioTier.STANDARD
    }

    val sourceDesc = when {
        track?.localUri != null -> "Downloaded to Phone"
        track?.source == Track.SOURCE_DRIVE -> "Google Drive"
        track?.source == Track.SOURCE_SUBSONIC -> "Subsonic / Navidrome"
        track?.source == Track.SOURCE_NEBULA -> "Nebula Stream"
        track?.source == Track.SOURCE_SAF || track?.source == Track.SOURCE_MEDIASTORE -> "Local Device Storage"
        else -> "Audio Stream"
    }

    val decoderDesc = when (codec) {
        "ALAC" -> "Custom In-App Bit-Exact ALAC Decoder"
        "FLAC" -> "Media3 FLAC Decoder"
        "MP3" -> "Media3 MediaCodec MP3 Decoder"
        "AAC" -> "Media3 AAC Audio Decoder"
        else -> "ExoPlayer Audio Renderer"
    }

    val isDownsampling = outputInfo.routeType == OutputRouteType.SONOS_CAST && isSonosDownsample && (sampleRateHz ?: 0) > 48000
    // Phone-side DSP (LoudnessEnhancer / ExoPlayer volume crossfade) only applies to local device playback, not Sonos HTTP relay
    val effectiveDsp = isDspActive && outputInfo.routeType != OutputRouteType.SONOS_CAST
    val signalPathDesc = when (outputInfo.routeType) {
        OutputRouteType.SONOS_CAST -> if (isDownsampling) {
            "Downsampled to 48 kHz FLAC for Sonos compatibility"
        } else {
            "Bit-Perfect UPnP Lossless HTTP Relay (localhost:8765)"
        }
        OutputRouteType.USB_DAC -> if (effectiveDsp) {
            "Direct USB AudioTrack (Audio DSP / Normalization active)"
        } else {
            "Bit-Perfect Direct USB AudioTrack (Float output)"
        }
        OutputRouteType.BLUETOOTH -> if (effectiveDsp) {
            "Bluetooth A2DP / LE Stream (Audio DSP active)"
        } else {
            "Bluetooth A2DP / LE Wireless Stream"
        }
        OutputRouteType.WIRED_HEADPHONES -> if (effectiveDsp) {
            "Direct Hardware AudioTrack (Audio DSP active)"
        } else {
            "Direct Hardware AudioTrack (Bit-Perfect Float)"
        }
        OutputRouteType.PHONE_SPEAKER -> if (effectiveDsp) {
            "Direct Device AudioTrack (Audio DSP active)"
        } else {
            "Direct Device AudioTrack (Bit-Perfect Float)"
        }
    }

    val integrityDesc = when {
        isDownsampling -> null
        outputInfo.routeType == OutputRouteType.BLUETOOTH -> "Bluetooth Codec Re-encoding (Not Bit-Perfect)"
        effectiveDsp -> "Modified by Audio DSP (Not Bit-Perfect)"
        tier == AudioTier.HI_RES_LOSSLESS || tier == AudioTier.LOSSLESS -> "Bit-Perfect (No DSP / Normalization)"
        else -> null
    }

    return AudioQualityDetails(
        tier = tier,
        codec = codec,
        bitDepth = bitDepth,
        sampleRateHz = sampleRateHz,
        bitrateKbps = bitrateKbps,
        channelCount = channels,
        sourceDescription = sourceDesc,
        outputInfo = outputInfo,
        decoderDescription = decoderDesc,
        signalPathDescription = signalPathDesc,
        integrityDescription = integrityDesc,
        isDownsampledForSonos = isDownsampling,
        isDspActive = effectiveDsp,
    )
}
