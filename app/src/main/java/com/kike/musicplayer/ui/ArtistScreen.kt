package com.kike.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.kike.musicplayer.data.db.Track

/**
 * An artist: photo with the name over it, totals, Play / Shuffle / Download, their albums (newest
 * first), their 10 most played songs (when any were played), then all their songs.
 */
@Composable
fun ArtistScreen(
    name: String,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val player by playerVm.state.collectAsState()
    val artistOrNull by remember(name) { libraryVm.artist(name) }.collectAsState(null)
    val albums by remember(name) { libraryVm.artistAlbums(name) }.collectAsState(emptyList())
    val allTracks by remember(name) { libraryVm.artistTracks(name) }.collectAsState(emptyList())
    val allTop by remember(name) { libraryVm.artistTop(name) }.collectAsState(emptyList())
    // Songs from several sources: all of them once (own copy first), or one source's.
    var source by rememberSaveable(name) { mutableStateOf<Int?>(null) }
    val tracks = remember(allTracks, source) { songsFromSource(allTracks, source) }
    val shownIds = remember(tracks) { tracks.mapTo(HashSet()) { it.id } }
    val shownAlbums = remember(tracks) { tracks.mapTo(HashSet()) { it.indexAlbum } }
    val topPlays = allTop.filter { it.track.id in shownIds }
    val followed by libraryVm.followedArtists.collectAsState()
    val extraCovers by libraryVm.albumCoverUrls.collectAsState()
    val image by produceState<String?>(null, name) { value = libraryVm.artistImage(name) }
    // Edge to edge: the photo goes under the status bar.
    Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding())) {
        val artist = artistOrNull ?: return@Box
        val albums = albums.filter { it.key in shownAlbums }
        val top = topPlays.map { it.track }
        val playCounts = topPlays.associate { it.track.id to it.plays }
        val listState = rememberLazyListState()
        // Once the photo has scrolled away, the status bar gets a solid background and the back
        // button sits in a bar, so neither covers the songs.
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Header(artist.name, image) }
            item {
                Text(
                    listOf(count(albums.size, "album"), count(tracks.size, "song"), formatTime(tracks.sumOf { it.durationMs.coerceAtLeast(0) }))
                        .joinToString(" · "),
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SourceChips(allTracks, source, { source = it }, Modifier.padding(top = 8.dp)) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(onClick = { playerVm.playTracks(tracks, 0) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.PlayArrow, "Play", Modifier.size(32.dp))
                    }
                    FilledTonalIconButton(onClick = { playerVm.playTracks(tracks, 0, shuffle = true) }) {
                        Icon(Icons.Default.Shuffle, "Shuffle")
                    }
                    DownloadButton(tracks)
                    FollowButton(artist.name in followed, onClick = {
                        if (artist.name in followed) libraryVm.unfollow(artist.name) else libraryVm.follow(artist.name)
                    })
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionTitle("Albums") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums, key = { it.key }) { album ->
                            val cover = album.artworkUri ?: extraCovers[album.key]
                            androidx.compose.runtime.LaunchedEffect(album.key) {
                                if (cover == null) {
                                    kotlinx.coroutines.delay(250)
                                    libraryVm.albumCover(album.key, album.title, album.artist)
                                }
                            }
                            Column(Modifier.width(140.dp).clickable { onOpenAlbum(album.key) }) {
                                Artwork(cover, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(10.dp))
                                Text(album.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 6.dp))
                                album.year?.let {
                                    Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (top.isNotEmpty()) {
                item { SectionTitle("Top songs") }
                itemsIndexed(top, key = { _, t -> "top:" + t.id }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == player.current?.mediaId,
                        onClick = { playerVm.playTracks(top, index) },
                        onPlayNext = { playerVm.playNext(track) },
                        onAddToQueue = { playerVm.addToQueue(track) },
                        leading = "${index + 1}",
                        badge = (playCounts[track.id] ?: 0).let { if (it == 1) "1 play" else "$it plays" },
                    )
                }
            }
            item { SectionTitle("Songs") }
            itemsIndexed(tracks, key = { _, t -> "all:" + t.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == player.current?.mediaId,
                    onClick = { playerVm.playTracks(tracks, index) },
                    onPlayNext = { playerVm.playNext(track) },
                    onAddToQueue = { playerVm.addToQueue(track) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .then(if (scrolled) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
                .statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (scrolled) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            if (scrolled) {
                Text(artist.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The artist's photo fading into the page, with the name on the fade. */
@Composable
private fun Header(name: String, image: String?) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.BottomCenter) {
        SubcomposeAsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
            loading = { HeaderPlaceholder() },
            error = { HeaderPlaceholder() },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to background)
            )
        )
        Text(
            name,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeaderPlaceholder() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, null, Modifier.size(96.dp).padding(bottom = 48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Follow: the artist's releases, concerts and news appear in Discover. */
@Composable
private fun FollowButton(following: Boolean, onClick: () -> Unit) {
    if (following) {
        FilledTonalButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Default.Check, null, Modifier.size(ButtonDefaults.IconSize))
            Text("Following", Modifier.padding(start = 6.dp))
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Default.Add, null, Modifier.size(ButtonDefaults.IconSize))
            Text("Follow", Modifier.padding(start = 6.dp))
        }
    }
}

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private const val TOP_SONGS = 10
