package com.kike.musicplayer.ui

import com.kike.musicplayer.playback.toTransientTrack
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Bedtime
import com.kike.musicplayer.playback.SleepTimerStatus
import com.kike.musicplayer.playback.SleepTimerMode
import com.kike.musicplayer.playback.AudioQualityDetails
import com.kike.musicplayer.playback.AudioTier
import com.kike.musicplayer.playback.OutputRouteType
import com.kike.musicplayer.playback.formatSampleRate


import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.ui.res.stringResource
import com.kike.musicplayer.R
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
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ModalBottomSheet
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Read in the progress lambda only: the line redraws without recomposing the mini player.
    val progress = playerVm.progress.collectAsStateWithLifecycle()
    val item = s.current ?: return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            LinearProgressIndicator(
                progress = { if (s.durationMs > 0) progress.value.positionMs.toFloat() / s.durationMs else 0f },
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
        else Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play))
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
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    val lyrics by playerVm.lyrics.collectAsState()
    val meta = s.current?.mediaMetadata
    // While the lyrics side is up, keep it in step with the song.
    LaunchedEffect(showLyrics, s.current?.mediaId) {
        if (showLyrics) s.current?.mediaId?.let(playerVm::loadLyrics)
    }
    if (showCast) CastSheet(playerVm) { showCast = false }
    if (showSleepTimer) {
        val sleepStatus by playerVm.sleepTimerStatus.collectAsState()
        SleepTimerSheet(
            status = sleepStatus,
            onSetMinutes = playerVm::startSleepTimerMinutes,
            onSetEndOfTrack = playerVm::startSleepTimerEndOfTrack,
            onCancel = playerVm::cancelSleepTimer,
            onDismiss = { showSleepTimer = false },
        )
    }
    val onFlip = { if (s.current != null) showLyrics = !showLyrics }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sideways: cover on the left, everything else on the right (the full cover is for upright only).
        val landscape = maxWidth > maxHeight
        val full = fullCover && !landscape
        if (!full) ArtworkBackdrop(meta?.artworkUri)
        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f)) {
                    FlipCard(
                        flipped = showLyrics,
                        onFlip = onFlip,
                        front = { Artwork(meta?.artworkUri, Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) },
                        back = { LiveLyrics(lyrics, playerVm) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // On the cover's corner, leaving the right side's height to the controls.
                    FilledIconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(8.dp).size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
                    ) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NowPlayingDetails(s, playerVm, showLyrics, { showLyrics = !showLyrics }, { showCast = true }, onOpenQueue, onOpenSleepTimer = { showSleepTimer = true }, heartOffset = 12.dp, compact = true)
                }
            }
        } else if (full) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // The cover's bottom colour carries on below it and fades into the page.
                EdgeColorBackdrop(meta?.artworkUri, coverFraction = maxWidth * FULL_COVER_HEIGHT / maxHeight)
                // Between the two, a soft blurred copy reaching a little below the cover, so the
                // picture goes sharp -> blurred -> plain colour without an edge.
                BlurredCoverTail(meta?.artworkUri, Modifier.fillMaxWidth().height(maxWidth * FULL_COVER_HEIGHT * 1.25f))
                val top = contentPadding.calculateTopPadding()
                val bottom = contentPadding.calculateBottomPadding()
                // Where the title is in the normal layout: close bar, cover (page width plus the bleed), gap.
                val naturalTitleTop = top + CLOSE_BAR + maxWidth - 48.dp + COVER_BLEED * 2 + TITLE_GAP
                // Bound titleTop so controls zone is guaranteed to fit without cutting off Up Next
                val titleTop = naturalTitleTop.coerceAtMost((maxHeight - bottom - 325.dp).coerceAtLeast(top + 180.dp))
                // A little taller than wide (the sides crop off), so it reaches further down.
                Box(Modifier.fillMaxWidth().aspectRatio(1f / FULL_COVER_HEIGHT)) {
                    FlipCard(
                        flipped = showLyrics,
                        onFlip = onFlip,
                        front = { FadingCover(meta?.artworkUri) },
                        back = { Box(Modifier.padding(top = top)) { LiveLyrics(lyrics, playerVm) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // A light shade at the top keeps the status bar and the close arrow readable on any cover.
                    Box(
                        Modifier.fillMaxWidth().height(top + 56.dp)
                            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent)),
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.padding(top = top, start = 4.dp).align(Alignment.TopStart)) {
                            Icon(Icons.Default.KeyboardArrowDown, "Close", tint = Color.White)
                        }
                    }
                }
                Column(
                    Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The title at the same height as without the full cover.
                    Spacer(Modifier.height(titleTop))
                    NowPlayingDetails(s, playerVm, showLyrics, { showLyrics = !showLyrics }, { showCast = true }, onOpenQueue, onOpenSleepTimer = { showSleepTimer = true }, heartOffset = 12.dp)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(contentPadding)) {
                // Just the close arrow: a slim bar leaves the cover more room.
                Box(Modifier.fillMaxWidth().height(CLOSE_BAR).padding(horizontal = 4.dp)) {
                    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Close")
                    }
                }
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FlipCard(
                        flipped = showLyrics,
                        onFlip = onFlip,
                        front = { Artwork(meta?.artworkUri, Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) },
                        back = { LiveLyrics(lyrics, playerVm) },
                        // A little wider than the rest of the page.
                        modifier = Modifier.bleed(COVER_BLEED).weight(1.5f, fill = false).aspectRatio(1f),
                    )
                    Spacer(Modifier.height(TITLE_GAP))
                    NowPlayingDetails(s, playerVm, showLyrics, { showLyrics = !showLyrics }, { showCast = true }, onOpenQueue, onOpenSleepTimer = { showSleepTimer = true }, heartOffset = 12.dp + COVER_BLEED)
                }
            }
        }
    }
}

