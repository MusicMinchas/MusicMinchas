package com.kike.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.kike.musicplayer.container
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.SessionState
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.getByIds
import com.kike.musicplayer.sonos.CastHost
import com.kike.musicplayer.sonos.Sonos
import com.kike.musicplayer.sonos.SonosGroup
import com.kike.musicplayer.sonos.SonosPlayer
import com.kike.musicplayer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Hosts the ExoPlayer and the MediaLibrarySession. Being a MediaLibraryService (a superset of
 * MediaSessionService) gives us background playback, the media notification, lock screen and
 * hardware button handling, and a browse tree for Android Auto.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), CastHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private var saveJob: Job? = null

    /** Set while the queue plays on a Sonos group instead of the phone. */
    private var sonosPlayer: SonosPlayer? = null
    private var castWakeLock: PowerManager.WakeLock? = null
    private var castWifiLock: WifiManager.WifiLock? = null

    private val dao by lazy { container.database.tracks() }
    private val queueDao by lazy { container.database.queue() }
    private val historyDao by lazy { container.database.history() }

    /**
     * Records plays from whichever player the session uses (phone or Sonos). A play is stored when a
     * track starts; the time actually listened accumulates while it plays (seeking doesn't count),
     * and the play is "counted" for Most Played and Replay once that reaches half the track.
     */
    private val playTracker = object : Player.Listener {
        private var mediaId: String? = null
        private var rowId: Deferred<Long>? = null
        private var durationMs = 0L
        private var listenedMs = 0L
        private var playingSince = 0L
        private var counted = false

        override fun onEvents(player: Player, events: Player.Events) {
            // The paused phone player keeps reporting while Sonos plays; only follow the active one.
            if (!::session.isInitialized || player !== session.player) return
            if (!events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) return
            val current = player.currentMediaItem?.mediaId
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || current != mediaId) finish()
            if (player.isPlaying && current != null) {
                if (mediaId == null) begin(player, current)
                if (playingSince == 0L) playingSince = SystemClock.elapsedRealtime()
            } else {
                pause()
            }
        }

        /** Called every few seconds: counts the play as soon as half has been listened to. */
        fun tick() {
            if (playingSince == 0L || counted) return
            if (listened() >= threshold()) {
                counted = true
                persist()
            }
        }

        /** Ends the current play, e.g. when playback moves to another player. */
        fun finish() {
            pause()
            if (mediaId != null) persist()
            mediaId = null
            rowId = null
        }

        private fun begin(player: Player, id: String) {
            mediaId = id
            durationMs = player.duration.takeIf { it > 0 } ?: player.currentMediaItem?.mediaMetadata?.durationMs ?: 0
            listenedMs = 0
            counted = false
            val entry = PlayHistory(trackId = trackIdOf(id), playedAt = System.currentTimeMillis())
            rowId = scope.async(Dispatchers.IO) { historyDao.insert(entry) }
        }

        private fun pause() {
            if (playingSince != 0L) {
                listenedMs += SystemClock.elapsedRealtime() - playingSince
                playingSince = 0L
                if (!counted && listenedMs >= threshold()) counted = true
                persist()
            }
        }

        private fun listened() =
            listenedMs + if (playingSince != 0L) SystemClock.elapsedRealtime() - playingSince else 0

        /** Half the track; 30 s when the length is unknown. */
        private fun threshold() = if (durationMs > 0) durationMs / 2 else UNKNOWN_LENGTH_THRESHOLD_MS

        private fun persist() {
            val row = rowId ?: return
            val ms = listened()
            val isCounted = counted
            scope.launch(Dispatchers.IO) {
                runCatching { historyDao.updateListening(row.await(), ms, isCounted) }
                    .onFailure { Log.w(TAG, "Recording play failed", it) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // content:// goes to ContentDataSource; https (Drive) goes through OkHttp with the OAuth interceptor.
        val dataSourceFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(container.driveHttp))

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                // Float output keeps 24-bit sources at full resolution through the mixer.
                AppRenderersFactory(this).setEnableAudioFloatOutput(true)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(
                // Larger buffers than the default smooth over Wi-Fi hiccups when streaming lossless from Drive.
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 1_500, 3_000)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) scheduleSave()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback error on ${player.currentMediaItem?.mediaId}", error)
            }
        })
        player.addListener(playTracker)

        val sessionActivity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()

        scope.launch { restoreQueue() }
        scope.launch {
            while (true) {
                delay(TRACKER_TICK_MS)
                playTracker.tick()
            }
        }
        container.castManager.host = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused stops the service; while playing (here or on Sonos), music continues.
        val active = session.player
        if (!active.playWhenReady || active.mediaItemCount == 0) {
            saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        playTracker.finish()
        stopCast()
        if (container.castManager.host === this) container.castManager.host = null
        saveNow()
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ---- Casting to Sonos ----

    /** Moves the current queue, position and play state from whatever plays now to [group]. */
    override fun startCast(group: SonosGroup) {
        val source = session.player
        if (source.mediaItemCount == 0) {
            container.castManager.onCastEnded("Play something first, then cast it")
            return
        }
        playTracker.finish()
        val items = (0 until source.mediaItemCount).map { source.getMediaItemAt(it) }
        val index = source.currentMediaItemIndex
        val position = source.currentPosition
        val playing = source.playWhenReady
        val repeat = source.repeatMode
        val shuffle = source.shuffleModeEnabled

        // Switching from one Sonos group to another: silence the old one.
        sonosPlayer?.let { old ->
            val oldIp = (container.castManager.active.value ?: group).coordinatorIp
            old.release()
            scope.launch(Dispatchers.IO) { runCatching { Sonos.pause(oldIp) } }
        }
        player.pause()

        val server = container.trackServer
        server.start()
        val sonos = SonosPlayer(group, server, dao) { message -> endCast(message) }
        sonos.addListener(playTracker)
        // Mode and play state first: with an empty queue they don't reach the speaker, and the
        // queue load then starts playback (or not) accordingly.
        sonos.repeatMode = repeat
        sonos.shuffleModeEnabled = shuffle
        sonos.playWhenReady = playing
        sonos.setMediaItems(items, index, position)
        sonosPlayer = sonos
        session.player = sonos
        acquireCastLocks()
        container.castManager.onCastStarted(group)
    }

    override fun stopCast() = endCast(null)

    /** Hands the queue back to the phone, paused where Sonos was, and stops the speaker. */
    private fun endCast(message: String?) {
        val sonos = sonosPlayer ?: return
        Log.i(TAG, "Ending cast: ${message ?: "stopped by user"}")
        val ip = container.castManager.active.value?.coordinatorIp
        playTracker.finish()
        val (items, index, position) = sonos.snapshot()
        val repeat = sonos.repeatMode
        val shuffle = sonos.shuffleModeEnabled
        sonosPlayer = null
        session.player = player
        sonos.release()
        ip?.let { scope.launch(Dispatchers.IO) { runCatching { Sonos.pause(it) } } }
        if (items.isNotEmpty()) {
            player.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
            player.repeatMode = repeat
            player.shuffleModeEnabled = shuffle
            player.prepare()
        }
        container.trackServer.stop()
        releaseCastLocks()
        container.castManager.onCastEnded(message)
    }

    /**
     * Sonos fetches every track from the phone, so keep the CPU and Wi-Fi awake while casting
     * (with the screen off, Android would otherwise let the server stall).
     */
    private fun acquireCastLocks() {
        if (castWakeLock == null) {
            castWakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicPlayer:cast").apply { acquire() }
        }
        if (castWifiLock == null) {
            castWifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MusicPlayer:cast").apply { acquire() }
        }
    }

    private fun releaseCastLocks() {
        castWakeLock?.takeIf { it.isHeld }?.release()
        castWakeLock = null
        castWifiLock?.takeIf { it.isHeld }?.release()
        castWifiLock = null
    }

    // ---- Queue persistence ----

    private suspend fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val saved = loadSavedQueue() ?: return
        if (player.mediaItemCount > 0) return // a controller set a queue in the meantime
        player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
        player.prepare()
    }

    private suspend fun loadSavedQueue(): MediaItemsWithStartPosition? {
        val state = queueDao.state() ?: return null
        val ids = queueDao.items().map { it.trackId }
        val byId = dao.getByIds(ids).associateBy { it.id }
        val items = ids.mapNotNull { byId[it]?.toMediaItem() }
        if (items.isEmpty()) return null
        player.shuffleModeEnabled = state.shuffle
        player.repeatMode = state.repeatMode
        val index = state.currentIndex.coerceIn(0, items.lastIndex)
        // If tracks vanished from the library the index may point elsewhere; then start at 0.
        val position = if (ids.size == items.size) state.positionMs else 0L
        return MediaItemsWithStartPosition(items, if (ids.size == items.size) index else 0, position)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            save(snapshot())
        }
    }

    /** Synchronous save for onDestroy / onTaskRemoved, where coroutines may not get to run. */
    private fun saveNow() {
        saveJob?.cancel()
        val snapshot = snapshot()
        runBlocking(Dispatchers.IO) { save(snapshot) }
    }

    private fun snapshot(): Pair<List<String>, SessionState> {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        return ids to SessionState(
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    private suspend fun save(snapshot: Pair<List<String>, SessionState>) {
        runCatching { queueDao.save(snapshot.first, snapshot.second) }
            .onFailure { Log.w(TAG, "Saving queue failed", it) }
    }

    // ---- Browse tree (Android Auto) and controller requests ----

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediate(
            LibraryResult.ofItem(browsableItem(ROOT, "Library", type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params)
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    browsableItem(SONGS, "Songs", type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                    browsableItem(ALBUMS, "Albums", type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                )
                parentId == SONGS -> dao.getAll().map { it.toMediaItem() }
                parentId == ALBUMS -> dao.getAll().groupBy { it.albumKey }.values
                    .map { tracks -> tracks.first() }
                    .sortedBy { (it.album ?: "").lowercase() }
                    .map { t ->
                        browsableItem(
                            ALBUM_PREFIX + t.albumKey, t.album ?: "Unknown album", t.albumArtist ?: t.artist,
                            t.artworkUri, MediaMetadata.MEDIA_TYPE_ALBUM,
                        )
                    }
                parentId.startsWith(ALBUM_PREFIX) -> {
                    val key = parentId.removePrefix(ALBUM_PREFIX)
                    albumTracks(key).map { it.toMediaItem(ALBUM_TRACK_PREFIX + key + SEP + it.id) }
                }
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(children.size)
            val to = (from + pageSize).coerceAtMost(children.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(children.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val track = dao.get(trackIdOf(mediaId))
            if (track != null) LibraryResult.ofItem(track.toMediaItem(), null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val results = search(query).drop(page * pageSize).take(pageSize).map { it.toMediaItem() }
            LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
        }

        /** Controllers send items without URIs (they may live in another process); resolve them from Room. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future { resolve(mediaItems) }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            // Android Auto plays a single tapped song; expand it to its album, or to all songs.
            val single = mediaItems.singleOrNull()
            if (single != null) {
                val id = single.mediaId
                val trackId = trackIdOf(id)
                val context = if (id.startsWith(ALBUM_TRACK_PREFIX)) {
                    albumTracks(id.removePrefix(ALBUM_TRACK_PREFIX).substringBeforeLast(SEP))
                } else if (controller.packageName != packageName) {
                    dao.getAll()
                } else null
                val index = context?.indexOfFirst { it.id == trackId } ?: -1
                if (context != null && index >= 0) {
                    return@future MediaItemsWithStartPosition(context.map { it.toMediaItem() }, index, startPositionMs)
                }
            }
            MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
        }

        /** Bluetooth "play" or the system media controls after the app was killed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            loadSavedQueue() ?: MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
    }

    private suspend fun resolve(items: List<MediaItem>): List<MediaItem> {
        val byId = dao.getByIds(items.map { trackIdOf(it.mediaId) }).associateBy { it.id }
        return items.mapNotNull { item -> byId[trackIdOf(item.mediaId)]?.toMediaItem() ?: item.takeIf { it.localConfiguration != null } }
    }

    private suspend fun albumTracks(albumKey: String): List<Track> =
        dao.getAll().filter { it.albumKey == albumKey }.sortedWith(albumTrackOrder)

    private suspend fun search(query: String): List<Track> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return dao.getAll().filter { t ->
            listOfNotNull(t.title, t.artist, t.album).any { it.lowercase().contains(q) }
        }
    }

    private object Futures {
        fun <T> immediate(value: T): ListenableFuture<T> = com.google.common.util.concurrent.Futures.immediateFuture(value)
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val TRACKER_TICK_MS = 5_000L
        private const val UNKNOWN_LENGTH_THRESHOLD_MS = 30_000L
        private const val ROOT = "root"
        private const val SONGS = "songs"
        private const val ALBUMS = "albums"
        private const val SEP = "\u001E"
        private const val ALBUM_PREFIX = "album$SEP"
        private const val ALBUM_TRACK_PREFIX = "albumtrack$SEP"

        /** Browse items inside an album carry the album in their id; the track id is the last part. */
        fun trackIdOf(mediaId: String): String =
            if (mediaId.startsWith(ALBUM_TRACK_PREFIX)) mediaId.substringAfterLast(SEP) else mediaId
    }
}
