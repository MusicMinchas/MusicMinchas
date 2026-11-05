package com.kike.musicplayer.playback

import com.kike.musicplayer.util.AppLog

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
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.kike.musicplayer.container
import com.kike.musicplayer.recommend.AutoplaySuggester
import kotlinx.coroutines.withContext
import com.kike.musicplayer.data.SongSearch
import com.kike.musicplayer.data.db.PlayHistory
import com.kike.musicplayer.data.db.SessionState
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.getByIds
import com.kike.musicplayer.sonos.CastHost
import com.kike.musicplayer.sonos.QueueSnapshot
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

    /** Shuffle order of a restored queue (indices in play order), set once its items are loaded. */
    private var pendingShuffleOrder: List<Int>? = null

    /** Set while the queue plays on a Sonos group instead of the phone. */
    private var sonosPlayer: SonosPlayer? = null
    private var castWakeLock: PowerManager.WakeLock? = null
    private var castWifiLock: WifiManager.WifiLock? = null
    private var dspManager: PlaybackDspManager? = null

    private val dao by lazy { container.database.tracks() }
    private val browse by lazy { container.database.browse() }
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
        private var startedAt = 0L
        private var scrobbled = false
        private var reportedToServer = false

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

        /** Called every few seconds: counts the play once half has been listened to; scrobbles it when due. */
        fun tick() {
            if (playingSince == 0L) return
            if (!counted && listened() >= threshold()) {
                counted = true
                persist()
            }
            checkScrobble()
        }

        /** Scrobbles the play once listened to long enough (the user's rule; Last.fm's at least). */
        private fun checkScrobble() {
            val id = mediaId ?: return
            if (scrobbled) return
            val due = container.lastFm.scrobbleSettings.value.rule.thresholdMs(durationMs) ?: return
            if (listened() >= due) {
                scrobbled = true
                // The play's own start time: importing from Last.fm later recognises it as the same play.
                container.scrobbler.scrobble(trackIdOf(id), startedAt)
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
            scrobbled = false
            reportedToServer = false
            startedAt = System.currentTimeMillis()
            val entry = PlayHistory(trackId = trackIdOf(id), playedAt = startedAt)
            container.historicalDates.recordInAppPlay(trackIdOf(id), startedAt)
            val currentItem = player.currentMediaItem
            val trackId = trackIdOf(id)
            scope.launch(Dispatchers.IO) {
                if (trackId.startsWith("nebula:") || currentItem?.mediaId?.startsWith("nebula:") == true) {
                    val db = container.database.tracks()
                    if (db.get(trackId) == null) {
                        currentItem?.toTransientTrack()?.let { db.upsert(it) }
                    }
                }
                container.scrobbler.nowPlaying(trackId)
            }
            rowId = scope.async(Dispatchers.IO) { historyDao.insert(entry) }
        }

        private fun pause() {
            if (playingSince != 0L) {
                listenedMs += SystemClock.elapsedRealtime() - playingSince
                playingSince = 0L
                if (!counted && listenedMs >= threshold()) counted = true
                persist()
                checkScrobble()
            }
        }

        private fun listened() =
            listenedMs + if (playingSince != 0L) SystemClock.elapsedRealtime() - playingSince else 0

        /** Half the track; 30 s when the length is unknown. */
        private fun threshold() = if (durationMs > 0) durationMs / 2 else UNKNOWN_LENGTH_THRESHOLD_MS

        private fun persist() {
            // A counted play also goes to the Subsonic server, when the user chose so (once per play).
            if (counted && !reportedToServer) {
                reportedToServer = true
                mediaId?.let { container.serverPlays.played(trackIdOf(it), startedAt) }
            }
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

        // content:// goes to ContentDataSource; https (Drive) goes through OkHttp with the OAuth interceptor;
        // server songs ("subsonic:<id>") get a freshly signed stream URL as they're opened.
        val urls = container.subsonicUrls
        val nebulaUrls = container.nebulaUrls
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, OkHttpDataSource.Factory(container.driveHttp)),
        ) { spec ->
            val resolved = urls.resolve(spec.uri.toString())?.let { spec.withUri(android.net.Uri.parse(it)) }
                ?: nebulaUrls.resolve(spec.uri.toString())?.let { spec.withUri(android.net.Uri.parse(it)) }
                ?: spec
            if (resolved.uri != spec.uri) {
                AppLog.log(TAG, "ResolvingDataSource: ${spec.uri} -> ${resolved.uri}")
            }
            resolved
        }

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
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                    pendingShuffleOrder?.let { order ->
                        pendingShuffleOrder = null
                        if (player.mediaItemCount == order.size) {
                            this@PlaybackService.player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
                        }
                    }
                }
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
                Log.e(TAG, "Playback error on ${player.currentMediaItem?.mediaId}: ${error.errorCodeName} (${error.errorCode})", error)
            }
        })
        player.addListener(playTracker)

        val sessionActivity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            // Notification and lock-screen covers load through the same sources, server covers included.
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(dataSourceFactory).build()))
            .build()

        scope.launch { restoreQueue() }
        scope.launch {
            container.settings.infiniteQueue.collect { enabled ->
                if (enabled && ::session.isInitialized) {
                    checkAutoplay(session.player)
                }
            }
        }
        scope.launch {
            while (true) {
                delay(TRACKER_TICK_MS)
                playTracker.tick()
            }
        }
        container.castManager.host = this
        container.sleepTimer.attachPlayer { if (::session.isInitialized) session.player else null }
        dspManager = PlaybackDspManager(player, container.settings, scope)
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
        container.sleepTimer.detachPlayer()
        dspManager?.release()
        dspManager = null
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
        val queue = (source as? SonosPlayer)?.snapshot() ?: phoneQueue(source)
        val playing = source.playWhenReady
        val repeat = source.repeatMode
        val shuffle = source.shuffleModeEnabled

        // Switching from one Sonos group to another: silence the old one.
        sonosPlayer?.let { old ->
            val oldIp = (container.castManager.active.value ?: group).coordinatorIp
            old.release()
            scope.launch(Dispatchers.IO) { silence(oldIp) }
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
        sonos.pendingPlayOrder = queue.playOrder
        sonos.setMediaItems(queue.items, queue.index, queue.positionMs)
        sonosPlayer = sonos
        session.player = sonos
        acquireCastLocks()
        container.castManager.onCastStarted(group)
    }

    override fun stopCast() = endCast(null)

    /**
     * Stops a speaker the cast left. Sonos refuses Pause while it is still loading a track (e.g.
     * right after a skip), and it keeps playing what it has buffered, so retry until it's quiet.
     */
    private suspend fun silence(ip: String) {
        repeat(20) { attempt ->
            val state = runCatching { Sonos.position(ip).transportState }.getOrNull()
            if (state == "PAUSED_PLAYBACK" || state == "STOPPED") return
            runCatching { if (attempt < 10) Sonos.pause(ip) else Sonos.stop(ip) }
                .onFailure { Log.w(TAG, "Couldn't pause $ip ($state): $it") }
            delay(500)
        }
        Log.w(TAG, "$ip kept playing after the cast ended")
    }

    /** The phone player's queue, with its shuffle order when shuffled. */
    private fun phoneQueue(p: Player): QueueSnapshot {
        val playOrder = if (!p.shuffleModeEnabled) null else buildList<Int> {
            val timeline = p.currentTimeline
            var i = timeline.getFirstWindowIndex(true)
            while (i != C.INDEX_UNSET) {
                add(i)
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
        }
        return QueueSnapshot((0 until p.mediaItemCount).map(p::getMediaItemAt), p.currentMediaItemIndex, p.currentPosition, playOrder)
    }

    /** Hands the queue back to the phone, paused where Sonos was, and stops the speaker. */
    private fun endCast(message: String?) {
        val sonos = sonosPlayer ?: return
        Log.i(TAG, "Ending cast: ${message ?: "stopped by user"}")
        val ip = container.castManager.active.value?.coordinatorIp
        playTracker.finish()
        val (items, index, position, playOrder) = sonos.snapshot()
        val repeat = sonos.repeatMode
        val shuffle = sonos.shuffleModeEnabled
        sonosPlayer = null
        session.player = player
        sonos.release()
        ip?.let { scope.launch(Dispatchers.IO) { silence(it) } }
        if (items.isNotEmpty()) {
            player.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
            player.repeatMode = repeat
            player.shuffleModeEnabled = shuffle
            // Keep the shuffle order Sonos was playing.
            if (shuffle && playOrder != null) {
                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(playOrder.toIntArray(), System.nanoTime()))
            }
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
        // Not prepared: that opened the song (Drive / Navidrome: downloading tens of MB) on every
        // start, play or not. Play prepares it (the app's button, and Media3 for the notification,
        // headphones and the car).
        player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
    }

    private suspend fun loadSavedQueue(): MediaItemsWithStartPosition? {
        val state = queueDao.state() ?: return null
        val saved = queueDao.items()
        val ids = saved.map { it.trackId }
        val byId = dao.getByIds(ids).associateBy { it.id }
        val items = ids.mapNotNull { byId[it]?.toMediaItem() }
        if (items.isEmpty()) return null
        player.shuffleModeEnabled = state.shuffle
        player.repeatMode = state.repeatMode
        val index = state.currentIndex.coerceIn(0, items.lastIndex)
        // If tracks vanished from the library the index may point elsewhere; then start at 0.
        val position = if (ids.size == items.size) state.positionMs else 0L
        // The saved shuffle order, applied once these items reach the player (see the listener).
        pendingShuffleOrder = saved.takeIf { state.shuffle && ids.size == items.size && it.all { q -> q.shufflePosition != null } }
            ?.sortedBy { it.shufflePosition }?.map { it.position }
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

    private class SavedQueue(val trackIds: List<String>, val playOrder: List<Int>?, val state: SessionState)

    private var autoplayJob: Job? = null

    private fun checkAutoplay(p: Player) {
        if (!container.settings.infiniteQueue.value) return
        if (p.mediaItemCount == 0 || p.currentMediaItemIndex == C.INDEX_UNSET) return
        val remaining = p.mediaItemCount - (p.currentMediaItemIndex + 1)
        if (remaining > 1) return
        if (autoplayJob?.isActive == true) return

        autoplayJob = scope.launch(Dispatchers.IO) {
            try {
                val currentMediaItem = withContext(Dispatchers.Main) { p.currentMediaItem } ?: return@launch
                val currentTrackId = trackIdOf(currentMediaItem.mediaId)
                val currentTrack = dao.get(currentTrackId) ?: currentMediaItem.toTransientTrack()

                val count = withContext(Dispatchers.Main) { p.mediaItemCount }
                val queueIds = withContext(Dispatchers.Main) {
                    (0 until count).map { trackIdOf(p.getMediaItemAt(it).mediaId) }.toSet()
                }

                val scopes = listOf(Track.SCOPE_LOCAL, Track.SCOPE_DRIVE, Track.SCOPE_SERVER)

                val suggested = AutoplaySuggester.suggestTracks(
                    currentTrack = currentTrack,
                    recentTracks = emptyList(),
                    existingQueueIds = queueIds,
                    db = container.database,
                    scopes = scopes,
                    count = 8,
                )

                if (suggested.isNotEmpty()) {
                    val mediaItems = suggested.map { it.toMediaItem() }
                    withContext(Dispatchers.Main) {
                        if (::session.isInitialized && p === session.player) {
                            p.addMediaItems(mediaItems)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Autoplay suggestion failed", e)
            }
        }
    }

    private fun snapshot(): SavedQueue {
        val queue = phoneQueue(player)
        return SavedQueue(
            trackIds = queue.items.map { it.mediaId },
            playOrder = queue.playOrder,
            state = SessionState(
                currentIndex = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            ),
        )
    }

    private suspend fun save(snapshot: SavedQueue) {
        runCatching { queueDao.save(snapshot.trackIds, snapshot.playOrder, snapshot.state) }
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
            // Songs and albums come a page at a time from the database; an album's songs whole.
            val children: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    browsableItem(SONGS, "Songs", type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                    browsableItem(ALBUMS, "Albums", type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                )
                parentId == SONGS -> return@future pageResult(browse.songsFrom(page * pageSize, pageSize, CAR_SCOPES).map { it.toMediaItem() }, params)
                parentId == ALBUMS -> return@future pageResult(
                    browse.albumsFrom(page * pageSize, pageSize, CAR_SCOPES).map { a ->
                        browsableItem(ALBUM_PREFIX + a.key, a.title, a.artist, a.artworkUri, MediaMetadata.MEDIA_TYPE_ALBUM)
                    },
                    params,
                )
                parentId.startsWith(ALBUM_PREFIX) -> {
                    val key = parentId.removePrefix(ALBUM_PREFIX)
                    albumTracks(key).map { it.toMediaItem(ALBUM_TRACK_PREFIX + key + SEP + it.id) }
                }
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(children.size)
            val to = (from + pageSize).coerceAtMost(children.size)
            pageResult(children.subList(from, to), params)
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
                    // The library A–Z from the tapped song on, like the Songs tab.
                    dao.get(trackId)?.let { browse.songsFromTitle(it.indexTitle, it.id, CONTEXT_LIMIT, listOf(it.scope)) }
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

    private fun pageResult(items: List<MediaItem>, params: LibraryParams?) =
        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)

    private suspend fun albumTracks(albumKey: String): List<Track> =
        browse.tracksOfAlbums(listOf(albumKey), CAR_SCOPES).sortedWith(albumTrackOrder)

    private suspend fun search(query: String): List<Track> = SongSearch.find(browse, query, SEARCH_LIMIT, CAR_SCOPES)

    private object Futures {
        fun <T> immediate(value: T): ListenableFuture<T> = com.google.common.util.concurrent.Futures.immediateFuture(value)
    }

    companion object {
        private const val TAG = "PlaybackService"
        /** Songs queued when another app (e.g. Android Auto) plays one from the Songs list. */
        private const val CONTEXT_LIMIT = 1000
        private const val SEARCH_LIMIT = 200
        private const val TRACKER_TICK_MS = 5_000L
        private const val UNKNOWN_LENGTH_THRESHOLD_MS = 30_000L
        /** Android Auto and other media browsers list every source. */
        private val CAR_SCOPES = Track.SCOPES
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
