package com.kike.musicplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.data.lyrics.LyricsRepository
import com.kike.musicplayer.data.lyrics.SongLyrics

/** What the back of the cover shows. */
sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Found(val lyrics: SongLyrics) : LyricsState
}

/**
 * The cover, which turns over (a 3D flip around the vertical axis) to show the lyrics on its back.
 * Tapping either side turns it again.
 */
@Composable
fun FlipCard(flipped: Boolean, onFlip: () -> Unit, front: @Composable () -> Unit, back: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(500), label = "flip")
    val density = LocalDensity.current.density
    Box(
        modifier.graphicsLayer {
            rotationY = rotation
            cameraDistance = 12f * density
        }.clickable(onClick = onFlip),
    ) {
        if (rotation <= 90f) {
            front()
        } else {
            // Mirrored so the back reads the right way round.
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { back() }
        }
    }
}

/** Lyrics on the back of the cover: synced ones follow the song (tap a line to jump to it). */
@Composable
fun LyricsPanel(state: LyricsState, positionMs: Long, onSeek: (Long) -> Unit) {
    Box(
        // No panel: the lyrics sit straight on the blurred cover backdrop.
        Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            LyricsState.Loading -> CircularProgressIndicator()
            LyricsState.None -> Message("No lyrics found for this song")
            is LyricsState.Found -> {
                val lyrics = state.lyrics
                when {
                    lyrics.instrumental && lyrics.synced.isNullOrEmpty() && lyrics.plain.isNullOrBlank() -> Message("Instrumental")
                    !lyrics.synced.isNullOrEmpty() -> SyncedLyrics(lyrics, positionMs, onSeek)
                    else -> Text(
                        lyrics.plain.orEmpty(),
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: SongLyrics, positionMs: Long, onSeek: (Long) -> Unit) {
    val lines = lyrics.synced.orEmpty()
    val current = remember(lines, positionMs) { LyricsRepository.currentLine(lines, positionMs) }
    val listState = rememberLazyListState()
    // Keeps the line being sung about a third of the way down.
    LaunchedEffect(current) {
        if (current >= 0) {
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(current, scrollOffset = -viewport / 3)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 120.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == current
            Text(
                line.text.ifEmpty { "♪" },
                Modifier.fillMaxWidth().clickable { onSeek(line.timeMs) }.padding(vertical = 6.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (index < current) 0.45f else 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
