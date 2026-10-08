package com.kike.musicplayer.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.kike.musicplayer.playback.alac.AlacAudioRenderer

/**
 * Default renderers plus our software ALAC decoder, placed first so it wins over platform
 * decoders: Qualcomm's c2.qti.alac.* reject valid files ("invalid ALAC CSD param"), even
 * through Android's own extractor, so ALAC is never trusted to MediaCodec.
 */
@OptIn(UnstableApi::class)
class AppRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out += AlacAudioRenderer(eventHandler, eventListener, audioSink)
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out,
        )
    }
}
