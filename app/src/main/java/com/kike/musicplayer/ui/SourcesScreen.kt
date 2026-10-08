package com.kike.musicplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
    onAddFolder: () -> Unit,
    onConnectDrive: () -> Unit,
    onAddDriveFolder: () -> Unit,
    contentPadding: PaddingValues,
) {
    val library by vm.library.collectAsState()
    val folders by vm.safFolders.collectAsState()
    val account by vm.driveAccount.collectAsState()
    val driveFolders by vm.driveFolders.collectAsState()
    val driveMessage by vm.driveMessage.collectAsState()
    val online by vm.onlineMetadata.collectAsState()
    val pending by vm.pendingMetadata.collectAsState()
    val progress by vm.metadataProgress.collectAsState()
    val status by vm.scanStatus.collectAsState()
    val error by vm.lastError.collectAsState()
    val unplayable by vm.unplayableCount.collectAsState()
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

            SourceCard(
                "Folders on this device",
                "${counts[Track.SOURCE_SAF] ?: 0} songs. Only the folders listed here are scanned, " +
                    "including their sub-folders (internal storage, SD card, USB).",
            ) {
                folders.forEach { uri ->
                    FolderRow(Icons.Default.Folder, folderLabel(uri)) { vm.removeFolder(uri) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddFolder) { Text("Add folder") }
                    if (folders.isNotEmpty()) OutlinedButton(onClick = { vm.refreshAll() }) { Text("Rescan") }
                }
            }

            SourceCard(
                "Google Drive",
                when {
                    account == null -> "Stream audio files from your Drive without downloading them"
                    else -> "Connected${if (account!!.isNotBlank()) " as $account" else ""} · " +
                        "${counts[Track.SOURCE_DRIVE] ?: 0} songs"
                },
            ) {
                driveMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (account == null) {
                    Button(onClick = onConnectDrive) { Text("Connect Google Drive") }
                } else {
                    if (driveFolders.isEmpty()) {
                        Text(
                            "Choose the Drive folders that hold your music. Only those folders and their " +
                                "sub-folders are imported.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (unplayable > 0) {
                        Text(
                            "$unplayable files skipped: no playable audio (usually DRM-protected Apple Music downloads)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    driveFolders.forEach { folder ->
                        FolderRow(Icons.Default.Cloud, folder.name) { vm.removeDriveFolder(folder.id) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAddDriveFolder) { Text("Add Drive folder") }
                        if (driveFolders.isNotEmpty()) OutlinedButton(onClick = { vm.refreshDrive() }) { Text("Sync") }
                    }
                    OutlinedButton(onClick = { vm.disconnectDrive() }) { Text("Disconnect") }
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
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remove folder") }
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

/** "content://.../tree/primary%3AMusic%2FFLAC" -> "Internal storage / Music/FLAC" */
private fun folderLabel(uri: String): String {
    val docId = Uri.parse(uri).lastPathSegment ?: return uri
    val volume = docId.substringBefore(':')
    val path = docId.substringAfter(':', "")
    val volumeName = if (volume == "primary") "Internal storage" else "Storage $volume"
    return if (path.isEmpty()) volumeName else "$volumeName / $path"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveFolderPickerScreen(vm: LibraryViewModel, onClose: () -> Unit, contentPadding: PaddingValues) {
    val state by vm.driveBrowser.collectAsState()
    val back = { if (!vm.driveBrowserBack()) onClose() }
    BackHandler(onBack = back)
    val current = state.path.lastOrNull()
    val canSelect = current != null && current.id != DriveBrowserState.SHARED_ID

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = {
                Text(current?.name ?: "Choose a Drive folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (state.path.size > 1) {
            Text(
                state.path.joinToString(" / ") { it.name },
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(
                    state.error!!, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    val entries = if (state.path.isEmpty()) DriveBrowserState.TOP_LEVEL else state.children
                    if (entries.isEmpty()) {
                        Text("No sub-folders", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.id }) { folder ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.enterDriveFolder(folder) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (folder.id == DriveBrowserState.SHARED_ID) Icons.Default.FolderShared else Icons.Default.Folder,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Box(Modifier.width(16.dp))
                                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Button(
                onClick = { vm.selectCurrentDriveFolder(onClose) },
                enabled = canSelect,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(if (canSelect) "Use “${current!!.name}”" else "Open a folder to select it", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
