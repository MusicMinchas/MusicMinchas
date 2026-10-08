package com.kike.musicplayer.ui

import androidx.compose.foundation.clickable
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
                    Text(
                        listOfNotNull(item.mediaMetadata.artist?.toString(), castTo?.let { "on ${it.name}" }).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
fun NowPlayingScreen(playerVm: PlayerViewModel, onBack: () -> Unit, onOpenQueue: () -> Unit, contentPadding: PaddingValues) {
    val s by playerVm.state.collectAsState()
    val castTo by playerVm.castActive.collectAsState()
    var showCast by remember { mutableStateOf(false) }
    val meta = s.current?.mediaMetadata
    if (showCast) CastSheet(playerVm) { showCast = false }
    Box(Modifier.fillMaxSize()) {
        ArtworkBackdrop(meta?.artworkUri)
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Close") } },
                actions = {
                    IconButton(onClick = { showCast = true }) {
                        if (castTo != null) Icon(Icons.Default.CastConnected, "Playing on ${castTo!!.name}", tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Default.Cast, "Play on a speaker")
                    }
                    IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
                },
            )
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(
                    meta?.artworkUri,
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    meta?.title?.toString() ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Text(
                    listOfNotNull(meta?.artist, meta?.albumTitle).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val group = castTo
                if (group != null) {
                    TextButton(onClick = { showCast = true }) {
                        Icon(Icons.Default.Speaker, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Playing on ${group.name}", style = MaterialTheme.typography.labelLarge)
                    }
                } else s.formatInfo?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(16.dp))
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
                    FilledIconButton(onClick = playerVm::togglePlay, modifier = Modifier.size(72.dp)) {
                        if (s.isBuffering && !s.isPlaying) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                        else Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (s.isPlaying) "Pause" else "Play", Modifier.size(40.dp))
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
                Spacer(Modifier.weight(1f))
                s.upNext.firstOrNull()?.let { next ->
                    TextButton(onClick = onOpenQueue, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("Up next: ${next.item.mediaMetadata.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Speaker volume while casting; sent when the finger lifts so Sonos isn't flooded with commands. */
@Composable
private fun RemoteVolume(volume: Int, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
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
private fun ArtworkBackdrop(uri: Any?) {
    if (uri == null) return
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
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

@Composable
private fun SeekBar(s: PlayerUiState, onSeek: (Long) -> Unit) {
    // While dragging, show the thumb where the finger is instead of the live position.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = s.durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: (s.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
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
            Text(
                item.mediaMetadata.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}