/** Lyrics in step with playback; only this part redraws as the song moves on. */
@Composable
private fun LiveLyrics(lyrics: LyricsState, playerVm: PlayerViewModel) {
    val position by playerVm.progress.collectAsStateWithLifecycle()
    LyricsPanel(lyrics, position.positionMs, playerVm::seekTo)
}

/** The cover edge to edge, fading out at the bottom into [EdgeColorBackdrop]. */
@Composable
private fun FadingCover(uri: Any?) {
    Artwork(
        uri,
        Modifier.fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(*smoothFadeOut(from = 0.62f)),
                    blendMode = BlendMode.DstIn,
                )
            },
        shape = RectangleShape,
    )
}

/** The cover heavily blurred, fading out smoothly towards its bottom. */
@Composable
private fun BlurredCoverTail(uri: Any?, modifier: Modifier) {
    if (uri == null) return
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier
            .blur(40.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(brush = Brush.verticalGradient(*smoothFadeOut(from = 0.55f)), blendMode = BlendMode.DstIn)
            },
    )
}

/**
 * Mask stops: opaque down to [from], then fading to nothing along an S-curve (smoothstep), which
 * leaves no visible line where a straight fade starts or ends.
 */
private fun smoothFadeOut(from: Float): Array<Pair<Float, Color>> =
    (listOf(0f to Color.Black) + (0..10).map { i ->
        val t = i / 10f
        (from + (1f - from) * t) to Color.Black.copy(alpha = 1f - t * t * (3f - 2f * t))
    }).toTypedArray()

/**
 * Behind the full-width cover: the colour along the cover's bottom edge, solid down to the cover's
 * end ([coverFraction] of the height) and fading into the page by the seek bar. Toned towards the
 * page colour so the title stays readable on it, and eased from song to song.
 */
@Composable
private fun EdgeColorBackdrop(uri: Any?, coverFraction: Float) {
    val page = MaterialTheme.colorScheme.background
    val edge = rememberBottomEdgeColor(uri)
    // Dark page: the colour a little darker; light page: well lightened, for the dark text.
    val tone = edge?.let { lerp(it, page, if (page.luminance() < 0.5f) 0.15f else 0.45f) } ?: page
    val color by animateColorAsState(tone, tween(600), label = "edge colour")
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to color,
                // Solid down past the title, then fading out by the seek bar.
                (coverFraction + 0.12f).coerceAtMost(1f) to color,
                (coverFraction + 0.4f).coerceAtMost(1f) to page,
            ),
        ),
    )
}

