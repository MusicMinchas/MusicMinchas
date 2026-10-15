package com.kike.musicplayer.ui

import androidx.paging.compose.itemKey
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.data.db.Track

private const val BY_SONG = "Songs"
private const val BY_ARTIST = "Artists"
private const val BY_ALBUM = "Albums"
private const val SEARCH_DELAY_MS = 150L

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
    var query by rememberSaveable { mutableStateOf("") }
    var fields by rememberSaveable { mutableStateOf(setOf<String>()) }
    // Selected track ids, in the order they were ticked.
    val selected = remember { mutableStateListOf<String>() }
    var naming by remember { mutableStateOf(false) }

    // Every song page by page; with a search, the matches from the database.
    val allSongs = libraryVm.songPages.collectAsLazyPagingItems()
    val found by produceState<List<Track>?>(null, query, fields) {
        if (query.isBlank()) { value = null; return@produceState }
        delay(SEARCH_DELAY_MS)
        fun by(field: String) = fields.isEmpty() || field in fields
        value = libraryVm.searchSongsBy(query, by(BY_SONG), by(BY_ARTIST), by(BY_ALBUM))
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
                @Composable
                fun row(track: Track) {
                    val already = track.id in inPlaylist
                    SelectableTrackRow(track, already || track.id in selected, enabled = !already) {
                        if (track.id in selected) selected.remove(track.id) else selected.add(track.id)
                    }
                }
                val results = found
                if (results != null) {
                    items(results, key = { it.id }) { row(it) }
                } else {
                    items(allSongs.itemCount, key = allSongs.itemKey { it.id }) { index ->
                        val track = allSongs[index]
                        if (track == null) RowPlaceholder() else row(track)
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

/** Renaming a playlist. */
@Composable
fun RenamePlaylistDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    // Cursor at the end of the current name, ready to edit it.
    var name by remember { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onRename(name.text); onDismiss() }, enabled = name.text.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A playlist the user made: Play / Shuffle / Download, its songs (drag the handle to reorder,
 * the song menu removes one), rename in the top bar, and Add songs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlist: PlaylistView?,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onAddSongs: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val player by playerVm.state.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    // Local order, so rows move under the finger; saved when the drag ends.
    var order by remember { mutableStateOf(playlist?.tracks.orEmpty()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(playlist?.tracks) { if (!dragging) order = playlist?.tracks.orEmpty() }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = order.indexOfFirst { it.id == from.key }
        val b = order.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) order = order.toMutableList().apply { add(b, removeAt(a)) }
    }
    if (renaming && playlist != null) {
        RenamePlaylistDialog(playlist.name, onDismiss = { renaming = false }) { libraryVm.renamePlaylist(playlist.id, it) }
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                windowInsets = NoTopInsets,
                title = { Text(playlist?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (playlist != null) IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                },
            )
            if (playlist == null) return@Column
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "actions") {
                    PlayShuffleRow(
                        onPlay = { playerVm.playTracks(order, 0) },
                        onShuffle = { playerVm.playTracks(order, 0, shuffle = true) },
                        enabled = order.isNotEmpty(),
                        downloads = order,
                    )
                }
                items(order, key = { it.id }) { track ->
                    ReorderableItem(reorderState, key = track.id) { isDragging ->
                        Surface(tonalElevation = if (isDragging) 6.dp else 0.dp) {
                            TrackRow(
                                track = track,
                                isCurrent = track.id == player.current?.mediaId,
                                onClick = { playerVm.playTracks(order, order.indexOf(track)) },
                                onPlayNext = { playerVm.playNext(track) },
                                onAddToQueue = { playerVm.addToQueue(track) },
                                onRemoveFromPlaylist = { libraryVm.removeFromPlaylist(playlist.id, track.id) },
                                trailing = {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = { dragging = true },
                                            onDragStopped = {
                                                dragging = false
                                                libraryVm.reorderPlaylist(playlist.id, order.map { it.id })
                                            },
                                        ),
                                    ) { Icon(Icons.Default.DragHandle, "Reorder") }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (playlist != null) {
            ExtendedFloatingActionButton(
                onClick = onAddSongs,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add songs") },
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
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
