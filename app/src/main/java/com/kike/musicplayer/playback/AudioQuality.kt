package com.kike.musicplayer.playback

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.sonos.SonosGroup
import com.kike.musicplayer.sonos.resample.ResamplePlan

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

data class BluetoothCodecDetails(
    val codecName: String,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelMode: String? = null,
    val bitrateInfo: String? = null,
) {
    /** Formatted string for Bluetooth format, e.g. "24-bit / 96 kHz" or "16-bit / 44.1 kHz". */
    val formatSummary: String?
        get() {
            val depthStr = bitDepth?.let { "$it-bit" }
            val rateStr = sampleRateHz?.let { formatSampleRate(it) }
            return when {
                depthStr != null && rateStr != null -> "$depthStr / $rateStr"
                rateStr != null -> rateStr
                depthStr != null -> depthStr
                else -> null
            }
        }

    /** Full summary for codec row, e.g. "LDAC (990 kbps)" or "AAC". */
    val codecSummary: String
        get() = if (!bitrateInfo.isNullOrBlank()) "$codecName ($bitrateInfo)" else codecName
}

data class AudioOutputInfo(
    val routeType: OutputRouteType,
    val displayName: String,
    val description: String,
    val hardwareRates: List<Int> = emptyList(),
    val maxHardwareRateHz: Int? = null,
    val nativeMixerRateHz: Int? = null,
    val bluetoothCodec: BluetoothCodecDetails? = null,
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
    val sonosTargetSampleRateHz: Int? = null,
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
                depthStr != null -> depthStr
                bitrateKbps != null && bitrateKbps > 0 -> "$codec $bitrateKbps kbps"
                else -> codec
            }
        }
}

fun formatSampleRate(hz: Int): String {
    val khz = hz / 1000.0
    return if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(java.util.Locale.US, khz).replace(".0 ", " ")
}

/**
 * Inspects active `BluetoothA2dp` codec status via reflection/SDK APIs when `BLUETOOTH_CONNECT` is granted,
 * falling back to `AudioDeviceInfo` negotiated sample rates and encodings.
 */
fun queryBluetoothCodecDetails(
    context: Context,
    btDevice: AudioDeviceInfo,
    a2dpProxy: BluetoothA2dp?,
): BluetoothCodecDetails? {
    val deviceRates = btDevice.sampleRates.filter { it > 0 }
    val fallbackRate = deviceRates.maxOrNull()
    val fallbackDepth = btDevice.encodings.toList().mapNotNull { enc ->
        when (enc) {
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_16BIT -> 16
            else -> null
        }
    }.maxOrNull()

    val isBle = btDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET || btDevice.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
    val isSco = btDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    if (isBle) {
        return BluetoothCodecDetails(
            codecName = "LC3 (LE Audio)",
            sampleRateHz = fallbackRate ?: 48000,
            bitDepth = fallbackDepth ?: 24,
            channelMode = "Stereo",
        )
    }
    if (isSco) {
        return BluetoothCodecDetails(
            codecName = "SCO (Hands-Free Voice)",
            sampleRateHz = fallbackRate ?: 16000,
            bitDepth = fallbackDepth ?: 16,
            channelMode = "Mono",
        )
    }

    val hasBtConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else true

    if (hasBtConnect && a2dpProxy != null) {
        runCatching {
            val activeDevice = a2dpProxy.connectedDevices.firstOrNull()
            val getCodecStatusMethod = BluetoothA2dp::class.java.getMethod(
                "getCodecStatus",
                android.bluetooth.BluetoothDevice::class.java,
            )
            val codecStatus = getCodecStatusMethod.invoke(a2dpProxy, activeDevice)
            if (codecStatus != null) {
                val getCodecConfigMethod = codecStatus.javaClass.getMethod("getCodecConfig")
                val config = getCodecConfigMethod.invoke(codecStatus) as? BluetoothCodecConfig
                if (config != null) {
                    return parseBluetoothCodecConfig(
                        codecType = config.codecType,
                        sampleRateFlag = config.sampleRate,
                        bitsPerSampleFlag = config.bitsPerSample,
                        channelModeFlag = config.channelMode,
                        codecSpecific1 = config.codecSpecific1,
                        fallbackRate = fallbackRate,
                        fallbackDepth = fallbackDepth,
                    )
                }
            }
        }
    }

    // Fallback when BLUETOOTH_CONNECT is not yet granted or BluetoothA2dp proxy is still connecting:
    val inferredCodec = when {
        fallbackRate != null && fallbackRate >= 88200 -> "Hi-Res Bluetooth (LDAC / LHDC)"
        else -> "A2DP Audio Codec (SBC / AAC / aptX)"
    }
    return BluetoothCodecDetails(
        codecName = inferredCodec,
        sampleRateHz = fallbackRate,
        bitDepth = fallbackDepth,
    )
}

