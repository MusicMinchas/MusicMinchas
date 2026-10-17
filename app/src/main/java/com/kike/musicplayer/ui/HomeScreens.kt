package com.kike.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.albumCovers
import com.kike.musicplayer.recommend.Mix
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home: the Recently Played tile, Replay / Favourite Songs / Most Played cards, generated mixes,
 * recently played and recently added albums.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenReplay: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenFavourites: () -> Unit,
    onOpenRecentTracks: () -> Unit,
    onOpenMix: (String) -> Unit,
    onOpenMixes: () -> Unit,
    onOpenRecentAlbums: () -> Unit,
    onOpenAddedAlbums: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val playlists by libraryVm.playlists.collectAsState()
    val mixes by libraryVm.mixes.collectAsState()
    val replay by libraryVm.replay.collectAsState()

    val refreshing by libraryVm.mixesRefreshing.collectAsState()
    // Pull down for new mixes; otherwise they stay as made (see LibraryViewModel.mixes).
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = libraryVm::refreshMixes, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val recent = playlists.recentTracks
                    PlaylistTile(
                        title = "Recently Played",
                        subtitle = if (recent.isEmpty()) "Nothing played yet" else "${recent.size} songs",
                        // One cover per album (see albumCovers).
                        covers = recent.albumCovers(),
                        onOpen = onOpenRecentTracks,
                        onPlay = if (recent.isEmpty()) null else ({ playerVm.playTracks(recent, 0) }),
                    )
                }
            }
            // Replay, Favourite Songs and Most Played as cards like the mixes, side by side.
            item {
                val r = replay
                val top = r?.topTracks.orEmpty().map { it.item }
                val favourites = playlists.favourites
                val most = playlists.mostPlayed.map { it.item }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CollectionCard(
                        title = "Replay ${r?.period?.year ?: ""}".trim(),
                        subtitle = if (r == null || r.plays == 0) "Your most played" else "${r.plays} plays",
                        tracks = top, onOpen = onOpenReplay,
                        onPlay = { playerVm.playTracks(top, 0) },
                        modifier = Modifier.width(MIX_CARD_WIDTH),
                    )
                    CollectionCard(
                        title = "Favourite Songs",
                        subtitle = if (favourites.isEmpty()) "Tap the heart on a song" else count(favourites.size, "song"),
                        tracks = favourites, onOpen = onOpenFavourites,
                        onPlay = { playerVm.playTracks(favourites, 0) },
                        modifier = Modifier.width(MIX_CARD_WIDTH),
                    )
                    CollectionCard(
                        title = "Most Played",
                        subtitle = if (most.isEmpty()) "Nothing played yet" else count(most.size, "song"),
                        tracks = most, onOpen = onOpenMostPlayed,
                        onPlay = { playerVm.playTracks(most, 0) },
                        modifier = Modifier.width(MIX_CARD_WIDTH),
                    )
                }
            }
            item { SectionTitle("Made for you", onClick = onOpenMixes.takeIf { mixes.isNotEmpty() }) }
            item {
                if (mixes.isEmpty()) {
                    Text(
                        "Mixes appear once genres have been fetched for your artists (Sources › online lookup) " +
                            "and grow better as you listen.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(mixes, key = { it.id }) { mix ->
                            CollectionCard(
                                mix.title, mix.subtitle, mix.tracks,
                                onOpen = { onOpenMix(mix.id) }, onPlay = { playerVm.playTracks(mix.tracks, 0) },
                                modifier = Modifier.width(MIX_CARD_WIDTH),
                            )
                        }
                    }
                }
            }
            if (playlists.recentAlbums.isNotEmpty()) {
                item { SectionTitle("Recently played albums", onClick = onOpenRecentAlbums) }
                item { AlbumRow(playlists.recentAlbums.take(HOME_ALBUMS), onOpenAlbum) }
            }
            if (playlists.addedAlbums.isNotEmpty()) {
                item { SectionTitle("Recently added albums", onClick = onOpenAddedAlbums) }
                item { AlbumRow(playlists.addedAlbums.take(HOME_ALBUMS), onOpenAlbum) }
            }
        }
    }
}

@Composable
private fun AlbumRow(albums: List<Album>, onOpenAlbum: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.key }) { album ->
            Column(Modifier.width(120.dp).clickable { onOpenAlbum(album.key) }) {
                Artwork(album.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                ArtistAlbumText(album.artist, listOf(album.artist))
            }
        }
    }
}

/** A section heading; with [onClick] it opens the whole list, marked by a chevron. */
@Composable
internal fun SectionTitle(text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "See all")
    }
}

/** Made for you: every mix as a card, in a grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixListScreen(
    mixes: List<Mix>,
    playerVm: PlayerViewModel,
    onOpenMix: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text("Made for you") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(mixes, key = { it.id }) { mix ->
                CollectionCard(mix.title, mix.subtitle, mix.tracks,
                    onOpen = { onOpenMix(mix.id) }, onPlay = { playerVm.playTracks(mix.tracks, 0) })
            }
        }
    }
}

/** Albums in a Home row; their pages show [LibraryViewModel]'s full 30. */
private const val HOME_ALBUMS = 10

/**
 * A mix or playlist card: 2×2 cover mosaic (one cover when fewer than four) with a play button,
 * title and subtitle. Square, as wide as [modifier] makes it.
 */
@Composable
internal fun CollectionCard(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            CoverMosaic(tracks, Modifier.fillMaxSize())
            if (tracks.isNotEmpty()) {
                FilledIconButton(onClick = onPlay, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    Icon(Icons.Default.PlayArrow, "Play $title")
                }
            }
        }
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The top of a mix or playlist page, laid out like an album's: the card's cover mosaic, the name,
 * an optional line about it (e.g. a mix's artists), then [extra] (e.g. "12 albums"), the number of
 * songs and the total time.
 */
@Composable
internal fun CollectionHeader(title: String, tracks: List<Track>, subtitle: String? = null, extra: String? = null) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverMosaic(tracks, Modifier.size(140.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(extra, count(tracks.size, "song"), formatTime(tracks.sumOf { it.durationMs.coerceAtLeast(0) }))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 2×2 covers of [tracks] (one cover when fewer than four), rounded. */
@Composable
internal fun CoverMosaic(tracks: List<Track>, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        val covers = tracks.albumCovers()
        if (covers.size >= 4) {
            Column {
                for (rowCovers in covers.chunked(2)) {
                    Row(Modifier.weight(1f)) {
                        rowCovers.forEach { Artwork(it, Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp)) }
                    }
                }
            }
        } else {
            Artwork(covers.firstOrNull(), Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp))
        }
    }
}

private val MIX_CARD_WIDTH = 160.dp

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
