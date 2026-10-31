package com.kike.musicplayer.playback

import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

enum class SleepTimerMode {
    OFF,
    MINUTES,
    END_OF_TRACK,
}

data class SleepTimerStatus(
    val isActive: Boolean = false,
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingSeconds: Long = 0,
    val totalSeconds: Long = 0,
) {
    val formattedRemaining: String
        get() {
            if (!isActive || remainingSeconds <= 0) return ""
            val m = remainingSeconds / 60
            val s = remainingSeconds % 60
            return if (m > 0) "%dm %02ds".format(m, s) else "%ds".format(s)
        }
}

class SleepTimerManager(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private var playerProvider: (() -> Player?)? = null

    private val _status = MutableStateFlow(SleepTimerStatus())
    val status: StateFlow<SleepTimerStatus> = _status.asStateFlow()

    private var timerJob: Job? = null
    private var originalVolume: Float = 1.0f

    fun attachPlayer(provider: () -> Player?) {
        playerProvider = provider
    }

    fun detachPlayer() {
        playerProvider = null
    }

    private fun getPlayer(): Player? = playerProvider?.invoke()

    fun startTimerMinutes(minutes: Int) {
        cancelTimer()
        val totalSec = minutes * 60L
        val endUptime = SystemClock.uptimeMillis() + totalSec * 1000L
        _status.value = SleepTimerStatus(
            isActive = true,
            mode = SleepTimerMode.MINUTES,
            remainingSeconds = totalSec,
            totalSeconds = totalSec,
        )

        val player = getPlayer()
        if (player != null) {
            originalVolume = player.volume
        }

        timerJob = scope.launch(dispatcher) {
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val remainingMs = max(0L, endUptime - now)
                val remainingSec = (remainingMs + 999L) / 1000L

                // Last 30 seconds: smooth linear fade out down to 0
                if (remainingMs <= 30_000L) {
                    val fadeFraction = (remainingMs.toFloat() / 30_000f).coerceIn(0f, 1f)
                    getPlayer()?.let { p ->
                        if (p.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                            p.volume = originalVolume * fadeFraction
                        }
                    }
                }

                _status.value = _status.value.copy(remainingSeconds = remainingSec)

                if (remainingMs <= 0L) {
                    getPlayer()?.let { p ->
                        p.pause()
                        if (p.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                            p.volume = originalVolume
                        }
                    }
                    _status.value = SleepTimerStatus()
                    break
                }
                delay(500)
            }
        }
    }

    fun startTimerEndOfTrack() {
        cancelTimer()
        _status.value = SleepTimerStatus(
            isActive = true,
            mode = SleepTimerMode.END_OF_TRACK,
            remainingSeconds = 0,
            totalSeconds = 0,
        )

        val player = getPlayer()
        if (player != null) {
            originalVolume = player.volume
        }

        timerJob = scope.launch(dispatcher) {
            while (isActive) {
                val p = getPlayer()
                if (p == null || !p.isPlaying) {
                    delay(500)
                    continue
                }
                val duration = p.duration
                val position = p.currentPosition
                if (duration <= 0L) {
                    delay(500)
                    continue
                }
                val remainingMs = max(0L, duration - position)
                val remainingSec = (remainingMs + 999L) / 1000L

                // Last 30 seconds of track: smooth linear fade out
                if (remainingMs in 1L..30_000L) {
                    val fadeFraction = (remainingMs.toFloat() / 30_000f).coerceIn(0f, 1f)
                    if (p.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                        p.volume = originalVolume * fadeFraction
                    }
                }

                _status.value = _status.value.copy(
                    remainingSeconds = remainingSec,
                    totalSeconds = if (_status.value.totalSeconds == 0L) remainingSec else _status.value.totalSeconds,
                )

                if (remainingMs <= 150L || position >= duration) {
                    p.pause()
                    if (p.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                        p.volume = originalVolume
                    }
                    _status.value = SleepTimerStatus()
                    break
                }
                delay(500)
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        getPlayer()?.let { p ->
            if (p.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                p.volume = originalVolume
            }
        }
        _status.value = SleepTimerStatus()
    }
}
