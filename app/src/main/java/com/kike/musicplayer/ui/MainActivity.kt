package com.kike.musicplayer.ui

import android.Manifest
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.kike.musicplayer.ui.theme.MusicPlayerTheme

private sealed interface Screen {
    data object Library : Screen
    data class AlbumDetail(val key: String) : Screen
    data class ArtistDetail(val name: String) : Screen
    data object NowPlaying : Screen
    data object Queue : Screen
    data object Sources : Screen
    data object DriveFolders : Screen
}

class MainActivity : ComponentActivity() {
    private val libraryVm: LibraryViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MusicPlayerTheme { App() } }
    }

    @Composable
    private fun App() {
        val backStack = remember { mutableStateListOf<Screen>(Screen.Library) }
        val screen = backStack.last()
        fun push(s: Screen) = backStack.add(s)
        fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
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
        val showMini = screen !is Screen.NowPlaying && screen !is Screen.Queue && screen !is Screen.DriveFolders

        Scaffold(bottomBar = { if (showMini) MiniPlayer(playerVm) { push(Screen.NowPlaying) } }) { padding: PaddingValues ->
            when (screen) {
                Screen.Library -> LibraryScreen(
                    libraryVm, playerVm,
                    onOpenAlbum = { push(Screen.AlbumDetail(it)) },
                    onOpenArtist = { push(Screen.ArtistDetail(it)) },
                    onOpenSources = { push(Screen.Sources) },
                    contentPadding = padding,
                )
                is Screen.AlbumDetail -> AlbumScreen(
                    library.albums.firstOrNull { it.key == screen.key }, playerVm, ::pop, padding,
                )
                is Screen.ArtistDetail -> ArtistScreen(
                    library.artists.firstOrNull { it.name == screen.name }, playerVm, ::pop, padding,
                )
                Screen.NowPlaying -> NowPlayingScreen(playerVm, ::pop, { push(Screen.Queue) }, padding)
                Screen.Queue -> QueueScreen(playerVm, ::pop, padding)
                Screen.DriveFolders -> DriveFolderPickerScreen(libraryVm, ::pop, padding)
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
