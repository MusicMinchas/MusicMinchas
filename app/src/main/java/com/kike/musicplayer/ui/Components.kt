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
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
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

/** Opening artist and album pages from anywhere; provided once at the top of the UI. */
class Navigation(
    /** Opens the page of the first of these names that is an artist in the library. */
    val openArtist: (names: List<String?>) -> Unit,
    val openAlbum: (albumKey: String) -> Unit,
    /** The library track with this id (for screens that only have the playing media item). */
    val track: (trackId: String) -> Track?,
)

val LocalNavigation = compositionLocalOf { Navigation({}, {}, { null }) }

/**
 * "Artist · Album" in which the artist name opens the artist page and the album name the album
 * page; taps elsewhere on the line go to the row or card around it. [artistNames]: the artist page
 * candidates, e.g. the track artist, then the album artist (compilations).
 */
@Composable
fun ArtistAlbumText(
    artist: String?,
    artistNames: List<String?>,
    album: String? = null,
    albumKey: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
    /** Plain text after the names, e.g. where it is playing. */
    suffix: String? = null,
    /** False where a mistap would get in the way of the row's own action (song lists, mini player). */
    links: Boolean = true,
) {
    val nav = LocalNavigation.current
    val text = buildAnnotatedString {
        val parts = mutableListOf<AnnotatedString.Builder.() -> Unit>()
        artist?.let { name ->
            parts += {
                if (links) withLink(LinkAnnotation.Clickable("artist", LINK_STYLE) { nav.openArtist(artistNames + name) }) { append(name) }
                else append(name)
            }
        }
        album?.let { title ->
            parts += {
                if (links && albumKey != null) withLink(LinkAnnotation.Clickable("album", LINK_STYLE) { nav.openAlbum(albumKey) }) { append(title) }
                else append(title)
            }
        }
        suffix?.let { s -> parts += { append(s) } }
        parts.forEachIndexed { i, part ->
            if (i > 0) append(" · ")
            part()
        }
    }
    Text(text, modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
}

/** Links look like the text around them; underlined while pressed. */
private val LINK_STYLE = TextLinkStyles(style = SpanStyle(), pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline))

/** The red of favourite hearts. */
val FAVOURITE_RED = Color(0xFFE53935)

/** The user's playlists and adding songs to them; provided once at the top of the UI. */
class PlaylistActions(
    val playlists: List<PlaylistView>,
    val addTo: (playlistId: Long, trackIds: List<String>) -> Unit,
    val create: (name: String, trackIds: List<String>) -> Unit,
    val nextName: () -> String,
)

val LocalPlaylists = compositionLocalOf { PlaylistActions(emptyList(), { _, _ -> }, { _, _ -> }, { "" }) }

/** Picks one of the user's playlists for [trackIds], or names a new one for them. */
@Composable
fun AddToPlaylistDialog(trackIds: List<String>, onDismiss: () -> Unit) {
    val actions = LocalPlaylists.current
    var newName by remember { mutableStateOf<String?>(null) }
    val name = newName
    if (name != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { newName = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { actions.create(name, trackIds); onDismiss() }) { Text("Create") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn {
                items(actions.playlists, key = { it.id }) { p ->
                    val already = trackIds.size == 1 && p.tracks.any { it.id == trackIds[0] }
                    Text(
                        p.name + if (already) " (already in it)" else "",
                        Modifier.fillMaxWidth().clickable(enabled = !already) { actions.addTo(p.id, trackIds); onDismiss() }
                            .padding(vertical = 12.dp),
                        color = if (already) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                item {
                    Text(
                        "+ New playlist…",
                        Modifier.fillMaxWidth().clickable { newName = actions.nextName() }.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
    /** "Remove from playlist", in a playlist the user made. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** A drag handle after the menu (reorderable lists). */
    trailing: @Composable () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf(false) }
    if (addToPlaylist) AddToPlaylistDialog(listOf(track.id)) { addToPlaylist = false }
    val favourites = LocalFavourites.current
    val downloads = LocalDownloads.current
    val navigation = LocalNavigation.current
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
            ArtistAlbumText(track.displayArtist, listOf(track.artist, track.albumArtist), track.album, track.albumKey, links = false)
        }
        badge?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp))
        }
        if (track.id in favourites.ids) {
            Icon(Icons.Default.Favorite, "Favourite", Modifier.padding(end = 6.dp).size(16.dp), tint = FAVOURITE_RED)
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
                DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menu = false; addToPlaylist = true })
                onRemoveFromPlaylist?.let { remove ->
                    DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { menu = false; remove() })
                }
                DropdownMenuItem(
                    text = { Text("Go to Artist") },
                    onClick = { menu = false; navigation.openArtist(listOf(track.artist, track.albumArtist)) },
                )
                if (track.album != null) {
                    DropdownMenuItem(text = { Text("Go to Album") }, onClick = { menu = false; navigation.openAlbum(track.albumKey) })
                }
                if (track.source == Track.SOURCE_DRIVE) when (track.downloadState) {
                    Track.DOWNLOADED -> DropdownMenuItem(text = { Text("Remove download") }, onClick = { menu = false; downloads.remove(track) })
                    Track.DOWNLOAD_QUEUED -> Unit
                    else -> DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; downloads.download(listOf(track)) })
                }
            }
        }
        trailing()
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
