package com.kike.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.kike.musicplayer.data.db.AlbumIndex
import com.kike.musicplayer.data.db.GenreIndex
import kotlinx.coroutines.launch

/** Every song A–Z, page by page; tapping one plays the list from there (up to a thousand songs). */
@Composable
internal fun SongsTab(libraryVm: LibraryViewModel, playerVm: PlayerViewModel) {
    val songs = libraryVm.songPages.collectAsLazyPagingItems()
    val jumps by libraryVm.songJumps.collectAsState()
    val player by playerVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(end = barSpace(jumps))) {
            item {
                PlayShuffleRow(
                    onPlay = { scope.launch { playerVm.playTracks(libraryVm.songsFrom(0), 0) } },
                    onShuffle = { scope.launch { playerVm.playTracks(libraryVm.randomSongs(), 0) } },
                    enabled = songs.itemCount > 0,
                )
            }
            items(songs.itemCount, key = songs.itemKey { it.id }) { index ->
                val track = songs[index]
                if (track == null) RowPlaceholder()
                else TrackRow(
                    track = track,
                    isCurrent = track.id == player.current?.mediaId,
                    onClick = { scope.launch { playerVm.playTracks(libraryVm.songsFrom(index), 0) } },
                    onPlayNext = { playerVm.playNext(track) },
                    onAddToQueue = { playerVm.addToQueue(track) },
                )
            }
        }
        AlphabetBar(jumps, Modifier.align(Alignment.CenterEnd)) { position -> scope.launch { state.scrollToItem(position + 1) } }
    }
}

/** Every album A–Z as a grid of covers, page by page. */
@Composable
internal fun AlbumsTab(libraryVm: LibraryViewModel, onOpenAlbum: (String) -> Unit) {
    val albums = libraryVm.albumPages.collectAsLazyPagingItems()
    val jumps by libraryVm.albumJumps.collectAsState()
    val scope = rememberCoroutineScope()
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        PagedAlbumGrid(albums, libraryVm, state, onOpenAlbum, endSpace = barSpace(jumps))
        AlphabetBar(jumps, Modifier.align(Alignment.CenterEnd)) { position -> scope.launch { state.scrollToItem(position) } }
    }
}

@Composable
private fun PagedAlbumGrid(
    albums: androidx.paging.compose.LazyPagingItems<AlbumIndex>,
    libraryVm: LibraryViewModel,
    state: LazyGridState,
    onOpenAlbum: (String) -> Unit,
    header: (@Composable () -> Unit)? = null,
    endSpace: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val extraCovers by libraryVm.albumCoverUrls.collectAsState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        state = state,
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 12.dp + endSpace),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(albums.itemCount, key = albums.itemKey { it.key }) { index ->
            val album = albums[index]
            val cover = album?.artworkUri ?: album?.key?.let { extraCovers[it] }
            if (album != null && cover == null) {
                LaunchedEffect(album.key) {
                    kotlinx.coroutines.delay(250)
                    libraryVm.albumCover(album.key, album.title, album.artist)
                }
            }
            Column(Modifier.then(if (album != null) Modifier.clickable { onOpenAlbum(album.key) } else Modifier)) {
                Artwork(cover, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(album?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                ArtistAlbumText(album?.artist ?: "", listOf(album?.artist))
            }
        }
    }
}

/** Every artist A–Z with their photo, page by page; photos and genres are looked up as rows settle in view. */
@Composable
internal fun ArtistsTab(libraryVm: LibraryViewModel, onOpenArtist: (String) -> Unit) {
    val artists = libraryVm.artistPages.collectAsLazyPagingItems()
    val jumps by libraryVm.artistJumps.collectAsState()
    val photos by libraryVm.artistImageUrls.collectAsState()
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(end = barSpace(jumps))) {
            items(artists.itemCount, key = artists.itemKey { it.name }) { index ->
                val artist = artists[index]
                if (artist == null) RowPlaceholder()
                else {
                    val photo = photos[artist.name.lowercase()]
                    LaunchedEffect(artist.name) {
                        kotlinx.coroutines.delay(250)
                        if (photo == null) libraryVm.artistImage(artist.name)
                    }
                    ArtistRow(artist.name, artist.albumCount, artist.trackCount, photo) { onOpenArtist(artist.name) }
                }
            }
        }
        AlphabetBar(jumps, Modifier.align(Alignment.CenterEnd)) { position -> scope.launch { state.scrollToItem(position) } }
    }
}

/** Genres A–Z with their size; tap for the genre's albums. */
@Composable
internal fun GenresTab(genres: List<GenreIndex>, onOpenGenre: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(genres, key = { it.key }) { genre ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenGenre(genre.key) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(genre.artworkUri, size = 52.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(genre.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(count(genre.artistCount, "artist"), count(genre.albumCount, "album"), count(genre.songCount, "song"))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A genre: its albums (only their songs of this genre open), Play / Shuffle of up to a thousand songs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreScreen(
    key: String,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val genre by remember(key) { libraryVm.genre(key) }.collectAsState(null)
    val albums = remember(key) { libraryVm.genreAlbumPages(key) }.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(genre?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        PagedAlbumGrid(albums, libraryVm, rememberLazyGridState(), onOpenAlbum, header = {
            Column {
                genre?.let {
                    Text(
                        listOf(count(it.artistCount, "artist"), count(it.albumCount, "album"), count(it.songCount, "song")).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                PlayShuffleRow(
                    onPlay = { scope.launch { playerVm.playTracks(libraryVm.genreTracks(key, shuffle = false), 0) } },
                    onShuffle = { scope.launch { playerVm.playTracks(libraryVm.genreTracks(key, shuffle = true), 0) } },
                    enabled = (genre?.songCount ?: 0) > 0,
                )
            }
        })
    }
}

/** Room on the right for the A–Z bar, when the list has one. */
private fun barSpace(jumps: List<Jump>) = if (jumps.size >= 2) 28.dp else 0.dp

/** The height of a song or artist row, for list positions whose page hasn't loaded yet. */
@Composable
internal fun RowPlaceholder() {
    Box(Modifier.fillMaxWidth().height(68.dp))
}

/**
 * The A–Z bar along a long list: touch or drag over it to jump to the first entry of a letter
 * ("#" for digits and symbols); the letter under the finger shows in a bubble.
 */
@Composable
internal fun AlphabetBar(jumps: List<Jump>, modifier: Modifier = Modifier, onJump: (Int) -> Unit) {
    if (jumps.size < 2) return
    var selected by remember { mutableStateOf<String?>(null) }
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    Row(
        modifier.fillMaxHeight().padding(vertical = 8.dp).onSizeChanged { barHeight = with(density) { it.height.toDp() } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        selected?.let {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(it, color = MaterialTheme.colorScheme.onPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(
            Modifier.fillMaxHeight().width(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                .pointerInput(jumps) {
                    fun pick(y: Float) {
                        val i = (y / size.height * jumps.size).toInt().coerceIn(0, jumps.size - 1)
                        if (jumps[i].label != selected) {
                            selected = jumps[i].label
                            onJump(jumps[i].position)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.y)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            pick(change.position.y)
                            change.consume()
                        }
                        selected = null
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Sized from the bar's height, not the font scale, so every letter fits.
            val fontSize = with(LocalDensity.current) { (barHeight / jumps.size * 0.7f).coerceIn(6.dp, 12.dp).toSp() }
            jumps.forEach { jump ->
                Text(
                    jump.label,
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    fontWeight = if (jump.label == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (jump.label == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


