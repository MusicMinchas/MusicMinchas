package com.kike.musicplayer.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.data.db.Track

private const val BY_SONG = "Songs"
private const val BY_ARTIST = "Artists"
private const val BY_ALBUM = "Albums"

/**
 * Making a playlist, or adding to [existing]: every song, narrowed by a search on song title, artist
 * or album (chips; none selected = all three). Ticking a song adds it, in the order ticked; the
 * check button asks for a name and creates the playlist, or appends to [existing]. Songs already in
 * [existing] show ticked and can't be changed here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPlaylistScreen(
    libraryVm: LibraryViewModel,
    existing: PlaylistView?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val library by libraryVm.library.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var fields by rememberSaveable { mutableStateOf(setOf<String>()) }
    // Selected track ids, in the order they were ticked.
    val selected = remember { mutableStateListOf<String>() }
    var naming by remember { mutableStateOf(false) }

    val shown = remember(library.tracks, query, fields) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) library.tracks
        else library.tracks.filter { t ->
            fun by(field: String) = fields.isEmpty() || field in fields
            (by(BY_SONG) && needle in t.title.lowercase()) ||
                (by(BY_ARTIST) && listOfNotNull(t.artist, t.albumArtist).any { needle in it.lowercase() }) ||
                (by(BY_ALBUM) && t.album?.lowercase()?.contains(needle) == true)
        }
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                windowInsets = NoTopInsets,
                title = {
                    Column {
                        Text(existing?.let { "Add to ${it.name}" } ?: "New playlist", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (selected.isEmpty()) "Tick the songs to add" else if (selected.size == 1) "1 song selected" else "${selected.size} songs selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search songs, artists, albums") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BY_SONG, BY_ARTIST, BY_ALBUM).forEach { field ->
                    FilterChip(
                        selected = field in fields,
                        onClick = { fields = if (field in fields) fields - field else fields + field },
                        label = { Text(field) },
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                val inPlaylist = existing?.tracks.orEmpty().mapTo(HashSet()) { it.id }
                items(shown, key = { it.id }) { track ->
                    val already = track.id in inPlaylist
                    SelectableTrackRow(track, already || track.id in selected, enabled = !already) {
                        if (track.id in selected) selected.remove(track.id) else selected.add(track.id)
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    if (existing != null) {
                        libraryVm.addToPlaylist(existing.id, selected.toList())
                        onDone()
                    } else {
                        naming = true
                    }
                },
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.Check, if (existing != null) "Add songs" else "Create playlist")
            }
        }
    }

    if (naming) {
        var name by rememberSaveable { mutableStateOf(libraryVm.nextPlaylistName()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name the playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    libraryVm.createPlaylist(name, selected.toList())
                    naming = false
                    onDone()
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/** A song with a tick box; tapping anywhere on the row ticks it. */
@Composable
private fun SelectableTrackRow(track: Track, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUri, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ArtistAlbumText(track.displayArtist, emptyList(), track.album, links = false)
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}
