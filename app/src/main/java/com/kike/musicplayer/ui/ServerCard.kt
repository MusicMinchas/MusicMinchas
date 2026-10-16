package com.kike.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import com.kike.musicplayer.data.DriveSyncMode
import com.kike.musicplayer.data.db.Track
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
        if (!connected) "Stream your music from your own Navidrome server or another Subsonic-compatible one"
        else buildString {
            append("Connected as ${state.username} to ${state.server ?: "Navidrome"}")
            state.songCount?.let { append(" · ${NumberFormat.getIntegerInstance().format(it)} songs on Navidrome") }
        },
    ) {
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!connected) {
            SignInFields(state.busy) { address, user, password -> vm.connectSubsonic(address, user, password) }
        } else {
            Text(state.address!!, style = MaterialTheme.typography.bodyMedium)
            if (state.folders.isNotEmpty()) {
                Text(
                    "Libraries: " + state.folders.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.checkedAt > 0) {
                Text(
                    "Navidrome last answered ${relativeTime(state.checkedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ServerSyncControls(vm)
            CollectionsSyncInfo(vm)
            PlayReports(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.checkSubsonic() }, enabled = !state.busy) { Text("Check connection") }
                OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !state.busy) { Text("Disconnect") }
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
    if (confirmDisconnect) {
        val songs = vm.sourceCounts.collectAsState().value[Track.SOURCE_SUBSONIC] ?: 0
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Navidrome?") },
            text = {
                Text(
                    "The ${count(songs)} from Navidrome will be removed from the library. Plays, favourites and " +
                        "playlists are kept and come back if you connect the same Navidrome server again."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; vm.disconnectSubsonic() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
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
    Text("Library", style = MaterialTheme.typography.labelLarge)
    Text(
        if (inLibrary == 0) "Sync to add Navidrome's songs to the library. They're listed apart from your own " +
            "music; the switch above the library shows either or both."
        else "${count(inLibrary)} in the library",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (sync.running) {
        val total = sync.total
        if (total != null && total > 0) {
            LinearProgressIndicator(progress = { (sync.listed.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            "Listing songs: ${number.format(sync.listed)}" + (total?.let { " of ${number.format(it)}" } ?: ""),
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
    Button(onClick = vm::syncServer, enabled = !sync.running) { Text("Sync now") }
    Text("Automatic sync", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    DriveSyncMode.entries.forEach { option ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = mode == option, onClick = { vm.setServerSyncMode(option) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = mode == option, onClick = null)
            Text(option.label, Modifier.padding(start = 8.dp))
        }
    }
    Text(
        (if (lastSync == 0L) "Not synced yet" else "Last synced ${relativeTime(lastSync)}") +
            ". An automatic sync only lists the songs again if Navidrome has scanned its library since.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/** Favourites ↔ stars and playlists, synced both ways: how it last went. */
@Composable
private fun CollectionsSyncInfo(vm: LibraryViewModel) {
    val sync by vm.collectionsSync.collectAsState()
    Text("Favourites & playlists", style = MaterialTheme.typography.labelLarge)
    Text(
        "Favourites of Navidrome songs are its starred songs, and your playlists are its playlists: changes on " +
            "either side go to the other. Songs from this device or Drive go into Navidrome playlists as its copy " +
            "of the same song.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    sync.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (sync.at > 0) {
        Text(
            "Synced ${relativeTime(sync.at)}" +
                (sync.lastChanges?.let { ". Last changes (${relativeTime(sync.lastChangesAt)}): $it" } ?: ". Nothing to change so far"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    InfoSwitch(
        "Star favourites from other sources on Navidrome", vm.starOtherFavourites.collectAsState().value, vm::setStarOtherFavourites,
        infoTitle = "Favourites from other sources",
        info = "Favourites of songs on this device or Google Drive are also starred on Navidrome's copy of the same " +
            "song, and removing them unstars it. Unstarring that copy on Navidrome removes the favourite here too.\n\n" +
            "Navidrome's copy is found by artist and title, ignoring notes like \"(Remastered)\" but keeping live and " +
            "demo versions apart (the same title and album first), so it can be imperfect: " +
            "a song with several versions may be starred on a different version, and songs spelled differently " +
            "aren't found. Switching this off later leaves the stars on Navidrome as they are.",
    )
    InfoSwitch(
        "Love Navidrome stars on Last.fm", vm.loveServerStars.collectAsState().value, vm::setLoveServerStars,
        infoTitle = "Stars and Last.fm",
        info = "Songs starred on Navidrome become favourites here; with this on they're also loved on Last.fm, and " +
            "unstarring them unloves them. Favourites you change here are always loved or unloved on Last.fm " +
            "(when it's connected), whatever this setting.\n\nIt applies to stars from now on: songs starred " +
            "before aren't loved retroactively.",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::syncCollections, enabled = !sync.running) { Text("Sync favourites & playlists") }
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
    Text("Plays", style = MaterialTheme.typography.labelLarge)
    InfoSwitch(
        "Report Navidrome song plays", server, vm::setReportServerPlays,
        infoTitle = "Navidrome song plays",
        info = "When a Navidrome song has played halfway, the app tells Navidrome, so its play counts and " +
            "\"recently played\" include what you play here. The app scrobbles to Last.fm itself (Last.fm card).\n\n" +
            "Warning: if scrobbling to Last.fm is also switched on in Navidrome (Personal settings → " +
            "Last.fm), Navidrome forwards these plays to Last.fm as well, and they would be scrobbled twice.",
    )
    InfoSwitch(
        "Report plays from other sources to Navidrome", other, vm::setReportOtherPlays,
        infoTitle = "Plays from other sources",
        info = "Plays of songs on this device or Google Drive are also reported, to Navidrome's copy of the same " +
            "song, so its play counts reflect all your listening.\n\nNavidrome's copy is found by artist and title, " +
            "ignoring notes like \"(Remastered)\" but keeping live and demo versions apart (the same title and album " +
            "first), so it can be imperfect: a song with several versions may be counted on " +
            "a different version, and songs spelled differently aren't found. The same Last.fm warning applies.",
    )
    if (pending > 0) {
        Text(
            "${if (pending == 1) "1 play" else "$pending plays"} waiting to be sent to Navidrome",
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
        IconButton(onClick = { showInfo = true }) { Icon(Icons.Outlined.Info, "About this setting") }
        Switch(checked = checked, onCheckedChange = onChange)
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(infoTitle) },
            text = { Text(info) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
        )
    }
}

private fun count(songs: Int) = if (songs == 1) "1 song" else "${NumberFormat.getIntegerInstance().format(songs)} songs"

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
        label = { Text("Server address") }, placeholder = { Text("https://music.example.com") },
        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = user, onValueChange = { user = it },
        label = { Text("User name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text("Password") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (ready) submit() }),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (showPassword) "Hide password" else "Show password",
                )
            }
        },
    )
    Text(
        "The password is stored encrypted on this phone and is never sent as is: each request is signed with it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = submit, enabled = ready) { Text("Connect") }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
