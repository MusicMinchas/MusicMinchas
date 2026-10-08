package com.kike.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.data.db.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenSources: () -> Unit,
    onOpenRecentTracks: () -> Unit,
    onOpenRecentAlbums: () -> Unit,
    contentPadding: PaddingValues,
) {
    val library by libraryVm.library.collectAsState()
    val playlists by libraryVm.playlists.collectAsState()
    val results by libraryVm.searchResults.collectAsState()
    val query by libraryVm.query.collectAsState()
    val scanStatus by libraryVm.scanStatus.collectAsState()
    val progress by libraryVm.metadataProgress.collectAsState()
    val player by playerVm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text("Music") },
            actions = { IconButton(onClick = onOpenSources) { Icon(Icons.Default.Tune, "Sources & settings") } },
        )
        val busy = scanStatus ?: progress?.let { "${it.stage} ${it.done}/${it.total}" }
        if (busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(busy, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Songs", "Albums", "Artists", "Playlists").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        if (library.tracks.isEmpty() && scanStatus == null) {
            EmptyLibrary(onOpenSources)
            return@Column
        }
        when (tab) {
            0 -> SongsTab(results, query, libraryVm::setQuery, player.current?.mediaId, playerVm)
            1 -> AlbumsGrid(library.albums, onOpenAlbum)
            2 -> LazyColumn(Modifier.fillMaxSize()) {
                items(library.artists, key = { it.name }) { artist ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenArtist(artist.name) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${artist.albumCount} albums · ${artist.tracks.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            3 -> PlaylistsTab(playlists, playerVm, onOpenRecentTracks, onOpenRecentAlbums)
        }
    }
}

@Composable
private fun EmptyLibrary(onOpenSources: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No music yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick the folders on this device or in Google Drive that hold your music.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSources) { Text("Add music sources") }
    }
}

@Composable
private fun SongsTab(
    tracks: List<Track>,
    query: String,
    onQuery: (String) -> Unit,
    currentId: String?,
    playerVm: PlayerViewModel,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search songs, artists, albums") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "Clear") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            PlayShuffleRow(
                onPlay = { playerVm.playTracks(tracks, 0) },
                onShuffle = { playerVm.playTracks(tracks, 0, shuffle = true) },
                enabled = tracks.isNotEmpty(),
            )
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                isCurrent = track.id == currentId,
                onClick = { playerVm.playTracks(tracks, index) },
                onPlayNext = { playerVm.playNext(track) },
                onAddToQueue = { playerVm.addToQueue(track) },
            )
        }
    }
}

@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlay, enabled = enabled) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play")
        }
        FilledTonalButton(onClick = onShuffle, enabled = enabled) {
            Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Shuffle")
        }
    }
}

@Composable
internal fun AlbumsGrid(albums: List<Album>, onOpenAlbum: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.key }) { album ->
            Column(Modifier.clickable { onOpenAlbum(album.key) }) {
                Artwork(album.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(
                    album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(album: Album?, playerVm: PlayerViewModel, onBack: () -> Unit, contentPadding: PaddingValues) {
    val player by playerVm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(album?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (album == null) return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(album.artworkUri, size = 140.dp)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(album.title, style = MaterialTheme.typography.titleLarge)
                        Text(album.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            listOfNotNull(album.year?.toString(), "${album.tracks.size} songs",
                                formatTime(album.tracks.sumOf { it.durationMs })).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                PlayShuffleRow(
                    onPlay = { playerVm.playTracks(album.tracks, 0) },
                    onShuffle = { playerVm.playTracks(album.tracks, 0, shuffle = true) },
                    enabled = true,
                )
            }
            itemsIndexed(album.tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == player.current?.mediaId,
                    onClick = { playerVm.playTracks(album.tracks, index) },
                    onPlayNext = { playerVm.playNext(track) },
                    onAddToQueue = { playerVm.addToQueue(track) },
                    leading = track.trackNumber?.toString() ?: "–",
                )
            }
        }
    }
}

@Composable
fun ArtistScreen(artist: Artist?, playerVm: PlayerViewModel, onBack: () -> Unit, contentPadding: PaddingValues) =
    TrackListScreen(artist?.name ?: "", artist?.tracks, playerVm, onBack, contentPadding)

/** A titled list of songs with Play / Shuffle: an artist's songs, or the Recently Played playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    title: String,
    tracks: List<Track>?,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val player by playerVm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (tracks == null) return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                PlayShuffleRow(
                    onPlay = { playerVm.playTracks(tracks, 0) },
                    onShuffle = { playerVm.playTracks(tracks, 0, shuffle = true) },
                    enabled = tracks.isNotEmpty(),
                )
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == player.current?.mediaId,
                    onClick = { playerVm.playTracks(tracks, index) },
                    onPlayNext = { playerVm.playNext(track) },
                    onAddToQueue = { playerVm.addToQueue(track) },
                )
            }
        }
    }
}

/** Recently Played Albums: the albums as a grid; Play plays them all, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    title: String,
    albums: List<Album>,
    playerVm: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        val all = albums.flatMap { it.tracks }
        PlayShuffleRow(
            onPlay = { playerVm.playTracks(all, 0) },
            onShuffle = { playerVm.playTracks(all, 0, shuffle = true) },
            enabled = all.isNotEmpty(),
        )
        AlbumsGrid(albums, onOpenAlbum)
    }
}

@Composable
private fun PlaylistsTab(
    playlists: Playlists,
    playerVm: PlayerViewModel,
    onOpenRecentTracks: () -> Unit,
    onOpenRecentAlbums: () -> Unit,
) {
    val tracks = playlists.recentTracks
    val albums = playlists.recentAlbums
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlaylistTile(
            title = "Recently Played",
            subtitle = if (tracks.isEmpty()) "Nothing played yet" else "${tracks.size} songs",
            covers = tracks.mapNotNull { it.artworkUri }.distinct().take(TILE_COVERS),
            onOpen = onOpenRecentTracks,
            onPlay = if (tracks.isEmpty()) null else ({ playerVm.playTracks(tracks, 0) }),
        )
        PlaylistTile(
            title = "Recently Played Albums",
            subtitle = if (albums.isEmpty()) "Nothing played yet" else "${albums.size} albums",
            covers = albums.mapNotNull { it.artworkUri }.take(TILE_COVERS),
            onOpen = onOpenRecentAlbums,
            onPlay = if (albums.isEmpty()) null else ({ playerVm.playTracks(albums.flatMap { it.tracks }, 0) }),
        )
    }
}

/**
 * A playlist tile: a strip of covers fading from 30% transparency on the left to 80% on the right,
 * with the title on the right where the covers are faintest.
 */
@Composable
private fun PlaylistTile(title: String, subtitle: String, covers: List<String>, onOpen: () -> Unit, onPlay: (() -> Unit)?) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().height(128.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (covers.isNotEmpty()) {
                Row(
                    Modifier.fillMaxSize()
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.7f), 1f to Color.Black.copy(alpha = 0.2f)),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    covers.forEach { cover ->
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            Row(
                Modifier.align(Alignment.CenterEnd).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.End)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onPlay != null) {
                    Spacer(Modifier.width(12.dp))
                    FilledIconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play $title") }
                }
            }
        }
    }
}

private const val TILE_COVERS = 4
