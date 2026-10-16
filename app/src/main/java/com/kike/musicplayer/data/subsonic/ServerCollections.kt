package com.kike.musicplayer.data.subsonic

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.Favourite
import com.kike.musicplayer.data.db.getByIds
import com.kike.musicplayer.data.lastfm.Scrobbler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the last sync of favourites and playlists went, for the server card. */
data class CollectionsSync(
    val running: Boolean = false,
    /** An error, or null. */
    val message: String? = null,
    val failed: Boolean = false,
    /** When it last synced. */
    val at: Long = 0,
    /** What the last sync that changed something did, and when. */
    val lastChanges: String? = null,
    val lastChangesAt: Long = 0,
)

/**
 * Keeps favourites and playlists in step with the Subsonic server, both ways.
 *
 * Favourites ↔ stars (server songs only): what was starred at the last sync is remembered, so a
 * change on either side is told apart from one on the other: starred or unstarred on Navidrome
 * comes to the app, favourited or unfavourited here goes to Navidrome.
 *
 * Playlists: each app playlist is linked to the user's own server playlist of the same songs. New
 * ones on either side are created on the other; one changed on one side (server: its "changed"
 * time; here: a fingerprint of name and songs) is copied to the other, the app's version winning
 * when both changed; one deleted on one side is deleted on the other (unless it was changed on the
 * other since). Device and Drive songs go to Navidrome as its copy of the same song (artist and
 * title); those it hasn't got are left out there. Songs coming from Navidrome keep being the
 * user's own copy where they were before.
 *
 * Runs at start, after a server sync, and a few seconds after favourites or playlists change here.
 */
