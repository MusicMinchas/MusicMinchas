package com.kike.musicplayer.ui

import androidx.compose.ui.res.stringResource
import com.kike.musicplayer.R

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Settings: the music sources (own page), downloads, Last.fm and metadata. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    onOpenMusicSources: () -> Unit,
    contentPadding: PaddingValues,
) {
    val online by vm.onlineMetadata.collectAsState()
    val pending by vm.pendingMetadata.collectAsState()
    val progress by vm.metadataProgress.collectAsState()
    val status by vm.scanStatus.collectAsState()
    val error by vm.lastError.collectAsState()
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
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            MusicSourcesEntry(vm, onOpenMusicSources)

            LanguageCard(vm)

            AppearanceCard(vm)

            SonosPlaybackCard(vm)

            SourceCard(
                stringResource(R.string.sources_downloads),
                stringResource(R.string.sources_downloads_subtitle),
            ) {
                val songs = downloads?.songs ?: 0
                Text(
                    if (songs == 0) stringResource(R.string.sources_downloads_none) else "${count(songs, "song")} · ${formatBytes(downloads!!.bytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (songs > 0) {
                    OutlinedButton(onClick = { confirmDeleteDownloads = true }) { Text(stringResource(R.string.sources_delete_all_downloads)) }
                }
            }
            if (confirmDeleteDownloads) {
                AlertDialog(
                    onDismissRequest = { confirmDeleteDownloads = false },
                    title = { Text(stringResource(R.string.sources_delete_downloads_confirm)) },
                    text = {
                        Text(stringResource(R.string.sources_delete_downloads_dialog_message, downloads?.songs ?: 0))
                    },
                    confirmButton = {
                        TextButton(onClick = { confirmDeleteDownloads = false; vm.removeAllDownloads() }) { Text(stringResource(R.string.action_delete)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmDeleteDownloads = false }) { Text(stringResource(R.string.action_cancel)) } },
                )
            }

            SourceCard(
                "Last.fm",
                when {
                    lastFm.user != null -> stringResource(R.string.sources_lastfm_connected_as, lastFm.user!!)
                    lastFm.awaitingApproval -> stringResource(R.string.sources_lastfm_awaiting_approval)
                    else -> stringResource(R.string.sources_lastfm_subtitle_connected)
                },
            ) {
                lastFm.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                when {
                    lastFm.user != null -> {
                        ScrobbleControls(scrobbling, pendingScrobbles, vm::setScrobbleSettings)
                        HistoryImport(scrobbleCounts, importStatus, vm::importScrobbles)
                        LovedTracks(importStatus, vm::importLoved)
                        OutlinedButton(onClick = vm::disconnectLastFm) { Text(stringResource(R.string.sources_disconnect)) }
                    }
                    lastFm.awaitingApproval -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.connectLastFm { uriHandler.openUri(it.toString()) } }) { Text(stringResource(R.string.sources_lastfm_open_again)) }
                        OutlinedButton(onClick = vm::cancelLastFmConnect) { Text(stringResource(R.string.action_cancel)) }
                    }
                    else -> Button(onClick = { vm.connectLastFm { uriHandler.openUri(it.toString()) } }) { Text(stringResource(R.string.sources_lastfm_connect)) }
                }
            }

            MetadataCard(vm, pending, progress)

            SyncCard(vm)
        }
    }
}

@Composable
private fun MetadataCard(
    vm: LibraryViewModel,
    pending: Int,
    progress: com.kike.musicplayer.data.metadata.MetadataEnricher.Progress?,
) {
    val deezer by vm.metaDeezer.collectAsState()
    val apple by vm.metaApple.collectAsState()
    val mb by vm.metaMusicBrainz.collectAsState()
    val anyOnline = deezer || apple || mb
    SourceCard(
        stringResource(R.string.sources_metadata_title),
        stringResource(R.string.sources_metadata_intro),
    ) {
        ProviderToggleRow(
            title = "Deezer",
            subtitle = stringResource(R.string.sources_metadata_deezer_subtitle),
            checked = deezer,
            onCheckedChange = vm::setMetaDeezer,
        )
        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        ProviderToggleRow(
            title = "Apple Music",
            subtitle = stringResource(R.string.sources_metadata_apple_subtitle),
            checked = apple,
            onCheckedChange = vm::setMetaApple,
        )
        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        ProviderToggleRow(
            title = stringResource(R.string.sources_metadata_musicbrainz_title),
            subtitle = stringResource(R.string.sources_metadata_musicbrainz_subtitle),
            checked = mb,
            onCheckedChange = vm::setMetaMusicBrainz,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            progress?.let { "${it.stage}: ${it.done} of ${it.total}" }
                ?: stringResource(R.string.sources_metadata_pending_songs, pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { vm.retryMetadata() }, enabled = anyOnline && progress == null) {
            Text(stringResource(R.string.sources_metadata_fetch_now))
        }
    }
}

@Composable
private fun ProviderToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.padding(start = 8.dp))
    }
}