/** The average colour of the bottom tenth of [uri]'s image, read from a small copy; null until loaded. */
@Composable
private fun rememberBottomEdgeColor(uri: Any?): Color? {
    val context = LocalContext.current
    var color by remember(uri) { mutableStateOf<Color?>(null) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(uri).size(48).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return@LaunchedEffect
        color = withContext(Dispatchers.Default) {
            val rows = (bitmap.height / 10).coerceAtLeast(1)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in bitmap.height - rows until bitmap.height) for (x in 0 until bitmap.width) {
                val p = bitmap.getPixel(x, y)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
            Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
    }
    return color
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
    onOpenSleepTimer: () -> Unit,
    heartOffset: Dp,
    /** Sideways, where height is short: no extra space around the lyrics / cast / queue row. */
    compact: Boolean = false,
) {
    val castTo by playerVm.castActive.collectAsState()
    val favourites by playerVm.favourites.collectAsState()
    val infiniteQueue by playerVm.infiniteQueue.collectAsState()
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
    val track = trackId?.let(LocalNavigation.current.track) ?: s.current?.toTransientTrack()
    val albumKey = track?.albumKey ?: meta?.albumTitle?.toString()?.let { com.kike.musicplayer.data.db.Track.albumKeyOf(it, meta.albumArtist?.toString() ?: meta.artist?.toString() ?: "") }
    ArtistAlbumText(
        meta?.artist?.toString(), listOf(track?.artist, track?.albumArtist),
        meta?.albumTitle?.toString(), albumKey,
        track = track,
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
    )
    var showAudioDetails by remember { mutableStateOf(false) }
    val group = castTo
    AudioQualityBadge(
        quality = s.audioQuality,
        formatInfo = s.formatInfo,
        isCasting = group != null,
        castGroupName = group?.name,
        onClick = { showAudioDetails = true },
    )
    if (showAudioDetails) {
        AudioDetailsSheet(
            quality = s.audioQuality,
            onOpenCast = onShowCast,
            onDismiss = { showAudioDetails = false },
        )
    }
    // Spare height goes here, so the controls below keep the same spacing whether the
    // title takes one line or two.
    Spacer(Modifier.weight(1f))
    SeekBar(s, playerVm)
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
    // Lyrics / Sleep / Cast / Infinite / Queue within thumb reach under playback controls.
    Row(
        // Tighter while casting: the speaker volume row needs the room.
        Modifier.fillMaxWidth().padding(
            top = if (s.remoteVolume != null || compact) 0.dp else 8.dp,
            bottom = if (s.remoteVolume != null || compact) 0.dp else 4.dp,
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Lyrics
        IconButton(onClick = onToggleLyrics, enabled = s.current != null) {
            Icon(
                Icons.Default.Lyrics, if (showLyrics) "Hide lyrics" else "Lyrics",
                tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 2. Sleep
        val sleepStatus by playerVm.sleepTimerStatus.collectAsState()
        IconButton(onClick = onOpenSleepTimer) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = if (sleepStatus.isActive) "Sleep timer: ${sleepStatus.formattedRemaining}" else "Sleep timer",
                tint = if (sleepStatus.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 3. Cast
        IconButton(onClick = onShowCast) {
            if (castTo != null) Icon(Icons.Default.CastConnected, "Playing on ${castTo!!.name}", tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Default.Cast, "Play on a speaker", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 4. Infinite
        IconButton(onClick = { playerVm.toggleInfiniteQueue() }) {
            Icon(
                Icons.Default.AllInclusive, if (infiniteQueue) "Autoplay on" else "Autoplay off",
                tint = if (infiniteQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 5. Queue
        IconButton(onClick = onOpenQueue) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.player_queue), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    s.upNext.firstOrNull()?.let { next ->
        // A slim line rather than a full-height button, so it fits under the big cover.
        Text(
            stringResource(R.string.player_up_next, next.item.mediaMetadata.title ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenQueue)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The full-width cover's height, relative to the screen width. */
private const val FULL_COVER_HEIGHT = 1.0f

/** Now Playing's close-arrow bar and the gap between cover and title (both layouts). */
private val CLOSE_BAR = 34.dp
private val TITLE_GAP = 10.dp

/** How far the Now Playing cover reaches past the page margin on each side. */
private val COVER_BLEED = 10.dp

/** Lays the content out [horizontal] wider on each side than the space it is given. */
private fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}


@Composable
private fun AudioQualityBadge(
    quality: AudioQualityDetails?,
    formatInfo: String?,
    isCasting: Boolean,
    castGroupName: String?,
    onClick: () -> Unit,
) {
    val tier = quality?.tier
    val containerColor = when (tier) {
        AudioTier.HI_RES_LOSSLESS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        AudioTier.LOSSLESS -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val contentColor = when (tier) {
        AudioTier.HI_RES_LOSSLESS -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isCasting) {
                Icon(Icons.Default.Speaker, null, Modifier.size(13.dp))
                val speakerLabel = castGroupName ?: "Sonos"
                val qualityLabel = quality?.badgeLabel ?: "Lossless"
                Text(
                    "$speakerLabel · $qualityLabel",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
            } else if (quality != null) {
                when (quality.tier) {
                    AudioTier.HI_RES_LOSSLESS -> {
                        Icon(Icons.Default.GraphicEq, null, Modifier.size(13.dp))
                        Text(
                            "Hi-Res · ${quality.resolutionLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    AudioTier.LOSSLESS -> {
                        Text(
                            "Lossless · ${quality.resolutionLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    else -> {
                        Text(
                            quality.resolutionLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            } else if (formatInfo != null) {
                Text(formatInfo, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioDetailsSheet(
    quality: AudioQualityDetails?,
    onOpenCast: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (quality == null) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Audio Quality & Signal Path",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (quality.tier) {
                        AudioTier.HI_RES_LOSSLESS -> MaterialTheme.colorScheme.primaryContainer
                        AudioTier.LOSSLESS -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        quality.badgeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        color = when (quality.tier) {
                            AudioTier.HI_RES_LOSSLESS -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            AudioInfoCard(title = "Source Audio", icon = Icons.Default.MusicNote) {
                AudioDetailRow("Format / Codec", quality.codec)
                quality.bitDepth?.let { AudioDetailRow("Bit Depth", "$it-bit") }
                quality.sampleRateHz?.let { AudioDetailRow("Sample Rate", formatSampleRate(it)) }
                quality.bitrateKbps?.let { AudioDetailRow("Bitrate", "$it kbps") }
                AudioDetailRow("Channels", if (quality.channelCount == 2) "Stereo (2 ch)" else "${quality.channelCount} ch")
                AudioDetailRow("Source", quality.sourceDescription)
            }

            Spacer(Modifier.height(12.dp))

            AudioInfoCard(title = "Signal Path", icon = Icons.Default.Route) {
                AudioDetailRow("Decoder", quality.decoderDescription)
                AudioDetailRow("Processing", quality.signalPathDescription)
                if (quality.isDownsampledForSonos) {
                    AudioDetailRow("Sonos Compatibility", "Downsampled 48 kHz FLAC")
                } else if (quality.integrityDescription != null) {
                    AudioDetailRow("Integrity", quality.integrityDescription)
                }
            }

            Spacer(Modifier.height(12.dp))

            AudioInfoCard(
                title = "Output Device",
                icon = when (quality.outputInfo.routeType) {
                    OutputRouteType.SONOS_CAST -> Icons.Default.Speaker
                    OutputRouteType.USB_DAC -> Icons.Default.Usb
                    OutputRouteType.WIRED_HEADPHONES -> Icons.Default.Headphones
                    OutputRouteType.BLUETOOTH -> Icons.Default.Bluetooth
                    OutputRouteType.PHONE_SPEAKER -> Icons.Default.PhoneAndroid
                },
            ) {
                AudioDetailRow("Destination", quality.outputInfo.displayName)
                AudioDetailRow("Route Type", quality.outputInfo.description)
                quality.outputInfo.maxHardwareRateHz?.let {
                    AudioDetailRow("DAC Max Rate", formatSampleRate(it))
                }
            }

            if (quality.outputInfo.routeType == OutputRouteType.SONOS_CAST) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenCast()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Speaker, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Switch Sonos Speaker")
                }
            }
        }
    }
}

@Composable
private fun AudioInfoCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun AudioDetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 90.dp, max = 130.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    status: SleepTimerStatus,
    onSetMinutes: (Int) -> Unit,
    onSetEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bedtime, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
                }
                if (status.isActive) {
                    Text(
                        status.formattedRemaining,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (status.isActive) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Timer Active: ${status.formattedRemaining} left",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                "Playback will smoothly fade out over 30s before stopping.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            onCancel()
                            onDismiss()
                        }) {
                            Text("Turn Off")
                        }
                    }
                }
            }

            Text("Set Timer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))

            val options = listOf(
                15 to "15 minutes",
                30 to "30 minutes",
                45 to "45 minutes",
                60 to "1 hour",
            )
            options.forEach { (mins, label) ->
                SleepTimerOptionRow(label = label, selected = status.mode == SleepTimerMode.MINUTES && status.totalSeconds == mins * 60L) {
                    onSetMinutes(mins)
                    onDismiss()
                }
            }

            SleepTimerOptionRow(label = "End of current track", selected = status.mode == SleepTimerMode.END_OF_TRACK) {
                onSetEndOfTrack()
                onDismiss()
            }
        }
    }
}

@Composable
private fun SleepTimerOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (selected) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
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
    val playerState by playerVm.state.collectAsState()
    val sonosDownsample by playerVm.sonosDownsample.collectAsState()
    val isHighRes = (playerState.sampleRate ?: 0) > 48000
    val isHighResUnsupported = isHighRes && !sonosDownsample
    LaunchedEffect(Unit) { playerVm.discoverSpeakers() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Play on", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (discovering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { playerVm.discoverSpeakers() }) { Text("Search again") }
            }
            if (isHighResUnsupported) {
                val rateStr = playerState.sampleRate?.let { " (%.1f kHz)".format(it / 1000.0).replace(".0 ", " ") } ?: ""
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            stringResource(R.string.sonos_hires_not_supported_warning, rateStr),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            CastRow(Icons.Default.PhoneAndroid, "This phone", selected = active == null) {
                playerVm.stopCasting()
                onDismiss()
            }
                        groups.forEach { group ->
                CastRow(
                    icon = Icons.Default.Speaker,
                    label = group.name,
                    selected = active?.uuid == group.uuid,
                    enabled = !isHighResUnsupported,
                ) {
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
private fun CastRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val iconTint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = iconTint)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), color = contentColor)
        if (selected) Icon(Icons.Default.Check, "Selected", tint = iconTint)
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
private fun SeekBar(s: PlayerUiState, playerVm: PlayerViewModel) {
    val onSeek: (Long) -> Unit = playerVm::seekTo
    val position by playerVm.progress.collectAsStateWithLifecycle()
    // While dragging, show the thumb where the finger is instead of the live position.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = s.durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: (position.positionMs.toFloat() / duration).coerceIn(0f, 1f)
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
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    if (showSleepTimer) {
        val sleepStatus by playerVm.sleepTimerStatus.collectAsState()
        SleepTimerSheet(
            status = sleepStatus,
            onSetMinutes = playerVm::startSleepTimerMinutes,
            onSetEndOfTrack = playerVm::startSleepTimerEndOfTrack,
            onCancel = playerVm::cancelSleepTimer,
            onDismiss = { showSleepTimer = false },
        )
    }
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
            title = { Text(stringResource(R.string.queue_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Close") } },
            actions = {
                val infiniteQueue by playerVm.infiniteQueue.collectAsState()
                val sleepStatus by playerVm.sleepTimerStatus.collectAsState()
                IconButton(onClick = { showSleepTimer = true }) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = if (sleepStatus.isActive) "Sleep timer: ${sleepStatus.formattedRemaining}" else "Sleep timer",
                        tint = if (sleepStatus.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { playerVm.toggleInfiniteQueue() }) {
                    Icon(
                        Icons.Default.AllInclusive,
                        contentDescription = if (infiniteQueue) "Autoplay on" else "Autoplay off",
                        tint = if (infiniteQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.upNext.isNotEmpty()) TextButton(onClick = { playerVm.clearUpNext() }) { Text("Clear") }
            },
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
                    if (s.shuffle) stringResource(R.string.queue_up_next_shuffled) else stringResource(R.string.queue_up_next_count, items.size)
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
