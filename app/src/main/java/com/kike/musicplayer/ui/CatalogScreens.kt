package com.kike.musicplayer.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kike.musicplayer.data.nebula.NebulaUrls
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.kike.musicplayer.data.catalog.CatalogAlbum
import com.kike.musicplayer.data.catalog.CatalogArtist
import com.kike.musicplayer.data.catalog.CatalogPlaylist
import com.kike.musicplayer.data.catalog.CatalogTrack
import com.kike.musicplayer.data.db.Track
import kotlinx.coroutines.launch

/**
 * Detail view for a Deezer or Apple Music catalog album. Songs present in the user's library
 * (Local, Google Drive or Navidrome, preferring own copy) can be played, queued or favourited;
 * missing songs are listed for reference with a "Not in library" badge and never streamed from
 * the catalog provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogAlbumScreen(
    album: CatalogAlbum,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenArtist: (String) -> Unit,
    onOpenCatalogArtist: (CatalogArtist) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val player by playerVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val cached = remember(album.id) { libraryVm.cachedCatalogAlbumTracks(album.id) }
    var loading by remember(album.id) { mutableStateOf(cached == null) }
    var tracks by remember(album.id) { mutableStateOf(cached?.first ?: emptyList()) }
    var matched by remember(album.id) { mutableStateOf(cached?.second ?: emptyMap()) }

    LaunchedEffect(album.id) {
        if (cached == null) {
            loading = true
        val (list, map) = libraryVm.catalogAlbumTracks(album)
        tracks = list
        matched = map
        loading = false
        }
    }

    val ownedTracks = remember(tracks, matched) { tracks.mapNotNull { matched[it.id] } }
    val nebulaState by libraryVm.nebulaState.collectAsState()
    val nebulaConnected = nebulaState.manifestUrl != null
    val playableTracks = remember(tracks, matched, nebulaConnected) {
        tracks.mapNotNull { catTrack ->
            matched[catTrack.id] ?: if (nebulaConnected) NebulaUrls.trackFor(catTrack) else null
        }
    }
    val year = album.year ?: tracks.firstNotNullOfOrNull { it.year }
    val genre = album.genre ?: tracks.firstNotNullOfOrNull { it.genre }
    val cover = album.coverUrl ?: tracks.firstNotNullOfOrNull { it.coverUrl }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            windowInsets = NoTopInsets,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(cover, size = 180.dp, shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        album.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable {
                                scope.launch {
                                    val libArtist = libraryVm.firstArtist(listOf(album.artist))
                                    if (libArtist != null) {
                                        onOpenArtist(libArtist)
                                    } else {
                                        onOpenCatalogArtist(
                                            CatalogArtist(
                                                id = "search:${album.artist}",
                                                name = album.artist,
                                                pictureUrl = null,
                                                source = album.source,
                                            )
                                        )
                                    }
                                }
                            },
                    )
                    val metaLine = buildList {
                        year?.let { add(it.toString()) }
                        genre?.let { add(it) }
                        add(if (album.source == CatalogPlaylist.SOURCE_APPLE) "Apple Music catalog" else "Deezer catalog")
                    }.joinToString(" · ")
                    Text(
                        metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!loading && tracks.isNotEmpty()) {
                        val statusText = if (nebulaConnected && ownedTracks.size < tracks.size) {
                            "${ownedTracks.size} in library · ${tracks.size - ownedTracks.size} via Nebula"
                        } else {
                            "${ownedTracks.size} of ${tracks.size} songs in your library"
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (playableTracks.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (playableTracks.isNotEmpty()) {
                item {
                    PlayShuffleRow(
                        onPlay = { playerVm.playTracks(playableTracks, 0) },
                        onShuffle = { playerVm.playTracks(playableTracks, 0, shuffle = true) },
                        enabled = true,
                        downloads = ownedTracks,
                    )
                }
            }
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (tracks.isEmpty()) {
                item {
                    Text(
                        "Couldn't load tracklist from catalog.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(tracks, key = { _, t -> t.id }) { idx, catTrack ->
                    val libTrack = matched[catTrack.id]
                    val num = (catTrack.trackNumber ?: (idx + 1)).toString()
                    if (libTrack != null) {
                        val playIdx = playableTracks.indexOfFirst { it.id == libTrack.id }.coerceAtLeast(0)
                        TrackRow(
                            track = libTrack,
                            isCurrent = libTrack.id == player.current?.mediaId,
                            onClick = { playerVm.playTracks(playableTracks, playIdx) },
                            onPlayNext = { playerVm.playNext(libTrack) },
                            onAddToQueue = { playerVm.addToQueue(libTrack) },
                            showArtwork = false,
                            leading = num,
                        )
                    } else if (nebulaConnected) {
                        val nebTrack = remember(catTrack) { NebulaUrls.trackFor(catTrack) }
                        val playIdx = playableTracks.indexOfFirst { it.id == nebTrack.id }.coerceAtLeast(0)
                        TrackRow(
                            track = nebTrack,
                            isCurrent = nebTrack.id == player.current?.mediaId,
                            onClick = { playerVm.playTracks(playableTracks, playIdx) },
                            onPlayNext = { playerVm.playNext(nebTrack) },
                            onAddToQueue = { playerVm.addToQueue(nebTrack) },
                            showArtwork = false,
                            leading = num,
                        )
                    } else {
                        UnownedCatalogTrackRow(
                            track = catTrack,
                            leading = num,
                            showArtwork = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Detail view for an artist from the Deezer / Apple Music catalog. Allows following the artist for
 * Discover updates, playing any top songs that exist in the user's library, and browsing their
 * full album discography.
 */
