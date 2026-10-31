package com.kike.musicplayer.ui

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch

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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlaylistAdd
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
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.FilterChip
import com.kike.musicplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import com.kike.musicplayer.data.db.mosaicCovers

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
    onOpenGenreDecadeMixes: () -> Unit = {},
    onOpenReplay: () -> Unit,
    onOpenHeavyRotation: () -> Unit = {},
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
    onOpenCatalogSection: (com.kike.musicplayer.data.catalog.DeezerHomeSection) -> Unit = {},
    onOpenPlaylistCategory: (PlaylistCategoryType) -> Unit = {},
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
    var filters by rememberSaveable { mutableStateOf(setOf<SearchFilter>()) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val isSearching = searchExpanded || query.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            kotlinx.coroutines.delay(50)
            focusRequester.requestFocus()
        }
    }
    val focusManager = LocalFocusManager.current
    // While the keyboard is up, let the system Back gesture dismiss the keyboard first so the full
    // list of search results can be seen; a second Back (with the keyboard down) clears the search.
    BackHandler(enabled = isSearching && !WindowInsets.isImeVisible) {
        libraryVm.setQuery("")
        searchExpanded = false
        focusManager.clearFocus()
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 0.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
                IconButton(
                    onClick = onOpenSources,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.settings_title), modifier = Modifier.size(22.dp))
                }
            }
            val available by libraryVm.availableScopes.collectAsState()
            val showSources = available.size + (if (catalogEnabled) 1 else 0) > 1
            if (isSearching) {
                SearchField(
                    query = query,
                    onChange = libraryVm::setQuery,
                    onCollapse = {
                        libraryVm.setQuery("")
                        searchExpanded = false
                        focusManager.clearFocus()
                    },
                    focusRequester = focusRequester,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 16.dp, top = 0.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { searchExpanded = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search_hint),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (showSources) {
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
                }
            }
        }
        if (query.isNotEmpty()) {
            val filterKinds = buildList {
                add(SearchFilter.ARTISTS)
                add(SearchFilter.ALBUMS)
                add(SearchFilter.SONGS)
                if (catalogEnabled && catalogShown) add(SearchFilter.PLAYLISTS)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                filterKinds.forEach { kind ->
                    FilterChip(
                        selected = kind in filters,
                        onClick = { filters = if (kind in filters) filters - kind else filters + kind },
                        label = {
                            Text(
                                stringResource(kind.labelRes),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
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
        PrimaryScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 4.dp,
            minTabWidth = 0.dp,
            modifier = Modifier.height(36.dp),
        ) {
            LibraryTab.all.forEachIndexed { i, libraryTab ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        stringResource(libraryTab.titleRes),
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        val songCount by libraryVm.songCount.collectAsState()
        val indexing by libraryVm.indexing.collectAsState()
        if (songCount == 0 && scanStatus == null && !indexing) {
            EmptyLibrary(onOpenMusicSources)
            return@Column
        }
        val currentTab = LibraryTab.all.getOrElse(tab) { LibraryTab.HOME }
        tabStates.SaveableStateProvider(currentTab.name) {
            when (currentTab) {
                LibraryTab.HOME -> HomeTab(
                    libraryVm, playerVm, onOpenReplay, onOpenHeavyRotation, onOpenMostPlayed, onOpenFavourites, onOpenRecentTracks,
                    onOpenMix, onOpenMixes, onOpenGenreDecadeMixes, onOpenRecentAlbums, onOpenAddedAlbums, onOpenAlbum,
                    onOpenCatalogPlaylist = onOpenCatalogPlaylist,
                    onOpenCatalogSection = onOpenCatalogSection,
                )
                LibraryTab.DISCOVER -> DiscoverTab(libraryVm, onOpenArtist, onOpenAlbum, onOpenFollowing)
                LibraryTab.ALBUMS -> AlbumsTab(libraryVm, onOpenAlbum)
                LibraryTab.ARTISTS -> ArtistsTab(libraryVm, onOpenArtist)
                LibraryTab.GENRES -> GenresTab(libraryVm.genreList.collectAsState().value, onOpenGenre)
                LibraryTab.SONGS -> SongsTab(libraryVm, playerVm)
                LibraryTab.PLAYLISTS -> {
                    val savedPlaylists by libraryVm.savedCatalogPlaylists.collectAsState()
                    PlaylistsTab(
                        playlists = playlists,
                        userPlaylists = libraryVm.userPlaylists.collectAsState().value,
                        savedCatalogPlaylists = savedPlaylists,
                        libraryVm = libraryVm,
                        playerVm = playerVm,
                        onOpenRecentTracks = onOpenRecentTracks,
                        onOpenRecentAlbums = onOpenRecentAlbums,
                        onOpenAddedAlbums = onOpenAddedAlbums,
                        onOpenHeavyRotation = onOpenHeavyRotation,
                        onOpenMostPlayed = onOpenMostPlayed,
                        onOpenFavourites = onOpenFavourites,
                        onNewPlaylist = onNewPlaylist,
                        onOpenPlaylist = onOpenPlaylist,
                        onOpenCatalogPlaylist = onOpenCatalogPlaylist,
                        onOpenPlaylistCategory = onOpenPlaylistCategory,
                        onDeletePlaylist = libraryVm::deletePlaylist,
                        onRenamePlaylist = libraryVm::renamePlaylist,
                        onRemoveSavedCatalogPlaylist = libraryVm::removeSavedCatalogPlaylist,
                    )
                }
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
    val localLabel = stringResource(R.string.source_local)
    fun label(scope: Int) = when (scope) {
        Track.SCOPE_DRIVE -> "GDrive"
        Track.SCOPE_SERVER -> serverName
        else -> localLabel
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
                        allShown -> stringResource(R.string.sources_all)
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
            modifier = Modifier.height(32.dp).widthIn(max = 260.dp),
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



/** The library search box: an outlined pill like OutlinedTextField, but 44 dp tall instead of 56. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    onCollapse: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val shape = RoundedCornerShape(22.dp)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier
            .height(44.dp)
            .focusRequester(focusRequester),
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
            placeholder = {
                Text(
                    stringResource(R.string.search_hint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                IconButton(onClick = onCollapse, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            colors = colors,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            container = { OutlinedTextFieldDefaults.Container(enabled = true, isError = false, interactionSource = interaction, colors = colors, shape = shape) },
        )
    }
}

enum class LibraryTab(@StringRes val titleRes: Int) {
    HOME(R.string.tab_home),
    DISCOVER(R.string.tab_discover),
    PLAYLISTS(R.string.tab_playlists),
    ALBUMS(R.string.tab_albums),
    ARTISTS(R.string.tab_artists),
    GENRES(R.string.tab_genres),
    SONGS(R.string.tab_songs);

    companion object {
        val all: List<LibraryTab> = entries
    }
}

enum class SearchFilter(@StringRes val labelRes: Int) {
    ARTISTS(R.string.search_filter_artists),
    ALBUMS(R.string.search_filter_albums),
    SONGS(R.string.search_filter_songs),
    PLAYLISTS(R.string.search_filter_playlists);
}





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
    filters: Set<SearchFilter>,
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
    val addonsConnected by libraryVm.addonsConnected.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var collapsedSections by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggleSection(key: String) {
        collapsedSections = if (key in collapsedSections) collapsedSections - key else collapsedSections + key
    }

    fun show(filter: SearchFilter) = filters.isEmpty() || filter in filters
    val artists = if (show(SearchFilter.ARTISTS)) results.artists else emptyList()
    val albums = if (show(SearchFilter.ALBUMS)) results.albums else emptyList()
    val songs = if (show(SearchFilter.SONGS)) results.songs else emptyList()

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
        if (!show(SearchFilter.ARTISTS)) emptyList()
        else catalogResults.artists
    }
    val catAlbums = remember(catalogResults.albums, libAlbumKeys, filters) {
        if (!show(SearchFilter.ALBUMS)) emptyList()
        else catalogResults.albums.filter {
            (com.kike.musicplayer.data.discover.Names.comparable(it.artist) + "\u0001" + com.kike.musicplayer.data.discover.Names.releaseKey(it.title)) !in libAlbumKeys
        }
    }
    val catSongs = remember(catalogResults.songs, libSongKeys, filters) {
        if (!show(SearchFilter.SONGS)) emptyList()
        else catalogResults.songs.filter {
            (com.kike.musicplayer.data.discover.Names.comparable(it.artist) + "\u0001" + com.kike.musicplayer.data.discover.Names.comparable(it.title)) !in libSongKeys
        }
    }
    val catStreamTracks = remember(catSongs) {
        catSongs.map { com.kike.musicplayer.data.nebula.NebulaUrls.trackFor(it) }
    }
    val catPlaylists = remember(catalogResults.playlists, filters) {
        if (!show(SearchFilter.PLAYLISTS)) emptyList()
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
                ResultsHeader(stringResource(R.string.tab_artists), artists.size, collapsed) { toggleSection("artists") }
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
                ResultsHeader(stringResource(R.string.tab_albums), albums.size, collapsed) { toggleSection("albums") }
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
                ResultsHeader(stringResource(R.string.tab_songs), songs.size, collapsed) { toggleSection("songs") }
            }
            if (!collapsed) {
                itemsIndexed(songs, key = { _, t -> "so:" + t.id }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentId,
                        onClick = { playerVm.playTracks(listOf(track), 0) },
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
                ResultsHeader(stringResource(R.string.search_cat_artists), catArtists.size, collapsed) { toggleSection("cat_artists") }
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
                                if (inLib) add(stringResource(R.string.cat_in_library))
                                artist.albumCount?.takeIf { it > 0 }?.let { add(pluralStringResource(R.plurals.albums_count, it, it)) }
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
                ResultsHeader(stringResource(R.string.search_cat_albums), catAlbums.size, collapsed) { toggleSection("cat_albums") }
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
                ResultsHeader(stringResource(R.string.search_cat_songs), catSongs.size, collapsed) { toggleSection("cat_songs") }
            }
            if (!collapsed) {
                itemsIndexed(catStreamTracks, key = { idx, track -> "cat-so:${track.id}:$idx" }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentId,
                        onClick = {
                            if (addonsConnected) {
                                playerVm.playTracks(listOf(track), 0)
                            } else {
                                android.widget.Toast.makeText(context, R.string.no_available_sources_to_play, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onPlayNext = {
                            if (addonsConnected) {
                                playerVm.playNext(track)
                            } else {
                                android.widget.Toast.makeText(context, R.string.no_available_sources_to_play, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onAddToQueue = {
                            if (addonsConnected) {
                                playerVm.addToQueue(track)
                            } else {
                                android.widget.Toast.makeText(context, R.string.no_available_sources_to_play, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
        if (catPlaylists.isNotEmpty()) {
            val collapsed = "cat_playlists" in collapsedSections
            item {
                ResultsHeader(stringResource(R.string.search_cat_playlists), catPlaylists.size, collapsed) { toggleSection("cat_playlists") }
            }
            if (!collapsed) {
                items(catPlaylists, key = { "cat-pl:" + it.id }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenCatalogPlaylist(playlist) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCover(playlist.coverUrl, playlist.mosaicCovers, size = 48.dp, shape = RoundedCornerShape(6.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = buildList {
                                playlist.creator?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.search_by_creator, it)) }
                                if (playlist.trackCount > 0) add(pluralStringResource(R.plurals.songs_count, playlist.trackCount, playlist.trackCount))
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
        Text(stringResource(R.string.sources_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.sources_empty_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSources) { Text(stringResource(R.string.sources_add)) }
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
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.action_play))
        }
        FilledTonalButton(onClick = onShuffle, enabled = enabled) {
            Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.action_shuffle))
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
    val albumOrNull by remember(albumKey) { libraryVm.album(albumKey) }.collectAsState(libraryVm.cachedAlbumIndex(albumKey))
    val extraCovers by libraryVm.albumCoverUrls.collectAsState()
    val allTracks by remember(albumKey, genreKey) { libraryVm.albumTracks(albumKey, genreKey) }.collectAsState(libraryVm.cachedAlbumTracks(albumKey))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsTab(
    playlists: Playlists,
    userPlaylists: List<PlaylistView>,
    savedCatalogPlaylists: List<com.kike.musicplayer.data.db.SavedCatalogPlaylist>,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenRecentTracks: () -> Unit,
    onOpenRecentAlbums: () -> Unit,
    onOpenAddedAlbums: () -> Unit,
    onOpenHeavyRotation: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenFavourites: () -> Unit,
    onNewPlaylist: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenCatalogPlaylist: (com.kike.musicplayer.data.catalog.CatalogPlaylist) -> Unit,
    onOpenPlaylistCategory: (PlaylistCategoryType) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onRemoveSavedCatalogPlaylist: (String) -> Unit,
) {
    var showChoiceDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showChoiceDialog) {
        CreateOrImportPlaylistDialog(
            onDismiss = { showChoiceDialog = false },
            onCreateFromScratch = {
                showChoiceDialog = false
                onNewPlaylist()
            },
            onImportUrl = {
                showChoiceDialog = false
                showImportDialog = true
            },
        )
    }

    if (showImportDialog) {
        ImportPlaylistDialog(
            onDismiss = { showImportDialog = false },
            onImport = { url, callback ->
                scope.launch {
                    val result = libraryVm.importPlaylistFromUrl(url)
                    callback(result)
                }
            },
            onSuccess = { importedPlaylist ->
                showImportDialog = false
                onOpenCatalogPlaylist(importedPlaylist)
            },
        )
    }

    val recent = playlists.recentTracks
    val albums = playlists.recentAlbums.flatMap { it.tracks }
    val added = playlists.addedAlbums.flatMap { it.tracks }
    val rotation = playlists.heavyRotation.map { it.item }
    val most = playlists.mostPlayed.map { it.item }
    val favourites = playlists.favourites

    val sortedPersonal = remember(userPlaylists) {
        userPlaylists.sortedWith(
            compareByDescending<PlaylistView> { it.lastPlayedAt }
                .thenByDescending { it.createdAt }
        )
    }

    val applePlaylists = remember(savedCatalogPlaylists) {
        savedCatalogPlaylists.filter {
            it.source == com.kike.musicplayer.data.catalog.CatalogPlaylist.SOURCE_APPLE || it.id.startsWith("ap:")
        }.sortedWith(
            compareByDescending<com.kike.musicplayer.data.db.SavedCatalogPlaylist> { it.lastPlayedAt }
                .thenByDescending { it.addedAt }
        )
    }

    val deezerPlaylists = remember(savedCatalogPlaylists) {
        savedCatalogPlaylists.filter {
            it.source == com.kike.musicplayer.data.catalog.CatalogPlaylist.SOURCE_DEEZER || it.id.startsWith("dz:")
        }.sortedWith(
            compareByDescending<com.kike.musicplayer.data.db.SavedCatalogPlaylist> { it.lastPlayedAt }
                .thenByDescending { it.addedAt }
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)) {
            // The automatic playlists, as cards like the Home mixes.
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val card = Modifier.width(160.dp)
                    CollectionCard(stringResource(R.string.home_recently_played), if (recent.isEmpty()) stringResource(R.string.home_empty_recent_subtitle) else count(recent.size, "song"),
                        recent, onOpenRecentTracks, { playerVm.playTracks(recent, 0) }, card)
                    CollectionCard(stringResource(R.string.home_recently_played_albums),
                        if (albums.isEmpty()) stringResource(R.string.home_empty_recent_subtitle) else count(playlists.recentAlbums.size, "album"),
                        albums, onOpenRecentAlbums, { playerVm.playTracks(albums, 0) }, card)
                    CollectionCard(stringResource(R.string.home_recently_added_albums),
                        if (added.isEmpty()) stringResource(R.string.home_empty_added_subtitle) else count(playlists.addedAlbums.size, "album"),
                        added, onOpenAddedAlbums, { playerVm.playTracks(added, 0) }, card)
                    CollectionCard(stringResource(R.string.home_heavy_rotation),
                        if (rotation.isEmpty()) stringResource(R.string.home_empty_rotation_subtitle) else count(rotation.size, "song"),
                        rotation, onOpenHeavyRotation, { playerVm.playTracks(rotation, 0) }, card)
                    CollectionCard(stringResource(R.string.home_most_played), if (most.isEmpty()) stringResource(R.string.home_empty_most_played_subtitle) else count(most.size, "song"),
                        most, onOpenMostPlayed, { playerVm.playTracks(most, 0) }, card)
                    CollectionCard(stringResource(R.string.home_favourites), if (favourites.isEmpty()) stringResource(R.string.home_empty_favourites_subtitle) else count(favourites.size, "song"),
                        favourites, onOpenFavourites, { playerVm.playTracks(favourites, 0) }, card)
                }
            }

            // Category 1: Personal Playlists
            item {
                CategoryHeaderRow(
                    title = stringResource(PlaylistCategoryType.PERSONAL.titleRes),
                    count = sortedPersonal.size,
                    onOpenCategory = { onOpenPlaylistCategory(PlaylistCategoryType.PERSONAL) },
                )
            }
            item {
                if (sortedPersonal.isEmpty()) {
                    Text(
                        stringResource(R.string.playlist_personal_empty),
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        sortedPersonal.take(10).forEach { playlist ->
                            CollectionCard(
                                title = playlist.name,
                                subtitle = count(playlist.tracks.size, "song"),
                                tracks = playlist.tracks,
                                onOpen = { onOpenPlaylist(playlist.id) },
                                onPlay = {
                                    libraryVm.recordPlaylistPlayed(playlist.id)
                                    playerVm.playTracks(playlist.tracks, 0)
                                },
                                modifier = Modifier.width(160.dp),
                            )
                        }
                        if (sortedPersonal.size > 10) {
                            MorePlaylistsCard(
                                totalCount = sortedPersonal.size,
                                onClick = { onOpenPlaylistCategory(PlaylistCategoryType.PERSONAL) },
                            )
                        }
                    }
                }
            }

            // Category 2: Apple Music Playlists
            item {
                CategoryHeaderRow(
                    title = stringResource(PlaylistCategoryType.APPLE_MUSIC.titleRes),
                    count = applePlaylists.size,
                    onOpenCategory = { onOpenPlaylistCategory(PlaylistCategoryType.APPLE_MUSIC) },
                )
            }
            item {
                if (applePlaylists.isEmpty()) {
                    Text(
                        stringResource(R.string.playlist_apple_empty),
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        applePlaylists.take(10).forEach { playlist ->
                            val mosaic = remember(playlist.id, playlist.cachedTracksJson) { playlist.mosaicCovers() }
                            val catPl = remember(playlist, mosaic) {
                                com.kike.musicplayer.data.catalog.CatalogPlaylist(
                                    id = playlist.id,
                                    title = playlist.title,
                                    creator = playlist.creator,
                                    trackCount = playlist.trackCount,
                                    coverUrl = playlist.coverUrl,
                                    source = playlist.source,
                                    mosaicCovers = mosaic,
                                )
                            }
                            CatalogPlaylistCard(
                                title = playlist.title,
                                subtitle = buildList {
                                    playlist.creator?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.search_by_creator, it)) }
                                    add(count(playlist.trackCount, "song"))
                                }.joinToString(" · "),
                                coverUrl = playlist.coverUrl,
                                mosaicCovers = mosaic,
                                onOpen = { onOpenCatalogPlaylist(catPl) },
                                modifier = Modifier.width(160.dp),
                            )
                        }
                        if (applePlaylists.size > 10) {
                            MorePlaylistsCard(
                                totalCount = applePlaylists.size,
                                onClick = { onOpenPlaylistCategory(PlaylistCategoryType.APPLE_MUSIC) },
                            )
                        }
                    }
                }
            }

            // Category 3: Deezer Playlists
            item {
                CategoryHeaderRow(
                    title = stringResource(PlaylistCategoryType.DEEZER.titleRes),
                    count = deezerPlaylists.size,
                    onOpenCategory = { onOpenPlaylistCategory(PlaylistCategoryType.DEEZER) },
                )
            }
            item {
                if (deezerPlaylists.isEmpty()) {
                    Text(
                        stringResource(R.string.playlist_deezer_empty),
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        deezerPlaylists.take(10).forEach { playlist ->
                            val mosaic = remember(playlist.id, playlist.cachedTracksJson) { playlist.mosaicCovers() }
                            val catPl = remember(playlist, mosaic) {
                                com.kike.musicplayer.data.catalog.CatalogPlaylist(
                                    id = playlist.id,
                                    title = playlist.title,
                                    creator = playlist.creator,
                                    trackCount = playlist.trackCount,
                                    coverUrl = playlist.coverUrl,
                                    source = playlist.source,
                                    mosaicCovers = mosaic,
                                )
                            }
                            CatalogPlaylistCard(
                                title = playlist.title,
                                subtitle = buildList {
                                    playlist.creator?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.search_by_creator, it)) }
                                    add(count(playlist.trackCount, "song"))
                                }.joinToString(" · "),
                                coverUrl = playlist.coverUrl,
                                mosaicCovers = mosaic,
                                onOpen = { onOpenCatalogPlaylist(catPl) },
                                modifier = Modifier.width(160.dp),
                            )
                        }
                        if (deezerPlaylists.size > 10) {
                            MorePlaylistsCard(
                                totalCount = deezerPlaylists.size,
                                onClick = { onOpenPlaylistCategory(PlaylistCategoryType.DEEZER) },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showChoiceDialog = true },
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, "New playlist")
        }
    }
}

@Composable
private fun CategoryHeaderRow(
    title: String,
    count: Int,
    onOpenCategory: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenCategory)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = " ($count)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "View all $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
internal fun CatalogPlaylistCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    mosaicCovers: List<String> = emptyList(),
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            PlaylistCover(
                coverUrl = coverUrl,
                mosaicCovers = mosaicCovers,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
            )
        }
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            subtitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MorePlaylistsCard(
    totalCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(160.dp).clickable(onClick = onClick)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "More…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "More…",
            maxLines = 1,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "View all $totalCount",
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreateOrImportPlaylistDialog(
    onDismiss: () -> Unit,
    onCreateFromScratch: () -> Unit,
    onImportUrl: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Playlist") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Surface(
                    onClick = onCreateFromScratch,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.PlaylistAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Create from scratch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Pick songs from your library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = onImportUrl,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Import from URL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Paste an Apple Music or Deezer link", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ImportPlaylistDialog(
    onDismiss: () -> Unit,
    onImport: (String, (Result<com.kike.musicplayer.data.catalog.CatalogPlaylist>) -> Unit) -> Unit,
    onSuccess: (com.kike.musicplayer.data.catalog.CatalogPlaylist) -> Unit,
) {
    var urlText by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Import Playlist") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Paste a link to a public Apple Music or Deezer playlist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        errorMessage = null
                    },
                    label = { Text("Playlist URL") },
                    placeholder = { Text("https://music.apple.com/... or https://www.deezer.com/...") },
                    singleLine = true,
                    enabled = !isImporting,
                    trailingIcon = {
                        if (urlText.isNotEmpty()) {
                            IconButton(onClick = { urlText = ""; errorMessage = null }, enabled = !isImporting) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim()
                                    if (!clip.isNullOrBlank()) {
                                        urlText = clip
                                        errorMessage = null
                                    }
                                },
                                enabled = !isImporting,
                            ) {
                                Icon(Icons.Default.ContentPaste, "Paste")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isImporting) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Importing playlist and matching songs…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = urlText.isNotBlank() && !isImporting,
                onClick = {
                    isImporting = true
                    errorMessage = null
                    onImport(urlText) { result ->
                        isImporting = false
                        result.onSuccess { playlist ->
                            onSuccess(playlist)
                        }.onFailure { err ->
                            errorMessage = err.message ?: "Failed to import playlist"
                        }
                    }
                },
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isImporting,
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    )
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
