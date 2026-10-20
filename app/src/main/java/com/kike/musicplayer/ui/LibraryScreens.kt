package com.kike.musicplayer.ui

import kotlinx.coroutines.flow.flowOf
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
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

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalFocusManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenSources: () -> Unit,
    onOpenMusicSources: () -> Unit,
    onOpenRecentTracks: () -> Unit,
    onOpenRecentAlbums: () -> Unit,
    onOpenAddedAlbums: () -> Unit,
    onOpenMixes: () -> Unit,
    onOpenReplay: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenFavourites: () -> Unit,
    onNewPlaylist: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenMix: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenCatalogArtist: (com.kike.musicplayer.data.catalog.CatalogArtist) -> Unit = {},
    onOpenCatalogAlbum: (com.kike.musicplayer.data.catalog.CatalogAlbum) -> Unit = {},
    onOpenCatalogPlaylist: (com.kike.musicplayer.data.catalog.CatalogPlaylist) -> Unit = {},
    contentPadding: PaddingValues,
) {
    val playlists by libraryVm.playlists.collectAsState()
    val results by libraryVm.searchResults.collectAsState()
    val catalogResults by libraryVm.catalogSearchResults.collectAsState()
    val query by libraryVm.query.collectAsState()
    val catalogEnabled by libraryVm.catalogEnabled.collectAsState()
    val catalogShown by libraryVm.catalogShown.collectAsState()
    val scanStatus by libraryVm.scanStatus.collectAsState()
    val progress by libraryVm.metadataProgress.collectAsState()
    val player by playerVm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabStates = rememberSaveableStateHolder()
    // Search filters; none selected means all three kinds.
    var filters by rememberSaveable { mutableStateOf(setOf<String>()) }
    val focusManager = LocalFocusManager.current
    // While the keyboard is up, let the system Back gesture dismiss the keyboard first so the full
    // list of search results can be seen; a second Back (with the keyboard down) clears the search.
    BackHandler(enabled = query.isNotEmpty() && !WindowInsets.isImeVisible) {
        libraryVm.setQuery("")
        focusManager.clearFocus()
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
                val available by libraryVm.availableScopes.collectAsState()
                if (available.size + (if (catalogEnabled) 1 else 0) > 1) {
                    val server = libraryVm.subsonicState.collectAsState().value.server
                    SourcesMenu(
                        available = available,
                        hidden = libraryVm.hiddenScopes.collectAsState().value,
                        serverName = server?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "Navidrome",
                        catalogEnabled = catalogEnabled,
                        catalogShown = catalogShown,
                        onToggle = libraryVm::toggleScope,
                        onToggleCatalog = libraryVm::toggleCatalogShown,
                    )
                }
                IconButton(onClick = onOpenSources) { Icon(Icons.Default.Tune, "Settings") }
            },
        )
        SearchField(query, libraryVm::setQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        if (query.isNotEmpty()) {
            val filterKinds = buildList {
                add(SEARCH_ARTISTS)
                add(SEARCH_ALBUMS)
                add(SEARCH_SONGS)
                if (catalogEnabled && catalogShown) add(SEARCH_PLAYLISTS)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filterKinds.forEach { kind ->
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
            SearchResultsList(
                results = results,
                catalogResults = catalogResults,
                filters = filters,
                currentId = player.current?.mediaId,
                libraryVm = libraryVm,
                playerVm = playerVm,
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
                onOpenCatalogArtist = onOpenCatalogArtist,
                onOpenCatalogAlbum = onOpenCatalogAlbum,
                onOpenCatalogPlaylist = onOpenCatalogPlaylist,
            )
            return@Column
        }
        // Narrow tabs, so five fit across a phone; the rest scroll in.
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp, minTabWidth = 0.dp) {
            LIBRARY_TABS.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }) {
                    Text(label, Modifier.padding(horizontal = 10.dp, vertical = 14.dp), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        val songCount by libraryVm.songCount.collectAsState()
        val indexing by libraryVm.indexing.collectAsState()
        if (songCount == 0 && scanStatus == null && !indexing) {
            EmptyLibrary(onOpenMusicSources)
            return@Column
        }
        tabStates.SaveableStateProvider(LIBRARY_TABS[tab]) {
            when (LIBRARY_TABS[tab]) {
                "Home" -> HomeTab(
                    libraryVm, playerVm, onOpenReplay, onOpenMostPlayed, onOpenFavourites, onOpenRecentTracks,
                    onOpenMix, onOpenMixes, onOpenRecentAlbums, onOpenAddedAlbums, onOpenAlbum,
                )
                "Discover" -> DiscoverTab(libraryVm, onOpenArtist, onOpenAlbum, onOpenFollowing)
                "Albums" -> AlbumsTab(libraryVm, onOpenAlbum)
                "Artists" -> ArtistsTab(libraryVm, onOpenArtist)
                "Genres" -> GenresTab(libraryVm.genreList.collectAsState().value, onOpenGenre)
                "Songs" -> SongsTab(libraryVm, playerVm)
                "Playlists" -> PlaylistsTab(
                    playlists, libraryVm.userPlaylists.collectAsState().value, playerVm,
                    onOpenRecentTracks, onOpenRecentAlbums, onOpenAddedAlbums, onOpenMostPlayed, onOpenFavourites, onNewPlaylist, onOpenPlaylist,
                    libraryVm::deletePlaylist, libraryVm::renamePlaylist,
                )
            }
        }
    }
}

/** Which sources the library lists: each one switched on or off (at least one library source stays on). */
@Composable
private fun SourcesMenu(
    available: List<Int>,
    hidden: Set<Int>,
    serverName: String,
    catalogEnabled: Boolean,
    catalogShown: Boolean,
    onToggle: (Int) -> Unit,
    onToggleCatalog: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shown = available.filter { it !in hidden }.ifEmpty { available }
    fun label(scope: Int) = when (scope) {
        Track.SCOPE_DRIVE -> "GDrive"
        Track.SCOPE_SERVER -> serverName
        else -> "Local"
    }
    fun icon(scope: Int) = when (scope) {
        Track.SCOPE_DRIVE -> Icons.Default.Cloud
        Track.SCOPE_SERVER -> SubmarineIcon
        else -> Icons.Default.Folder
    }
    val allShown = shown.size == available.size && (!catalogEnabled || catalogShown)
    val activeLabels = buildList {
        shown.forEach { add(label(it)) }
        if (catalogEnabled && catalogShown) add("Deezer / Apple")
    }
    Box {
        AssistChip(
            onClick = { open = true },
            label = {
                Text(
                    when {
                        allShown -> "All sources"
                        activeLabels.size == 1 -> activeLabels.single()
                        else -> activeLabels.joinToString(" + ")
                    },
                    maxLines = 1,
                )
            },
            leadingIcon = {
                Icon(
                    if (shown.size == 1 && (!catalogEnabled || !catalogShown)) icon(shown.single()) else Icons.Default.LibraryMusic,
                    null,
                    Modifier.size(18.dp),
                )
            },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
            modifier = Modifier.widthIn(max = 200.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            available.forEach { scope ->
                val on = scope in shown
                DropdownMenuItem(
                    text = { Text(label(scope)) },
                    // Stays open, so several sources can be switched in one go.
                    onClick = { onToggle(scope) },
                    leadingIcon = { Icon(icon(scope), null) },
                    trailingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                    enabled = !(on && shown.size == 1),
                )
            }
            if (catalogEnabled) {
                DropdownMenuItem(
                    text = { Text("Deezer / Apple Music") },
                    onClick = onToggleCatalog,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { Checkbox(checked = catalogShown, onCheckedChange = null) },
                )
            }
        }
    }
}

private fun count(n: Int, noun: String) =if (n == 1) "1 $noun" else "$n ${noun}s"

/** The library search box: an outlined pill like OutlinedTextField, but 44 dp tall instead of 56. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val shape = RoundedCornerShape(22.dp)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.height(44.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = query,
            innerTextField = inner,
            enabled = true,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interaction,
            placeholder = { Text("Search artists, albums, songs") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "Clear") }
            },
            colors = colors,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            container = { OutlinedTextFieldDefaults.Container(enabled = true, isError = false, interactionSource = interaction, colors = colors, shape = shape) },
        )
    }
}

private val LIBRARY_TABS = listOf("Home", "Discover", "Playlists", "Albums", "Artists", "Genres", "Songs")
private const val SEARCH_ARTISTS = "Artists"
private const val SEARCH_ALBUMS = "Albums"
private const val SEARCH_SONGS = "Songs"
private const val SEARCH_PLAYLISTS = "Playlists"

@Composable
internal fun ArtistRow(name: String, albumCount: Int, songCount: Int, photo: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistPhoto(photo, Modifier.size(52.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${count(albumCount, "album")} · ${count(songCount, "song")}",
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
    catalogResults: com.kike.musicplayer.data.catalog.CatalogSearchResults,
    filters: Set<String>,
    currentId: String?,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenCatalogArtist: (com.kike.musicplayer.data.catalog.CatalogArtist) -> Unit,
    onOpenCatalogAlbum: (com.kike.musicplayer.data.catalog.CatalogAlbum) -> Unit,
    onOpenCatalogPlaylist: (com.kike.musicplayer.data.catalog.CatalogPlaylist) -> Unit,
) {
    val photos by libraryVm.artistImageUrls.collectAsState()
    val extraCovers by libraryVm.albumCoverUrls.collectAsState()
    var collapsedSections by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggleSection(key: String) {
        collapsedSections = if (key in collapsedSections) collapsedSections - key else collapsedSections + key
    }

    fun show(kind: String) = filters.isEmpty() || kind in filters
    val artists = if (show(SEARCH_ARTISTS)) results.artists else emptyList()
    val albums = if (show(SEARCH_ALBUMS)) results.albums else emptyList()
    val songs = if (show(SEARCH_SONGS)) results.songs else emptyList()

    val libArtistKeys = remember(results.artists) {
        results.artists.mapTo(HashSet()) { com.kike.musicplayer.data.discover.Names.comparable(it.name) }
    }
    val libAlbumKeys = remember(results.albums) {
        results.albums.mapTo(HashSet()) {
            com.kike.musicplayer.data.discover.Names.comparable(it.artist) + "\u0001" + com.kike.musicplayer.data.discover.Names.releaseKey(it.title)
        }
    }
    val libSongKeys = remember(results.songs) {
        results.songs.mapTo(HashSet()) {
            com.kike.musicplayer.data.discover.Names.comparable(it.displayArtist) + "\u0001" + com.kike.musicplayer.data.discover.Names.comparable(it.title)
        }
    }

    // Keep catalog artists in search results even if owned in library (badged with "In library").
    val catArtists = remember(catalogResults.artists, filters) {
        if (!show(SEARCH_ARTISTS)) emptyList()
        else catalogResults.artists
    }
    val catAlbums = remember(catalogResults.albums, libAlbumKeys, filters) {
        if (!show(SEARCH_ALBUMS)) emptyList()
        else catalogResults.albums.filter {
            (com.kike.musicplayer.data.discover.Names.comparable(it.artist) + "\u0001" + com.kike.musicplayer.data.discover.Names.releaseKey(it.title)) !in libAlbumKeys
        }
    }
    val catSongs = remember(catalogResults.songs, libSongKeys, filters) {
        if (!show(SEARCH_SONGS)) emptyList()
        else catalogResults.songs.filter {
            (com.kike.musicplayer.data.discover.Names.comparable(it.artist) + "\u0001" + com.kike.musicplayer.data.discover.Names.comparable(it.title)) !in libSongKeys
        }
    }
    val catPlaylists = remember(catalogResults.playlists, filters) {
        if (!show(SEARCH_PLAYLISTS)) emptyList()
        else catalogResults.playlists
    }

    var inLibraryArtists by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(catArtists) {
        val inLib = catArtists.map { it.name }.filter { libraryVm.firstArtist(listOf(it)) != null }
        inLibraryArtists = inLib.mapTo(HashSet()) { com.kike.musicplayer.data.discover.Names.comparable(it) }
    }

    if (artists.isEmpty() && albums.isEmpty() && songs.isEmpty() &&
        catArtists.isEmpty() && catAlbums.isEmpty() && catSongs.isEmpty() &&
        catPlaylists.isEmpty() && !catalogResults.loading
    ) {
        Text("No matches", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (artists.isNotEmpty()) {
            val collapsed = "artists" in collapsedSections
            item {
                ResultsHeader("Artists", artists.size, collapsed) { toggleSection("artists") }
            }
            if (!collapsed) {
                items(artists, key = { "ar:" + it.name }) { artist ->
                    val photo = photos[artist.name.lowercase()]
                    LaunchedEffect(artist.name) {
                        if (photo == null) {
                            kotlinx.coroutines.delay(250)
                            libraryVm.artistImage(artist.name)
                        }
                    }
                    ArtistRow(artist.name, artist.albumCount, artist.trackCount, photo) { onOpenArtist(artist.name) }
                }
            }
        }
        if (albums.isNotEmpty()) {
            val collapsed = "albums" in collapsedSections
            item {
                ResultsHeader("Albums", albums.size, collapsed) { toggleSection("albums") }
            }
            if (!collapsed) {
                items(albums, key = { "al:" + it.key }) { album ->
                    val cover = album.artworkUri ?: extraCovers[album.key]
                    LaunchedEffect(album.key) {
                        if (cover == null) {
                            kotlinx.coroutines.delay(250)
                            libraryVm.albumCover(album.key, album.title, album.artist)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenAlbum(album.key) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(cover, size = 48.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ArtistAlbumText(album.artist, listOf(album.artist))
                        }
                    }
                }
            }
        }
        if (songs.isNotEmpty()) {
            val collapsed = "songs" in collapsedSections
            item {
                ResultsHeader("Songs", songs.size, collapsed) { toggleSection("songs") }
            }
            if (!collapsed) {
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
        if (catalogResults.loading) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Searching Deezer & Apple Music…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (catArtists.isNotEmpty()) {
            val collapsed = "cat_artists" in collapsedSections
            item {
                ResultsHeader("Artists on Deezer & Apple Music", catArtists.size, collapsed) { toggleSection("cat_artists") }
            }
            if (!collapsed) {
                items(catArtists, key = { "cat-ar:" + it.id }) { artist ->
                    val inLib = remember(artist.name, libArtistKeys, inLibraryArtists) {
                        com.kike.musicplayer.data.discover.Names.comparable(artist.name) in libArtistKeys ||
                            com.kike.musicplayer.data.discover.Names.comparable(artist.name) in inLibraryArtists
                    }
                    val photo = artist.pictureUrl ?: photos[artist.name.lowercase()]
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenCatalogArtist(artist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtistPhoto(photo, Modifier.size(52.dp))
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = buildList {
                                if (inLib) add("In library")
                                artist.albumCount?.takeIf { it > 0 }?.let { add(count(it, "album")) }
                                add(if (artist.source == com.kike.musicplayer.data.catalog.CatalogPlaylist.SOURCE_APPLE) "Apple Music" else "Deezer")
                            }.joinToString(" · ")
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (catAlbums.isNotEmpty()) {
            val collapsed = "cat_albums" in collapsedSections
            item {
                ResultsHeader("Albums on Deezer & Apple Music", catAlbums.size, collapsed) { toggleSection("cat_albums") }
            }
            if (!collapsed) {
                items(catAlbums, key = { "cat-al:" + it.id }) { album ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenCatalogAlbum(album) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(album.coverUrl, size = 48.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = buildList {
                                add(album.artist)
                                album.year?.let { add(it.toString()) }
                                album.genre?.let { add(it) }
                            }.joinToString(" · ")
                            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (catSongs.isNotEmpty()) {
            val collapsed = "cat_songs" in collapsedSections
            item {
                ResultsHeader("Songs on Deezer & Apple Music", catSongs.size, collapsed) { toggleSection("cat_songs") }
            }
            if (!collapsed) {
                items(catSongs, key = { "cat-so:" + it.id }) { track ->
                    UnownedCatalogTrackRow(
                        track = track,
                        onClick = if (track.albumId != null) {
                            {
                                onOpenCatalogAlbum(
                                    com.kike.musicplayer.data.catalog.CatalogAlbum(
                                        id = track.albumId,
                                        title = track.album.ifBlank { track.title },
                                        artist = track.albumArtist ?: track.artist,
                                        year = track.year,
                                        coverUrl = track.coverUrl,
                                        genre = track.genre,
                                        trackCount = null,
                                        source = track.source,
                                    )
                                )
                            }
                        } else null,
                    )
                }
            }
        }
        if (catPlaylists.isNotEmpty()) {
            val collapsed = "cat_playlists" in collapsedSections
            item {
                ResultsHeader("Playlists on Deezer", catPlaylists.size, collapsed) { toggleSection("cat_playlists") }
            }
            if (!collapsed) {
                items(catPlaylists, key = { "cat-pl:" + it.id }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenCatalogPlaylist(playlist) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(playlist.coverUrl, size = 48.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = buildList {
                                playlist.creator?.takeIf { it.isNotBlank() }?.let { add("by $it") }
                                if (playlist.trackCount > 0) add(count(playlist.trackCount, "song"))
                                add("Deezer")
                            }.joinToString(" · ")
                            Text(
                                sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsHeader(
    text: String,
    count: Int? = null,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "($count)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onToggle != null) {
            Icon(
                imageVector = if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (collapsed) "Expand $text" else "Collapse $text",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
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
    albumKey: String,
    /** Opened from a genre: only the album's songs of that genre. */
    genreKey: String?,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val player by playerVm.state.collectAsState()
    val albumOrNull by remember(albumKey) { libraryVm.album(albumKey) }.collectAsState(null)
    val extraCovers by libraryVm.albumCoverUrls.collectAsState()
    val allTracks by remember(albumKey, genreKey) { libraryVm.albumTracks(albumKey, genreKey) }.collectAsState(emptyList())
    // The album in several sources: each song once (own copy first), or one source's copy.
    var source by rememberSaveable(albumKey) { mutableStateOf<Int?>(null) }
    val tracks = remember(allTracks, source) { songsFromSource(allTracks, source) }
    val genre by remember(genreKey) { genreKey?.let { libraryVm.genre(it) } ?: flowOf(null) }.collectAsState(null)
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(albumOrNull?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        val album = albumOrNull ?: return@Column
        val cover = album.artworkUri ?: extraCovers[album.key]
        LaunchedEffect(album.key) {
            if (album.artworkUri == null || album.year == null) {
                libraryVm.enrichAlbum(album.key, album.title, album.artist)
            }
        }
        // Shown under the album info when only some of its songs are listed.
        val note = genre?.takeIf { tracks.isNotEmpty() && tracks.size < album.trackCount }
            ?.let { "Only ${it.name} songs · ${album.trackCount} on the album" }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(cover, size = 140.dp)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(album.title, style = MaterialTheme.typography.titleLarge)
                        ArtistAlbumText(album.artist, listOf(album.artist), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(album.year?.toString(), count(tracks.size, "song"),
                                formatTime(tracks.sumOf { it.durationMs })).joinToString(" · "),
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
            item { SourceChips(allTracks, source, { source = it }, Modifier.padding(bottom = 4.dp)) }
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
    /** A line under the title, e.g. a mix's artists. */
    subtitle: String? = null,
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
            item { CollectionHeader(title, tracks, subtitle) }
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

/** Recently Played / Added Albums: the albums as a grid; Play plays them all, newest first. */
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
        CollectionHeader(title, all, extra = count(albums.size, "album"))
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
    onOpenAddedAlbums: () -> Unit,
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
    val added = playlists.addedAlbums.flatMap { it.tracks }
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
                    CollectionCard("Recently Added Albums",
                        if (added.isEmpty()) "Nothing added yet" else count(playlists.addedAlbums.size, "album"),
                        added, onOpenAddedAlbums, { playerVm.playTracks(added, 0) }, card)
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