@Composable
fun CatalogArtistScreen(
    artist: CatalogArtist,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenCatalogAlbum: (CatalogAlbum) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val player by playerVm.state.collectAsState()
    val followed by libraryVm.followedArtists.collectAsState()
    val scope = rememberCoroutineScope()
    val image by produceState(artist.pictureUrl, artist.name) {
        if (value == null) value = libraryVm.artistImage(artist.name)
    }

    var loading by remember(artist.id, artist.name) { mutableStateOf(true) }
    var topTracks by remember(artist.id, artist.name) { mutableStateOf<List<CatalogTrack>>(emptyList()) }
    var matchedTop by remember(artist.id, artist.name) { mutableStateOf<Map<String, Track>>(emptyMap()) }
    var albums by remember(artist.id, artist.name) { mutableStateOf<List<CatalogAlbum>>(emptyList()) }

    LaunchedEffect(artist.id, artist.name) {
        loading = true
        val (top, matched, discography) = libraryVm.catalogArtistOverview(artist)
        topTracks = top
        matchedTop = matched
        albums = discography
        loading = false
    }

    val ownedTop = remember(topTracks, matchedTop) { topTracks.mapNotNull { matchedTop[it.id] } }
    val nebulaState by libraryVm.nebulaState.collectAsState()
    val nebulaConnected = nebulaState.manifestUrl != null
    val playableTop = remember(topTracks, matchedTop, nebulaConnected) {
        topTracks.mapNotNull { catTrack ->
            matchedTop[catTrack.id] ?: if (nebulaConnected) NebulaUrls.trackFor(catTrack) else null
        }
    }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val isFollowed = artist.name in followed

    Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding())) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            item { CatalogArtistHeader(artist.name, image) }
            item {
                Text(
                    buildList {
                        if (albums.isNotEmpty()) add("${albums.size} albums in catalog")
                        if (ownedTop.isNotEmpty()) add("${ownedTop.size} top songs in library")
                        else add("Deezer & Apple Music catalog")
                    }.joinToString(" · "),
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (playableTop.isNotEmpty()) {
                        FilledIconButton(onClick = { playerVm.playTracks(playableTop, 0) }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.PlayArrow, "Play", Modifier.size(32.dp))
                        }
                        FilledTonalIconButton(onClick = { playerVm.playTracks(playableTop, 0, shuffle = true) }) {
                            Icon(Icons.Default.Shuffle, "Shuffle")
                        }
                    }
                    if (isFollowed) {
                        FilledTonalButton(
                            onClick = { libraryVm.unfollow(artist.name) },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(Icons.Default.Check, null, Modifier.size(ButtonDefaults.IconSize))
                            Text("Following", Modifier.padding(start = 6.dp))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { libraryVm.follow(artist.name) },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(ButtonDefaults.IconSize))
                            Text("Follow", Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionTitle("Discography") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(albums, key = { it.id }) { album ->
                            Column(
                                Modifier.width(140.dp).clickable {
                                    scope.launch {
                                        val libKey = libraryVm.albumInLibrary(album.artist, album.title)
                                        if (libKey != null) onOpenAlbum(libKey) else onOpenCatalogAlbum(album)
                                    }
                                }
                            ) {
                                Artwork(album.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(10.dp))
                                Text(
                                    album.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                album.year?.let {
                                    Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (topTracks.isNotEmpty()) {
                item { SectionTitle("Top songs") }
                itemsIndexed(topTracks, key = { _, t -> t.id }) { index, catTrack ->
                    val libTrack = matchedTop[catTrack.id]
                    val num = "${index + 1}"
                    if (libTrack != null) {
                        val playIdx = playableTop.indexOfFirst { it.id == libTrack.id }.coerceAtLeast(0)
                        TrackRow(
                            track = libTrack,
                            isCurrent = libTrack.id == player.current?.mediaId,
                            onClick = { playerVm.playTracks(playableTop, playIdx) },
                            onPlayNext = { playerVm.playNext(libTrack) },
                            onAddToQueue = { playerVm.addToQueue(libTrack) },
                            leading = num,
                        )
                    } else if (nebulaConnected) {
                        val nebTrack = remember(catTrack) { NebulaUrls.trackFor(catTrack) }
                        val playIdx = playableTop.indexOfFirst { it.id == nebTrack.id }.coerceAtLeast(0)
                        TrackRow(
                            track = nebTrack,
                            isCurrent = nebTrack.id == player.current?.mediaId,
                            onClick = { playerVm.playTracks(playableTop, playIdx) },
                            onPlayNext = { playerVm.playNext(nebTrack) },
                            onAddToQueue = { playerVm.addToQueue(nebTrack) },
                            leading = num,
                        )
                    } else {
                        UnownedCatalogTrackRow(
                            track = catTrack,
                            leading = num,
                            showArtwork = false,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .then(if (scrolled) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
                .statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (scrolled) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            if (scrolled) {
                Text(
                    artist.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Detail view for a public Deezer playlist. Matches every song against the user's library (Local,
 * Google Drive, Navidrome) so the user can play the songs they own or save the matched playlist
 * into their Playlists tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogPlaylistScreen(
    playlist: CatalogPlaylist,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val player by playerVm.state.collectAsState()
    val savedPlaylists by libraryVm.savedCatalogPlaylists.collectAsState()
    val isSaved = savedPlaylists.any { it.id == playlist.id }
    var loading by remember(playlist.id) { mutableStateOf(!isSaved) }
    var tracks by remember(playlist.id) { mutableStateOf<List<CatalogTrack>>(emptyList()) }
    var matched by remember(playlist.id) { mutableStateOf<Map<String, Track>>(emptyMap()) }
    var saveDialog by remember { mutableStateOf(false) }
    var savedBanner by remember { mutableStateOf(false) }

    LaunchedEffect(playlist.id) {
        if (!isSaved) loading = true
        val (list, map) = libraryVm.catalogPlaylistTracks(playlist)
        tracks = list
        matched = map
        loading = false
    }

    val ownedTracks = remember(tracks, matched) { tracks.mapNotNull { matched[it.id] }.distinctBy { it.id } }
    val nebulaState by libraryVm.nebulaState.collectAsState()
    val nebulaConnected = nebulaState.manifestUrl != null
    val playableTracks = remember(tracks, matched, nebulaConnected) {
        tracks.mapNotNull { catTrack ->
            matched[catTrack.id] ?: if (nebulaConnected) NebulaUrls.trackFor(catTrack) else null
        }
    }

    if (saveDialog) {
        var name by remember { mutableStateOf(playlist.title) }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save to Playlists") },
            text = {
                Column {
                    Text(
                        "${ownedTracks.size} of ${tracks.size} songs matched in your library will be added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryVm.createPlaylist(name, ownedTracks.map { it.id })
                        saveDialog = false
                        savedBanner = true
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } },
        )
    }

    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(
                    onClick = {
                        if (!refreshing) {
                            refreshing = true
                            scope.launch {
                                val (list, map) = libraryVm.catalogPlaylistTracks(playlist, forceRefresh = true)
                                tracks = list
                                matched = map
                                refreshing = false
                            }
                        }
                    },
                    enabled = !refreshing,
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, "Refresh tracklist")
                    }
                }
            },
            windowInsets = NoTopInsets,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val mosaic = remember(playlist.coverUrl, playlist.mosaicCovers, tracks) {
                        if (!playlist.coverUrl.isNullOrBlank() && !com.kike.musicplayer.data.catalog.isDefaultCatalogCover(playlist.coverUrl)) emptyList()
                        else if (playlist.mosaicCovers.isNotEmpty()) playlist.mosaicCovers
                        else tracks.mapNotNull { it.coverUrl }.filter { it.isNotBlank() }.distinct().take(4)
                    }
                    PlaylistCover(playlist.coverUrl, mosaic, size = 160.dp, shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        playlist.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    val sub = buildList {
                        playlist.creator?.takeIf { it.isNotBlank() }?.let { add("by $it") }
                        val platform = if (playlist.source == CatalogPlaylist.SOURCE_APPLE || playlist.id.startsWith("ap:")) "Apple Music playlist" else "Deezer public playlist"
                        add(platform)
                    }.joinToString(" · ")
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (!loading && tracks.isNotEmpty()) {
                        val statusText = if (nebulaConnected && ownedTracks.size < tracks.size) {
                            "${ownedTracks.size} in library · ${tracks.size - ownedTracks.size} via Nebula"
                        } else {
                            "${ownedTracks.size} of ${tracks.size} songs in your library"
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (playableTracks.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (isSaved) {
                        val categoryName = if (playlist.source == CatalogPlaylist.SOURCE_APPLE || playlist.id.startsWith("ap:")) "Apple Music" else "Deezer"
                        Text(
                            "Saved in $categoryName",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            libraryVm.recordCatalogPlaylistPlayed(playlist.id)
                            playerVm.playTracks(playableTracks, 0)
                        },
                        enabled = playableTracks.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }
                    FilledTonalButton(
                        onClick = {
                            libraryVm.recordCatalogPlaylistPlayed(playlist.id)
                            playerVm.playTracks(playableTracks, 0, shuffle = true)
                        },
                        enabled = playableTracks.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Shuffle, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Shuffle")
                    }
                    FilledTonalIconButton(
                        onClick = {
                            if (isSaved) {
                                libraryVm.removeSavedCatalogPlaylist(playlist.id)
                            } else {
                                libraryVm.saveCatalogPlaylist(playlist, tracks, matched)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (isSaved) Icons.Default.Bookmark else Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = if (isSaved) {
                                val catName = if (playlist.source == CatalogPlaylist.SOURCE_APPLE || playlist.id.startsWith("ap:")) "Apple Music" else "Deezer"
                                "Remove from $catName Playlists"
                            } else {
                                val catName = if (playlist.source == CatalogPlaylist.SOURCE_APPLE || playlist.id.startsWith("ap:")) "Apple Music" else "Deezer"
                                "Save to $catName Playlists"
                            },
                            tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (loading && !isSaved) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                itemsIndexed(tracks, key = { idx, t -> "${t.id}:$idx" }) { idx, catTrack ->
                    val libTrack = matched[catTrack.id]
                    if (libTrack != null) {
                        val playIdx = playableTracks.indexOfFirst { it.id == libTrack.id }.coerceAtLeast(0)
                        TrackRow(
                            track = libTrack,
                            isCurrent = libTrack.id == player.current?.mediaId,
                            onClick = {
                                libraryVm.recordCatalogPlaylistPlayed(playlist.id)
                                playerVm.playTracks(playableTracks, playIdx)
                            },
                            onPlayNext = { playerVm.playNext(libTrack) },
                            onAddToQueue = { playerVm.addToQueue(libTrack) },
                        )
                    } else if (nebulaConnected) {
                        val nebTrack = remember(catTrack) { NebulaUrls.trackFor(catTrack) }
                        val playIdx = playableTracks.indexOfFirst { it.id == nebTrack.id }.coerceAtLeast(0)
                        TrackRow(
                            track = nebTrack,
                            isCurrent = nebTrack.id == player.current?.mediaId,
                            onClick = {
                                libraryVm.recordCatalogPlaylistPlayed(playlist.id)
                                playerVm.playTracks(playableTracks, playIdx)
                            },
                            onPlayNext = { playerVm.playNext(nebTrack) },
                            onAddToQueue = { playerVm.addToQueue(nebTrack) },
                        )
                    } else {
                        UnownedCatalogTrackRow(
                            track = catTrack,
                            leading = null,
                            showArtwork = true,
                        )
                    }
                }
            }
        }
    }
}

/** A catalog song that is not in the user's library: dimmed, informative only, never streamed. */
@Composable
internal fun UnownedCatalogTrackRow(
    track: CatalogTrack,
    leading: String? = null,
    showArtwork: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(
                leading,
                Modifier.width(32.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (showArtwork) {
            Artwork(track.coverUrl, size = 48.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                if (track.artist.isNotBlank()) add(track.artist)
                if (track.album.isNotBlank()) add(track.album)
                if (track.durationMs > 0) add(formatTime(track.durationMs))
            }.joinToString(" · ")
            Text(
                sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Not in library",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun CatalogArtistHeader(name: String, image: String?) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.BottomCenter) {
        SubcomposeAsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, Modifier.size(96.dp).padding(bottom = 48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            error = {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, Modifier.size(96.dp).padding(bottom = 48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to background)
            )
        )
        Text(
            name,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
