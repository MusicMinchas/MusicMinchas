package com.kike.musicplayer.data.lastfm

/**
 * When a play is scrobbled: after [percent] of the song or [capMinutes] minutes of listening,
 * whichever comes first (no time limit when [capMinutes] is null). Never earlier than Last.fm's
 * own rule (half the song or 4 minutes), and songs of 30 seconds or less are never scrobbled.
 */
data class ScrobbleRule(val percent: Int = MIN_PERCENT, val capMinutes: Int? = MIN_CAP_MINUTES) {
    init {
        require(percent in MIN_PERCENT..100) { "percent $percent" }
        require(capMinutes == null || capMinutes >= MIN_CAP_MINUTES) { "capMinutes $capMinutes" }
    }

    /** Listening time (ms) after which a play of a [durationMs]-long song is scrobbled; null if never. */
    fun thresholdMs(durationMs: Long): Long? {
        val cap = capMinutes?.let { it * 60_000L }
        // Unknown length: Last.fm needs the song to be over 30 s, which a few minutes of listening proves.
        if (durationMs <= 0) return cap ?: (MIN_CAP_MINUTES * 60_000L)
        if (durationMs <= MIN_LENGTH_MS) return null
        val byPercent = durationMs * percent / 100
        return if (cap == null) byPercent else minOf(byPercent, cap)
    }

    companion object {
        const val MIN_PERCENT = 50
        const val MIN_CAP_MINUTES = 4
        const val MAX_CAP_MINUTES = 20
        const val MIN_LENGTH_MS = 30_000L
    }
}
