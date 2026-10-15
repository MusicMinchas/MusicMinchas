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
    // Fresh song count and folders each time the settings open.
    LaunchedEffect(connected) { if (connected) vm.checkSubsonic() }

    SourceCard(
        "Navidrome / Subsonic server",
        if (!connected) "Stream your music from your own Navidrome server or another Subsonic-compatible one"
        else buildString {
            append("Connected as ${state.username} to ${state.server ?: "the server"}")
            state.songCount?.let { append(" · ${NumberFormat.getIntegerInstance().format(it)} songs on the server") }
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
            Text(
                "Your server's songs aren't added to the library yet: that comes with the next update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.checkedAt > 0) {
                Text(
                    "Server last answered ${relativeTime(state.checkedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.checkSubsonic() }, enabled = !state.busy) { Text("Check connection") }
                OutlinedButton(onClick = vm::disconnectSubsonic, enabled = !state.busy) { Text("Disconnect") }
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
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
