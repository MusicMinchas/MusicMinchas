package com.kike.musicplayer.ui

import com.kike.musicplayer.playback.PlaybackService
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import android.Manifest
import android.content.Intent
import com.kike.musicplayer.container
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.kike.musicplayer.ui.theme.MusicPlayerTheme

private sealed interface Screen {
    data object Library : Screen
    /** An album; opened from a genre ([genreKey]), it shows only that genre's songs. */
    data class AlbumDetail(val key: String, val genreKey: String? = null) : Screen
    data class ArtistDetail(val name: String) : Screen
    data class ArtistDiscography(val artistName: String, val initialTab: Int = 0, val source: Int? = null) : Screen
    data object NowPlaying : Screen
    data object Queue : Screen
    data object Sources : Screen
    data object MusicSources : Screen
    data object DriveFolders : Screen
    data object RecentTracks : Screen
    data object RecentAlbums : Screen
    data object AddedAlbums : Screen
    data object Mixes : Screen
    data object Replay : Screen
    data object HeavyRotation : Screen
    data object MostPlayed : Screen
    data object Favourites : Screen
    data object NewPlaylist : Screen
    data class AddToPlaylist(val id: Long) : Screen
    data class PlaylistDetail(val id: Long) : Screen
    data class MixDetail(val id: String) : Screen
    data class GenreDetail(val key: String) : Screen
    data object Following : Screen
    data class CatalogAlbumDetail(val album: com.kike.musicplayer.data.catalog.CatalogAlbum) : Screen
    data class CatalogArtistDetail(val artist: com.kike.musicplayer.data.catalog.CatalogArtist) : Screen
    data class CatalogPlaylistDetail(val playlist: com.kike.musicplayer.data.catalog.CatalogPlaylist) : Screen
    data class PlaylistCategory(val type: PlaylistCategoryType) : Screen
}

