package com.kike.musicplayer.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.kike.musicplayer.container
import com.kike.musicplayer.data.db.Favourite
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.sonos.SonosGroup
import com.kike.musicplayer.playback.PlaybackService
import com.kike.musicplayer.playback.toMediaItem
import com.kike.musicplayer.playback.toTransientTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QueueEntry(val playlistIndex: Int, val item: MediaItem)

/**
 * Where playback is. Apart from [PlayerUiState] because it changes twice a second while playing:
 * only the progress line, seek bar and lyrics read it, so the rest of the screen doesn't redraw.
 */
data class PlayerProgress(val positionMs: Long = 0, val bufferedMs: Long = 0)

data class PlayerUiState(
    val connected: Boolean = false,
    val current: MediaItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val currentIndex: Int = C.INDEX_UNSET,
    /** Tracks that will play after the current one, in playback (shuffle-aware) order. */
    val upNext: List<QueueEntry> = emptyList(),
    val queueSize: Int = 0,
    /** e.g. "FLAC · 44.1 kHz · 2 ch" */
    val formatInfo: String? = null,
    val sampleRate: Int? = null,
    val error: String? = null,
    /** Speaker volume (0-100) while playing on a remote device such as Sonos, else null. */
    val remoteVolume: Int? = null,
)

/** UI-side MediaController, connected asynchronously to [PlaybackService]. */
class PlayerViewModel(private val app: Application) : AndroidViewModel(app) {
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
            // Media3 drops the device volume commands while the player is local and doesn't restore
            // them when the session switches to Sonos, which left the speaker volume slider dead.
            .setAllowDeviceVolumeCommandsForLocalPlayback(true)
            .buildAsync()
    private var controller: MediaController? = null

    private val cast = app.container.castManager
    val castGroups = cast.groups
    val castDiscovering = cast.discovering
    val castActive = cast.active
    val castMessage = cast.message

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress.asStateFlow()

    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    init {
        controllerFuture.addListener({
            val c = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh(c)
        }, MoreExecutors.directExecutor())
    }

