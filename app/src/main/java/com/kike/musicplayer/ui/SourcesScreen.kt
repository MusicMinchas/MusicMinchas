package com.kike.musicplayer.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kike.musicplayer.data.lastfm.ScrobbleRule
import com.kike.musicplayer.data.lastfm.ImportStatus
import com.kike.musicplayer.data.db.ScrobbleCounts
import androidx.compose.foundation.layout.size
import com.kike.musicplayer.data.lastfm.ScrobbleSettings
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import com.kike.musicplayer.data.DriveSyncMode
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
    val syncMode by vm.syncMode.collectAsState()
    val lastSync by vm.lastDriveSync.collectAsState()
    val counts = library.tracks.groupingBy { it.source }.eachCount()
    val downloads by vm.downloadTotals.collectAsState()
    var confirmDeleteDownloads by remember { mutableStateOf(false) }
    val lastFm by vm.lastFmState.collectAsState()
    val scrobbling by vm.scrobbleSettings.collectAsState()
    val pendingScrobbles by vm.pendingScrobbles.collectAsState()
    val importStatus by vm.importStatus.collectAsState()
    val scrobbleCounts by vm.scrobbleCounts.collectAsState()
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
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
                    if (driveFolders.isNotEmpty()) {
                        Text("Automatic sync", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        DriveSyncMode.entries.forEach { mode ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = syncMode == mode, onClick = { vm.setSyncMode(mode) }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = syncMode == mode, onClick = null)
                                Text(mode.label, Modifier.padding(start = 8.dp))
                            }
                        }
                        Text(
                            if (lastSync == 0L) "Not synced yet" else "Last synced ${relativeTime(lastSync)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { vm.disconnectDrive() }) { Text("Disconnect") }
                }
            }

            SourceCard(
                "Downloaded songs",
                "Google Drive songs kept on the phone (Music/MusicMinchas), so they play without internet",
            ) {
                val songs = downloads?.songs ?: 0
                Text(
                    if (songs == 0) "No songs downloaded" else "${if (songs == 1) "1 song" else "$songs songs"} · ${formatBytes(downloads!!.bytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (songs > 0) {
                    OutlinedButton(onClick = { confirmDeleteDownloads = true }) { Text("Delete all downloads") }
                }
            }
            if (confirmDeleteDownloads) {
                AlertDialog(
                    onDismissRequest = { confirmDeleteDownloads = false },
                    title = { Text("Delete all downloads?") },
                    text = {
                        Text(
                            "The ${downloads?.songs ?: 0} downloaded songs are removed from the phone and play from " +
                                "Google Drive again. Plays, favourites and the files on Drive are not affected."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { confirmDeleteDownloads = false; vm.removeAllDownloads() }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { confirmDeleteDownloads = false }) { Text("Cancel") } },
                )
            }

            SourceCard(
                "Last.fm",
                when {
                    lastFm.user != null -> "Connected as ${lastFm.user}"
                    lastFm.awaitingApproval -> "Approve MusicMinchas on the Last.fm page, then come back here"
                    else -> "Scrobble what you play and bring in your Last.fm history and loved tracks"
                },
            ) {
                lastFm.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                when {
                    lastFm.user != null -> {
                        ScrobbleControls(scrobbling, pendingScrobbles, vm::setScrobbleSettings)
                        HistoryImport(scrobbleCounts, importStatus, vm::importScrobbles)
                        LovedTracks(importStatus, vm::importLoved)
                        OutlinedButton(onClick = vm::disconnectLastFm) { Text("Disconnect") }
                    }
                    lastFm.awaitingApproval -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.connectLastFm { uriHandler.openUri(it.toString()) } }) { Text("Open Last.fm again") }
                        OutlinedButton(onClick = vm::cancelLastFmConnect) { Text("Cancel") }
                    }
                    else -> Button(onClick = { vm.connectLastFm { uriHandler.openUri(it.toString()) } }) { Text("Connect Last.fm") }
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