internal fun parseBluetoothCodecConfig(
    codecType: Int,
    sampleRateFlag: Int,
    bitsPerSampleFlag: Int,
    channelModeFlag: Int,
    codecSpecific1: Long = 0L,
    fallbackRate: Int? = null,
    fallbackDepth: Int? = null,
): BluetoothCodecDetails {
    // Standard Android AOSP & Qualcomm/OPlus BluetoothCodecConfig codec types:
    val codecName = when (codecType) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        5 -> "LC3"
        6 -> "Opus"
        7 -> "aptX Adaptive"
        8 -> "aptX TWS+"
        9, 10, 11 -> "LHDC"
        else -> "Bluetooth A2DP"
    }

    val sampleRateHz = when {
        (sampleRateFlag and BluetoothCodecConfig.SAMPLE_RATE_192000) != 0 -> 192000
        (sampleRateFlag and BluetoothCodecConfig.SAMPLE_RATE_176400) != 0 -> 176400
        (sampleRateFlag and BluetoothCodecConfig.SAMPLE_RATE_96000) != 0 -> 96000
        (sampleRateFlag and BluetoothCodecConfig.SAMPLE_RATE_88200) != 0 -> 88200
        (sampleRateFlag and BluetoothCodecConfig.SAMPLE_RATE_48000) != 0 -> 48000
        (sampleRateFlag and BluetoothCodecConfig.SAMPLE_RATE_44100) != 0 -> 44100
        else -> fallbackRate
    }

    val bitDepth = when {
        (bitsPerSampleFlag and BluetoothCodecConfig.BITS_PER_SAMPLE_32) != 0 -> 32
        (bitsPerSampleFlag and BluetoothCodecConfig.BITS_PER_SAMPLE_24) != 0 -> 24
        (bitsPerSampleFlag and BluetoothCodecConfig.BITS_PER_SAMPLE_16) != 0 -> 16
        else -> fallbackDepth
    }

    val channelMode = when {
        (channelModeFlag and BluetoothCodecConfig.CHANNEL_MODE_STEREO) != 0 -> "Stereo"
        (channelModeFlag and BluetoothCodecConfig.CHANNEL_MODE_MONO) != 0 -> "Mono"
        else -> null
    }

    val bitrateInfo = when (codecName) {
        "LDAC" -> when (codecSpecific1) {
            1000L -> if (sampleRateHz == 88200 || sampleRateHz == 44100) "909 kbps" else "990 kbps"
            1001L -> if (sampleRateHz == 88200 || sampleRateHz == 44100) "606 kbps" else "660 kbps"
            1002L -> if (sampleRateHz == 88200 || sampleRateHz == 44100) "303 kbps" else "330 kbps"
            else -> "Adaptive Bitrate"
        }
        "aptX HD" -> "576 kbps"
        "aptX" -> "352 kbps"
        "AAC" -> "~256 kbps"
        "SBC" -> "~328 kbps"
        else -> null
    }

    return BluetoothCodecDetails(
        codecName = codecName,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        channelMode = channelMode,
        bitrateInfo = bitrateInfo,
    )
}

fun detectAudioOutput(
    context: Context,
    castGroup: SonosGroup?,
    a2dpProxy: BluetoothA2dp? = null,
): AudioOutputInfo {
    if (castGroup != null) {
        return AudioOutputInfo(
            routeType = OutputRouteType.SONOS_CAST,
            displayName = "Sonos (${castGroup.name})",
            description = "Lossless Bit-Perfect UPnP Relay",
        )
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return AudioOutputInfo(
            routeType = OutputRouteType.PHONE_SPEAKER,
            displayName = "Phone Speaker",
            description = "Internal Device Speaker",
            nativeMixerRateHz = 48000,
        )

    val nativeMixerRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        ?.toIntOrNull()?.takeIf { it > 0 } ?: 48000

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
            nativeMixerRateHz = nativeMixerRate,
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
        val btDetails = queryBluetoothCodecDetails(context, bt, a2dpProxy)
        val routeDesc = when (bt.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Wireless Bluetooth LE Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Hands-Free (SCO)"
            else -> "Wireless Bluetooth A2DP Sink"
        }
        return AudioOutputInfo(
            routeType = OutputRouteType.BLUETOOTH,
            displayName = displayName,
            description = routeDesc,
            hardwareRates = bt.sampleRates.filter { it > 0 },
            maxHardwareRateHz = btDetails?.sampleRateHz ?: bt.sampleRates.filter { it > 0 }.maxOrNull(),
            nativeMixerRateHz = nativeMixerRate,
            bluetoothCodec = btDetails,
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
            description = "3.5mm / Analog Audio Output (${formatSampleRate(nativeMixerRate)} System Mixer)",
            nativeMixerRateHz = nativeMixerRate,
        )
    }

    // 4. Default phone speaker
    return AudioOutputInfo(
        routeType = OutputRouteType.PHONE_SPEAKER,
        displayName = "Phone Speaker",
        description = "Internal Device Stereo Speakers (${formatSampleRate(nativeMixerRate)} System Mixer)",
        nativeMixerRateHz = nativeMixerRate,
    )
}

