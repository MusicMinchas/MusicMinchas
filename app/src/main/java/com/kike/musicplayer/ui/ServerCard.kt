package com.kike.musicplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.R
import com.kike.musicplayer.data.DriveSyncMode
import com.kike.musicplayer.data.db.Track
import java.text.NumberFormat

/** Signing in to a Navidrome (or other Subsonic) server. */
@Composable
internal fun ServerCard(vm: LibraryViewModel) {
    val state by vm.subsonicState.collectAsState()
    val connected = state.address != null
    var confirmDisconnect by remember { mutableStateOf(false) }
    // Fresh song count and folders each time the settings open.
    LaunchedEffect(connected) { if (connected) vm.checkSubsonic() }

    SourceCard(
        "Navidrome",
        if (!connected) stringResource(R.string.server_subtitle_disconnected)
        else buildString {
            append(stringResource(R.string.server_connected_as, state.username ?: "", state.server ?: "Navidrome"))
            state.songCount?.let { append(" · " + stringResource(R.string.server_songs_count, NumberFormat.getIntegerInstance().format(it))) }
        },
    ) {
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!connected) {
            SignInFields(state.busy) { address, user, password -> vm.connectSubsonic(address, user, password) }
        } else {
            Text(state.address!!, style = MaterialTheme.typography.bodyMedium)
            if (state.folders.isNotEmpty()) {
                Text(
                    stringResource(R.string.server_libraries, state.folders.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.checkedAt > 0) {
                Text(
                    stringResource(R.string.server_last_answered, relativeTime(state.checkedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ServerSyncControls(vm)
            CollectionsSyncInfo(vm)
            PlayReports(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.checkSubsonic() }, enabled = !state.busy) { Text(stringResource(R.string.server_check_connection)) }
                OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !state.busy) { Text(stringResource(R.string.sources_disconnect)) }
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
    if (confirmDisconnect) {
        val songs = vm.sourceCounts.collectAsState().value[Track.SOURCE_SUBSONIC] ?: 0
        val downloaded by vm.serverDownloads.collectAsState()
        var deleteDownloads by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.server_disconnect_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.server_disconnect_message_with_count, formatSongCount(songs))
                    )
                    if (downloaded > 0) {
                        Row(
                            Modifier.fillMaxWidth().clickable { deleteDownloads = !deleteDownloads },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = deleteDownloads, onCheckedChange = null)
                            Text(
                                if (downloaded == 1) stringResource(R.string.server_delete_downloads_checkbox_one)
                                else stringResource(R.string.server_delete_downloads_checkbox_many, downloaded),
                                Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; vm.disconnectSubsonic(deleteDownloads && downloaded > 0) }) { Text(stringResource(R.string.sources_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Listing Navidrome's songs into the library: now, or automatically. */
@Composable
private fun ServerSyncControls(vm: LibraryViewModel) {
    val sync by vm.serverSync.collectAsState()
    val mode by vm.serverSyncMode.collectAsState()
    val lastSync by vm.lastServerSync.collectAsState()
    val inLibrary = vm.sourceCounts.collectAsState().value[Track.SOURCE_SUBSONIC] ?: 0
    val number = NumberFormat.getIntegerInstance()

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.server_section_library), style = MaterialTheme.typography.labelLarge)
    Text(
        if (inLibrary == 0) stringResource(R.string.server_in_library_info)
        else stringResource(R.string.server_library_count_in_library, formatSongCount(inLibrary)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (sync.running) {
        val total = sync.total
        if (total != null && total > 0 && !sync.isIndexing) {
            LinearProgressIndicator(progress = { (sync.listed.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val totalStr = total?.let { stringResource(R.string.server_sync_of_total, number.format(it)) } ?: ""
        Text(
            if (sync.isIndexing) stringResource(R.string.server_sync_indexing)
            else stringResource(R.string.server_sync_listing, number.format(sync.listed), totalStr),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        sync.message?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall,
                color = if (sync.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
    Button(onClick = vm::syncServer, enabled = !sync.running) { Text(stringResource(R.string.server_sync_now)) }
    Text(
        stringResource(R.string.server_automatic_sync),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        DriveSyncMode.entries.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == option, onClick = { vm.setServerSyncMode(option) })
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = mode == option,
                    onClick = null,
                    modifier = Modifier.scale(0.8f),
                )
                Text(
                    stringResource(option.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
    val lastSyncText = if (lastSync == 0L) stringResource(R.string.sources_not_synced_yet) else stringResource(R.string.sources_last_synced, relativeTime(lastSync))
    Text(
        lastSyncText + ". " + stringResource(R.string.server_auto_sync_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/** Favourites <-> stars and playlists, synced both ways: how it last went. */
@Composable
private fun CollectionsSyncInfo(vm: LibraryViewModel) {
    val sync by vm.collectionsSync.collectAsState()
    Text(stringResource(R.string.server_section_favourites_playlists), style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(R.string.server_favourites_playlists_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    sync.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (sync.at > 0) {
        val lastChanges = sync.lastChanges?.let {
            stringResource(R.string.server_collections_last_changes, relativeTime(sync.lastChangesAt), it)
        } ?: stringResource(R.string.server_collections_no_changes)
        Text(
            stringResource(R.string.server_collections_synced, relativeTime(sync.at)) + lastChanges,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    InfoSwitch(
        stringResource(R.string.server_star_other_favourites),
        vm.starOtherFavourites.collectAsState().value,
        vm::setStarOtherFavourites,
        infoTitle = stringResource(R.string.server_star_other_favourites_dialog_title),
        info = stringResource(R.string.server_star_other_favourites_dialog_desc),
    )
    InfoSwitch(
        stringResource(R.string.server_love_stars_lastfm),
        vm.loveServerStars.collectAsState().value,
        vm::setLoveServerStars,
        infoTitle = stringResource(R.string.server_love_stars_lastfm_dialog_title),
        info = stringResource(R.string.server_love_stars_lastfm_dialog_desc),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::syncCollections, enabled = !sync.running) { Text(stringResource(R.string.server_sync_collections)) }
        if (sync.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/** Whether plays made in the app go to Navidrome's play counts too (both off by default). */
@Composable
private fun PlayReports(vm: LibraryViewModel) {
    val server by vm.reportServerPlays.collectAsState()
    val other by vm.reportOtherPlays.collectAsState()
    val pending by vm.pendingServerPlays.collectAsState()
    Text(stringResource(R.string.server_section_plays), style = MaterialTheme.typography.labelLarge)
    InfoSwitch(
        stringResource(R.string.server_report_server_plays),
        server,
        vm::setReportServerPlays,
        infoTitle = stringResource(R.string.server_report_server_plays_dialog_title),
        info = stringResource(R.string.server_report_server_plays_dialog_desc),
    )
    InfoSwitch(
        stringResource(R.string.server_report_other_plays),
        other,
        vm::setReportOtherPlays,
        infoTitle = stringResource(R.string.server_report_other_plays_dialog_title),
        info = stringResource(R.string.server_report_other_plays_dialog_desc),
    )
    if (pending > 0) {
        Text(
            if (pending == 1) stringResource(R.string.server_pending_plays_one) else stringResource(R.string.server_pending_plays_many, pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/** A setting with a switch and an (i) button that explains it. */
@Composable
private fun InfoSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, infoTitle: String, info: String) {
    var showInfo by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { showInfo = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.server_about_setting_cd)) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(infoTitle) },
            text = { Text(info) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun formatSongCount(songs: Int): String {
    val formatted = NumberFormat.getIntegerInstance().format(songs)
    return if (songs == 1) stringResource(R.string.server_song_count_one)
    else stringResource(R.string.server_song_count_many, formatted)
}

@Composable
private fun SignInFields(busy: Boolean, onConnect: (address: String, user: String, password: String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    // Not saveable on purpose: the password isn't written to the saved instance state.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val ready = address.isNotBlank() && user.isNotBlank() && password.isNotEmpty() && !busy
    val submit = { focus.clearFocus(); onConnect(address, user, password) }

    OutlinedTextField(
        value = address, onValueChange = { address = it },
        label = { Text(stringResource(R.string.server_address_label)) }, placeholder = { Text("https://music.example.com") },
        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = user, onValueChange = { user = it },
        label = { Text(stringResource(R.string.server_username_label)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text(stringResource(R.string.server_password_label)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (ready) submit() }),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (showPassword) stringResource(R.string.server_hide_password_cd) else stringResource(R.string.server_show_password_cd),
                )
            }
        },
    )
    Text(
        stringResource(R.string.server_password_security_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = submit, enabled = ready) { Text(stringResource(R.string.server_connect_button)) }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
