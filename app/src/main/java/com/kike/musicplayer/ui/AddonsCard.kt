package com.kike.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.kike.musicplayer.R
import com.kike.musicplayer.data.addon.AddonInstance
import com.kike.musicplayer.data.addon.AddonManager
import sh.calvin.reorderable.ReorderableColumn

/**
 * Settings card inside Music Sources for managing streaming add-on providers (Nullbeat, Nebula, etc.)
 * with drag-and-drop priority reordering and automatic sequential fallback.
 */
@Composable
internal fun AddonsCard(vm: LibraryViewModel) {
    val addons by vm.addons.collectAsState()
    val busy by vm.addonBusy.collectAsState()
    val message by vm.addonMessage.collectAsState()

    var addonToDisconnect by remember { mutableStateOf<AddonInstance?>(null) }
    var showCustomInput by rememberSaveable { mutableStateOf(false) }

    SourceCard(
        title = stringResource(R.string.addon_card_title),
        subtitle = if (addons.isEmpty()) stringResource(R.string.addon_card_subtitle_disconnected) else null,
    ) {
        message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (addons.isNotEmpty()) {
            if (addons.size > 1) {
                Text(
                    text = stringResource(R.string.addon_fallback_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                ReorderableColumn(
                    list = addons,
                    onSettle = { from, to -> vm.reorderAddons(from, to) },
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { index, addon, isDragging ->
                    ReorderableItem {
                        Surface(
                            tonalElevation = if (isDragging) 6.dp else 1.dp,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDragging) 0.85f else 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AddonItemRow(
                                addon = addon,
                                priority = index + 1,
                                isDraggable = true,
                                onDisconnect = { addonToDisconnect = addon },
                                dragHandle = {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle(),
                                    ) {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = stringResource(R.string.addon_fallback_explanation),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                val single = addons.first()
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AddonItemRow(
                        addon = single,
                        priority = 1,
                        isDraggable = false,
                        onDisconnect = { addonToDisconnect = single },
                        dragHandle = null,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
        }

        // Add additional providers / presets
        val hasNullbeat = addons.any { it.id == "nullbeat" || it.manifestUrl.contains("nullbeat", ignoreCase = true) }
        val hasNebula = addons.any { it.id == "nebula" || it.manifestUrl.contains("nebula", ignoreCase = true) }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!hasNullbeat) {
                    OutlinedButton(
                        onClick = { vm.connectAddon(AddonManager.NULLBEAT_PRESET_URL, "nullbeat") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.addon_connect_nullbeat), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (!hasNebula) {
                    OutlinedButton(
                        onClick = { vm.connectAddon(AddonManager.NEBULA_PRESET_URL, "nebula") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.addon_connect_nebula), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (!showCustomInput) {
                TextButton(
                    onClick = { showCustomInput = true },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.addon_connect_custom), modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                AddonConnectCustomForm(busy = busy) { url ->
                    vm.connectAddon(url)
                    showCustomInput = false
                }
            }
        }
    }

    addonToDisconnect?.let { addon ->
        AlertDialog(
            onDismissRequest = { addonToDisconnect = null },
            title = { Text(stringResource(R.string.addon_disconnect_confirm, addon.name)) },
            text = { Text(stringResource(R.string.addon_disconnect_message)) },
            confirmButton = {
                TextButton(onClick = {
                    val toRemove = addonToDisconnect?.id
                    addonToDisconnect = null
                    toRemove?.let { vm.disconnectAddon(it) }
                }) {
                    Text(stringResource(R.string.sources_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { addonToDisconnect = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AddonItemRow(
    addon: AddonInstance,
    priority: Int,
    isDraggable: Boolean,
    onDisconnect: () -> Unit,
    dragHandle: @Composable (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!addon.icon.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = addon.icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(CircleShape),
                loading = {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
                },
                error = {
                    Box(
                        Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
            )
        } else {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (priority == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = if (priority == 1) {
                            stringResource(R.string.addon_priority_primary, priority)
                        } else {
                            stringResource(R.string.addon_priority_fallback, priority)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (priority == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                Text(
                    text = addon.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val masked = addon.manifestUrl.let { url ->
                val domain = url.substringAfter("://").substringBefore('/')
                "https://$domain/.../manifest.json"
            }
            Text(
                text = masked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = stringResource(R.string.addon_quality_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        IconButton(onClick = onDisconnect) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.sources_disconnect),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
            )
        }

        if (isDraggable && dragHandle != null) {
            dragHandle()
        }
    }
}

@Composable
private fun AddonConnectCustomForm(
    busy: Boolean,
    onConnect: (String) -> Unit,
) {
    var manifestUrl by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        OutlinedTextField(
            value = manifestUrl,
            onValueChange = { manifestUrl = it },
            label = { Text(stringResource(R.string.addon_connect_custom)) },
            placeholder = { Text("https://.../manifest.json") },
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
                Text(if (busy) stringResource(R.string.addon_connecting) else stringResource(R.string.addon_connect_button))
            }
        }
    }
}
