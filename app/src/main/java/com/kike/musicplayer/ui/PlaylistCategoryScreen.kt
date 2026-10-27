package com.kike.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.kike.musicplayer.R
import com.kike.musicplayer.data.catalog.CatalogPlaylist
import com.kike.musicplayer.data.db.SavedCatalogPlaylist
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.mosaicCovers



enum class PlaylistCategoryType(@StringRes val titleRes: Int) {
    PERSONAL(R.string.playlist_category_personal),
    APPLE_MUSIC(R.string.playlist_category_apple_music),
    DEEZER(R.string.playlist_category_deezer),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistCategoryScreen(
    type: PlaylistCategoryType,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenPlaylist: (Long) -> Unit,
    onOpenCatalogPlaylist: (CatalogPlaylist) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val title = stringResource(type.titleRes)

    val userPlaylists by libraryVm.userPlaylists.collectAsState()
    val savedCatalogPlaylists by libraryVm.savedCatalogPlaylists.collectAsState()
    val downloads = LocalDownloads.current

    var renaming by remember { mutableStateOf<PlaylistView?>(null) }
    renaming?.let { p ->
        RenamePlaylistDialog(p.name, onDismiss = { renaming = null }) { libraryVm.renamePlaylist(p.id, it) }
    }

    var deleting by remember { mutableStateOf<PlaylistView?>(null) }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("The playlist is deleted; its songs stay in your library.") },
            confirmButton = {
                TextButton(onClick = { libraryVm.deletePlaylist(p.id); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )

        when (type) {
            PlaylistCategoryType.PERSONAL -> {
                val sorted = remember(userPlaylists) {
                    userPlaylists.sortedWith(
                        compareByDescending<PlaylistView> { it.lastPlayedAt }
                            .thenByDescending { it.createdAt }
                    )
                }

                if (sorted.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No personal playlists created yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(sorted, key = { "pl:" + it.id }) { playlist ->
                            Column(Modifier.clickable { onOpenPlaylist(playlist.id) }) {
                                CoverMosaic(playlist.tracks, Modifier.fillMaxWidth().aspectRatio(1f))
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        playlist.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Box {
                                        var menu by remember { mutableStateOf(false) }
                                        IconButton(onClick = { menu = true }) {
                                            Icon(Icons.Default.MoreVert, "More")
                                        }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            if (playlist.tracks.any { it.source == Track.SOURCE_DRIVE }) {
                                                DropdownMenuItem(
                                                    text = { Text("Download") },
                                                    onClick = { menu = false; downloads.download(playlist.tracks) },
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Rename") },
                                                onClick = { menu = false; renaming = playlist },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete") },
                                                onClick = { menu = false; deleting = playlist },
                                            )
                                        }
                                    }
                                }
                                Text(
                                    count(playlist.tracks.size, "song"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            PlaylistCategoryType.APPLE_MUSIC, PlaylistCategoryType.DEEZER -> {
                val isApple = type == PlaylistCategoryType.APPLE_MUSIC
                val sorted = remember(savedCatalogPlaylists, isApple) {
                    savedCatalogPlaylists.filter {
                        if (isApple) {
                            it.source == CatalogPlaylist.SOURCE_APPLE || it.id.startsWith("ap:")
                        } else {
                            it.source == CatalogPlaylist.SOURCE_DEEZER || it.id.startsWith("dz:")
                        }
                    }.sortedWith(
                        compareByDescending<SavedCatalogPlaylist> { it.lastPlayedAt }
                            .thenByDescending { it.addedAt }
                    )
                }

                if (sorted.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (isApple) "No Apple Music playlists imported yet." else "No Deezer playlists saved yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(sorted, key = { "cat-pl:" + it.id }) { playlist ->
                            val mosaic = remember(playlist.id, playlist.cachedTracksJson) { playlist.mosaicCovers() }
                            val catPl = remember(playlist, mosaic) {
                                CatalogPlaylist(
                                    id = playlist.id,
                                    title = playlist.title,
                                    creator = playlist.creator,
                                    trackCount = playlist.trackCount,
                                    coverUrl = playlist.coverUrl,
                                    source = playlist.source,
                                    mosaicCovers = mosaic,
                                )
                            }
                            Column(Modifier.clickable { onOpenCatalogPlaylist(catPl) }) {
                                PlaylistCover(playlist.coverUrl, mosaic, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(12.dp))
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        playlist.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Box {
                                        var menu by remember { mutableStateOf(false) }
                                        IconButton(onClick = { menu = true }) {
                                            Icon(Icons.Default.MoreVert, "More")
                                        }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(
                                                text = { Text("Remove from saved") },
                                                onClick = { menu = false; libraryVm.removeSavedCatalogPlaylist(playlist.id) },
                                            )
                                        }
                                    }
                                }
                                val sub = buildList {
                                    playlist.creator?.takeIf { it.isNotBlank() }?.let { add("by $it") }
                                    add(count(playlist.trackCount, "song"))
                                    if (playlist.matchedCount > 0) add("${playlist.matchedCount} in library")
                                }.joinToString(" · ")
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
