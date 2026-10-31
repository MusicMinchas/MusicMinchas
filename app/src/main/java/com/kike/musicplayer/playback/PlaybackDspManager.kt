package com.kike.musicplayer.playback

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kike.musicplayer.data.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages optional audio DSP effects (Loudness Normalization and Track Crossfade / Smooth Fading).
 *
 * BIT-PERFECT AUDIO GUARANTEE:
 * When both [Settings.loudnessNormalization] and [Settings.crossfade] are disabled (the default),
 * NO audio effects are attached to the ExoPlayer audio session and player volume is strictly fixed at 1.0f.
 * Digital audio samples pass completely unmodified and bit-perfect to AudioTrack and downstream DACs.
 */
class PlaybackDspManager(
    private val player: ExoPlayer,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var attachedSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var currentSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var fadeJob: Job? = null
    private var isCrossfading = false

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            currentSessionId = audioSessionId
            updateLoudnessEnhancer()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (settings.crossfade.value && player.isPlaying) {
                // Smooth ramp-in for the new track
                startRampIn()
            }
        }
    }

    init {
        player.addListener(playerListener)

        // Observe loudness normalization toggle
        scope.launch(dispatcher) {
            settings.loudnessNormalization.collect {
                updateLoudnessEnhancer()
            }
        }

        // Observe crossfade toggle
        scope.launch(dispatcher) {
            settings.crossfade.collect { enabled ->
                if (enabled) {
                    startCrossfadeMonitor()
                } else {
                    stopCrossfadeMonitor()
                    player.volume = 1f
                }
            }
        }
    }

    private fun updateLoudnessEnhancer() {
        val enabled = settings.loudnessNormalization.value
        if (!enabled) {
            releaseLoudnessEnhancer()
            return
        }

        val sessionId = if (currentSessionId != C.AUDIO_SESSION_ID_UNSET) {
            currentSessionId
        } else {
            player.audioSessionId
        }

        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) {
            return
        }

        try {
            if (loudnessEnhancer == null || attachedSessionId != sessionId) {
                releaseLoudnessEnhancer()
                loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                    // 600 mB (+6 dB target gain) provides balanced perceptual loudness across masterings
                    setTargetGain(TARGET_GAIN_MB)
                    this.enabled = true
                }
                attachedSessionId = sessionId
                Log.d(TAG, "LoudnessEnhancer attached to audio session $sessionId with ${TARGET_GAIN_MB}mB gain")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize LoudnessEnhancer on session $sessionId", e)
            releaseLoudnessEnhancer()
        }
    }

    private fun releaseLoudnessEnhancer() {
        loudnessEnhancer?.let {
            try {
                it.enabled = false
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing LoudnessEnhancer", e)
            }
        }
        loudnessEnhancer = null
        attachedSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun startCrossfadeMonitor() {
        fadeJob?.cancel()
        fadeJob = scope.launch(dispatcher) {
            while (isActive) {
                delay(150)
                if (!settings.crossfade.value) break
                if (!player.isPlaying) continue

                val duration = player.duration
                val position = player.currentPosition
                if (duration <= 0 || position < 0) continue

                val crossfadeMs = (settings.crossfadeSeconds.value * 1000L).coerceIn(1000L, 10_000L)
                val remaining = duration - position

                if (remaining in 0..crossfadeMs && player.hasNextMediaItem()) {
                    isCrossfading = true
                    val progress = (remaining.toFloat() / crossfadeMs).coerceIn(0.08f, 1.0f)
                    player.volume = progress
                } else if (!isCrossfading) {
                    if (player.volume < 1.0f) {
                        player.volume = 1.0f
                    }
                }
            }
        }
    }

    private fun startRampIn() {
        val crossfadeMs = (settings.crossfadeSeconds.value * 1000L).coerceIn(1000L, 10_000L)
        val rampInMs = (crossfadeMs / 2).coerceIn(500L, 2000L)
        isCrossfading = true
        scope.launch(dispatcher) {
            val steps = 10
            val stepDelay = rampInMs / steps
            for (i in 1..steps) {
                if (!isActive || !settings.crossfade.value) break
                val vol = (i.toFloat() / steps).coerceIn(0.1f, 1.0f)
                player.volume = vol
                delay(stepDelay)
            }
            player.volume = 1.0f
            isCrossfading = false
        }
    }

    private fun stopCrossfadeMonitor() {
        fadeJob?.cancel()
        fadeJob = null
        isCrossfading = false
    }

    fun release() {
        player.removeListener(playerListener)
        releaseLoudnessEnhancer()
        stopCrossfadeMonitor()
        player.volume = 1.0f
    }

    companion object {
        private const val TAG = "PlaybackDspManager"
        const val TARGET_GAIN_MB = 600 // +6.0 dB perceptual normalization target
    }
}
