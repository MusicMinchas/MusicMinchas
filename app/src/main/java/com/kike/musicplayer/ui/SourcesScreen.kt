package com.kike.musicplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp

import androidx.compose.ui.res.stringResource
import com.kike.musicplayer.R

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

/** Section header for settings categories with optional explanatory subtitle. */
@Composable
fun SettingsSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Settings: the music sources (own page), downloads, Last.fm and metadata. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    onOpenMusicSources: () -> Unit,
    onOpenLibraryHealth: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenDataStorage: () -> Unit,
    onOpenAudioSettings: () -> Unit,
    onOpenGeneralPreferences: () -> Unit,
    contentPadding: PaddingValues,
) {
    val status by vm.scanStatus.collectAsState()
    val error by vm.lastError.collectAsState()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            // 1. Library & Content
            SettingsSectionHeader(stringResource(R.string.settings_section_library))
            MusicSourcesEntry(vm, onOpenMusicSources)
            LibraryHealthEntry(onOpenLibraryHealth)

            // 2. Integrations
            SettingsSectionHeader(stringResource(R.string.settings_section_integrations))
            IntegrationsEntry(vm, onOpenIntegrations)

            // 3. Data & Storage
            SettingsSectionHeader(stringResource(R.string.settings_section_data))
            DataStorageEntry(vm, onOpenDataStorage)

            // 4. Audio Settings
            SettingsSectionHeader(stringResource(R.string.settings_section_audio))
            AudioSettingsEntry(vm, onOpenAudioSettings)

            // 5. General Preferences
            SettingsSectionHeader(stringResource(R.string.settings_section_general))
            GeneralPreferencesEntry(vm, onOpenGeneralPreferences)
        }
    }
}

/** Entry card leading to the Integrations submenu (Last.fm, Metadata, Automatic Sync). */
@Composable
private fun IntegrationsEntry(vm: LibraryViewModel, onOpen: () -> Unit) {
    val lastFm by vm.lastFmState.collectAsState()
    val deezer by vm.metaDeezer.collectAsState()
    val apple by vm.metaApple.collectAsState()
    val mb by vm.metaMusicBrainz.collectAsState()
    val autoSync by vm.autoSync.collectAsState()

    val summary = buildString {
        if (lastFm.user != null) {
            append("Last.fm (${lastFm.user})")
        } else {
            append("Last.fm")
        }
        val metaCount = listOf(deezer, apple, mb).count { it }
        append(" · ")
        append(if (metaCount > 0) "Metadata ($metaCount/3)" else "Metadata")
        append(" · ")
        append(if (autoSync.enabled) "Auto-sync (On)" else "Auto-sync (Off)")
    }

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_integrations_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Entry card leading to Data & Storage submenu (Downloads, Cache & Storage Management). */
@Composable
private fun DataStorageEntry(vm: LibraryViewModel, onOpen: () -> Unit) {
    val downloads by vm.downloadTotals.collectAsState()
    val stats by vm.storageStats.collectAsState()
    val cacheLimitMb by vm.imageCacheLimitMb.collectAsState()

    val summary = buildString {
        val downloadedSongs = downloads?.songs ?: 0
        if (downloadedSongs > 0) {
            append("$downloadedSongs downloaded · ")
        }
        if (stats.databaseBytes > 0) {
            append("${formatBytes(stats.databaseBytes)} DB · ")
        }
        append("${formatBytes(stats.imageCacheBytes)} cache (max ${cacheLimitMb}MB)")
    }

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_data_storage_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Entry card leading to Audio Settings submenu (Sonos playback, DSP engine, Bit-perfect). */
@Composable
private fun AudioSettingsEntry(vm: LibraryViewModel, onOpen: () -> Unit) {
    val downsample by vm.sonosDownsample.collectAsState()
    val loudness by vm.loudnessNormalization.collectAsState()
    val crossfade by vm.crossfade.collectAsState()
    val crossfadeSec by vm.crossfadeSeconds.collectAsState()

    val summary = buildString {
        if (loudness || crossfade) {
            val active = mutableListOf<String>()
            if (loudness) active.add("Loudness")
            if (crossfade) active.add("Crossfade (${crossfadeSec}s)")
            append(active.joinToString(" · "))
        } else {
            append("Bit-perfect output")
        }
        append(" · ")
        append(if (downsample) "Sonos downsample" else "Direct Sonos")
    }

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_audio_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Entry card leading to General Preferences submenu (Language, Theme, Appearance). */
@Composable
private fun GeneralPreferencesEntry(vm: LibraryViewModel, onOpen: () -> Unit) {
    val language by vm.language.collectAsState()
    val dark by vm.darkMode.collectAsState()
    val accentColor by vm.accentColor.collectAsState()
    val fullCover by vm.fullScreenCover.collectAsState()

    val summary = buildString {
        val langLabel = when (language) {
            "en" -> "English"
            "es" -> "Español"
            else -> "System"
        }
        append(langLabel)
        append(" · ")
        append(if (dark) "Dark" else "Light")
        append(" (${accentColor.replaceFirstChar { it.uppercase() }})")
        if (fullCover) {
            append(" · Edge-to-edge")
        }
    }

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_section_general), style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Audio Settings sub-screen: Sonos S2 playback and DSP engine settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.settings_audio_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SonosPlaybackCard(vm)
            AudioDspCard(vm)
        }
    }
}

