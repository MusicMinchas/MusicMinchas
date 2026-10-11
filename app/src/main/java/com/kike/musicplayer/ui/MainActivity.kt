package com.kike.musicplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.kike.musicplayer.ui.theme.MusicPlayerTheme

private sealed interface Screen {
    data object Library : Screen
    /** An album; opened from a genre ([genreKey]), it shows only that genre's songs. */
    data class AlbumDetail(val key: String, val genreKey: String? = null) : Screen
    data class ArtistDetail(val name: String) : Screen
    data object NowPlaying : Screen
    data object Queue : Screen
    data object Sources : Screen
    data object DriveFolders : Screen
    data object RecentTracks : Screen
    data object RecentAlbums : Screen
    data object Replay : Screen
    data object MostPlayed : Screen
    data object Favourites : Screen
    data class MixDetail(val id: String) : Screen
    data class GenreDetail(val key: String) : Screen
}

class MainActivity : ComponentActivity() {
    private val libraryVm: LibraryViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MusicPlayerTheme { App() } }
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
    }

    private fun handleLastFmCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "musicminchas" && uri.host == "lastfm") libraryVm.finishLastFmConnect(uri.getQueryParameter("token"))
    }

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
        BackHandler(enabled = backStack.size > 1) { pop() }

        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(libraryVm::addFolder)
        }
        val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            libraryVm.onDriveConsentResult(it.data)
        }
        LaunchedEffect(Unit) {
            libraryVm.start()
            // For the playback notification. Music files are reached through picked folders, so no storage permission.
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val library by libraryVm.library.collectAsState()
        val playlists by libraryVm.playlists.collectAsState()
        val mixes by libraryVm.mixes.collectAsState()
        val genres by libraryVm.genres.collectAsState()
        val showMini = screen !is Screen.NowPlaying && screen !is Screen.Queue && screen !is Screen.DriveFolders

        val favouriteIds by playerVm.favourites.collectAsState()
        CompositionLocalProvider(LocalFavourites provides Favourites(favouriteIds, playerVm::toggleFavourite)) {
            Scaffold(bottomBar = { if (showMini) MiniPlayer(playerVm) { push(Screen.NowPlaying) } }) { padding: PaddingValues ->
                savedStates.SaveableStateProvider(screen.toString()) {
                    when (screen) {
                        Screen.Library -> LibraryScreen(
                            libraryVm, playerVm,
                            onOpenAlbum = { push(Screen.AlbumDetail(it)) },
                            onOpenArtist = { push(Screen.ArtistDetail(it)) },
                            onOpenSources = { push(Screen.Sources) },
                            onOpenRecentTracks = { push(Screen.RecentTracks) },
                            onOpenRecentAlbums = { push(Screen.RecentAlbums) },
                            onOpenReplay = { push(Screen.Replay) },
                            onOpenMostPlayed = { push(Screen.MostPlayed) },
                            onOpenFavourites = { push(Screen.Favourites) },
                            onOpenMix = { push(Screen.MixDetail(it)) },
                            onOpenGenre = { push(Screen.GenreDetail(it)) },
                            contentPadding = padding,
                        )
                        is Screen.AlbumDetail -> {
                            val full = library.albums.firstOrNull { it.key == screen.key }
                            val genre = screen.genreKey?.let { key -> genres.firstOrNull { it.key == key } }
                            val album = genre?.albums?.firstOrNull { it.key == screen.key } ?: full
                            AlbumScreen(
                                album, playerVm, ::pop, padding,
                                note = if (genre != null && full != null && album !== full) {
                                    "Only ${genre.name} songs · ${full.tracks.size} on the album"
                                } else null,
                            )
                        }
                        is Screen.ArtistDetail -> ArtistScreen(
                            library.artists.firstOrNull { it.name == screen.name }, playerVm, ::pop, padding,
                        )
                        Screen.NowPlaying -> NowPlayingScreen(playerVm, ::pop, { push(Screen.Queue) }, padding)
                        Screen.Queue -> QueueScreen(playerVm, ::pop, padding)
                        Screen.DriveFolders -> DriveFolderPickerScreen(libraryVm, ::pop, padding)
                        Screen.RecentTracks -> TrackListScreen("Recently Played", playlists.recentTracks, playerVm, ::pop, padding)
                        Screen.MostPlayed -> {
                            val counts = playlists.mostPlayed.associate { it.item.id to it.plays }
                            TrackListScreen("Most Played", playlists.mostPlayed.map { it.item }, playerVm, ::pop, padding) { t ->
                                counts[t.id]?.let { if (it == 1) "1 play" else "$it plays" }
                            }
                        }
                        Screen.Favourites -> TrackListScreen("Favourite Songs", playlists.favourites, playerVm, ::pop, padding)
                        Screen.Replay -> ReplayScreen(libraryVm, playerVm, onOpenAlbum = { push(Screen.AlbumDetail(it)) }, onBack = ::pop, contentPadding = padding)
                        is Screen.GenreDetail -> {
                            val genre = genres.firstOrNull { it.key == screen.key }
                            AlbumListScreen(
                                genre?.name ?: "", genre?.albums.orEmpty(), playerVm,
                                onOpenAlbum = { push(Screen.AlbumDetail(it, genreKey = screen.key)) }, onBack = ::pop, contentPadding = padding,
                            )
                        }
                        is Screen.MixDetail -> {
                            val mix = mixes.firstOrNull { it.id == screen.id }
                            TrackListScreen(mix?.title ?: "", mix?.tracks, playerVm, ::pop, padding)
                        }
                        Screen.RecentAlbums -> AlbumListScreen(
                            "Recently Played Albums", playlists.recentAlbums, playerVm,
                            onOpenAlbum = { push(Screen.AlbumDetail(it)) }, onBack = ::pop, contentPadding = padding,
                        )
                        Screen.Sources -> SourcesScreen(
                            libraryVm,
                            onBack = ::pop,
                            onAddFolder = { folderPicker.launch(null) },
                            onConnectDrive = {
                                libraryVm.connectDrive { pending ->
                                    driveConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                }
                            },
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