/** One switch (and a button) instead of importing and syncing in each service's card. */
@Composable
private fun SyncCard(vm: LibraryViewModel) {
    val sync by vm.autoSync.collectAsState()
    SourceCard(
        stringResource(R.string.sources_sync_card_title),
        stringResource(R.string.sources_sync_card_subtitle),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sources_sync_card_subtitle),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = sync.enabled, onCheckedChange = vm::setAutoSync, modifier = Modifier.padding(start = 8.dp))
        }
        if (sync.lastRun > 0) {
            Text(stringResource(R.string.sources_last_synced, relativeTime(sync.lastRun)), style = MaterialTheme.typography.labelMedium)
            sync.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm::syncEverything, enabled = !sync.running) { Text(stringResource(R.string.sources_sync_everything)) }
            if (sync.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** The settings row that opens Music sources: each source with its song count. */
@Composable
private fun MusicSourcesEntry(vm: LibraryViewModel, onOpen: () -> Unit) {
    val counts by vm.sourceCounts.collectAsState()
    val account by vm.driveAccount.collectAsState()
    val server by vm.subsonicState.collectAsState()
    val catalog by vm.catalogEnabled.collectAsState()
    val needsReconnect by vm.driveNeedsReconnect.collectAsState()
    val number = java.text.NumberFormat.getIntegerInstance()
    val songParts = buildList {
        counts[Track.SOURCE_SAF]?.takeIf { it > 0 }?.let { add("${stringResource(R.string.source_local)} ${number.format(it)}") }
        if (account != null) add("GDrive ${number.format(counts[Track.SOURCE_DRIVE] ?: 0)}")
        if (server.address != null) {
            add("${server.server?.substringBefore(' ') ?: "Navidrome"} ${number.format(counts[Track.SOURCE_SUBSONIC] ?: 0)}")
        }
    }
    val summary = buildString {
        if (songParts.isNotEmpty()) append(songParts.joinToString(" · ")).append(" songs")
        if (catalog) {
            if (isNotEmpty()) append(" · ")
            append("Deezer / Apple Music")
        }
        if (isEmpty()) append("Folders on this device, Google Drive, Navidrome, Deezer & Apple Music")
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.sources_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (needsReconnect && account != null) {
                    Text(stringResource(R.string.sources_drive_sign_in_again), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** Where the music comes from: folders on the phone, Google Drive and a Subsonic server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSourcesScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    onConnectDrive: () -> Unit,
    onAddDriveFolder: () -> Unit,
    contentPadding: PaddingValues,
) {
    val folders by vm.safFolders.collectAsState()
    val account by vm.driveAccount.collectAsState()
    val driveFolders by vm.driveFolders.collectAsState()
    val driveMessage by vm.driveMessage.collectAsState()
    val status by vm.scanStatus.collectAsState()
    val error by vm.lastError.collectAsState()
    val unplayable by vm.unplayableCount.collectAsState()
    val syncMode by vm.syncMode.collectAsState()
    val lastSync by vm.lastDriveSync.collectAsState()
    val counts by vm.sourceCounts.collectAsState()
    val downloads by vm.downloadTotals.collectAsState()
    var confirmDisconnect by remember { mutableStateOf(false) }
    val needsReconnect by vm.driveNeedsReconnect.collectAsState()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.sources_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            SourceCard(
                stringResource(R.string.sources_saf_title),
                stringResource(R.string.sources_saf_subtitle_pattern, counts[Track.SOURCE_SAF] ?: 0),
            ) {
                folders.forEach { uri ->
                    FolderRow(Icons.Default.Folder, folderLabel(uri)) { vm.removeFolder(uri) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddFolder) { Text(stringResource(R.string.sources_saf_add_folder)) }
                    if (folders.isNotEmpty()) OutlinedButton(onClick = { vm.refreshAll() }) { Text(stringResource(R.string.sources_rescan)) }
                }
            }

            SourceCard(
                "Google Drive",
                when {
                    account == null -> stringResource(R.string.sources_drive_subtitle_disconnected)
                    account!!.isNotBlank() -> stringResource(R.string.sources_drive_connected_as_count, account!!, counts[Track.SOURCE_DRIVE] ?: 0)
                    else -> stringResource(R.string.sources_drive_connected_count, counts[Track.SOURCE_DRIVE] ?: 0)
                },
            ) {
                driveMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (account != null && needsReconnect) {
                    Text(
                        stringResource(R.string.sources_drive_reconnect_desc),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (account == null) {
                    Button(onClick = onConnectDrive) { Text(stringResource(R.string.sources_drive_connect)) }
                } else {
                    if (driveFolders.isEmpty()) {
                        Text(
                            stringResource(R.string.sources_drive_choose_folders),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (unplayable > 0) {
                        Text(
                            stringResource(R.string.sources_unplayable_skipped, unplayable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    driveFolders.forEach { folder ->
                        FolderRow(Icons.Default.Cloud, folder.name) { vm.removeDriveFolder(folder.id) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAddDriveFolder) { Text(stringResource(R.string.sources_add_drive_folder)) }
                        if (driveFolders.isNotEmpty()) OutlinedButton(onClick = { vm.refreshDrive() }) { Text(stringResource(R.string.action_sync)) }
                    }
                    if (driveFolders.isNotEmpty()) {
                        Text(stringResource(R.string.sources_sync_card_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        DriveSyncMode.entries.forEach { mode ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = syncMode == mode, onClick = { vm.setSyncMode(mode) }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = syncMode == mode, onClick = null)
                                Text(stringResource(mode.labelRes), Modifier.padding(start = 8.dp))
                            }
                        }
                        Text(
                            if (lastSync == 0L) stringResource(R.string.sources_not_synced_yet) else stringResource(R.string.sources_last_synced, relativeTime(lastSync)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (needsReconnect) Button(onClick = onConnectDrive) { Text(stringResource(R.string.sources_drive_reconnect)) }
                        else OutlinedButton(onClick = onConnectDrive) { Text(stringResource(R.string.sources_drive_reconnect)) }
                        OutlinedButton(onClick = { confirmDisconnect = true }) { Text(stringResource(R.string.sources_disconnect)) }
                    }
                }
            }
            if (confirmDisconnect) {
                DisconnectDriveDialog(
                    songs = counts[Track.SOURCE_DRIVE] ?: 0,
                    downloaded = downloads?.songs ?: 0,
                    onDismiss = { confirmDisconnect = false },
                    onConfirm = { deleteDownloads -> confirmDisconnect = false; vm.disconnectDrive(deleteDownloads) },
                )
            }

            ServerCard(vm)

            NebulaCard(vm)

            CatalogSourceCard(vm)
        }
    }
}

/** Public catalog & playlist discovery via Deezer and Apple Music (no account, no audio streaming). */
@Composable
private fun CatalogSourceCard(vm: LibraryViewModel) {
    val enabled by vm.catalogEnabled.collectAsState()
    val deezerHomeEnrichment by vm.deezerHomeEnrichment.collectAsState()
    SourceCard(
        stringResource(R.string.sources_catalog_title),
        if (enabled) stringResource(R.string.sources_catalog_subtitle_enabled)
        else stringResource(R.string.sources_catalog_subtitle_disabled),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sources_catalog_enable_toggle), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    vm.setCatalogEnabled(checked)
                    if (!checked) vm.setDeezerHomeEnrichment(false)
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sources_catalog_enrich_home), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.sources_catalog_enrich_home_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = deezerHomeEnrichment,
                onCheckedChange = { checked ->
                    vm.setDeezerHomeEnrichment(checked)
                    if (checked && !enabled) {
                        vm.setCatalogEnabled(true)
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            stringResource(R.string.sources_catalog_explanation_what),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.sources_catalog_explanation_limitations),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Disconnecting removes the Drive songs from the library; downloads stay unless ticked. */
@Composable
private fun DisconnectDriveDialog(songs: Int, downloaded: Int, onDismiss: () -> Unit, onConfirm: (deleteDownloads: Boolean) -> Unit) {
    var deleteDownloads by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sources_drive_disconnect_confirm)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.sources_drive_disconnect_dialog_message, count(songs, "song"))
                )
                if (downloaded > 0) {
                    Row(
                        Modifier.fillMaxWidth().clickable { deleteDownloads = !deleteDownloads },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = deleteDownloads, onCheckedChange = null)
                        Text(
                            stringResource(R.string.sources_drive_delete_downloads_checkbox),
                            Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteDownloads) }) { Text(stringResource(R.string.sources_disconnect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Scrobbling on/off and when: X% of the song or Y minutes, whichever comes first (never below 50% / 4 min). */
@Composable
private fun ScrobbleControls(settings: ScrobbleSettings, pending: Int, onChange: (ScrobbleSettings) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sources_lastfm_scrobble_plays), Modifier.weight(1f))
        Switch(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) })
    }
    if (!settings.enabled) return
    // Local slider positions while dragging; saved when the finger lifts.
    var percent by remember(settings.rule.percent) { mutableFloatStateOf(settings.rule.percent.toFloat()) }
    var cap by remember(settings.rule.capMinutes) { mutableFloatStateOf((settings.rule.capMinutes ?: NO_CAP).toFloat()) }
    fun save() = onChange(
        settings.copy(rule = ScrobbleRule(percent.roundToInt(), cap.roundToInt().takeIf { it < NO_CAP }))
    )
    Text(stringResource(R.string.sources_lastfm_scrobble_percent, percent.roundToInt()), style = MaterialTheme.typography.bodyMedium)
    DotSlider(
        value = percent, onValueChange = { percent = it }, onValueChangeFinished = ::save,
        valueRange = ScrobbleRule.MIN_PERCENT.toFloat()..100f, steps = 9,
        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
    )
    val capLabel = cap.roundToInt().let { if (it >= NO_CAP) stringResource(R.string.sources_lastfm_no_time_limit) else stringResource(R.string.sources_lastfm_minutes_cap, it) }
    Text(stringResource(R.string.sources_lastfm_scrobble_cap, capLabel), style = MaterialTheme.typography.bodyMedium)
    DotSlider(
        value = cap, onValueChange = { cap = it }, onValueChangeFinished = ::save,
        valueRange = ScrobbleRule.MIN_CAP_MINUTES.toFloat()..NO_CAP.toFloat(),
        steps = NO_CAP - ScrobbleRule.MIN_CAP_MINUTES - 1,
        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
    )
    Text(
        stringResource(R.string.sources_lastfm_min_duration_note) +
            if (pending > 0) " " + stringResource(R.string.sources_lastfm_pending_scrobbles, pending) else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Importing the Last.fm history as plays. */
@Composable
private fun HistoryImport(counts: ScrobbleCounts?, status: ImportStatus, onImport: (everything: Boolean) -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.sources_lastfm_history_title), style = MaterialTheme.typography.labelLarge)
    Text(
        when {
            counts == null || counts.total == 0 ->
                stringResource(R.string.sources_lastfm_import_history_desc)
            else -> stringResource(R.string.sources_lastfm_imported_count, counts.total) +
                if (counts.unmatched > 0) stringResource(R.string.sources_lastfm_unmatched_count, counts.unmatched) else ""
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status.text?.takeIf { !status.loved }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val first = counts == null || counts.total == 0
        Button(onClick = { onImport(first) }, enabled = !status.running) {
            Text(if (first) stringResource(R.string.sources_lastfm_import_history_button) else stringResource(R.string.sources_lastfm_import_new_button))
        }
        if (!first) OutlinedButton(onClick = { onImport(true) }, enabled = !status.running) { Text(stringResource(R.string.sources_lastfm_reimport_all)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageCard(vm: LibraryViewModel) {
    val language by vm.language.collectAsState()
    SourceCard(stringResource(R.string.settings_language_title), stringResource(R.string.settings_language_subtitle)) {
        val options = listOf(
            "system" to stringResource(R.string.settings_language_system),
            "en" to stringResource(R.string.settings_language_en),
            "es" to stringResource(R.string.settings_language_es),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (tag, label) ->
                SegmentedButton(
                    selected = language == tag,
                    onClick = { vm.setLanguage(tag) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

/** Appearance: light or dark (black and red) theme, and Now Playing's full-width cover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(vm: LibraryViewModel) {
    val dark by vm.darkMode.collectAsState()
    val fullCover by vm.fullScreenCover.collectAsState()
    SourceCard(stringResource(R.string.settings_appearance_title), stringResource(R.string.settings_appearance_subtitle)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to stringResource(R.string.settings_theme_light), true to stringResource(R.string.settings_theme_dark)).forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = dark == value,
                    onClick = { vm.setDarkMode(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_edge_to_edge_title))
                Text(
                    stringResource(R.string.settings_edge_to_edge_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = fullCover, onCheckedChange = vm::setFullScreenCover, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SonosPlaybackCard(vm: LibraryViewModel) {
    val downsample by vm.sonosDownsample.collectAsState()
    SourceCard(
        stringResource(R.string.settings_sonos_title),
        stringResource(R.string.settings_sonos_subtitle),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_sonos_downsample_title))
                Text(
                    stringResource(R.string.settings_sonos_downsample_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = downsample, onCheckedChange = vm::setSonosDownsample, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
internal fun SourceCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
@Composable
internal fun relativeTime(time: Long): String {
    val minutes = (System.currentTimeMillis() - time) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.relative_time_just_now)
        minutes < 60 -> stringResource(R.string.relative_time_minutes_ago, minutes.toInt())
        minutes < 48 * 60 -> stringResource(R.string.relative_time_hours_ago, (minutes / 60).toInt())
        else -> stringResource(R.string.relative_time_days_ago, (minutes / (24 * 60)).toInt())
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