/** General Preferences sub-screen: Language, Theme, and Appearance settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralPreferencesScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.settings_section_general)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageCard(vm)
            AppearanceCard(vm)
        }
    }
}

/** Integrations sub-screen: Last.fm settings, Online Metadata Lookup, Automatic Sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val pending by vm.pendingMetadata.collectAsState()
    val progress by vm.metadataProgress.collectAsState()
    val lastFm by vm.lastFmState.collectAsState()
    val scrobbling by vm.scrobbleSettings.collectAsState()
    val pendingScrobbles by vm.pendingScrobbles.collectAsState()
    val importStatus by vm.importStatus.collectAsState()
    val scrobbleCounts by vm.scrobbleCounts.collectAsState()
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.settings_integrations_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Last.fm
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

            // 2. Online Metadata Lookup
            MetadataCard(vm, pending, progress)

            // 3. Automatic Sync
            SyncCard(vm)
        }
    }
}

/** Data & Storage sub-screen: Downloaded Songs and Storage & Cache Management. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataStorageScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val downloads by vm.downloadTotals.collectAsState()
    var confirmDeleteDownloads by remember { mutableStateOf(false) }
    val stats by vm.storageStats.collectAsState()
    val cacheLimitMb by vm.imageCacheLimitMb.collectAsState()
    var actionMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.settings_data_storage_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            actions = {
                IconButton(onClick = vm::refreshStorageStats) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                }
            }
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            actionMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actionMessage = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }

            // 1. Downloaded Songs
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

            // 2. Cache & Storage Management
            StorageManagementCard(
                stats = stats,
                cacheLimitMb = cacheLimitMb,
                onSetLimit = vm::setImageCacheLimit,
                onClearCache = {
                    vm.clearImageCache {
                        actionMessage = "Image cache cleared successfully"
                    }
                },
                onPruneArtwork = {
                    vm.pruneArtwork { freed, count ->
                        actionMessage = if (count > 0) "Pruned $count unused cover files (${formatBytes(freed)} freed)" else "No unused artwork found"
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageManagementCard(
    stats: StorageStats,
    cacheLimitMb: Long,
    onSetLimit: (Long) -> Unit,
    onClearCache: () -> Unit,
    onPruneArtwork: () -> Unit,
) {
    SourceCard(
        title = stringResource(R.string.storage_mgmt_title),
        subtitle = stringResource(R.string.storage_mgmt_subtitle),
    ) {
        val number = java.text.NumberFormat.getIntegerInstance()

        // Storage Breakdown Table
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StorageStatRow(
                label = stringResource(R.string.storage_stat_database),
                size = formatBytes(stats.databaseBytes),
                detail = "${number.format(stats.databaseTrackCount)} songs · ${number.format(stats.databaseAlbumCount)} albums · ${number.format(stats.databaseScrobbleCount)} scrobbles",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            StorageStatRow(
                label = stringResource(R.string.storage_stat_embedded_artwork),
                size = formatBytes(stats.artworkBytes),
                detail = "${number.format(stats.artworkCount)} extracted ID3/Drive covers",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            StorageStatRow(
                label = stringResource(R.string.storage_stat_image_cache),
                size = formatBytes(stats.imageCacheBytes),
                detail = "${number.format(stats.imageCacheCount)} cached network images",
            )
            if (stats.codeCacheBytes > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                StorageStatRow(
                    label = stringResource(R.string.storage_stat_runtime_cache),
                    size = formatBytes(stats.codeCacheBytes),
                    detail = "ART bytecode compilation & GPU shaders",
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Image Cache Limit Selector
        Text(
            text = stringResource(R.string.storage_cache_limit_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.storage_cache_limit_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val options = listOf(250L, 500L, 1024L, 2048L)
        val labels = listOf("250 MB", "500 MB", "1 GB", "2 GB")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, mb ->
                SegmentedButton(
                    selected = cacheLimitMb == mb,
                    onClick = { onSetLimit(mb) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(labels[index], style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Management Actions
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onClearCache,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.storage_clear_image_cache), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onPruneArtwork,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.storage_prune_artwork), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StorageStatRow(label: String, size: String, detail: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(size, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun LibraryHealthEntry(onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FactCheck, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Library Health & Management", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Check missing tags, covers, lyrics, audio quality, and import play history",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val addons by vm.addons.collectAsState()
    val needsReconnect by vm.driveNeedsReconnect.collectAsState()
    val number = java.text.NumberFormat.getIntegerInstance()
    val songParts = buildList {
        counts[Track.SOURCE_SAF]?.takeIf { it > 0 }?.let { add("${stringResource(R.string.source_local)} ${number.format(it)}") }
        if (account != null) add("GDrive ${number.format(counts[Track.SOURCE_DRIVE] ?: 0)}")
        if (server.address != null) {
            add("${server.server?.substringBefore(' ') ?: "Navidrome"} ${number.format(counts[Track.SOURCE_SUBSONIC] ?: 0)}")
        }
    }
    val totalSongs = (counts[Track.SOURCE_SAF] ?: 0) + (counts[Track.SOURCE_DRIVE] ?: 0) + (counts[Track.SOURCE_SUBSONIC] ?: 0)
    val summary = buildString {
        if (songParts.isNotEmpty()) {
            append(songParts.joinToString(" · "))
            append(" · ")
            append(stringResource(R.string.sources_summary_songs, number.format(totalSongs)))
        }
        val activeAddons = addons.filter { it.enabled }
        if (activeAddons.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(activeAddons.joinToString(" + ") { it.name.substringBefore('·').trim() })
        }
        if (catalog) {
            if (isNotEmpty()) append(" · ")
            append("Deezer / Apple Music")
        }
        if (isEmpty()) append(stringResource(R.string.sources_summary_empty))
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
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            // 1. Storage & Servers
            SettingsSectionHeader(stringResource(R.string.settings_section_storage))

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
                        Text(
                            stringResource(R.string.sources_sync_card_title),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            DriveSyncMode.entries.forEach { mode ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(selected = syncMode == mode, onClick = { vm.setSyncMode(mode) })
                                        .padding(vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = syncMode == mode,
                                        onClick = null,
                                        modifier = Modifier.scale(0.8f),
                                    )
                                    Text(
                                        stringResource(mode.labelRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = 2.dp),
                                    )
                                }
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

            // 2. Music Add-ons (Nullbeat, Nebula, etc.)
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_addons),
                subtitle = stringResource(R.string.settings_section_addons_desc),
            )

            AddonsCard(vm)

            // 3. Public Catalog & Discovery
            SettingsSectionHeader(stringResource(R.string.settings_section_catalog))

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
    Text(stringResource(R.string.sources_lastfm_loved_tracks_title), style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(R.string.sources_lastfm_loved_tracks_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status.text?.takeIf { status.loved }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onImport, enabled = !status.running) { Text(stringResource(R.string.sources_lastfm_import_loved_button)) }
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
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, stringResource(R.string.sources_folder_remove_cd)) }
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
    val accentColor by vm.accentColor.collectAsState()
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
        Text(
            stringResource(R.string.settings_accent_color_title),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("red" to stringResource(R.string.settings_accent_color_red), "blue" to stringResource(R.string.settings_accent_color_blue)).forEachIndexed { index, (colorKey, label) ->
                SegmentedButton(
                    selected = accentColor == colorKey,
                    onClick = { vm.setAccentColor(colorKey) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
internal fun SourceCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
@Composable
private fun folderLabel(uri: String): String {
    val docId = Uri.parse(uri).lastPathSegment ?: return uri
    val volume = docId.substringBefore(':')
    val path = docId.substringAfter(':', "")
    val volumeName = if (volume == "primary") stringResource(R.string.sources_saf_storage_internal) else stringResource(R.string.sources_saf_storage_external, volume)
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
                Text(current?.name ?: stringResource(R.string.sources_drive_choose_folder_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
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
                        Text(stringResource(R.string.sources_drive_no_subfolders), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(if (canSelect) stringResource(R.string.sources_drive_use_folder, current.name) else stringResource(R.string.sources_drive_open_folder_to_select), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


@Composable
internal fun PlayHistoryArchiveCard(vm: LibraryViewModel) {
    val importState by vm.historyImportState.collectAsState()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPlayHistory(uri)
    }

    SourceCard(
        title = "Play History Archive",
        subtitle = "Import iTunes & Apple Music plays (2005–2026)",
    ) {
        Text(
            text = "Enrich your listening history with past plays from an exported iTunes or Apple Music CSV. Powers Forgotten Favourites, Rediscover, and Time Machine mixes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        if (importState?.inProgress == true) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Importing plays...", style = MaterialTheme.typography.bodyMedium)
                    val pct = ((importState?.progress ?: 0f) * 100).toInt()
                    Text("$pct%", style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(
                    progress = { importState?.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("text/*", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import History CSV")
            }
        }

        importState?.result?.let { stats ->
            val dateFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
            val timeSpan = if (stats.earliestDate != null && stats.latestDate != null) {
                "${dateFormat.format(Date(stats.earliestDate))} – ${dateFormat.format(Date(stats.latestDate))}"
            } else "N/A"

            AlertDialog(
                onDismissRequest = vm::dismissHistoryImport,
                title = { Text("History Import Complete") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("• Matched ${stats.matchedPlays} plays (${stats.matchedTracks} tracks with original dates)", fontWeight = FontWeight.SemiBold)
                        Text("• 0 plays added to playcount", color = MaterialTheme.colorScheme.primary)
                        Text("• Skipped ${stats.skippedDuplicates} duplicates")
                        if (stats.unmatchedRows > 0) {
                            Text("• ${stats.unmatchedRows} rows not in library", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("• Time span: $timeSpan", style = MaterialTheme.typography.bodySmall)
                        if (stats.topArtists.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Top artists imported:", fontWeight = FontWeight.Medium)
                            stats.topArtists.forEach { (artist, count) ->
                                Text("  $artist ($count plays)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::dismissHistoryImport) {
                        Text("Done")
                    }
                },
            )
        }

        importState?.error?.let { err ->
            AlertDialog(
                onDismissRequest = vm::dismissHistoryImport,
                title = { Text("Import Failed") },
                text = { Text(err) },
                confirmButton = {
                    TextButton(onClick = vm::dismissHistoryImport) {
                        Text("OK")
                    }
                },
            )
        }
    }
}

@Composable
private fun AudioDspCard(vm: LibraryViewModel) {
    val loudness by vm.loudnessNormalization.collectAsState()
    val crossfade by vm.crossfade.collectAsState()
    val crossfadeSec by vm.crossfadeSeconds.collectAsState()
    val isDspActive = loudness || crossfade

    SourceCard(
        title = "Audio Engine & Bit-Perfect Playback",
        subtitle = "DSP effects and output configuration",
    ) {
        Surface(
            color = if (isDspActive) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isDspActive) Icons.Default.Tune else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isDspActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isDspActive) "Audio DSP Active" else "Bit-Perfect Direct Output Active",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (isDspActive)
                            "Digital audio samples are modified by active DSP effects below."
                        else
                            "No DSP active. Audio samples stream directly to DACs and speakers unaltered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Loudness Normalization", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Equalizes perceived loudness across albums. Modifies digital levels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = loudness, onCheckedChange = vm::setLoudnessNormalization)
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Track Crossfade", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Smooth volume transition at track boundaries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = crossfade, onCheckedChange = vm::setCrossfade)
        }

        if (crossfade) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Duration", style = MaterialTheme.typography.bodySmall)
                    Text("${crossfadeSec}s", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = crossfadeSec.toFloat(),
                    onValueChange = { vm.setCrossfadeSeconds(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