fun inferAddonName(track: Track?): String {
    if (track == null) return "Add-on"
    val params = com.kike.musicplayer.data.nebula.NebulaUrls.parseParams(track.uri)
    val provider = params["provider"]
    if (!provider.isNullOrBlank()) {
        if (provider.contains("aero", ignoreCase = true) || provider.contains("apple", ignoreCase = true)) return "Aero"
        if (provider.contains("nullbeat", ignoreCase = true)) return "NullBeat"
        if (provider.contains("nebula", ignoreCase = true)) return "Nebula"
        return provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
    }
    val id = params["id"] ?: track.id.removePrefix("nebula:")
    if (id.startsWith("a1_") || id.startsWith("torbox_am_")) return "Aero"
    if (id.startsWith("dz:")) return "NullBeat"
    if (id.startsWith("tidal:track:")) return "Nebula"
    return "Add-on"
}

fun resolveAudioQuality(
    format: Format?,
    track: Track?,
    outputInfo: AudioOutputInfo,
    isSonosDownsample: Boolean = false,
    isDspActive: Boolean = false,
    addonName: String? = null,
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
        track?.source == Track.SOURCE_NEBULA -> {
            val name = addonName ?: inferAddonName(track)
            "$name Stream"
        }
        track?.source == Track.SOURCE_SAF || track?.source == Track.SOURCE_MEDIASTORE -> "Local Device Storage"
        else -> "Audio Stream"
    }

    // TrackServer only downsamples when:
    // 1. Casting to Sonos
    // 2. The Sonos downsample setting is enabled
    // 3. The track is a resample candidate (FLAC/ALAC) with sampleRate > 48 kHz
    val sonosPlan = if (
        outputInfo.routeType == OutputRouteType.SONOS_CAST &&
        isSonosDownsample &&
        codec in listOf("FLAC", "ALAC") &&
        sampleRateHz != null
    ) {
        ResamplePlan.forStream(sampleRateHz, channels)
    } else null

    val isDownsampling = sonosPlan != null
    val sonosTargetRateHz = sonosPlan?.outputRate ?: if (
        outputInfo.routeType == OutputRouteType.SONOS_CAST &&
        isSonosDownsample &&
        codec in listOf("FLAC", "ALAC") &&
        (sampleRateHz ?: 0) > ResamplePlan.MAX_SONOS_SAMPLE_RATE
    ) {
        ResamplePlan.MAX_SONOS_SAMPLE_RATE
    } else null

    val decoderDesc = when {
        outputInfo.routeType == OutputRouteType.SONOS_CAST -> {
            if (isDownsampling) {
                when (codec) {
                    "ALAC" -> "In-App ALAC Demuxer & 64-bit FIR Decimator"
                    "FLAC" -> "In-App FLAC Decoder & 64-bit FIR Decimator"
                    else -> "Sonos Speaker Hardware Decoder"
                }
            } else {
                "Sonos Speaker Hardware ($codec Passthrough)"
            }
        }
        codec == "ALAC" -> "Custom In-App Bit-Exact ALAC Decoder"
        codec == "FLAC" -> "Media3 FLAC Decoder"
        codec == "MP3" -> "Media3 MediaCodec MP3 Decoder"
        codec == "AAC" -> "Media3 AAC Audio Decoder"
        else -> "ExoPlayer Audio Renderer"
    }

    // Phone-side DSP (LoudnessEnhancer / ExoPlayer volume crossfade) only applies to local device playback, not Sonos HTTP relay
    val effectiveDsp = isDspActive && outputInfo.routeType != OutputRouteType.SONOS_CAST
    val mixerRateHz = outputInfo.nativeMixerRateHz ?: 48000
    val mixerRateLabel = formatSampleRate(mixerRateHz)
    val isResampledByAndroidMixer = sampleRateHz != null && sampleRateHz != mixerRateHz

    // Resolve output description dynamically so Sonos Route Type reflects whether the current track is actually downsampled
    val resolvedOutputInfo = if (outputInfo.routeType == OutputRouteType.SONOS_CAST) {
        val targetStr = sonosTargetRateHz?.let(::formatSampleRate) ?: "48 kHz"
        outputInfo.copy(
            description = if (isDownsampling) {
                "Lossless UPnP Relay (Downsampled to $targetStr)"
            } else {
                "Lossless Bit-Perfect UPnP Relay"
            }
        )
    } else {
        outputInfo
    }

    val btCodec = outputInfo.bluetoothCodec
    val btDetailSuffix = if (btCodec != null) {
        val fmt = btCodec.formatSummary
        if (fmt != null) "${btCodec.codecName} · $fmt" else btCodec.codecName
    } else null

    val signalPathDesc = when (outputInfo.routeType) {
        OutputRouteType.SONOS_CAST -> if (isDownsampling) {
            val targetStr = sonosTargetRateHz?.let(::formatSampleRate) ?: "48 kHz"
            "Downsampled to 24-bit / $targetStr FLAC for Sonos S2"
        } else {
            "Bit-Perfect UPnP Lossless HTTP Relay (localhost:8765)"
        }
        OutputRouteType.USB_DAC -> if (effectiveDsp) {
            "Direct USB AudioTrack (Audio DSP / Normalization active)"
        } else {
            "Bit-Perfect Direct USB AudioTrack (Float output)"
        }
        OutputRouteType.BLUETOOTH -> when {
            effectiveDsp && btDetailSuffix != null -> "Bluetooth Encoding ($btDetailSuffix · DSP active)"
            effectiveDsp -> "Bluetooth A2DP / LE Stream (Audio DSP active)"
            btDetailSuffix != null -> "Bluetooth Encoding ($btDetailSuffix)"
            else -> "Bluetooth A2DP / LE Wireless Stream"
        }
        OutputRouteType.WIRED_HEADPHONES, OutputRouteType.PHONE_SPEAKER -> when {
            effectiveDsp && isResampledByAndroidMixer ->
                "Android AudioMixer (Resampled to $mixerRateLabel · Audio DSP active)"
            effectiveDsp ->
                "Android AudioMixer ($mixerRateLabel · Audio DSP active)"
            isResampledByAndroidMixer ->
                "Android AudioMixer (32-bit Float · Resampled to $mixerRateLabel)"
            else ->
                "Android AudioMixer (32-bit Float · Native $mixerRateLabel)"
        }
    }

    val integrityDesc = when {
        outputInfo.routeType == OutputRouteType.SONOS_CAST -> if (isDownsampling) {
            val targetStr = sonosTargetRateHz?.let(::formatSampleRate) ?: "48 kHz"
            "Lossless 64-bit FIR Decimation (24-bit / $targetStr)"
        } else if (isLossless) {
            "Bit-Perfect (Untouched Original Stream)"
        } else {
            "Untouched Original Stream (Lossy Source)"
        }
        outputInfo.routeType == OutputRouteType.BLUETOOTH -> {
            val codecLabel = btCodec?.codecName ?: "Bluetooth"
            "Lossy Bluetooth Re-encoding ($codecLabel — Not Bit-Perfect)"
        }
        effectiveDsp -> "Modified by Audio DSP (Not Bit-Perfect)"
        outputInfo.routeType == OutputRouteType.USB_DAC -> {
            if (isLossless) "Bit-Perfect (No DSP / Normalization)" else null
        }
        outputInfo.routeType == OutputRouteType.WIRED_HEADPHONES || outputInfo.routeType == OutputRouteType.PHONE_SPEAKER -> {
            if (isResampledByAndroidMixer) {
                "OS Mixer Resampled (${sampleRateHz?.let(::formatSampleRate) ?: "Source"} → $mixerRateLabel — Not Bit-Perfect)"
            } else {
                "Lossless In-App Decode · Shared OS Mixer ($mixerRateLabel)"
            }
        }
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
        outputInfo = resolvedOutputInfo,
        decoderDescription = decoderDesc,
        signalPathDescription = signalPathDesc,
        integrityDescription = integrityDesc,
        isDownsampledForSonos = isDownsampling,
        sonosTargetSampleRateHz = sonosTargetRateHz,
        isDspActive = effectiveDsp,
    )
}
