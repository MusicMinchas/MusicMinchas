package com.kike.musicplayer.data.history

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface HistoricalDatesRegistry {
    val csvDates: StateFlow<Map<String, Long>>
    val inAppDates: StateFlow<Map<String, Long>>
    fun recordInAppPlay(trackId: String, timestamp: Long)
    fun setCsvDates(dates: Map<String, Long>)
    fun initializeInAppPlays(plays: Map<String, Long>)
    fun clear()
}

/**
 * Manages authoritative historical last-played dates from iTunes and Apple Music CSV exports,
 * separate from play counts and Last.fm scrobble synchronization.
 *
 * Rules:
 * 1. CSV imports set authoritative historical last-played dates for tracks without modifying play counts.
 * 2. Last.fm imports (past and future) provide play counts and scrobbles, but cannot overwrite CSV last-played dates.
 * 3. Genuine playback inside MusicMinchas ([recordInAppPlay]) advances the last-played date when newer than the CSV date.
 */
class HistoricalDatesStore(context: Context) : HistoricalDatesRegistry {
    private val prefs: SharedPreferences = context.getSharedPreferences("historical_dates", Context.MODE_PRIVATE)
    private val inAppPrefs: SharedPreferences = context.getSharedPreferences("in_app_plays", Context.MODE_PRIVATE)

    private val _csvDates = MutableStateFlow<Map<String, Long>>(loadMap(prefs))
    override val csvDates: StateFlow<Map<String, Long>> = _csvDates.asStateFlow()

    private val _inAppDates = MutableStateFlow<Map<String, Long>>(loadMap(inAppPrefs))
    override val inAppDates: StateFlow<Map<String, Long>> = _inAppDates.asStateFlow()

    override fun recordInAppPlay(trackId: String, timestamp: Long) {
        inAppPrefs.edit { putLong(trackId, timestamp) }
        val updated = _inAppDates.value.toMutableMap()
        updated[trackId] = timestamp
        _inAppDates.value = updated
    }

    override fun setCsvDates(dates: Map<String, Long>) {
        prefs.edit {
            dates.forEach { (trackId, ts) ->
                putLong(trackId, ts)
            }
        }
        val current = _csvDates.value.toMutableMap()
        current.putAll(dates)
        _csvDates.value = current
    }

    override fun initializeInAppPlays(plays: Map<String, Long>) {
        if (inAppPrefs.all.isEmpty() && plays.isNotEmpty()) {
            inAppPrefs.edit {
                plays.forEach { (trackId, ts) ->
                    putLong(trackId, ts)
                }
            }
            val current = _inAppDates.value.toMutableMap()
            current.putAll(plays)
            _inAppDates.value = current
        }
    }

    override fun clear() {
        prefs.edit { clear() }
        _csvDates.value = emptyMap()
    }

    private fun loadMap(sp: SharedPreferences): Map<String, Long> {
        val map = HashMap<String, Long>()
        for ((key, value) in sp.all) {
            if (value is Long) {
                map[key] = value
            } else if (value is Number) {
                map[key] = value.toLong()
            }
        }
        return map
    }
}
