package com.kike.musicplayer.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.kike.musicplayer.recommend.Mix
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Home: Replay, Most Played and Recently Played tiles, generated mixes, recently played albums. */
@Composable
fun HomeTab(
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenReplay: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenRecentTracks: () -> Unit,
    onOpenMix: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val playlists by libraryVm.playlists.collectAsState()
    val mixes by libraryVm.mixes.collectAsState()
    val replay by libraryVm.replay.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val r = replay
                PlaylistTile(
                    title = "Replay ${r?.period?.year ?: ""}".trim(),
                    subtitle = if (r == null || r.plays == 0) "Your most played, by year and month" else "${r.plays} plays",
                    covers = r?.topTracks.orEmpty().mapNotNull { it.item.artworkUri }.distinct().take(4),
                    onOpen = onOpenReplay,
                    onPlay = r?.topTracks?.takeIf { it.isNotEmpty() }?.let { top -> { playerVm.playTracks(top.map { it.item }, 0) } },
                )
                val most = playlists.mostPlayed
                PlaylistTile(
                    title = "Most Played",
                    subtitle = if (most.isEmpty()) "Nothing played through yet" else "${most.size} songs",
                    covers = most.mapNotNull { it.item.artworkUri }.distinct().take(4),
                    onOpen = onOpenMostPlayed,
                    onPlay = if (most.isEmpty()) null else ({ playerVm.playTracks(most.map { it.item }, 0) }),
                )
                val recent = playlists.recentTracks
                PlaylistTile(
                    title = "Recently Played",
                    subtitle = if (recent.isEmpty()) "Nothing played yet" else "${recent.size} songs",
                    covers = recent.mapNotNull { it.artworkUri }.distinct().take(4),
                    onOpen = onOpenRecentTracks,
                    onPlay = if (recent.isEmpty()) null else ({ playerVm.playTracks(recent, 0) }),
                )
            }
        }
        item { SectionTitle("Made for you") }
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
                        MixCard(mix, onOpen = { onOpenMix(mix.id) }, onPlay = { playerVm.playTracks(mix.tracks, 0) })
                    }
                }
            }
        }
        if (playlists.recentAlbums.isNotEmpty()) {
            item { SectionTitle("Recently played albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(playlists.recentAlbums, key = { it.key }) { album ->
                        Column(Modifier.width(120.dp).clickable { onOpenAlbum(album.key) }) {
                            Artwork(album.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** A mix: 2×2 cover mosaic with a play button, title and the artists in it. */
@Composable
private fun MixCard(mix: Mix, onOpen: () -> Unit, onPlay: () -> Unit) {
    Column(Modifier.width(160.dp).clickable(onClick = onOpen)) {
        Box(Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))) {
            val covers = mix.covers
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
            FilledIconButton(onClick = onPlay, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Icon(Icons.Default.PlayArrow, "Play ${mix.title}")
            }
        }
        Text(mix.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Text(mix.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Replay: most played songs, artists and albums of a year or one of its months. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val replay by libraryVm.replay.collectAsState()
    val player by playerVm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text("Replay") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        val r = replay ?: return@Column
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(r.years) { year ->
                        FilterChip(
                            selected = r.period.year == year,
                            onClick = { libraryVm.setReplayPeriod(com.kike.musicplayer.recommend.ReplayPeriod(year)) },
                            label = { Text(year.toString()) },
                        )
                    }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = r.period.month == null,
                            onClick = { libraryVm.setReplayPeriod(r.period.copy(month = null)) },
                            label = { Text("Whole year") },
                        )
                    }
                    items((r.months + listOfNotNull(r.period.month)).distinct().sorted()) { month ->
                        FilterChip(
                            selected = r.period.month == month,
                            onClick = { libraryVm.setReplayPeriod(r.period.copy(month = month)) },
                            label = { Text(monthName(month)) },
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        val label = r.period.month?.let { "${monthName(it, TextStyle.FULL)} ${r.period.year}" } ?: "${r.period.year}"
                        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${r.plays} plays · ${hoursMinutes(r.listenedMs)} listened" +
                                (r.topArtists.firstOrNull()?.let { " · top artist ${it.name}" } ?: ""),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (r.topTracks.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { playerVm.playTracks(r.topTracks.map { it.item }, 0) }) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play top songs")
                            }
                        }
                    }
                }
            }
            if (r.plays == 0) {
                item {
                    Text(
                        "No plays counted in this period yet. A song counts once you've listened to half of it.",
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (r.topTracks.isNotEmpty()) {
                item { SectionTitle("Top songs") }
                itemsIndexed(r.topTracks, key = { _, t -> "t" + t.item.id }) { index, ranked ->
                    TrackRow(
                        track = ranked.item,
                        isCurrent = ranked.item.id == player.current?.mediaId,
                        onClick = { playerVm.playTracks(r.topTracks.map { it.item }, index) },
                        onPlayNext = { playerVm.playNext(ranked.item) },
                        onAddToQueue = { playerVm.addToQueue(ranked.item) },
                        leading = "${index + 1}",
                        badge = plays(ranked.plays),
                    )
                }
            }
            if (r.topArtists.isNotEmpty()) {
                item { SectionTitle("Top artists") }
                items(r.topArtists, key = { "a" + it.name }) { artist ->
                    RankedRow(artist.artworkUri, artist.name, null, plays(artist.plays), onClick = null)
                }
            }
            if (r.topAlbums.isNotEmpty()) {
                item { SectionTitle("Top albums") }
                items(r.topAlbums, key = { "b" + it.key }) { album ->
                    RankedRow(album.artworkUri, album.title, album.artist, plays(album.plays)) { onOpenAlbum(album.key) }
                }
            }
        }
    }
}

@Composable
private fun RankedRow(artwork: String?, title: String, subtitle: String?, badge: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artwork, size = 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

private fun monthName(month: Int, style: TextStyle = TextStyle.SHORT) =
    Month.of(month).getDisplayName(style, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun hoursMinutes(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}