/** Scrobbling on/off and when: X% of the song or Y minutes, whichever comes first (never below 50% / 4 min). */
@Composable
private fun ScrobbleControls(settings: ScrobbleSettings, pending: Int, onChange: (ScrobbleSettings) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Scrobble plays", Modifier.weight(1f))
        Switch(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) })
    }
    if (!settings.enabled) return
    // Local slider positions while dragging; saved when the finger lifts.
    var percent by remember(settings.rule.percent) { mutableFloatStateOf(settings.rule.percent.toFloat()) }
    var cap by remember(settings.rule.capMinutes) { mutableFloatStateOf((settings.rule.capMinutes ?: NO_CAP).toFloat()) }
    fun save() = onChange(
        settings.copy(rule = ScrobbleRule(percent.roundToInt(), cap.roundToInt().takeIf { it < NO_CAP }))
    )
    Text("After ${percent.roundToInt()}% of the song", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = percent, onValueChange = { percent = it }, onValueChangeFinished = ::save,
        valueRange = ScrobbleRule.MIN_PERCENT.toFloat()..100f, steps = 9,
    )
    val capLabel = cap.roundToInt().let { if (it >= NO_CAP) "no time limit" else "$it minutes" }
    Text("…or after $capLabel, whichever comes first", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = cap, onValueChange = { cap = it }, onValueChangeFinished = ::save,
        valueRange = ScrobbleRule.MIN_CAP_MINUTES.toFloat()..NO_CAP.toFloat(),
        steps = NO_CAP - ScrobbleRule.MIN_CAP_MINUTES - 1,
    )
    Text(
        "Songs of 30 seconds or less aren't scrobbled." +
            if (pending > 0) " $pending ${if (pending == 1) "scrobble" else "scrobbles"} waiting to be sent." else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Importing the Last.fm history as plays. */
@Composable
private fun HistoryImport(counts: ScrobbleCounts?, status: ImportStatus, onImport: (everything: Boolean) -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("Listening history", style = MaterialTheme.typography.labelLarge)
    Text(
        when {
            counts == null || counts.total == 0 ->
                "Import your scrobbles: plays of songs in your library are added to your plays " +
                    "(Replay, Most Played, mixes)."
            else -> "${counts.total} scrobbles imported" +
                if (counts.unmatched > 0) " · ${counts.unmatched} of songs not in your library yet " +
                    "(added as plays when you add those songs)" else ""
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status.text?.takeIf { !status.loved }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val first = counts == null || counts.total == 0
        Button(onClick = { onImport(first) }, enabled = !status.running) {
            Text(if (first) "Import history" else "Import new scrobbles")
        }
        if (!first) OutlinedButton(onClick = { onImport(true) }, enabled = !status.running) { Text("Import all again") }
        if (status.running && !status.loved) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** Favourites and Last.fm loved tracks. */
@Composable
private fun LovedTracks(status: ImportStatus, onImport: () -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("Loved tracks", style = MaterialTheme.typography.labelLarge)
    Text(
        "Favouriting a song loves it on Last.fm, and removing it unloves it. Import your loved tracks " +
            "to add those in your library to your favourites.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status.text?.takeIf { status.loved }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onImport, enabled = !status.running) { Text("Import loved tracks") }
        if (status.running && status.loved) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** The time slider's last position: no time limit. */
private const val NO_CAP = ScrobbleRule.MAX_CAP_MINUTES + 1

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

/** "850 KB", "312 MB", "4.2 GB" */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    else -> "${(bytes shr 10).coerceAtLeast(1)} KB"
}

/** "just now", "5 min ago", "3 h ago", "2 days ago" */
private fun relativeTime(time: Long): String {
    val minutes = (System.currentTimeMillis() - time) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
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
            windowInsets = NoTopInsets,
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
                Text(if (canSelect) "Use “${current.name}”" else "Open a folder to select it", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
