package com.kike.musicplayer.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.kike.musicplayer.R
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.recommend.Replay
import com.kike.musicplayer.recommend.ReplayPeriod
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Replay: a year or one of its months in numbers. A header with the totals, a plays-per-month chart
 * whose bars pick the month, the top artists and albums as cards, the top songs with bars, and a
 * few insights.
 */
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
    val photos by libraryVm.artistImageUrls.collectAsState()
    val navigation = LocalNavigation.current
    val context = LocalContext.current
    var allSongs by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        val r = replay
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(stringResource(R.string.replay_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (r != null) YearPicker(r) { libraryVm.setReplayPeriod(ReplayPeriod(it)) } },
        )
        if (r == null) return@Column
        val label = r.period.month?.let { "${monthName(it, TextStyle.FULL)} ${r.period.year}" } ?: "${r.period.year}"
        val songs = r.topTracks.map { it.item }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Header(
                    r, label,
                    onPlay = { playerVm.playTracks(songs, 0) },
                    onShuffle = { playerVm.playTracks(songs, 0, shuffle = true) },
                    onSave = {
                        libraryVm.createPlaylist("Replay $label", songs.map { it.id })
                        Toast.makeText(context, "Saved as playlist \"Replay $label\"", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            item {
                MonthChart(r, onPick = { month ->
                    libraryVm.setReplayPeriod(r.period.copy(month = if (month == r.period.month) null else month))
                    allSongs = false
                }, onWholeYear = { libraryVm.setReplayPeriod(r.period.copy(month = null)) })
            }
            if (r.plays == 0) {
                item {
                    Text(
                        stringResource(R.string.replay_empty_plays, label),
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }
            if (r.topArtists.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.replay_top_artists)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(r.topArtists, key = { _, a -> a.name }) { i, artist ->
                            Column(
                                Modifier.width(88.dp).clip(RoundedCornerShape(8.dp)).clickable { navigation.openArtist(listOf(artist.name)) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box {
                                    ArtistPhoto(photos[artist.name.lowercase()] ?: artist.artworkUri, Modifier.size(84.dp))
                                    RankBadge(i + 1, Modifier.align(Alignment.TopStart))
                                }
                                Text(artist.name, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                                Text(plays(artist.plays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (r.topAlbums.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.replay_top_albums)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(r.topAlbums, key = { _, a -> a.key }) { i, album ->
                            Column(Modifier.width(132.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpenAlbum(album.key) }) {
                                Box {
                                    Artwork(album.artworkUri, size = 132.dp, shape = RoundedCornerShape(8.dp))
                                    RankBadge(i + 1, Modifier.align(Alignment.BottomStart).padding(6.dp))
                                }
                                Text(album.title, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${album.artist} · ${plays(album.plays)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (r.topTracks.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.replay_top_songs)) }
                val top = r.topTracks.first().plays.toFloat()
                val shown = if (allSongs) r.topTracks else r.topTracks.take(TOP_SONGS)
                itemsIndexed(shown, key = { _, t -> "t" + t.item.id }) { index, ranked ->
                    TrackRow(
                        track = ranked.item,
                        isCurrent = ranked.item.id == player.current?.mediaId,
                        onClick = { playerVm.playTracks(songs, index) },
                        onPlayNext = { playerVm.playNext(ranked.item) },
                        onAddToQueue = { playerVm.addToQueue(ranked.item) },
                        badge = "${ranked.plays}",
                        rank = index + 1,
                        share = ranked.plays / top,
                        showStatus = false,
                    )
                }
                if (r.topTracks.size > TOP_SONGS) {
                    item {
                        TextButton(onClick = { allSongs = !allSongs }, Modifier.padding(start = 8.dp)) {
                            Text(if (allSongs) stringResource(R.string.replay_show_top, TOP_SONGS) else stringResource(R.string.replay_show_all, r.topTracks.size))
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.replay_insights)) }
            item { Insights(r) }
        }
    }
}

@Composable
private fun YearPicker(r: Replay, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${r.period.year}")
            Icon(Icons.Default.ArrowDropDown, "Choose year")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            r.years.forEach { year ->
                DropdownMenuItem(text = { Text("$year") }, onClick = { open = false; onPick(year) })
            }
        }
    }
}

@Composable
private fun Header(r: Replay, label: String, onPlay: () -> Unit, onShuffle: () -> Unit, onSave: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 18.dp)) {
            Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            comparison(r)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile(compact(r.plays.toLong()), stringResource(R.string.replay_stat_plays))
                StatTile(hours(r.listenedMs), stringResource(R.string.replay_stat_listened))
                StatTile(compact(r.artists.toLong()), stringResource(R.string.replay_stat_artists))
            }
            if (r.topTracks.isNotEmpty()) {
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.replay_play_top, r.topTracks.size))
                    }
                    FilledTonalIconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, stringResource(R.string.replay_shuffle_top)) }
                    FilledTonalIconButton(onClick = onSave) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.replay_save_as_playlist)) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(value: String, label: String) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Plays per month of the year; a bar opens its month, the selected one goes back to the year. */
@Composable
private fun MonthChart(r: Replay, onPick: (Int) -> Unit, onWholeYear: () -> Unit) {
    val max = (r.monthPlays.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.replay_plays_per_month), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (r.period.month != null) TextButton(onClick = onWholeYear) { Text(stringResource(R.string.replay_whole_year, r.period.year)) }
        }
        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            r.monthPlays.forEachIndexed { i, n ->
                val month = i + 1
                val selected = r.period.month == month
                val color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    r.period.month == null && n > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    n > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                        .then(if (n > 0) Modifier.clickable { onPick(month) } else Modifier),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight((n.toFloat() / max).coerceAtLeast(0.04f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(color),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..12).forEach { month ->
                Text(
                    monthName(month, TextStyle.NARROW), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (r.period.month == month) FontWeight.Bold else FontWeight.Normal,
                    color = if (r.period.month == month) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text("$rank", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun Insights(r: Replay) {
    val tiles = buildList {
        if (r.newArtists > 0) add(Triple(Icons.Default.AutoAwesome, stringResource(R.string.replay_insight_new_to_you), count(r.newArtists, "artist")))
        r.peakHour?.let { add(Triple(Icons.Default.Schedule, stringResource(R.string.replay_insight_peak_hour), "%02d:00–%02d:00".format(it, (it + 1) % 24))) }
        r.onRepeat?.let {
            val day = it.day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            add(Triple(Icons.Default.Repeat, stringResource(R.string.replay_insight_on_repeat), stringResource(R.string.replay_insight_on_repeat_desc, it.track.title, it.plays, day)))
        }
        r.topGenre?.let { add(Triple(Icons.Default.Category, stringResource(R.string.replay_insight_top_genre), it)) }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (icon, title, value) -> InsightTile(icon, title, value) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowScope.InsightTile(icon: ImageVector, title: String, value: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "+12% vs September", "−30% vs 2025", or null when there is nothing to compare with. */
@Composable
private fun comparison(r: Replay): String? {
    val before = r.previousPlays ?: return null
    if (r.plays == 0) return null
    val change = ((r.plays - before) * 100.0 / before).toInt()
    val previous = r.period.month?.let { monthName(if (it == 1) 12 else it - 1, TextStyle.FULL) } ?: "${r.period.year - 1}"
    return stringResource(R.string.replay_comparison, if (change >= 0) "+" else "−", kotlin.math.abs(change), previous)
}

private const val TOP_SONGS = 10

private fun plays(n: Int) = if (n == 1) "1 play" else "%,d plays".format(n)

private fun monthName(month: Int, style: TextStyle = TextStyle.SHORT) =
    Month.of(month).getDisplayName(style, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** "533 h", "45 min"; thousands of hours shortened like [compact]. */
private fun hours(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${compact(minutes / 60)} h" else "$minutes min"
}

/** Fits a stat tile: "850", "9,999", "10.6k", "125k". */
private fun compact(n: Long): String = when {
    n >= 100_000 -> "${n / 1000}k"
    n >= 10_000 -> "%.1fk".format(n / 1000.0)
    else -> "%,d".format(n)
}
