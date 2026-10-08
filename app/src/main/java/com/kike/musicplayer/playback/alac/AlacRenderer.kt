package com.kike.musicplayer.playback.alac

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import java.nio.ByteOrder

class AlacDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

// SimpleDecoder only uses the arrays' length and fills them via createInputBuffer/createOutputBuffer.
@Suppress("UNCHECKED_CAST")
@OptIn(UnstableApi::class)
class AlacDecoder(format: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, AlacDecoderException>(
        arrayOfNulls<DecoderInputBuffer>(BUFFER_COUNT) as Array<DecoderInputBuffer>,
        arrayOfNulls<SimpleDecoderOutputBuffer>(BUFFER_COUNT) as Array<SimpleDecoderOutputBuffer>,
    ) {

    private val frameDecoder = AlacFrameDecoder(
        format.initializationData.firstOrNull() ?: throw AlacDecoderException("ALAC stream without magic cookie")
    )
    val channelCount get() = frameDecoder.config.numChannels
    val sampleRate get() = frameDecoder.config.sampleRate
    val pcmEncoding: Int = when (frameDecoder.config.bitDepth) {
        16 -> C.ENCODING_PCM_16BIT
        20, 24 -> C.ENCODING_PCM_24BIT
        else -> C.ENCODING_PCM_32BIT
    }

    init {
        setInitialInputBufferSize(if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else frameDecoder.maxOutputBytes)
    }

    override fun getName() = "kotlin-alac"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable) =
        AlacDecoderException("Unexpected ALAC decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): AlacDecoderException? {
        val input = inputBuffer.data ?: return null
        val out = outputBuffer.init(inputBuffer.timeUs, frameDecoder.maxOutputBytes).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            frameDecoder.decode(input, out)
            out.flip()
            null
        } catch (e: Exception) {
            AlacDecoderException("ALAC decode failed", e)
        }
    }

    private companion object {
        const val BUFFER_COUNT = 16
    }
}

/** Decodes ALAC in software, bit-exactly, on every device. */
@OptIn(UnstableApi::class)
class AlacAudioRenderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    DecoderAudioRenderer<AlacDecoder>(handler, listener, sink) {

    override fun getName() = "AlacAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_ALAC || format.initializationData.isEmpty()) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        val config = runCatching { AlacConfig.parse(format.initializationData[0]) }.getOrNull()
            ?: return C.FORMAT_UNSUPPORTED_SUBTYPE
        val encoding = when (config.bitDepth) {
            16 -> C.ENCODING_PCM_16BIT
            20, 24 -> C.ENCODING_PCM_24BIT
            32 -> C.ENCODING_PCM_32BIT
            else -> return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        val pcm = Util.getPcmFormat(encoding, config.numChannels, config.sampleRate)
        return if (sinkSupportsFormat(pcm)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?) = AlacDecoder(format)

    override fun getOutputFormat(decoder: AlacDecoder): Format =
        Util.getPcmFormat(decoder.pcmEncoding, decoder.channelCount, decoder.sampleRate)
}
