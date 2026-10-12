package com.kike.musicplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import com.kike.musicplayer.data.download.DownloadProgress
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.kike.musicplayer.data.db.Track

@Composable
fun Artwork(uri: Any?, modifier: Modifier = Modifier, size: Dp? = null, shape: Shape = RoundedCornerShape(6.dp)) {
    val m = (if (size != null) modifier.size(size) else modifier).clip(shape)
    SubcomposeAsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = m,
        loading = { ArtworkPlaceholder() },
        error = { ArtworkPlaceholder() },
    )
}

@Composable
private fun ArtworkPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The favourite songs and how to toggle one; provided once at the top of the UI for the track menus. */
class Favourites(val ids: Set<String>, val toggle: (trackId: String) -> Unit)

val LocalFavourites = compositionLocalOf { Favourites(emptySet()) {} }

/** Downloading Drive songs to the phone; provided once at the top of the UI. */
class Downloads(val progress: DownloadProgress?, val download: (List<Track>) -> Unit, val remove: (Track) -> Unit)

val LocalDownloads = compositionLocalOf { Downloads(null, {}, {}) }

/**
 * Downloads the Drive songs of an album, playlist or mix: a down arrow, a progress ring while they
 * download, a check once all are on the phone. Nothing when none of them is on Drive.
 */
@Composable
fun DownloadButton(tracks: List<Track>, modifier: Modifier = Modifier) {
    val drive = tracks.filter { it.source == Track.SOURCE_DRIVE }
    if (drive.isEmpty()) return
    val downloads = LocalDownloads.current
    val done = drive.count { it.downloadState == Track.DOWNLOADED }
    val busy = drive.any { it.downloadState == Track.DOWNLOAD_QUEUED }
    FilledTonalIconButton(onClick = { if (!busy && done < drive.size) downloads.download(drive) }, modifier = modifier) {
        when {
            // Spinning until the first song is in, then how many of them are.
            busy && done == 0 -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            busy -> CircularProgressIndicator(
                progress = { done.toFloat() / drive.size }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp,
            )
            done == drive.size -> Icon(Icons.Default.DownloadDone, "Downloaded to the phone")
            else -> Icon(Icons.Default.Download, "Download to the phone")
        }
    }
}

/** Where a Drive song is: in the cloud, downloading (with progress), or on the phone. */
@Composable
private fun DownloadStatus(track: Track, progress: DownloadProgress?) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (track.downloadState) {
        Track.DOWNLOADED -> Icon(Icons.Default.DownloadDone, "On the phone", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Track.DOWNLOAD_QUEUED ->
            if (progress?.trackId == track.id && progress.total > 0) {
                CircularProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Downloading, "Waiting to download", Modifier.size(16.dp), tint = tint)
            }
        else -> Icon(Icons.Default.Cloud, "Google Drive", Modifier.size(16.dp), tint = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    showArtwork: Boolean = true,
    leading: String? = null,
    /** Short label shown before the menu, e.g. a play count. */
    badge: String? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val favourites = LocalFavourites.current
    val downloads = LocalDownloads.current
    Row(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(
                leading, Modifier.width(32.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (showArtwork) {
            Artwork(track.artworkUri, size = 48.dp)
            Box(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(track.displayArtist, track.album).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        badge?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp))
        }
        if (track.source == Track.SOURCE_DRIVE) DownloadStatus(track, downloads.progress)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; onPlayNext() })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; onAddToQueue() })
                DropdownMenuItem(
                    text = { Text(if (track.id in favourites.ids) "Remove from Favourites" else "Add to Favourites") },
                    onClick = { menu = false; favourites.toggle(track.id) },
                )
                if (track.source == Track.SOURCE_DRIVE) when (track.downloadState) {
                    Track.DOWNLOADED -> DropdownMenuItem(text = { Text("Remove download") }, onClick = { menu = false; downloads.remove(track) })
                    Track.DOWNLOAD_QUEUED -> Unit
                    else -> DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; downloads.download(listOf(track)) })
                }
            }
        }
    }
}

/**
 * For screen top bars: the Scaffold's content padding already starts below the status bar, so an
 * app bar adding the status bar height again left a large empty band above the title.
 */
val NoTopInsets = WindowInsets(0, 0, 0, 0)

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
