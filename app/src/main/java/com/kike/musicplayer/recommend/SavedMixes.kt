package com.kike.musicplayer.recommend

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Mixes as made at [madeAt] for the sources [scopes]: their titles and song ids, in order. */
data class SavedMixSet(val madeAt: Long, val scopes: List<Int>, val mixes: List<SavedMix>)

data class SavedMix(val id: String, val title: String, val subtitle: String, val trackIds: List<String>)

/**
 * Keeps the Home mixes as they were made, so playing, favouriting or downloading songs doesn't
 * reshuffle them; they're made again only when asked (pull to refresh), when they're old, or when
 * the sources change.
 */
class SavedMixes(context: Context, name: String = "mixes") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun load(): SavedMixSet? = runCatching {
        val json = JSONObject(prefs.getString(KEY, null) ?: return null)
        val scopes = json.getJSONArray("scopes").let { a -> List(a.length()) { a.getInt(it) } }
        val mixes = json.getJSONArray("mixes").let { a ->
            List(a.length()) { i ->
                val m = a.getJSONObject(i)
                val ids = m.getJSONArray("tracks")
                SavedMix(m.getString("id"), m.getString("title"), m.getString("subtitle"), List(ids.length()) { ids.getString(it) })
            }
        }
        SavedMixSet(json.getLong("madeAt"), scopes, mixes)
    }.getOrNull()

    fun save(set: SavedMixSet) {
        val json = JSONObject()
            .put("madeAt", set.madeAt)
            .put("scopes", JSONArray(set.scopes))
            .put("mixes", JSONArray(set.mixes.map { m ->
                JSONObject().put("id", m.id).put("title", m.title).put("subtitle", m.subtitle).put("tracks", JSONArray(m.trackIds))
            }))
        prefs.edit { putString(KEY, json.toString()) }
    }

    private companion object {
        const val KEY = "saved"
    }
}
