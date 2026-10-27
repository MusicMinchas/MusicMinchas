package com.kike.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.res.stringResource
import com.kike.musicplayer.R

/**
 * Settings card inside Music Sources for connecting a Stremio / Eclipse Music Nebula manifest URL.
 */
@Composable
internal fun NebulaCard(vm: LibraryViewModel) {
    val state by vm.nebulaState.collectAsState()
    val connected = state.manifestUrl != null
    var confirmDisconnect by remember { mutableStateOf(false) }

    SourceCard(
        title = stringResource(R.string.nebula_card_title),
        subtitle = if (!connected) {
            stringResource(R.string.nebula_card_subtitle_disconnected)
        } else {
            stringResource(R.string.nebula_card_subtitle_connected, state.addonName ?: "Nebula")
        },
    ) {
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (!connected) {
            NebulaConnectForm(state.busy) { url ->
                vm.connectNebula(url)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.icon.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = state.icon,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                        loading = {
                            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
                        },
                        error = {
                            Box(
                                Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        },
                    )
                } else {
                    Box(
                        Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        state.addonName ?: "Nebula (Tidal - Torrents)",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val masked = state.manifestUrl?.let { url ->
                        val domain = url.substringAfter("://").substringBefore('/')
                        "https://$domain/.../manifest.json"
                    } ?: ""
                    Text(
                        masked,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.nebula_quality_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            OutlinedButton(
                onClick = { confirmDisconnect = true },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.sources_disconnect))
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.nebula_disconnect_confirm)) },
            text = {
                Text(stringResource(R.string.nebula_disconnect_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    vm.disconnectNebula()
                }) {
                    Text(stringResource(R.string.sources_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun NebulaConnectForm(
    busy: Boolean,
    onConnect: (String) -> Unit,
) {
    var manifestUrl by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = manifestUrl,
            onValueChange = { manifestUrl = it },
            label = { Text(stringResource(R.string.nebula_manifest_url_label)) },
            placeholder = { Text("https://nebula.eclipsemusic.app/.../manifest.json") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focus.clearFocus()
                if (manifestUrl.isNotBlank()) onConnect(manifestUrl)
            }),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    focus.clearFocus()
                    if (manifestUrl.isNotBlank()) onConnect(manifestUrl)
                },
                enabled = manifestUrl.isNotBlank() && !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(if (busy) stringResource(R.string.nebula_connecting) else stringResource(R.string.nebula_connect_button))
            }
        }
    }
}
