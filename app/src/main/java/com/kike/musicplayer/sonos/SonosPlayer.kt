package com.kike.musicplayer.sonos

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.getByIds
import com.kike.musicplayer.data.nebula.NebulaUrls
import com.kike.musicplayer.playback.PlaybackService
import com.kike.musicplayer.playback.toTransientTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException

/**
 * A Media3 [Player] whose playback happens on a Sonos group. The app's queue is mirrored into the
 * Sonos queue, Sonos fetches each track from [TrackServer] and plays it itself, and this player
 * polls the speaker to reflect position, track changes and volume back to the session (UI,
 * notification, lock screen, hardware volume keys).
 *
 * Shuffle is done here, not by Sonos: Sonos' own shuffle reorders its queue and jumps to a random
 * track, and its track numbers then follow the shuffled order. Instead the Sonos queue always
 * holds the play order, shuffled or not, and Sonos only gets the repeat mode.
 */
@OptIn(UnstableApi::class)
class SonosPlayer(
    private val group: SonosGroup,
    private val server: TrackServer,
    private val dao: TrackDao,
    /** Called on the main thread when the cast can't continue (speaker gone, or switched to other music). */
    private val onLost: (String) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** One queue entry; [id] is also in the URL Sonos plays, which is how its position is mapped back. */
    private class Entry(val id: Long, val item: MediaItem)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Sonos commands run one at a time, in order. */
    private val sonosThread = Dispatchers.IO.limitedParallelism(1)
    private val ip get() = group.coordinatorIp

    // Player state; only touched on the main thread.
    private var nextId = 1L
    private var entries = listOf<Entry>()
    private var currentIndex = 0
    private var positionMs = 0L
    private var positionAt = SystemClock.elapsedRealtime()
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var volume = 0

    /** Paused during the queue load: Sonos' position needs restoring once loading is done. */
    private var pendingResume = false

    /** Polled state is ignored until then, so it doesn't undo a command still in flight. */
    private var holdUntil = 0L
    private var failedPolls = 0
    private var foreignPolls = 0

    /**
     * While shuffle is on: the queue in its unshuffled order. [entries] is then the shuffled play
     * order, which is also the Sonos queue's order.
     */
    private var unshuffled: List<Entry>? = null

    /**
     * Play order for the next [setMediaItems] when shuffle is on, as indices into its items, so a
     * cast keeps the shuffle order it had on the phone. Used once.
     */
    var pendingPlayOrder: List<Int>? = null

    /** Loads (or rebuilds) the Sonos queue; queue edits wait for it. */
    private var loadJob: Job? = null

    /** Entry IDs in Sonos queue order; only touched on [sonosThread]. */
    private val sonosOrder = mutableListOf<Long>()

    init {
        scope.launch { poll() }
    }

    override fun getState(): State {
        val playing = playWhenReady && playbackState == Player.STATE_READY
        val position = if (playing) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (entries.isEmpty()) Player.STATE_IDLE else playbackState)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffle)
            .setDeviceInfo(DEVICE_INFO)
            .setDeviceVolume(volume)
            .setPlaylist(entries.map { e ->
                val durationMs = e.item.mediaMetadata.durationMs
                MediaItemData.Builder(e.id)
                    .setMediaItem(e.item)
                    .setDurationUs(if (durationMs != null && durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            })
        if (entries.isNotEmpty()) {
            builder.setCurrentMediaItemIndex(currentIndex.coerceIn(0, entries.lastIndex))
                .setContentPositionMs(
                    if (playing) PositionSupplier.getExtrapolating(position, 1f) else PositionSupplier.getConstant(position)
                )
        }
        return builder.build()
    }

    // ---- Commands ----

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        loadJob?.cancel()
        val all = mediaItems.map { Entry(nextId++, it) }
        val first = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (all.size - 1).coerceAtLeast(0))
        val order = pendingPlayOrder?.takeIf { shuffle && it.sorted() == all.indices.toList() }
            ?: if (shuffle && all.isNotEmpty()) listOf(first) + all.indices.filter { it != first }.shuffled() else null
        pendingPlayOrder = null
        if (order != null) {
            unshuffled = all
            entries = order.map { all[it] }
            currentIndex = order.indexOf(first)
        } else {
            unshuffled = null
            entries = all
            currentIndex = first
        }
        positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        positionAt = SystemClock.elapsedRealtime()
        playbackState = if (entries.isEmpty()) Player.STATE_IDLE else Player.STATE_BUFFERING
        hold(10_000)
        val started = SettableFuture.create<Unit>()
        val snapshot = entries
        val start = currentIndex
        val startPosition = positionMs
        loadJob = scope.launch {
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                loadQueue(snapshot, start, startPosition) {
                    com.kike.musicplayer.Bench.log("cast: playing after ${android.os.SystemClock.elapsedRealtime() - t0} ms (${snapshot.size} in queue)")
                    started.set(Unit)
                }
            } catch (e: CancellationException) {
                started.set(Unit)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the Sonos queue failed", e)
                started.set(Unit)
                lose("Couldn't start playback on ${group.name}: ${e.message}")
            }
        }
        return started
    }

    /** Fills the Sonos queue: tracks from [start] on first (and starts playing), then the ones before it. */
    private suspend fun loadQueue(snapshot: List<Entry>, start: Int, startPositionMs: Long, onStarted: () -> Unit) {
        val base = server.baseUrl() ?: throw IOException("the phone has no Wi-Fi address")
        // The first two songs' first pieces, before Sonos asks for them one by one.
        server.prefetch(snapshot.drop(start).take(2).map { trackIdOf(it.item) })
        val tracks = withContext(Dispatchers.IO) { dao.getByIds(snapshot.map { trackIdOf(it.item) }).associateBy { it.id } }
        fun item(e: Entry) = trackFor(e.item, tracks)?.let { queueItem(base, e.id, it) }

        withContext(sonosThread) {
            sonosOrder.clear()
            Sonos.clearQueue(ip)
            if (snapshot.isEmpty()) return@withContext
            val after = snapshot.subList(start, snapshot.size)
            val before = snapshot.subList(0, start)
            var started = false
            for (batch in after.chunked(Sonos.MAX_URIS_PER_ADD)) {
                val items = batch.mapNotNull { e -> item(e)?.let { e.id to it } }
                if (items.isNotEmpty()) {
                    // Once playing, one bad batch shouldn't end the cast: skip it and go on.
                    try {
                        Sonos.addToQueue(ip, items.map { it.second })
                        sonosOrder += items.map { it.first }
                    } catch (e: IOException) {
                        if (!started) throw e
                        Log.w(TAG, "Skipped ${items.size} tracks that Sonos refused", e)
                    }
                }
                if (!started && sonosOrder.isNotEmpty()) {
                    started = true
                    Sonos.useQueue(ip, group.uuid)
                    // Before picking the track: leaving a Sonos shuffle mode reorders the queue and jumps.
                    applyPlayMode()
                    Sonos.seekTrack(ip, 1)
                    if (startPositionMs > 1000) seekTimeVerified(startPositionMs)
                    val play = withContext(Dispatchers.Main) {
                        playbackState = Player.STATE_READY
                        // Adding tracks to the queue resets a stopped track to 0:00, so while
                        // paused the position is restored once loading is done (or when play is
                        // pressed); until then the poll mustn't copy Sonos' 0:00 into the app.
                        pendingResume = !playWhenReady
                        hold(if (playWhenReady) 2_000 else HOLD_WHILE_LOADING_MS)
                        invalidateState()
                        playWhenReady
                    }
                    if (play) Sonos.play(ip)
                    onStarted()
                }
                yield() // let transport commands (pause, volume...) through between batches
            }
            // Earlier tracks go in front of the current one; Sonos keeps playing through the renumbering.
            var position = 1
            for (batch in before.chunked(Sonos.MAX_URIS_PER_ADD)) {
                val items = batch.mapNotNull { e -> item(e)?.let { e.id to it } }
                if (items.isNotEmpty()) {
                    try {
                        Sonos.addToQueue(ip, items.map { it.second }, position)
                        sonosOrder.addAll(position - 1, items.map { it.first })
                        position += items.size
                    } catch (e: IOException) {
                        Log.w(TAG, "Skipped ${items.size} tracks that Sonos refused", e)
                    }
                }
                yield()
            }
            if (!started) {
                if (sonosOrder.isNotEmpty()) {
                    started = true
                    Sonos.useQueue(ip, group.uuid)
                    applyPlayMode()
                    Sonos.seekTrack(ip, 1)
                }
                onStarted()
            }
            val (entryId, resumeAt, restore) = withContext(Dispatchers.Main) {
                Triple(entries.getOrNull(currentIndex)?.id, currentPosition, pendingResume && !playWhenReady)
            }
            if (restore) {
                entryId?.let { id -> sonosOrder.indexOf(id).takeIf { it >= 0 } }?.let { Sonos.seekTrack(ip, it + 1) }
                if (resumeAt > 1000) seekTimeVerified(resumeAt)
            }
            withContext(Dispatchers.Main) {
                pendingResume = false
                hold()
            }
        }
        // Entries whose track is no longer in the library never reached Sonos; drop them here too.
        if (entries === snapshot && sonosOrder.size != snapshot.size) {
            val loaded = withContext(sonosThread) { sonosOrder.toSet() }
            val current = entries.getOrNull(currentIndex)
            entries = entries.filter { it.id in loaded }
            unshuffled = unshuffled?.filter { it.id in loaded }
            currentIndex = entries.indexOf(current).coerceAtLeast(0)
            invalidateState()
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        hold()
        if (playWhenReady && pendingResume) {
            // Pressed play while the queue is still loading: Sonos' position was reset, set it first.
            pendingResume = false
            val entryId = entries.getOrNull(currentIndex)?.id
            val position = positionMs
            return command {
                entryId?.let { sonosPosition(it) }?.let { Sonos.seekTrack(ip, it) }
                if (position > 1000) seekTimeVerified(position)
                Sonos.play(ip)
            }
        }
        // While the queue is still being set up, the loader starts playback itself.
        if (entries.isEmpty() || playbackState != Player.STATE_READY) return Futures.immediateVoidFuture()
        return command { if (playWhenReady) Sonos.play(ip) else Sonos.pause(ip) }
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        playWhenReady = false
        hold()
        return command { Sonos.pause(ip) }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (entries.isEmpty()) return Futures.immediateVoidFuture()
        val target = if (mediaItemIndex == C.INDEX_UNSET) currentIndex else mediaItemIndex.coerceIn(0, entries.lastIndex)
        val changeTrack = target != currentIndex
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs
        currentIndex = target
        this.positionMs = position
        positionAt = SystemClock.elapsedRealtime()
        hold()
        val entryId = entries[target].id
        return command {
            if (changeTrack) {
                val track = sonosPosition(entryId) ?: return@command
                Sonos.seekTrack(ip, track)
            }
            if (!changeTrack || position > 0) Sonos.seekTime(ip, position)
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        hold()
        return command { applyPlayMode() }
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffle = shuffleModeEnabled
        val current = entries.getOrNull(currentIndex)
        if (current == null) {
            unshuffled = null
            return Futures.immediateVoidFuture()
        }
        val previous = unshuffled
        when {
            // The playing track stays; the rest follows it in random order.
            shuffleModeEnabled && previous == null -> {
                unshuffled = entries
                entries = listOf(current) + entries.filter { it !== current }.shuffled()
            }
            !shuffleModeEnabled && previous != null -> {
                entries = previous
                unshuffled = null
            }
            else -> return Futures.immediateVoidFuture()
        }
        currentIndex = entries.indexOf(current)
        rebuildAround(current.id)
        return Futures.immediateVoidFuture()
    }

    /**
     * Puts the Sonos queue in the order of [entries] without interrupting the playing track: every
     * other track is removed and added back around it, the way the initial load does.
     */
    private fun rebuildAround(currentId: Long) {
        val previous = loadJob
        val order = entries
        val playing = playWhenReady
        val resumeAt = currentPosition
        // Paused, Sonos may reset the track's position while its queue changes; restored below.
        hold(if (playing) 2_000 else HOLD_WHILE_LOADING_MS)
        loadJob = scope.launch {
            previous?.join()
            try {
                withContext(sonosThread) {
                    val base = server.baseUrl() ?: throw IOException("the phone has no Wi-Fi address")
                    val p = sonosOrder.indexOf(currentId)
                    if (p < 0) return@withContext
                    if (p + 1 < sonosOrder.size) Sonos.removeFromQueue(ip, p + 2, sonosOrder.size - p - 1)
                    if (p > 0) Sonos.removeFromQueue(ip, 1, p)
                    sonosOrder.clear()
                    sonosOrder += currentId
                    val tracks = dao.getByIds(order.map { trackIdOf(it.item) }).associateBy { it.id }
                    fun items(batch: List<Entry>) =
                        batch.mapNotNull { e -> trackFor(e.item, tracks)?.let { e.id to queueItem(base, e.id, it) } }
                    val i = order.indexOfFirst { it.id == currentId }
                    for (batch in order.subList(i + 1, order.size).chunked(Sonos.MAX_URIS_PER_ADD)) {
                        val added = items(batch)
                        if (added.isNotEmpty()) {
                            try {
                                Sonos.addToQueue(ip, added.map { it.second })
                                sonosOrder += added.map { it.first }
                            } catch (e: IOException) {
                                Log.w(TAG, "Skipped ${added.size} tracks that Sonos refused", e)
                            }
                        }
                        yield()
                    }
                    var position = 1
                    for (batch in order.subList(0, i).chunked(Sonos.MAX_URIS_PER_ADD)) {
                        val added = items(batch)
                        if (added.isNotEmpty()) {
                            try {
                                Sonos.addToQueue(ip, added.map { it.second }, position)
                                sonosOrder.addAll(position - 1, added.map { it.first })
                                position += added.size
                            } catch (e: IOException) {
                                Log.w(TAG, "Skipped ${added.size} tracks that Sonos refused", e)
                            }
                        }
                        yield()
                    }
                    if (!playing) {
                        val now = Sonos.position(ip)
                        if (server.entryIdOf(now.trackUri) != currentId) Sonos.seekTrack(ip, sonosOrder.indexOf(currentId) + 1)
                        if (resumeAt > 1000 && kotlin.math.abs(Sonos.position(ip).relTimeMs - resumeAt) > 3_000) seekTimeVerified(resumeAt)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reordering the Sonos queue failed", e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { hold() }
            }
        }
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val added = mediaItems.map { Entry(nextId++, it) }
        // Shuffled, the unshuffled order gets them after the same track as the play order.
        unshuffled = unshuffled?.let { u ->
            val after = entries.getOrNull(index - 1)
            u.toMutableList().apply { addAll(if (after == null) 0 else u.indexOf(after) + 1, added) }
        }
        entries = entries.toMutableList().apply { addAll(index.coerceIn(0, size), added) }
        if (index <= currentIndex && entries.size > added.size) currentIndex += added.size
        return queueEdit {
            val base = server.baseUrl() ?: return@queueEdit
            val tracks = dao.getByIds(added.map { trackIdOf(it.item) }).associateBy { it.id }
            // Insert after whatever precedes it in the app's queue, so both orders stay the same.
            var position = afterPositionOf(added.first().id)
            for (batch in added.chunked(Sonos.MAX_URIS_PER_ADD)) {
                val items = batch.mapNotNull { e -> trackFor(e.item, tracks)?.let { e.id to queueItem(base, e.id, it) } }
                if (items.isEmpty()) continue
                Sonos.addToQueue(ip, items.map { it.second }, position)
                sonosOrder.addAll(position - 1, items.map { it.first })
                position += items.size
            }
        }
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val removed = entries.subList(fromIndex, toIndex).map { it.id }
        val current = entries.getOrNull(currentIndex)
        entries = entries.filterIndexed { i, _ -> i !in fromIndex until toIndex }
        unshuffled = unshuffled?.filter { it.id !in removed }
        currentIndex = when {
            current != null && current.id !in removed -> entries.indexOf(current)
            else -> fromIndex.coerceAtMost((entries.size - 1).coerceAtLeast(0))
        }
        return queueEdit {
            // Remove one by one from the end, as the entries need not be contiguous in Sonos order.
            removed.mapNotNull { id -> sonosOrder.indexOf(id).takeIf { it >= 0 } }.sortedDescending().forEach { i ->
                Sonos.removeFromQueue(ip, i + 1, 1)
                sonosOrder.removeAt(i)
            }
        }
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val current = entries.getOrNull(currentIndex)
        val moving = entries.subList(fromIndex, toIndex).toList()
        entries = entries.toMutableList().apply {
            subList(fromIndex, toIndex).clear()
            addAll(newIndex.coerceIn(0, size), moving)
        }
        currentIndex = entries.indexOf(current).coerceAtLeast(0)
        val anchorBefore = entries.getOrNull(newIndex - 1)?.id
        return queueEdit {
            for ((i, e) in moving.withIndex()) {
                val from = sonosOrder.indexOf(e.id)
                if (from < 0) continue
                sonosOrder.removeAt(from)
                // Place right after the previous entry in the app's order (or first).
                val prev = if (i == 0) anchorBefore else moving[i - 1].id
                val to = if (prev == null) 0 else sonosOrder.indexOf(prev) + 1
                sonosOrder.add(to, e.id)
                // InsertBefore refers to positions before the move.
                val insertBefore = if (to >= from) to + 2 else to + 1
                Sonos.reorderQueue(ip, from + 1, 1, insertBefore)
            }
        }
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        handleAddMediaItems(toIndex, mediaItems)
        return handleRemoveMediaItems(fromIndex, toIndex)
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        volume = deviceVolume.coerceIn(0, 100)
        hold()
        val target = volume
        return command { Sonos.setGroupVolume(ip, target) }
    }

    override fun handleIncreaseDeviceVolume(flags: Int) = handleSetDeviceVolume(volume + VOLUME_STEP, flags)

    override fun handleDecreaseDeviceVolume(flags: Int) = handleSetDeviceVolume(volume - VOLUME_STEP, flags)

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    /** Current app-side state, for handing playback back to the phone (or to another group). */
    fun snapshot(): QueueSnapshot {
        val u = unshuffled ?: return QueueSnapshot(entries.map { it.item }, currentIndex, currentPosition, null)
        val positionOf = u.withIndex().associate { (i, e) -> e.id to i }
        return QueueSnapshot(
            items = u.map { it.item },
            index = entries.getOrNull(currentIndex)?.let { positionOf[it.id] } ?: 0,
            positionMs = currentPosition,
            playOrder = entries.mapNotNull { positionOf[it.id] },
        )
    }

    // ---- Helpers ----

    private fun command(block: suspend () -> Unit): ListenableFuture<*> = scope.future {
        try {
            withContext(sonosThread) { block() }
        } catch (e: Exception) {
            Log.w(TAG, "Sonos command failed", e)
        }
    }

    /** Queue edits wait until the initial queue load is done, so positions line up. */
    private fun queueEdit(block: suspend () -> Unit): ListenableFuture<*> {
        val load = loadJob
        return command {
            load?.join()
            block()
        }
    }

    /** 1-based Sonos queue position of an entry, waiting for the queue load if needed. */
    private suspend fun sonosPosition(entryId: Long): Int? {
        sonosOrder.indexOf(entryId).takeIf { it >= 0 }?.let { return it + 1 }
        loadJob?.join()
        return sonosOrder.indexOf(entryId).takeIf { it >= 0 }?.plus(1)
    }

    /** 1-based Sonos position right after the entry preceding [entryId] in the app's queue. */
    private suspend fun afterPositionOf(entryId: Long): Int {
        val appOrder = withContext(Dispatchers.Main) { entries.map { it.id } }
        val prev = appOrder.getOrNull(appOrder.indexOf(entryId) - 1) ?: return 1
        val i = sonosOrder.indexOf(prev)
        return if (i < 0) sonosOrder.size + 1 else i + 2
    }

    /**
     * Right after the queue or track changes, Sonos accepts a time seek but ignores it until the
     * track is ready; repeat it until the reported position matches.
     */
    private suspend fun seekTimeVerified(positionMs: Long) {
        repeat(SEEK_ATTEMPTS) {
            Sonos.seekTime(ip, positionMs)
            delay(SEEK_RETRY_MS)
            if (kotlin.math.abs(Sonos.position(ip).relTimeMs - positionMs) < 3_000) return
        }
        Log.w(TAG, "Sonos didn't take the seek to $positionMs ms")
    }

    /** Repeat only: shuffle is already in the queue's order (see the class comment). */
    private suspend fun applyPlayMode() {
        val mode = when (withContext(Dispatchers.Main) { repeatMode }) {
            Player.REPEAT_MODE_ALL -> "REPEAT_ALL"
            Player.REPEAT_MODE_ONE -> "REPEAT_ONE"
            else -> "NORMAL"
        }
        Sonos.setPlayMode(ip, mode)
    }

    private fun trackFor(item: MediaItem, dbTracks: Map<String, Track>): Track? =
        dbTracks[trackIdOf(item)] ?: item.toTransientTrack()

    private fun queueItem(base: String, entryId: Long, track: Track): SonosQueueItem {
        val url = server.trackUrl(base, entryId, track)
        return SonosQueueItem(
            url,
            Sonos.didl(track.title, track.displayArtist, track.album, server.artUrl(base, track), track.durationMs, server.mimeTypeFor(track), url),
        )
    }

    private fun hold(ms: Long = 2_000) {
        holdUntil = SystemClock.elapsedRealtime() + ms
    }

    private fun lose(message: String) {
        if (!scope.isActive) return
        Log.w(TAG, "Cast lost: $message")
        onLost(message)
    }

    /** Mirrors the speaker's state every second; volume and play mode every few seconds. */
    private suspend fun poll() {
        var tick = 0
        while (scope.isActive) {
            delay(POLL_MS)
            val slow = tick++ % 5 == 0
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    Triple(Sonos.position(ip), if (slow) Sonos.groupVolume(ip) else null, if (slow) Sonos.playMode(ip) else null)
                }
            }
            val (p, vol, mode) = result.getOrElse {
                if (it is CancellationException) return
                Log.w(TAG, "Poll failed ($failedPolls): $it")
                if (++failedPolls >= MAX_FAILED_POLLS) lose("Lost connection to ${group.name}")
                continue
            }
            failedPolls = 0
            Log.i(TAG, "Sonos poll: state=${p.transportState}, relTime=${p.relTimeMs}, track=${p.trackUri}")
            if (SystemClock.elapsedRealtime() < holdUntil) continue
            vol?.let { volume = it }
            mode?.let {
                // Shuffled from elsewhere (e.g. the Sonos app): Sonos reordered its queue, so it no
                // longer matches ours. Turn its shuffle off again; the app's shuffle button is the one.
                if (it.startsWith("SHUFFLE")) command { applyPlayMode() }
                repeatMode = when (it) {
                    "REPEAT_ALL", "SHUFFLE" -> Player.REPEAT_MODE_ALL
                    "REPEAT_ONE", "SHUFFLE_REPEAT_ONE" -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            val entryId = server.entryIdOf(p.trackUri)
            if (entryId == null) {
                // Someone started other music on this speaker (e.g. from the Sonos app): the cast is over.
                if (p.trackUri != null && loadJob?.isCompleted == true && ++foreignPolls >= 3) {
                    lose("${group.name} is now playing something else")
                }
                invalidateState()
                continue
            }
            foreignPolls = 0
            entries.indexOfFirst { it.id == entryId }.takeIf { it >= 0 }?.let { index ->
                // A new song: get the next one's first pieces ready, so moving on is quick too.
                if (index != currentIndex) entries.getOrNull(index + 1)?.let { server.prefetch(listOf(trackIdOf(it.item))) }
                currentIndex = index
            }
            positionMs = p.relTimeMs
            positionAt = SystemClock.elapsedRealtime()
            when (p.transportState) {
                "PLAYING" -> { playWhenReady = true; playbackState = Player.STATE_READY }
                "PAUSED_PLAYBACK", "STOPPED" -> { playWhenReady = false; playbackState = Player.STATE_READY }
                "TRANSITIONING" -> playbackState = Player.STATE_BUFFERING
            }
            invalidateState()
        }
    }

    private companion object {
        const val TAG = "SonosPlayer"
        const val POLL_MS = 1_000L
        const val MAX_FAILED_POLLS = 15
        const val VOLUME_STEP = 2
        const val HOLD_WHILE_LOADING_MS = 600_000L
        const val SEEK_ATTEMPTS = 8
        const val SEEK_RETRY_MS = 400L

        val DEVICE_INFO: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()

        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_RELEASE,
        ).build()

        fun trackIdOf(item: MediaItem) = PlaybackService.trackIdOf(item.mediaId)
    }
}

/** A queue handed between players: [items] in queue order, plus the play order (indices into [items]) while shuffled. */
data class QueueSnapshot(val items: List<MediaItem>, val index: Int, val positionMs: Long, val playOrder: List<Int>?)
