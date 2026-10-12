package com.kike.musicplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Person
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.FilterChip
import com.kike.musicplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
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
    onOpenReplay: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenFavourites: () -> Unit,
    onNewPlaylist: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenMix: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val library by libraryVm.library.collectAsState()
    val playlists by libraryVm.playlists.collectAsState()
    val genres by libraryVm.genres.collectAsState()
    val results by libraryVm.searchResults.collectAsState()
    val query by libraryVm.query.collectAsState()
    val scanStatus by libraryVm.scanStatus.collectAsState()
    val progress by libraryVm.metadataProgress.collectAsState()
    val player by playerVm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Search filters; none selected means all three kinds.
    var filters by rememberSaveable { mutableStateOf(setOf<String>()) }
    // With a search typed, Back clears it instead of leaving the app.
    BackHandler(enabled = query.isNotEmpty()) { libraryVm.setQuery("") }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.app_name)) },
            actions = { IconButton(onClick = onOpenSources) { Icon(Icons.Default.Tune, "Sources & settings") } },
        )
        OutlinedTextField(
            value = query,
            onValueChange = libraryVm::setQuery,
            placeholder = { Text("Search artists, albums, songs") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { libraryVm.setQuery("") }) { Icon(Icons.Default.Clear, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (query.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SEARCH_ARTISTS, SEARCH_ALBUMS, SEARCH_SONGS).forEach { kind ->
                    FilterChip(
                        selected = kind in filters,
                        onClick = { filters = if (kind in filters) filters - kind else filters + kind },
                        label = { Text(kind) },
                    )
                }
            }
        }
        val busy = scanStatus ?: progress?.let { "${it.stage} ${it.done}/${it.total}" }
        if (busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            Text(busy, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
        if (query.isNotEmpty()) {
            SearchResultsList(results, filters, player.current?.mediaId, playerVm, onOpenArtist, onOpenAlbum, libraryVm.artistImageUrls.collectAsState().value)
            return@Column
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            LIBRARY_TABS.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        if (library.tracks.isEmpty() && scanStatus == null) {
            EmptyLibrary(onOpenSources)
            return@Column
        }
        when (LIBRARY_TABS[tab]) {
            "Home" -> HomeTab(libraryVm, playerVm, onOpenReplay, onOpenMostPlayed, onOpenFavourites, onOpenRecentTracks, onOpenMix, onOpenAlbum)
            "Albums" -> AlbumsGrid(library.albums, onOpenAlbum)
            "Artists" -> {
                val photos by libraryVm.artistImageUrls.collectAsState()
                LaunchedEffect(library.artists.size) { libraryVm.prefetchArtistImages() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(library.artists, key = { it.name }) { artist ->
                        ArtistRow(artist, photos[artist.name.lowercase()]) { onOpenArtist(artist.name) }
                    }
                }
            }
            "Genres" -> GenresTab(genres, onOpenGenre)
            "Songs" -> SongsTab(library.tracks, player.current?.mediaId, playerVm)
            "Playlists" -> PlaylistsTab(
                playlists, libraryVm.userPlaylists.collectAsState().value, playerVm,
                onOpenRecentTracks, onOpenRecentAlbums, onOpenMostPlayed, onOpenFavourites, onNewPlaylist, onOpenPlaylist,
                libraryVm::deletePlaylist, libraryVm::renamePlaylist,
            )
        }
    }
}

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private val LIBRARY_TABS = listOf("Home", "Albums", "Artists", "Genres", "Playlists", "Songs")
private const val SEARCH_ARTISTS = "Artists"
private const val SEARCH_ALBUMS = "Albums"
private const val SEARCH_SONGS = "Songs"

@Composable
private fun ArtistRow(artist: Artist, photo: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistPhoto(photo, Modifier.size(52.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${count(artist.albumCount, "album")} · ${count(artist.tracks.size, "song")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A round artist photo, or a person icon while there is none. */
@Composable
internal fun ArtistPhoto(url: String?, modifier: Modifier = Modifier) {
    val placeholder = @Composable {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier.clip(CircleShape),
        loading = { placeholder() },
        error = { placeholder() },
    )
}

/** Search results by kind; the chips narrow them down. */
@Composable
private fun SearchResultsList(
    results: SearchResults,
    filters: Set<String>,
    currentId: String?,
    playerVm: PlayerViewModel,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    photos: Map<String, String>,
) {
    fun show(kind: String) = filters.isEmpty() || kind in filters
    val artists = if (show(SEARCH_ARTISTS)) results.artists else emptyList()
    val albums = if (show(SEARCH_ALBUMS)) results.albums else emptyList()
    val songs = if (show(SEARCH_SONGS)) results.songs else emptyList()
    if (artists.isEmpty() && albums.isEmpty() && songs.isEmpty()) {
        Text("No matches", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (artists.isNotEmpty()) {
            item { ResultsHeader("Artists") }
            items(artists, key = { "ar:" + it.name }) { artist -> ArtistRow(artist, photos[artist.name.lowercase()]) { onOpenArtist(artist.name) } }
        }
        if (albums.isNotEmpty()) {
            item { ResultsHeader("Albums") }
            items(albums, key = { "al:" + it.key }) { album ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenAlbum(album.key) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(album.artworkUri, size = 48.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ArtistAlbumText(album.artist, listOf(album.artist))
                    }
                }
            }
        }
        if (songs.isNotEmpty()) {
            item { ResultsHeader("Songs") }
            itemsIndexed(songs, key = { _, t -> "so:" + t.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == currentId,
                    onClick = { playerVm.playTracks(songs, index) },
                    onPlayNext = { playerVm.playNext(track) },
                    onAddToQueue = { playerVm.addToQueue(track) },
                )
            }
        }
    }
}

@Composable
private fun ResultsHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Genres A–Z with their size; tap for the genre's albums. */
@Composable
private fun GenresTab(genres: List<Genre>, onOpenGenre: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(genres, key = { it.key }) { genre ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenGenre(genre.key) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(genre.albums.firstNotNullOfOrNull { it.artworkUri }, size = 52.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(genre.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(count(genre.artistCount, "artist"), count(genre.albums.size, "album"), count(genre.songCount, "song"))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
private fun SongsTab(tracks: List<Track>, currentId: String?, playerVm: PlayerViewModel) {
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
                isCurrent = track.id == currentId,
                onClick = { playerVm.playTracks(tracks, index) },
                onPlayNext = { playerVm.playNext(track) },
                onAddToQueue = { playerVm.addToQueue(track) },
            )
        }
    }
}

@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean, downloads: List<Track>? = null) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onPlay, enabled = enabled) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play")
        }
        FilledTonalButton(onClick = onShuffle, enabled = enabled) {
            Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Shuffle")
        }
        downloads?.let { DownloadButton(it) }
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
                ArtistAlbumText(album.artist, listOf(album.artist))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    album: Album?,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    /** Shown under the album info, e.g. when only some of its songs are listed. */
    note: String? = null,
) {
    val player by playerVm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
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
                        ArtistAlbumText(album.artist, listOf(album.artist), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(album.year?.toString(), count(album.tracks.size, "song"),
                                formatTime(album.tracks.sumOf { it.durationMs })).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        note?.let {
                            Text(
                                it,
                                Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            item {
                PlayShuffleRow(
                    onPlay = { playerVm.playTracks(album.tracks, 0) },
                    onShuffle = { playerVm.playTracks(album.tracks, 0, shuffle = true) },
                    enabled = true,
                    downloads = album.tracks,
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

/** A titled list of songs with Play / Shuffle: a playlist or mix. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    title: String,
    tracks: List<Track>?,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    badge: (Track) -> String? = { null },
) {
    val player by playerVm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
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
                    downloads = tracks,
                )
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == player.current?.mediaId,
                    onClick = { playerVm.playTracks(tracks, index) },
                    onPlayNext = { playerVm.playNext(track) },
                    onAddToQueue = { playerVm.addToQueue(track) },
                    badge = badge(track),
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
    /** Offer downloading every song shown (playlists, not whole genres). */
    downloadable: Boolean = false,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        val all = albums.flatMap { it.tracks }
        PlayShuffleRow(
            onPlay = { playerVm.playTracks(all, 0) },
            onShuffle = { playerVm.playTracks(all, 0, shuffle = true) },
            enabled = all.isNotEmpty(),
            downloads = all.takeIf { downloadable },
        )
        AlbumsGrid(albums, onOpenAlbum)
    }
}

@Composable
private fun PlaylistsTab(
    playlists: Playlists,
    userPlaylists: List<PlaylistView>,
    playerVm: PlayerViewModel,
    onOpenRecentTracks: () -> Unit,
    onOpenRecentAlbums: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenFavourites: () -> Unit,
    onNewPlaylist: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
) {
    var renaming by remember { mutableStateOf<PlaylistView?>(null) }
    renaming?.let { p -> RenamePlaylistDialog(p.name, onDismiss = { renaming = null }) { onRenamePlaylist(p.id, it) } }
    var deleting by remember { mutableStateOf<PlaylistView?>(null) }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("The playlist is deleted; its songs stay in your library.") },
            confirmButton = { TextButton(onClick = { onDeletePlaylist(p.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    val downloads = LocalDownloads.current
    val recent = playlists.recentTracks
    val albums = playlists.recentAlbums.flatMap { it.tracks }
    val most = playlists.mostPlayed.map { it.item }
    val favourites = playlists.favourites
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)) {
            // The automatic playlists, as cards like the Home mixes.
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val card = Modifier.width(160.dp)
                    CollectionCard("Recently Played", if (recent.isEmpty()) "Nothing played yet" else count(recent.size, "song"),
                        recent, onOpenRecentTracks, { playerVm.playTracks(recent, 0) }, card)
                    CollectionCard("Recently Played Albums",
                        if (albums.isEmpty()) "Nothing played yet" else count(playlists.recentAlbums.size, "album"),
                        albums, onOpenRecentAlbums, { playerVm.playTracks(albums, 0) }, card)
                    CollectionCard("Most Played", if (most.isEmpty()) "Nothing played yet" else count(most.size, "song"),
                        most, onOpenMostPlayed, { playerVm.playTracks(most, 0) }, card)
                    CollectionCard("Favourite Songs", if (favourites.isEmpty()) "Tap the heart on a song" else count(favourites.size, "song"),
                        favourites, onOpenFavourites, { playerVm.playTracks(favourites, 0) }, card)
                }
            }
            item { SectionTitle("Your playlists") }
            if (userPlaylists.isEmpty()) {
                item {
                    Text(
                        "Tap + to make a playlist.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(userPlaylists, key = { "pl:" + it.id }) { playlist ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenPlaylist(playlist.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverMosaic(playlist.tracks, Modifier.size(72.dp))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(count(playlist.tracks.size, "song"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (playlist.tracks.any { it.source == Track.SOURCE_DRIVE }) {
                                DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; downloads.download(playlist.tracks) })
                            }
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = playlist })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = playlist })
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onNewPlaylist,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, "New playlist")
        }
    }
}

/**
 * A playlist tile: a strip of covers fading from 30% transparency on the left to 80% on the right,
 * with the title on a dark panel in the bottom-left corner and a play button bottom-right.
 */
@Composable
internal fun PlaylistTile(title: String, subtitle: String, covers: List<String>, onOpen: () -> Unit, onPlay: (() -> Unit)?) {
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
            // Title on a grey panel (25% transparent) in the bottom-left corner: readable over any cover.
            Column(
                Modifier.align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(TilePanel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
            if (onPlay != null) {
                FilledIconButton(onClick = onPlay, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    Icon(Icons.Default.PlayArrow, "Play $title")
                }
            }
        }
    }
}

/** Mid-dark grey at 75% opacity (25% transparent). */
private val TilePanel = Color(0xFF4A4A4A).copy(alpha = 0.75f)

private const val TILE_COVERS = 4
