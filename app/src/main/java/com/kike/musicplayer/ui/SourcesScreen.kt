package com.kike.musicplayer.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.data.db.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onAddFolder: () -> Unit,
    onConnectDrive: () -> Unit,
    contentPadding: PaddingValues,
) {
    val library by vm.library.collectAsState()
    val hasPermission by vm.hasAudioPermission.collectAsState()
    val folders by vm.safFolders.collectAsState()
    val account by vm.driveAccount.collectAsState()
    val driveMessage by vm.driveMessage.collectAsState()
    val online by vm.onlineMetadata.collectAsState()
    val pending by vm.pendingMetadata.collectAsState()
    val progress by vm.metadataProgress.collectAsState()
    val status by vm.scanStatus.collectAsState()
    val error by vm.lastError.collectAsState()
    val counts = library.tracks.groupingBy { it.source }.eachCount()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text("Sources & metadata") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            SourceCard("This device", "${counts[Track.SOURCE_MEDIASTORE] ?: 0} songs found by Android's media library") {
                if (hasPermission) OutlinedButton(onClick = { vm.refreshAll() }) { Text("Rescan all") }
                else Button(onClick = onRequestPermission) { Text("Allow access to music") }
            }

            SourceCard(
                "Folders",
                "${counts[Track.SOURCE_SAF] ?: 0} songs from folders you picked (SD cards, USB, other apps' folders)",
            ) {
                folders.forEach { uri ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folderLabel(uri), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.removeFolder(uri) }) { Icon(Icons.Default.Delete, "Remove folder") }
                    }
                }
                Button(onClick = onAddFolder) { Text("Add folder") }
            }

            SourceCard(
                "Google Drive",
                if (account == null) "Stream audio files from your Drive without downloading them"
                else "Connected${if (account!!.isNotBlank()) " as $account" else ""} · ${counts[Track.SOURCE_DRIVE] ?: 0} songs",
            ) {
                driveMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (account == null) {
                    Button(onClick = onConnectDrive) { Text("Connect Google Drive") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.refreshDrive() }) { Text("Sync") }
                        OutlinedButton(onClick = { vm.disconnectDrive() }) { Text("Disconnect") }
                    }
                }
            }

            SourceCard(
                "Metadata & cover art",
                "Tags and artwork are read from the files first. Songs still missing details are looked up on " +
                    "MusicBrainz, with covers from the Cover Art Archive.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Look up missing info online", Modifier.weight(1f))
                    Switch(checked = online, onCheckedChange = vm::setOnlineMetadata)
                }
                Text(
                    progress?.let { "${it.stage}: ${it.done} of ${it.total}" } ?: "$pending songs waiting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.retryMetadata() }) { Text("Retry songs with missing info") }
            }
        }
    }
}

@Composable
private fun SourceCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** "content://.../tree/primary%3AMusic%2FFLAC" -> "primary: Music/FLAC" */
private fun folderLabel(uri: String): String =
    Uri.parse(uri).lastPathSegment?.replace(":", ": ") ?: uri