    private fun refresh(p: Player) {
        val format = selectedAudioFormat(p)
        _state.value = PlayerUiState(
            connected = true,
            current = p.currentMediaItem,
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            // Before the player opens the song (a restored queue), the length from the library.
            durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: p.mediaMetadata.durationMs ?: 0,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            currentIndex = p.currentMediaItemIndex,
            upNext = upNext(p),
            queueSize = p.mediaItemCount,
            formatInfo = formatInfo(format),
            sampleRate = format?.sampleRate?.takeIf { it > 0 },
            error = p.playerError?.let(::playbackErrorText),
            remoteVolume = p.deviceVolume.takeIf { p.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE },
        )
        _progress.value = PlayerProgress(p.currentPosition, p.bufferedPosition)
        if (p.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun upNext(p: Player): List<QueueEntry> {
        val timeline = p.currentTimeline
        if (timeline.isEmpty || p.currentMediaItemIndex == C.INDEX_UNSET) return emptyList()
        val result = mutableListOf<QueueEntry>()
        var i = timeline.getNextWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && result.size < timeline.windowCount) {
            result += QueueEntry(i, p.getMediaItemAt(i))
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return result
    }

    private fun selectedAudioFormat(p: Player): Format? =
        p.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }

    private fun formatInfo(format: Format?): String? {
        if (format == null) return null
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_RAW -> "PCM"
            else -> format.sampleMimeType?.substringAfter('/')?.uppercase()
        }
        val parts = listOfNotNull(
            codec,
            format.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(it / 1000.0).replace(".0 ", " ") },
            format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
            format.channelCount.takeIf { it > 0 }?.let { if (it == 2) "stereo" else "$it ch" },
        )
        return parts.joinToString(" · ").ifEmpty { null }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                // Only while something on screen shows it (not with the app in the background).
                if (_progress.subscriptionCount.value > 0) controller?.let { c ->
                    _progress.value = PlayerProgress(c.currentPosition, c.bufferedPosition)
                }
                delay(500)
            }
        }
    }

    // ---- Commands ----

    fun playTracks(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex
        c.setMediaItems(tracks.map { it.toMediaItem() }, start, 0)
        c.prepare()
        c.play()
    }

    fun playNext(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTracks(listOf(track), 0)
        c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
    }

    fun addToQueue(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTracks(listOf(track), 0)
        c.addMediaItem(track.toMediaItem())
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
        if (c.isPlaying) c.pause() else c.play()
    }

    fun discoverSpeakers() = cast.discover()
    fun castTo(group: SonosGroup) = cast.cast(group)
    fun stopCasting() = cast.stopCast()

    // ---- Favourites ----

    private val favouriteDao = app.container.database.favourites()

    /** Track ids of the favourite songs. */
    val favourites: StateFlow<Set<String>> = favouriteDao.observeAll()
        .map { list -> list.mapTo(HashSet()) { it.trackId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleFavourite(trackId: String, track: Track? = null) {
        val add = trackId !in favourites.value
        viewModelScope.launch(Dispatchers.IO) {
            if (add) {
                val t = track ?: app.container.database.tracks().get(trackId) ?: transientTrackFor(trackId, trackId)
                if (t != null && (t.source == Track.SOURCE_NEBULA || t.scope == Track.SCOPE_STREAM)) {
                    app.container.database.tracks().upsert(t.copy(scope = Track.SCOPE_STREAM, metadataState = Track.META_DONE))
                }
                favouriteDao.insert(Favourite(trackId, System.currentTimeMillis()))
            } else {
                favouriteDao.delete(trackId)
            }
            getApplication<Application>().container.scrobbler.love(trackId, add)
        }
    }

    // ---- Lyrics ----

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Loading)
    /** Lyrics of the song last asked for with [loadLyrics]. */
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()
    private var lyricsFor: String? = null

        private fun transientTrackFor(mediaId: String, trackId: String): Track? {

        val c = controller ?: return null

        val item = c.currentMediaItem?.takeIf { it.mediaId == mediaId || PlaybackService.trackIdOf(it.mediaId) == trackId }

            ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }.firstOrNull { it.mediaId == mediaId || PlaybackService.trackIdOf(it.mediaId) == trackId }

            ?: return null

        return item.toTransientTrack()

    }



    /** Looks up the lyrics of the song with this media id (cached after the first time). */
    fun loadLyrics(mediaId: String) {
        if (lyricsFor == mediaId && _lyrics.value !is LyricsState.None) return
        lyricsFor = mediaId
        _lyrics.value = LyricsState.Loading
        val container = getApplication<Application>().container
        viewModelScope.launch {
            val trackId = PlaybackService.trackIdOf(mediaId)
            val track = container.database.tracks().get(trackId)
                ?: transientTrackFor(mediaId, trackId)
            val found = track?.let { container.lyrics.lyrics(it) }
            if (lyricsFor == mediaId) _lyrics.value = found?.let { LyricsState.Found(it) } ?: LyricsState.None
        }
    }

    fun setRemoteVolume(volume: Int) {
        controller?.setDeviceVolume(volume, 0)
        _state.update { it.copy(remoteVolume = volume) }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _progress.update { it.copy(positionMs = positionMs) }
    }

    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun skipTo(playlistIndex: Int) = controller?.let {
        it.seekToDefaultPosition(playlistIndex)
        it.play()
    }

    fun moveItem(from: Int, to: Int) = controller?.moveMediaItem(from, to)
    fun removeItem(playlistIndex: Int) = controller?.removeMediaItem(playlistIndex)

    fun clearUpNext() = controller?.let { c ->
        val current = c.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return@let
        c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }
}

/** What went wrong, in words: the HTTP status of a failed stream when there is one. */
internal fun playbackErrorText(e: androidx.media3.common.PlaybackException): String {
    val detail = e.cause?.message
    val status = detail?.let { Regex("""Response code: (\d+)""").find(it)?.groupValues?.get(1)?.toInt() }
    return when {
        // Cloudflare and other proxies: the address answers, the music server behind it doesn't.
        status != null && status in 520..530 -> "The server is unreachable right now (HTTP $status)"
        status == 401 || status == 403 -> "The server refused access (HTTP $status): check the sign-in"
        status == 404 -> "The file isn't on the server any more (HTTP 404): try syncing"
        status != null -> "The server answered HTTP $status"
        e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Can't connect: check the internet connection"
        else -> detail ?: e.errorCodeName
    }
}