class ServerCollections(
    context: Context,
    private val account: SubsonicAccount,
    private val db: AppDatabase,
    private val match: ServerMatch,
    private val scrobbler: Scrobbler,
    private val scope: CoroutineScope,
    prefsName: String = "subsonic-sync",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val client get() = account.client
    private val playlists = db.playlists()
    private val favourites = db.favourites()

    private val _status = MutableStateFlow(
        CollectionsSync(at = prefs.getLong(KEY_AT, 0), lastChanges = prefs.getString(KEY_CHANGES, null), lastChangesAt = prefs.getLong(KEY_CHANGES_AT, 0))
    )
    val status: StateFlow<CollectionsSync> = _status.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        // Changes made here go to Navidrome shortly after (the first emission is the start-up sync).
        scope.launch {
            db.invalidationTracker.createFlow("favourites", "playlists", "playlist_tracks").debounce(CHANGE_DELAY_MS).collect { sync() }
        }
    }

    /** Syncs favourites and playlists now (also after Navidrome's songs were synced). */
    fun syncNow() {
        scope.launch { sync() }
    }

    /** Signed out of the server: forget the sync state, so a later sign-in starts afresh. */
    fun forget() {
        prefs.edit { clear() }
        _status.value = CollectionsSync()
        scope.launch(Dispatchers.IO) {
            for (p in playlists.all()) if (p.serverId != null) playlists.setSynced(p.id, null, null, null)
        }
    }

    private suspend fun sync() = mutex.withLock {
        val login = account.login() ?: return@withLock
        _status.value = _status.value.copy(running = true)
        val result = runCatching {
            val stars = syncStars(login)
            val lists = syncPlaylists(login)
            listOfNotNull(stars, lists).joinToString(" · ")
        }
        val now = System.currentTimeMillis()
        prefs.edit { putLong(KEY_AT, now) }
        val before = _status.value
        _status.value = result.fold(
            onSuccess = { changes ->
                if (changes.isEmpty()) before.copy(running = false, message = null, failed = false, at = now)
                else {
                    prefs.edit { putString(KEY_CHANGES, changes); putLong(KEY_CHANGES_AT, now) }
                    CollectionsSync(at = now, lastChanges = changes, lastChangesAt = now)
                }
            },
            onFailure = {
                Log.w(TAG, "Sync of favourites and playlists failed", it)
                before.copy(running = false, message = "Couldn't sync favourites and playlists: ${it.message ?: it.javaClass.simpleName}", failed = true)
            },
        )
    }

    // ---- Favourites ↔ stars ----

    private suspend fun syncStars(login: SubsonicLogin): String? {
        val onServer = client.starredSongs(login).mapTo(HashSet()) { it.id }
        // What the app wants starred: its favourite server songs, and, when chosen, Navidrome's copy of
        // its device and Drive favourites. Which favourites stand behind each server song, to undo them.
        val behind = HashMap<String, MutableList<String>>()
        val withOthers = account.starOtherFavourites.value
        for (trackId in favourites.allTrackIds()) {
            val songId = if (trackId.startsWith(SubsonicUrls.PREFIX)) trackId.removePrefix(SubsonicUrls.PREFIX)
            else if (withOthers) db.tracks().get(trackId)?.let { match.serverSongIdCached(it) } else null
            if (songId != null) behind.getOrPut(songId) { ArrayList() } += trackId
        }
        val here = behind.keys.toSet()

        // The two sides as they were after the last sync (one set before both were kept apart).
        val lastThere = (prefs.getStringSet(KEY_STARRED_THERE, null) ?: prefs.getStringSet(KEY_STARRED, null))?.toSet()
        var lastHere = (prefs.getStringSet(KEY_STARRED_HERE, null) ?: prefs.getStringSet(KEY_STARRED, null))?.toSet()
        // Starring other sources' favourites switched off: those stars stay on Navidrome, nothing is unstarred.
        if (prefs.getBoolean(KEY_WITH_OTHERS, false) && !withOthers) lastHere = lastHere?.let { it intersect here }

        val (toFavourite, toUnfavourite, toStar, toUnstar) = planStars(onServer, here, lastThere, lastHere)
        val now = System.currentTimeMillis()
        val love = account.loveServerStars.value
        if (toFavourite.isNotEmpty()) favourites.insertAll(toFavourite.map { Favourite(SubsonicUrls.PREFIX + it, now) })
        if (love) toFavourite.forEach { scrobbler.love(SubsonicUrls.PREFIX + it, true) }
        val unfavourited = toUnfavourite.flatMap { behind[it].orEmpty() }
        for (trackId in unfavourited) {
            favourites.delete(trackId)
            if (love) scrobbler.love(trackId, false)
        }
        client.star(login, toStar.toList())
        client.unstar(login, toUnstar.toList())
        val starred = (onServer + toStar - toUnstar).toSet()
        prefs.edit {
            putStringSet(KEY_STARRED_THERE, starred)
            putStringSet(KEY_STARRED_HERE, here - toUnfavourite + toFavourite)
            putBoolean(KEY_WITH_OTHERS, withOthers)
            remove(KEY_STARRED)
        }

        val parts = listOfNotNull(
            plural(toFavourite.size, "favourite", "from Navidrome"), plural(unfavourited.size, "favourite", "removed (unstarred on Navidrome)"),
            plural(toStar.size, "song", "starred on Navidrome"), plural(toUnstar.size, "song", "unstarred on Navidrome"),
        )
        return parts.joinToString(", ").ifEmpty { null }
    }

    // ---- Playlists ----

    private suspend fun syncPlaylists(login: SubsonicLogin): String? {
        // Only the user's own playlists: others' public ones can't be changed from here.
        val serverLists = client.playlists(login).filter { it.owner == null || it.owner.equals(login.username, ignoreCase = true) }
        val onServer = serverLists.associateBy { it.id }.toMutableMap()
        val linkedBefore = prefs.getStringSet(KEY_LINKED, emptySet())!!.toSet()
        var sent = 0
        var received = 0
        var removed = 0
        val pushed = HashSet<Long>()

        // Deleted here since the last sync: delete on Navidrome too.
        val linkedNow = playlists.all().mapNotNullTo(HashSet()) { it.serverId }
        for (serverId in linkedBefore - linkedNow) {
            if (onServer.remove(serverId) != null) {
                runCatching { client.deletePlaylist(login, serverId) }.onSuccess { removed++ }
                    .onFailure { Log.w(TAG, "Couldn't delete server playlist $serverId", it) }
            }
        }

        for (p in playlists.all()) {
            val ids = playlists.trackIds(p.id)
            val hash = fingerprint(p.name, ids)
            val changedHere = p.syncedHash == null || p.syncedHash != hash
            val there = p.serverId?.let { onServer[it] }
            when {
                // New here, or deleted on Navidrome while edited here: create it there.
                p.serverId == null || (there == null && changedHere) -> {
                    val created = client.savePlaylist(login, null, p.name, serverSongIds(ids))
                    playlists.setSynced(p.id, created?.id, created?.changed, hash)
                    pushed += p.id
                    sent++
                }
                // Deleted on Navidrome and not edited here since: delete here too.
                there == null -> {
                    playlists.delete(p.id)
                    removed++
                }
                changedHere -> {
                    runCatching {
                        client.savePlaylist(login, there.id, null, serverSongIds(ids))
                        if (there.name != p.name) client.renamePlaylist(login, there.id, p.name)
                    }.onFailure { Log.w(TAG, "Couldn't update server playlist ${there.name} (read-only?)", it) }
                    playlists.setSynced(p.id, there.id, there.changed, hash)
                    pushed += p.id
                    sent++
                }
                there.changed != p.serverChanged -> {
                    val full = client.playlist(login, there.id)
                    val newIds = fromServer(full.entry.orEmpty().map { it.id }, ids)
                    val name = full.name ?: p.name
                    playlists.setTracks(p.id, newIds)
                    if (name != p.name) playlists.rename(p.id, name)
                    playlists.setSynced(p.id, there.id, full.changed, fingerprint(name, newIds))
                    received++
                }
            }
            p.serverId?.let { onServer.remove(it) }
        }

        // New on Navidrome: create here.
        for (sp in onServer.values) {
            val full = client.playlist(login, sp.id)
            val name = full.name ?: "Playlist"
            val ids = full.entry.orEmpty().map { SubsonicUrls.PREFIX + it.id }
            val id = playlists.create(name, ids)
            playlists.setSynced(id, sp.id, full.changed, fingerprint(name, ids))
            received++
        }

        // Sending changes Navidrome's "changed" time: take the new one, or the edit would look like Navidrome's next time.
        if (pushed.isNotEmpty()) {
            val changed = client.playlists(login).associate { it.id to it.changed }
            for (id in pushed) {
                val p = playlists.get(id) ?: continue
                playlists.setSynced(p.id, p.serverId, p.serverId?.let { changed[it] }, p.syncedHash)
            }
        }
        prefs.edit { putStringSet(KEY_LINKED, playlists.all().mapNotNullTo(HashSet()) { it.serverId }) }

        return listOfNotNull(
            plural(sent, "playlist", "sent to Navidrome"), plural(received, "playlist", "from Navidrome"), plural(removed, "playlist", "deleted"),
        ).joinToString(", ").ifEmpty { null }
    }

    /** Navidrome's song ids for these library songs, in order; songs the server hasn't got are left out. */
    private suspend fun serverSongIds(trackIds: List<String>): List<String> {
        val byId = db.tracks().getByIds(trackIds.toSet()).associateBy { it.id }
        return trackIds.mapNotNull { id -> byId[id]?.let { match.serverSongId(it) } }
    }

    /**
     * Navidrome's playlist as library songs: where a song was there before as the user's own copy
     * (that copy's server song is the same), it stays that copy; the rest are Navidrome's songs.
     */
    private suspend fun fromServer(serverSongIds: List<String>, before: List<String>): List<String> {
        val tracks = db.tracks().getByIds(before.toSet()).associateBy { it.id }
        val ownCopies = HashMap<String, ArrayDeque<String>>()
        for (id in before) {
            val t = tracks[id] ?: continue
            match.serverSongId(t)?.let { ownCopies.getOrPut(it) { ArrayDeque() }.addLast(id) }
        }
        return serverSongIds.map { s -> ownCopies[s]?.removeFirstOrNull() ?: (SubsonicUrls.PREFIX + s) }
    }

    private fun fingerprint(name: String, trackIds: List<String>) =
        SubsonicClient.md5(name + "\u0001" + trackIds.joinToString("\u0002"))

    private fun plural(n: Int, noun: String, what: String) = if (n == 0) null else "$n $noun${if (n == 1) "" else "s"} $what"

    private companion object {
        const val TAG = "ServerCollections"
        const val KEY_STARRED = "starred"
        const val KEY_STARRED_THERE = "starred_on_server"
        const val KEY_STARRED_HERE = "starred_from_app"
        const val KEY_WITH_OTHERS = "star_other_favourites"
        const val KEY_LINKED = "linked_playlists"
        const val KEY_AT = "synced_at"
        const val KEY_CHANGES = "last_changes"
        const val KEY_CHANGES_AT = "last_changes_at"
        const val CHANGE_DELAY_MS = 3_000L
    }
}

/** What a star sync does: server songs to favourite and unfavourite here, to star and unstar there. */
internal data class StarPlan(val toFavourite: Set<String>, val toUnfavourite: Set<String>, val toStar: Set<String>, val toUnstar: Set<String>)

/**
 * Compares Navidrome's starred songs ([there]) and the app's ([here]) with both as they were after
 * the last sync, so a change on one side isn't mistaken for one on the other. With no last sync,
 * both keep everything and nothing is removed.
 */
internal fun planStars(there: Set<String>, here: Set<String>, lastThere: Set<String>?, lastHere: Set<String>?): StarPlan {
    if (lastThere == null || lastHere == null) return StarPlan(there - here, emptySet(), here - there, emptySet())
    val addedHere = here - lastHere
    val removedHere = lastHere - here
    val addedThere = there - lastThere
    val removedThere = lastThere - there
    return StarPlan(
        toFavourite = addedThere - here,
        toUnfavourite = (removedThere intersect here) - addedHere,
        toStar = addedHere - there,
        toUnstar = (removedHere intersect there) - addedThere,
    )
}
