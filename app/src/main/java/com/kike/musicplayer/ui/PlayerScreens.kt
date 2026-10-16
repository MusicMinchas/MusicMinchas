package com.kike.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SliderDefaults
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import com.kike.musicplayer.playback.PlaybackService
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.TopAppBarDefaults
import coil3.compose.AsyncImage
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun MiniPlayer(playerVm: PlayerViewModel, onOpen: () -> Unit) {
    val s by playerVm.state.collectAsState()
    val castTo by playerVm.castActive.collectAsState()
    val item = s.current ?: return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            LinearProgressIndicator(
                progress = { if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(item.mediaMetadata.artworkUri, size = 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(item.mediaMetadata.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val track = LocalNavigation.current.track(PlaybackService.trackIdOf(item.mediaId))
                    ArtistAlbumText(
                        item.mediaMetadata.artist?.toString(), listOf(track?.artist, track?.albumArtist),
                        suffix = castTo?.let { "on ${it.name}" },
                        links = false,
                    )
                }
                PlayPauseIcon(s, playerVm::togglePlay)
                IconButton(onClick = { playerVm.next() }) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

@Composable
private fun PlayPauseIcon(s: PlayerUiState, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        if (s.isBuffering && !s.isPlaying) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.isPlaying) "Pause" else "Play")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    contentPadding: PaddingValues,
    /** Appearance › Full screen cover art: the cover across the whole width, under the status bar. */
    fullCover: Boolean = false,
) {
    val s by playerVm.state.collectAsState()
    var showCast by remember { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val lyrics by playerVm.lyrics.collectAsState()
    val meta = s.current?.mediaMetadata
    // While the lyrics side is up, keep it in step with the song.
    LaunchedEffect(showLyrics, s.current?.mediaId) {
        if (showLyrics) s.current?.mediaId?.let(playerVm::loadLyrics)
    }
    if (showCast) CastSheet(playerVm) { showCast = false }
    val onFlip = { if (s.current != null) showLyrics = !showLyrics }
    Box(Modifier.fillMaxSize()) {
        // Further down with the full cover, so its colours carry on under the title.
        ArtworkBackdrop(meta?.artworkUri, reach = if (fullCover) 1f else 0.85f)
        if (fullCover) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val top = contentPadding.calculateTopPadding()
                // Where the title is in the normal layout: close bar, cover (page width plus the bleed), gap.
                val titleTop = top + CLOSE_BAR + maxWidth - 48.dp + COVER_BLEED * 2 + TITLE_GAP
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    FlipCard(
                        flipped = showLyrics,
                        onFlip = onFlip,
                        front = { FadingCover(meta?.artworkUri) },
                        back = { Box(Modifier.padding(top = top)) { LyricsPanel(lyrics, s.positionMs, playerVm::seekTo) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // A light shade at the top keeps the status bar and the close arrow readable on any cover.
                    Box(
                        Modifier.fillMaxWidth().height(top + 56.dp)
                            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent)),
                    )
                    IconButton(onClick = onBack, modifier = Modifier.padding(top = top, start = 4.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Close", tint = Color.White)
                    }
                }
                Column(
                    Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The title at the same height as without the full cover.
                    Spacer(Modifier.height(titleTop))
                    NowPlayingDetails(s, playerVm, showLyrics, { showLyrics = !showLyrics }, { showCast = true }, onOpenQueue, heartOffset = 12.dp)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(contentPadding)) {
                // Just the close arrow: a slim bar leaves the cover more room.
                Box(Modifier.fillMaxWidth().height(CLOSE_BAR).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
                }
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FlipCard(
                        flipped = showLyrics,
                        onFlip = onFlip,
                        front = { Artwork(meta?.artworkUri, Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) },
                        back = { LyricsPanel(lyrics, s.positionMs, playerVm::seekTo) },
                        // A little wider than the rest of the page.
                        modifier = Modifier.bleed(COVER_BLEED).fillMaxWidth().aspectRatio(1f),
                    )
                    Spacer(Modifier.height(TITLE_GAP))
                    NowPlayingDetails(s, playerVm, showLyrics, { showLyrics = !showLyrics }, { showCast = true }, onOpenQueue, heartOffset = 12.dp + COVER_BLEED)
                }
            }
        }
    }
}

/** The cover edge to edge, fading out at the bottom into the blurred backdrop. */
@Composable
private fun FadingCover(uri: Any?) {
    Artwork(
        uri,
        Modifier.fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(0.6f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        shape = RectangleShape,
    )
}

/**
 * Below the cover: title with the heart ([heartOffset] past the right edge), artist and album,
 * format or speaker, then (at the bottom) seek bar, controls, lyrics / cast / queue and up next.
 */
@Composable
private fun ColumnScope.NowPlayingDetails(
    s: PlayerUiState,
    playerVm: PlayerViewModel,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onShowCast: () -> Unit,
    onOpenQueue: () -> Unit,
    heartOffset: Dp,
) {
    val castTo by playerVm.castActive.collectAsState()
    val favourites by playerVm.favourites.collectAsState()
    val meta = s.current?.mediaMetadata
    val trackId = s.current?.mediaId?.let(PlaybackService::trackIdOf)
    // Title centred; the heart sits at the cover's right edge.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            meta?.title?.toString() ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        if (trackId != null) {
            val favourite = trackId in favourites
            IconButton(
                onClick = { playerVm.toggleFavourite(trackId) },
                // The icon's edge, not the touch target's, lines up with the cover.
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = heartOffset),
            ) {
                Icon(
                    if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (favourite) "Remove from Favourites" else "Add to Favourites",
                    tint = if (favourite) FAVOURITE_RED else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val track = trackId?.let(LocalNavigation.current.track)
    ArtistAlbumText(
        meta?.artist?.toString(), listOf(track?.artist, track?.albumArtist),
        meta?.albumTitle?.toString(), track?.albumKey,
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
    )
    val group = castTo
    if (group != null) {
        TextButton(onClick = onShowCast) {
            Icon(Icons.Default.Speaker, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Playing on ${group.name}", style = MaterialTheme.typography.labelLarge)
        }
    } else s.formatInfo?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp))
    }
    // Spare height goes here, so the controls below keep the same spacing whether the
    // title takes one line or two.
    Spacer(Modifier.weight(1f))
    SeekBar(s, playerVm::seekTo)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { playerVm.toggleShuffle() }) {
            Icon(Icons.Default.Shuffle, "Shuffle",
                tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { playerVm.previous() }) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp)) }
        FilledIconButton(onClick = playerVm::togglePlay, modifier = Modifier.size(64.dp)) {
            if (s.isBuffering && !s.isPlaying) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            else Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (s.isPlaying) "Pause" else "Play", Modifier.size(36.dp))
        }
        IconButton(onClick = { playerVm.next() }) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp)) }
        IconButton(onClick = { playerVm.cycleRepeat() }) {
            Icon(
                if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                "Repeat",
                tint = if (s.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    s.remoteVolume?.let { volume -> RemoteVolume(volume, playerVm::setRemoteVolume) }
    s.error?.let {
        Text("Playback error: $it", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    // Lyrics · Cast · Queue, within thumb reach under the playback controls.
    Row(
        // Tighter while casting: the speaker volume row needs the room.
        Modifier.fillMaxWidth().padding(top = if (s.remoteVolume != null) 0.dp else 16.dp, bottom = if (s.remoteVolume != null) 0.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleLyrics, enabled = s.current != null) {
            Icon(
                Icons.Default.Lyrics, if (showLyrics) "Hide lyrics" else "Lyrics",
                tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShowCast) {
            if (castTo != null) Icon(Icons.Default.CastConnected, "Playing on ${castTo!!.name}", tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Default.Cast, "Play on a speaker", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onOpenQueue) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    s.upNext.firstOrNull()?.let { next ->
        // A slim line rather than a full-height button, so it fits under the big cover.
        Text(
            "Up next: ${next.item.mediaMetadata.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenQueue)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Now Playing's close-arrow bar and the gap between cover and title (both layouts). */
private val CLOSE_BAR = 40.dp
private val TITLE_GAP = 22.dp

/** How far the Now Playing cover reaches past the page margin on each side. */
private val COVER_BLEED = 10.dp

/** Lays the content out [horizontal] wider on each side than the space it is given. */
private fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Speaker volume while casting; sent when the finger lifts so Sonos isn't flooded with commands. */
@Composable
private fun RemoteVolume(volume: Int, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        DotSlider(
            value = dragging ?: (volume / 100f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onChange((it * 100).roundToInt()) }
                dragging = null
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Picks where the queue plays: this phone or a Sonos room/group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastSheet(playerVm: PlayerViewModel, onDismiss: () -> Unit) {
    val groups by playerVm.castGroups.collectAsState()
    val discovering by playerVm.castDiscovering.collectAsState()
    val active by playerVm.castActive.collectAsState()
    val message by playerVm.castMessage.collectAsState()
    LaunchedEffect(Unit) { playerVm.discoverSpeakers() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Play on", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (discovering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { playerVm.discoverSpeakers() }) { Text("Search again") }
            }
            CastRow(Icons.Default.PhoneAndroid, "This phone", selected = active == null) {
                playerVm.stopCasting()
                onDismiss()
            }
            groups.forEach { group ->
                CastRow(Icons.Default.Speaker, group.name, selected = active?.uuid == group.uuid) {
                    if (active?.uuid != group.uuid) playerVm.castTo(group)
                    onDismiss()
                }
            }
            message?.let {
                Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (groups.isEmpty() && discovering) {
                Text("Looking for Sonos speakers…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CastRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The cover, blurred and stretched across the top of the screen, fading from semi-transparent at
 * the top to fully transparent before the transport controls.
 */
@Composable
private fun ArtworkBackdrop(uri: Any?, reach: Float = 0.85f) {
    if (uri == null) return
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(reach)
            .blur(20.dp) // no-op before Android 12
            // Offscreen so the gradient below can mask this layer's alpha (DstIn keeps the image
            // only where the mask is opaque).
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.65f),
                        0.45f to Color.Black.copy(alpha = 0.38f),
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/** A thin track with a round thumb that grows while held (seek bar, speaker volume, settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DotSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    /** The unfilled part of the track; on a card it needs more contrast than the default. */
    inactiveTrackColor: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val thumbSize by animateDpAsState(if (pressed || dragged) 20.dp else 12.dp, label = "thumb")
    val colors = inactiveTrackColor?.let { SliderDefaults.colors(inactiveTrackColor = it) } ?: SliderDefaults.colors()
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        interactionSource = interaction,
        modifier = modifier.height(28.dp),
        thumb = {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(thumbSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(4.dp),
                colors = colors,
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
            )
        },
    )
}

@Composable
private fun SeekBar(s: PlayerUiState, onSeek: (Long) -> Unit) {
    // While dragging, show the thumb where the finger is instead of the live position.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = s.durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: (s.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        DotSlider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * duration).toLong()) }
                dragFraction = null
            },
            enabled = s.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime((fraction * duration).toLong()), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(s.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(playerVm: PlayerViewModel, onBack: () -> Unit, contentPadding: PaddingValues) {
    val s by playerVm.state.collectAsState()
    // Local copy so rows move under the finger; committed to the player when the drag ends.
    var items by remember { mutableStateOf(s.upNext) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(s.upNext) { if (draggedIndex == null) items = s.upNext }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = items.indexOfFirst { "q${it.playlistIndex}" == from.key }
        val b = items.indexOfFirst { "q${it.playlistIndex}" == to.key }
        if (a >= 0 && b >= 0) items = items.toMutableList().apply { add(b, removeAt(a)) }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text("Queue") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Close") } },
            actions = { if (s.upNext.isNotEmpty()) TextButton(onClick = { playerVm.clearUpNext() }) { Text("Clear") } },
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            s.current?.let { current ->
                item(key = "now") {
                    SectionHeader("Now playing")
                    QueueRow(current, highlighted = true)
                }
            }
            item(key = "header") {
                SectionHeader(
                    if (s.shuffle) "Up next (shuffled — turn shuffle off to reorder)" else "Up next · ${items.size}"
                )
            }
            items(items, key = { "q${it.playlistIndex}" }) { entry ->
                ReorderableItem(reorderState, key = "q${entry.playlistIndex}") { isDragging ->
                    Surface(tonalElevation = if (isDragging) 6.dp else 0.dp) {
                        QueueRow(
                            entry.item,
                            highlighted = false,
                            onClick = { playerVm.skipTo(entry.playlistIndex) },
                            trailing = {
                                IconButton(onClick = { playerVm.removeItem(entry.playlistIndex) }) {
                                    Icon(Icons.Default.Close, "Remove")
                                }
                                if (!s.shuffle) {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = { draggedIndex = entry.playlistIndex },
                                            onDragStopped = {
                                                val from = draggedIndex
                                                draggedIndex = null
                                                val newPos = items.indexOfFirst { it.playlistIndex == from }
                                                if (from != null && newPos >= 0) {
                                                    // Without shuffle, up-next is the contiguous run after the current item.
                                                    val to = s.currentIndex + 1 + newPos
                                                    if (to != from) playerVm.moveItem(from, to)
                                                }
                                            },
                                        ),
                                    ) { Icon(Icons.Default.DragHandle, "Reorder") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun QueueRow(
    item: MediaItem,
    highlighted: Boolean,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.mediaMetadata.artworkUri, size = 44.dp)
        Box(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.mediaMetadata.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val track = LocalNavigation.current.track(PlaybackService.trackIdOf(item.mediaId))
            ArtistAlbumText(item.mediaMetadata.artist?.toString(), listOf(track?.artist, track?.albumArtist), links = false)
        }
        trailing()
    }
}
