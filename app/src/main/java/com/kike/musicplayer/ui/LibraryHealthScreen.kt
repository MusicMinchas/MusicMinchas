package com.kike.musicplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.container
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.health.HealthCheckType
import com.kike.musicplayer.data.health.HealthInspectionCriteria
import com.kike.musicplayer.data.health.HealthInspectionReport
import com.kike.musicplayer.data.health.LibraryHealthInspector
import com.kike.musicplayer.data.health.TrackHealthIssue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryHealthScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = context.container.database

    var selectedScope by remember { mutableStateOf<Int?>(null) }
    var enabledChecks by remember { mutableStateOf(HealthCheckType.values().toSet()) }
    var isInspecting by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<HealthInspectionReport?>(null) }
    var selectedFilterIssue by remember { mutableStateOf<HealthCheckType?>(null) }

    fun runInspection() {
        isInspecting = true
        scope.launch {
            try {
                val result = LibraryHealthInspector.inspect(
                    db = db,
                    criteria = HealthInspectionCriteria(
                        selectedScope = selectedScope,
                        enabledChecks = enabledChecks,
                    ),
                )
                report = result
                selectedFilterIssue = null
            } finally {
                isInspecting = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text("Library Health & Management") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // Section 1: Music Source
                Text(
                    "Source to Inspect",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedScope == null,
                        onClick = { selectedScope = null },
                        label = { Text("All Sources") },
                    )
                    FilterChip(
                        selected = selectedScope == Track.SCOPE_LOCAL,
                        onClick = { selectedScope = Track.SCOPE_LOCAL },
                        label = { Text("Local Device") },
                    )
                    FilterChip(
                        selected = selectedScope == Track.SCOPE_DRIVE,
                        onClick = { selectedScope = Track.SCOPE_DRIVE },
                        label = { Text("Google Drive") },
                    )
                    FilterChip(
                        selected = selectedScope == Track.SCOPE_SERVER,
                        onClick = { selectedScope = Track.SCOPE_SERVER },
                        label = { Text("Subsonic / Navidrome") },
                    )
                }
            }

            item {
                // Section 2: Selectable Checks
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Checks to Perform",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row {
                        TextButton(onClick = { enabledChecks = HealthCheckType.values().toSet() }) {
                            Text("Select All")
                        }
                        TextButton(onClick = { enabledChecks = emptySet() }) {
                            Text("Clear")
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HealthCheckType.values().forEach { check ->
                            val isChecked = check in enabledChecks
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    enabledChecks = if (isChecked) enabledChecks - check else enabledChecks + check
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        iconForCheck(check),
                                        contentDescription = null,
                                        tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            check.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            check.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Switch(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        enabledChecks = if (checked) enabledChecks + check else enabledChecks - check
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = ::runInspection,
                    enabled = !isInspecting && enabledChecks.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (isInspecting) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Inspecting library…")
                    } else {
                        Icon(Icons.Default.FactCheck, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Run Inspection")
                    }
                }
            }

            // Results Section
            report?.let { rep ->
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (rep.issuesByTrack.isEmpty()) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                if (rep.issuesByTrack.isEmpty()) "All Clean! No issues found." else "Inspection Completed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Scanned ${rep.totalScanned} songs · Found ${rep.issuesByTrack.size} songs with issues",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (rep.issuesByTrack.isNotEmpty()) {
                    item {
                        Text(
                            "Filter by Issue",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = selectedFilterIssue == null,
                                onClick = { selectedFilterIssue = null },
                                label = { Text("All Issues (${rep.issuesByTrack.size})") },
                            )
                            rep.issueCounts.forEach { (issue, count) ->
                                FilterChip(
                                    selected = selectedFilterIssue == issue,
                                    onClick = { selectedFilterIssue = issue },
                                    label = { Text("${issue.displayName} ($count)") },
                                )
                            }
                        }
                    }

                    val filteredIssues = if (selectedFilterIssue == null) {
                        rep.issuesByTrack
                    } else {
                        rep.issuesByTrack.filter { selectedFilterIssue in it.issues }
                    }

                    items(filteredIssues, key = { it.track.id }) { issueItem ->
                        HealthIssueTrackRow(issueItem = issueItem, onPlay = { onPlayTrack(issueItem.track) })
                    }
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PlayHistoryArchiveCard(vm)
            }

            item {
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthIssueTrackRow(
    issueItem: TrackHealthIssue,
    onPlay: () -> Unit,
) {
    val track = issueItem.track
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.artworkUri, size = 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.displayArtist + (track.album?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                issueItem.issues.forEach { issue ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            issue.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun iconForCheck(check: HealthCheckType): ImageVector = when (check) {
    HealthCheckType.MISSING_COVER -> Icons.Default.Image
    HealthCheckType.MISSING_LYRICS -> Icons.Default.Lyrics
    HealthCheckType.MISSING_GENRE -> Icons.Default.Category
    HealthCheckType.MISSING_YEAR -> Icons.Default.DateRange
    HealthCheckType.MISSING_TRACK_NUMBER -> Icons.Default.FormatListNumbered
    HealthCheckType.MISSING_ARTIST -> Icons.Default.Person
    HealthCheckType.MISSING_ALBUM -> Icons.Default.Album
    HealthCheckType.LOW_QUALITY -> Icons.Default.Audiotrack
    HealthCheckType.CORRUPTED_OR_DRM -> Icons.Default.ErrorOutline
    HealthCheckType.DUPLICATE -> Icons.Default.ContentCopy
}