class MainActivity : ComponentActivity() {
    private val libraryVm: LibraryViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark by libraryVm.darkMode.collectAsState()
            MusicPlayerTheme(dark) { App() }
        }
        handleLastFmCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLastFmCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back from Last.fm's approval page (whether or not it sent the callback link).
        libraryVm.finishLastFmConnect(null)
        // Downloads interrupted by the app being closed carry on.
        container.downloader.resume()
    }

    private fun handleLastFmCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "musicminchas" && uri.host == "lastfm") libraryVm.finishLastFmConnect(uri.getQueryParameter("token"))
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun App() {
        val backStack = remember { mutableStateListOf<Screen>(Screen.Library) }
        val screen = backStack.last()
        // Keeps each screen's saved UI state (selected tab, scroll positions) while it is in the back
        // stack; without it, returning from an album rebuilt the library on the Songs tab, at the top.
        val savedStates = rememberSaveableStateHolder()
        fun push(s: Screen) = backStack.add(s)
        fun pop() {
            if (backStack.size > 1) savedStates.removeState(backStack.removeAt(backStack.lastIndex).toString())
        }
        BackHandler(enabled = backStack.size > 1 && !WindowInsets.isImeVisible) { pop() }

        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(libraryVm::addFolder)
        }
        val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            libraryVm.onDriveConsentResult(it.data)
        }
        val connectDrive = {
            libraryVm.connectDrive { pending -> driveConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
        }
        // Google wants the user to sign in again (access expired or removed): offer it once per launch.
        val driveNeedsReconnect by libraryVm.driveNeedsReconnect.collectAsState()
        var reconnectOffered by rememberSaveable { mutableStateOf(false) }
        if (driveNeedsReconnect && !reconnectOffered) {
            AlertDialog(
                onDismissRequest = { reconnectOffered = true },
                title = { Text("Reconnect Google Drive") },
                text = { Text("Google asks you to sign in again before your Drive songs can play. Your library, plays and downloads are kept.") },
                confirmButton = { TextButton(onClick = { reconnectOffered = true; connectDrive() }) { Text("Reconnect") } },
                dismissButton = { TextButton(onClick = { reconnectOffered = true }) { Text("Later") } },
            )
        }
        LaunchedEffect(Unit) {
            libraryVm.start()
            // For the playback notification. Music files are reached through picked folders, so no storage permission.
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val playlists by libraryVm.playlists.collectAsState()
        val mixes by libraryVm.mixes.collectAsState()
        val userPlaylists by libraryVm.userPlaylists.collectAsState()
        val showMini = screen !is Screen.NowPlaying && screen !is Screen.Queue && screen !is Screen.DriveFolders
        val dark by libraryVm.darkMode.collectAsState()
        val fullCover by libraryVm.fullScreenCover.collectAsState()
        // Status bar icons: light on the dark theme and on a full-screen cover, dark otherwise.
        val lightIcons = dark || (screen is Screen.NowPlaying && fullCover)
        // Turning the phone resets the bar colours, so they're set again then too.
        LaunchedEffect(lightIcons, dark, LocalConfiguration.current.orientation) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { lightIcons },
                navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            )
        }

        val favouriteIds by playerVm.favourites.collectAsState()
        val downloadProgress by libraryVm.downloadProgress.collectAsState()
        // The playing song and the queue, looked up in the library for Now Playing and the queue.
        val player by playerVm.state.collectAsState()
        val trackCache by libraryVm.trackCache.collectAsState()
        LaunchedEffect(player.current?.mediaId, player.upNext) {
            libraryVm.cacheTracks(listOfNotNull(player.current?.mediaId).plus(player.upNext.map { it.item.mediaId }).map(PlaybackService::trackIdOf))
        }
        val hasArtistInStack = backStack.any { it is Screen.ArtistDetail || it is Screen.ArtistDiscography }
        LaunchedEffect(hasArtistInStack) {
            if (!hasArtistInStack) {
                libraryVm.clearActiveArtistCache()
            }
        }
        val hasAlbumInStack = backStack.any { it is Screen.AlbumDetail }
        LaunchedEffect(hasAlbumInStack) {
            if (!hasAlbumInStack) {
                libraryVm.clearActiveAlbumCache()
            }
        }
        val hasCatalogAlbumInStack = backStack.any { it is Screen.CatalogAlbumDetail }
        LaunchedEffect(hasCatalogAlbumInStack) {
            if (!hasCatalogAlbumInStack) {
                libraryVm.clearCatalogAlbumCache()
            }
        }
        val scope = rememberCoroutineScope()
        val navigation = Navigation(
            openArtist = { names, track ->
                scope.launch {
                    val owned = libraryVm.firstArtist(names)
                    if (owned != null) {
                        if ((screen as? Screen.ArtistDetail)?.name != owned) push(Screen.ArtistDetail(owned))
                    } else {
                        val artistName = track?.albumArtist ?: track?.artist ?: names.filterNotNull().firstOrNull()
                        if (artistName != null) {
                            val catArtist = com.kike.musicplayer.data.catalog.CatalogArtist(
                                id = "search:$artistName",
                                name = artistName,
                                pictureUrl = track?.artworkUri,
                                source = com.kike.musicplayer.data.catalog.CatalogPlaylist.SOURCE_DEEZER,
                            )
                            if ((screen as? Screen.CatalogArtistDetail)?.artist?.name != artistName) {
                                push(Screen.CatalogArtistDetail(catArtist))
                            }
                        }
                    }
                }
            },
            openAlbum = { key, track ->
                scope.launch {
                    val artist = track?.albumArtist ?: track?.artist ?: key.substringAfter('|', "")
                    val title = track?.album ?: key.substringBefore('|')
                    val isStream = track?.scope == com.kike.musicplayer.data.db.Track.SCOPE_STREAM ||
                        track?.source == com.kike.musicplayer.data.db.Track.SOURCE_NEBULA ||
                        track?.id?.startsWith("nebula:") == true

                    if (isStream) {
                        val catAlbumId = track?.mbReleaseId
                            ?: track?.uri?.let { com.kike.musicplayer.data.nebula.NebulaUrls.parseParams(it)["albumId"] }
                        val catAlbum = if (catAlbumId != null) {
                            com.kike.musicplayer.data.catalog.CatalogAlbum(
                                id = catAlbumId,
                                title = track.album ?: title,
                                artist = track.albumArtist ?: track.artist ?: artist,
                                year = track.year,
                                coverUrl = track.artworkUri,
                                genre = track.genre,
                                trackCount = null,
                                source = if (catAlbumId.startsWith("ap:")) com.kike.musicplayer.data.catalog.CatalogPlaylist.SOURCE_APPLE else com.kike.musicplayer.data.catalog.CatalogPlaylist.SOURCE_DEEZER,
                            )
                        } else {
                            libraryVm.findCatalogAlbum(artist, title)
                        }
                        if (catAlbum != null) {
                            if ((screen as? Screen.CatalogAlbumDetail)?.album?.id != catAlbum.id) {
                                push(Screen.CatalogAlbumDetail(catAlbum))
                            }
                            return@launch
                        }
                    }

                    // Owned music path
                    val ownedKey = libraryVm.albumInLibrary(artist, title) ?: key
                    if ((screen as? Screen.AlbumDetail)?.key != ownedKey) {
                        push(Screen.AlbumDetail(ownedKey))
                    }
                }
            },
            track = { trackCache[it] },
        )
        CompositionLocalProvider(
            LocalPlaylists provides PlaylistActions(
                playlists = userPlaylists,
                addTo = { id, ids, tracks -> libraryVm.addToPlaylist(id, ids, tracks) },
                create = { name, ids, tracks -> libraryVm.createPlaylist(name, ids, tracks) },
                nextName = libraryVm::nextPlaylistName,
            ),
            LocalNavigation provides navigation,
            LocalFavourites provides Favourites(favouriteIds, playerVm::toggleFavourite),
            LocalDownloads provides Downloads(downloadProgress, libraryVm::download, libraryVm::removeDownload),
        ) {
            Scaffold(bottomBar = { if (showMini) MiniPlayer(playerVm) { push(Screen.NowPlaying) } }) { padding: PaddingValues ->
                savedStates.SaveableStateProvider(screen.toString()) {
                    when (screen) {
                        Screen.Library -> LibraryScreen(
                            libraryVm, playerVm,
                            onOpenAlbum = { push(Screen.AlbumDetail(it)) },
                            onOpenArtist = { push(Screen.ArtistDetail(it)) },
                            onOpenSources = { push(Screen.Sources) },
                            onOpenMusicSources = { push(Screen.MusicSources) },
                            onOpenRecentTracks = { push(Screen.RecentTracks) },
                            onOpenRecentAlbums = { push(Screen.RecentAlbums) },
                            onOpenAddedAlbums = { push(Screen.AddedAlbums) },
                            onOpenMixes = { push(Screen.Mixes) },
                            onOpenReplay = { push(Screen.Replay) },
                            onOpenHeavyRotation = { push(Screen.HeavyRotation) },
                            onOpenMostPlayed = { push(Screen.MostPlayed) },
                            onOpenFavourites = { push(Screen.Favourites) },
                            onNewPlaylist = { push(Screen.NewPlaylist) },
                            onOpenPlaylist = { push(Screen.PlaylistDetail(it)) },
                            onOpenMix = { push(Screen.MixDetail(it)) },
                            onOpenGenre = { push(Screen.GenreDetail(it)) },
                            onOpenFollowing = { push(Screen.Following) },
                            onOpenCatalogArtist = { push(Screen.CatalogArtistDetail(it)) },
                            onOpenCatalogAlbum = { push(Screen.CatalogAlbumDetail(it)) },
                            onOpenCatalogPlaylist = { push(Screen.CatalogPlaylistDetail(it)) },
                            onOpenPlaylistCategory = { push(Screen.PlaylistCategory(it)) },
                            contentPadding = padding,
                        )
                        is Screen.AlbumDetail -> AlbumScreen(screen.key, screen.genreKey, libraryVm, playerVm, ::pop, padding)
                        is Screen.ArtistDetail -> ArtistScreen(
                            screen.name, libraryVm, playerVm,
                            onOpenAlbum = { push(Screen.AlbumDetail(it)) },
                            onOpenArtistDiscography = { artistName, tab, source -> push(Screen.ArtistDiscography(artistName, tab, source)) },
                            onOpenCatalogAlbum = { push(Screen.CatalogAlbumDetail(it)) },
                            onBack = ::pop,
                            contentPadding = padding,
                        )
                        is Screen.ArtistDiscography -> ArtistDiscographyScreen(
                            artistName = screen.artistName,
                            initialTab = screen.initialTab,
                            initialSource = screen.source,
                            libraryVm = libraryVm,
                            playerVm = playerVm,
                            onOpenAlbum = { push(Screen.AlbumDetail(it)) },
                            onOpenCatalogAlbum = { push(Screen.CatalogAlbumDetail(it)) },
                            onBack = ::pop,
                            contentPadding = padding,
                        )
                        is Screen.CatalogAlbumDetail -> CatalogAlbumScreen(
                            screen.album, libraryVm, playerVm,
                            onOpenArtist = { push(Screen.ArtistDetail(it)) },
                            onOpenCatalogArtist = { push(Screen.CatalogArtistDetail(it)) },
                            onBack = ::pop,
                            contentPadding = padding,
                        )
                        is Screen.CatalogArtistDetail -> CatalogArtistScreen(
                            screen.artist, libraryVm, playerVm,
                            onOpenAlbum = { push(Screen.AlbumDetail(it)) },
                            onOpenCatalogAlbum = { push(Screen.CatalogAlbumDetail(it)) },
                            onBack = ::pop,
                            contentPadding = padding,
                        )
                        is Screen.CatalogPlaylistDetail -> CatalogPlaylistScreen(
                            screen.playlist, libraryVm, playerVm,
                            onBack = ::pop,
                            contentPadding = padding,
                        )
                        is Screen.PlaylistCategory -> PlaylistCategoryScreen(
                            screen.type, libraryVm, playerVm,
                            onOpenPlaylist = { push(Screen.PlaylistDetail(it)) },
                            onOpenCatalogPlaylist = { push(Screen.CatalogPlaylistDetail(it)) },
                            onBack = ::pop,
                            contentPadding = padding,
                        )
                        Screen.NowPlaying -> NowPlayingScreen(playerVm, ::pop, { push(Screen.Queue) }, padding, fullCover)
                        Screen.Queue -> QueueScreen(playerVm, ::pop, padding)
                        Screen.DriveFolders -> DriveFolderPickerScreen(libraryVm, ::pop, padding)
                        Screen.RecentTracks -> TrackListScreen("Recently Played", playlists.recentTracks, playerVm, ::pop, padding)
                        Screen.HeavyRotation -> TrackListScreen("Heavy Rotation", playlists.heavyRotation.map { it.item }, playerVm, ::pop, padding)
                        Screen.MostPlayed -> {
                            val counts = playlists.mostPlayed.associate { it.item.id to it.plays }
                            TrackListScreen("Most Played", playlists.mostPlayed.map { it.item }, playerVm, ::pop, padding) { t ->
                                counts[t.id]?.let { if (it == 1) "1 play" else "$it plays" }
                            }
                        }
                        Screen.NewPlaylist -> NewPlaylistScreen(libraryVm, null, onDone = ::pop, onBack = ::pop, contentPadding = padding)
                        is Screen.AddToPlaylist -> NewPlaylistScreen(
                            libraryVm, userPlaylists.firstOrNull { it.id == screen.id }, onDone = ::pop, onBack = ::pop, contentPadding = padding,
                        )
                        is Screen.PlaylistDetail -> PlaylistScreen(
                            userPlaylists.firstOrNull { it.id == screen.id }, libraryVm, playerVm,
                            onAddSongs = { push(Screen.AddToPlaylist(screen.id)) }, onBack = ::pop, contentPadding = padding,
                        )
                        Screen.Favourites -> TrackListScreen("Favourite Songs", playlists.favourites, playerVm, ::pop, padding)
                        Screen.Replay -> ReplayScreen(libraryVm, playerVm, onOpenAlbum = { push(Screen.AlbumDetail(it)) }, onBack = ::pop, contentPadding = padding)
                        is Screen.GenreDetail -> GenreScreen(
                            screen.key, libraryVm, playerVm,
                            onOpenAlbum = { push(Screen.AlbumDetail(it, genreKey = screen.key)) }, onBack = ::pop, contentPadding = padding,
                        )
                        is Screen.MixDetail -> {
                            val mix = mixes.firstOrNull { it.id == screen.id }
                            TrackListScreen(mix?.title ?: "", mix?.tracks, playerVm, ::pop, padding, subtitle = mix?.subtitle)
                        }
                        Screen.RecentAlbums -> AlbumListScreen(
                            "Recently Played Albums", playlists.recentAlbums, playerVm, downloadable = true,
                            onOpenAlbum = { push(Screen.AlbumDetail(it)) }, onBack = ::pop, contentPadding = padding,
                        )
                        Screen.AddedAlbums -> AlbumListScreen(
                            "Recently Added Albums", playlists.addedAlbums, playerVm, downloadable = true,
                            onOpenAlbum = { push(Screen.AlbumDetail(it)) }, onBack = ::pop, contentPadding = padding,
                        )
                        Screen.Mixes -> MixListScreen(mixes, playerVm, onOpenMix = { push(Screen.MixDetail(it)) }, onBack = ::pop, contentPadding = padding)
                        Screen.Following -> FollowingScreen(libraryVm, onOpenArtist = { push(Screen.ArtistDetail(it)) }, onBack = ::pop, contentPadding = padding)
                        Screen.Sources -> SourcesScreen(
                            libraryVm,
                            onBack = ::pop,
                            onOpenMusicSources = { push(Screen.MusicSources) },
                            contentPadding = padding,
                        )
                        Screen.MusicSources -> MusicSourcesScreen(
                            libraryVm,
                            onBack = ::pop,
                            onAddFolder = { folderPicker.launch(null) },
                            onConnectDrive = { connectDrive() },
                            onAddDriveFolder = {
                                libraryVm.openDriveBrowser()
                                push(Screen.DriveFolders)
                            },
                            contentPadding = padding,
                        )
                    }
                }
            }
        }
    }
}
