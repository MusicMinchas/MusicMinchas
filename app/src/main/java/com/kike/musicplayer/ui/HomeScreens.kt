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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.kike.musicplayer.R
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.albumCovers
import com.kike.musicplayer.data.catalog.CatalogPlaylist
import com.kike.musicplayer.data.catalog.DeezerHomeSection
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
    onOpenHeavyRotation: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenFavourites: () -> Unit,
    onOpenRecentTracks: () -> Unit,
    onOpenMix: (String) -> Unit,
    onOpenMixes: () -> Unit,
    onOpenGenreDecadeMixes: () -> Unit = {},
    onOpenRecentAlbums: () -> Unit,
    onOpenAddedAlbums: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenCatalogPlaylist: (CatalogPlaylist) -> Unit = {},
    onOpenCatalogSection: (DeezerHomeSection) -> Unit = {},
) {
    val playlists by libraryVm.playlists.collectAsState()
    val mixes by libraryVm.mixes.collectAsState()
    val replay by libraryVm.replay.collectAsState()
    val deezerEnrichment by libraryVm.deezerHomeEnrichment.collectAsState()
    val deezerContent by libraryVm.deezerHomeContent.collectAsState()

    val refreshing by libraryVm.mixesRefreshing.collectAsState()
    val recentCovers = remember(playlists.recentTracks) { playlists.recentTracks.albumCovers() }
    val replayTopTracks = remember(replay) { replay?.topTracks.orEmpty().map { it.item } }
    val rotationTracks = remember(playlists.heavyRotation) { playlists.heavyRotation.map { it.item } }
    val mostPlayedTracks = remember(playlists.mostPlayed) { playlists.mostPlayed.map { it.item } }
    val personalMixes = remember(mixes) { mixes.filter { !it.id.startsWith("genre:") && !it.id.startsWith("decade:") } }
    val genreDecadeMixes = remember(mixes) { mixes.filter { it.id.startsWith("genre:") || it.id.startsWith("decade:") } }

    // Pull down for new mixes; otherwise they stay as made (see LibraryViewModel.mixes).
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = libraryVm::refreshMixes, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
            item(key = "home_recent_tile") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val recent = playlists.recentTracks
                    PlaylistTile(
                        title = stringResource(R.string.home_recently_played),
                        subtitle = if (recent.isEmpty()) stringResource(R.string.home_empty_recent_subtitle) else count(recent.size, "song"),
                        // One cover per album (see albumCovers).
                        covers = recentCovers,
                        onOpen = onOpenRecentTracks,
                        onPlay = if (recent.isEmpty()) null else ({ playerVm.playTracks(recent, 0) }),
                    )
                }
            }
            // Replay, Favourite Songs and Most Played as cards like the mixes, side by side.
            item(key = "home_collection_cards") {
                val r = replay
                val favourites = playlists.favourites
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    item(key = "card_replay") {
                        CollectionCard(
                            title = "${stringResource(R.string.home_replay)} ${r?.period?.year ?: ""}".trim(),
                            subtitle = if (r == null || r.plays == 0) stringResource(R.string.home_empty_replay_subtitle) else count(r.plays, "play"),
                            tracks = replayTopTracks, onOpen = onOpenReplay,
                            onPlay = { playerVm.playTracks(replayTopTracks, 0) },
                            modifier = Modifier.width(MIX_CARD_WIDTH),
                        )
                    }
                    item(key = "card_rotation") {
                        CollectionCard(
                            title = stringResource(R.string.home_heavy_rotation),
                            subtitle = if (rotationTracks.isEmpty()) stringResource(R.string.home_empty_rotation_subtitle) else count(rotationTracks.size, "song"),
                            tracks = rotationTracks, onOpen = onOpenHeavyRotation,
                            onPlay = { playerVm.playTracks(rotationTracks, 0) },
                            modifier = Modifier.width(MIX_CARD_WIDTH),
                        )
                    }
                    item(key = "card_favourites") {
                        CollectionCard(
                            title = stringResource(R.string.home_favourites),
                            subtitle = if (favourites.isEmpty()) stringResource(R.string.home_empty_favourites_subtitle) else count(favourites.size, "song"),
                            tracks = favourites, onOpen = onOpenFavourites,
                            onPlay = { playerVm.playTracks(favourites, 0) },
                            modifier = Modifier.width(MIX_CARD_WIDTH),
                        )
                    }
                    item(key = "card_most_played") {
                        CollectionCard(
                            title = stringResource(R.string.home_most_played),
                            subtitle = if (mostPlayedTracks.isEmpty()) stringResource(R.string.home_empty_most_played_subtitle) else count(mostPlayedTracks.size, "song"),
                            tracks = mostPlayedTracks, onOpen = onOpenMostPlayed,
                            onPlay = { playerVm.playTracks(mostPlayedTracks, 0) },
                            modifier = Modifier.width(MIX_CARD_WIDTH),
                        )
                    }
                }
            }
            item(key = "title_made_for_you", contentType = "section_title") {
                SectionTitle(stringResource(R.string.home_made_for_you), onClick = onOpenMixes.takeIf { personalMixes.isNotEmpty() })
            }
            item(key = "row_made_for_you", contentType = "mix_row") {
                if (personalMixes.isEmpty()) {
                    Text(
                        stringResource(R.string.home_mixes_empty_hint),
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(personalMixes, key = { it.id }, contentType = { "mix_card" }) { mix ->
                            CollectionCard(
                                mixDisplayTitle(mix), mixDisplaySubtitle(mix), mix.tracks,
                                onOpen = { onOpenMix(mix.id) }, onPlay = { playerVm.playTracks(mix.tracks, 0) },
                                modifier = Modifier.width(MIX_CARD_WIDTH),
                            )
                        }
                    }
                }
            }
            if (deezerEnrichment && deezerContent != null) {
                val sTrending = deezerContent?.sections?.find { it.id == "trending" }
                if (sTrending != null && sTrending.playlists.isNotEmpty()) {
                    item(key = "title_dz_trending", contentType = "section_title") {
                        SectionTitle(sectionDisplayTitle(sTrending), sectionDisplaySubtitle(sTrending), onClick = { onOpenCatalogSection(sTrending) })
                    }
                    item(key = "row_dz_trending", contentType = "deezer_row") {
                        DeezerPlaylistRow(sTrending.playlists.take(10), onOpenCatalogPlaylist)
                    }
                }
                val sCharts = deezerContent?.sections?.find { it.id == "top_charts" }
                if (sCharts != null && sCharts.playlists.isNotEmpty()) {
                    item(key = "title_dz_charts", contentType = "section_title") {
                        SectionTitle(sectionDisplayTitle(sCharts), sectionDisplaySubtitle(sCharts), onClick = { onOpenCatalogSection(sCharts) })
                    }
                    item(key = "row_dz_charts", contentType = "deezer_row") {
                        DeezerPlaylistRow(sCharts.playlists.take(10), onOpenCatalogPlaylist)
                    }
                }
            }
            if (playlists.recentAlbums.isNotEmpty()) {
                item(key = "title_recent_albums", contentType = "section_title") {
                    SectionTitle(stringResource(R.string.home_recently_played_albums), onClick = onOpenRecentAlbums)
                }
                item(key = "row_recent_albums", contentType = "album_row") {
                    AlbumRow(playlists.recentAlbums.take(HOME_ALBUMS), onOpenAlbum)
                }
            }
            if (genreDecadeMixes.isNotEmpty()) {
                item(key = "title_genre_decade", contentType = "section_title") {
                    SectionTitle(stringResource(R.string.home_genre_decade_mixes), onClick = onOpenGenreDecadeMixes)
                }
                item(key = "row_genre_decade", contentType = "mix_row") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(genreDecadeMixes, key = { it.id }, contentType = { "mix_card" }) { mix ->
                            CollectionCard(
                                mixDisplayTitle(mix), mixDisplaySubtitle(mix), mix.tracks,
                                onOpen = { onOpenMix(mix.id) }, onPlay = { playerVm.playTracks(mix.tracks, 0) },
                                modifier = Modifier.width(MIX_CARD_WIDTH),
                            )
                        }
                    }
                }
            }
            if (deezerEnrichment && deezerContent != null) {
                val otherSections = deezerContent?.sections.orEmpty()
                    .filter { it.id != "trending" && it.id != "top_charts" }
                val s1 = otherSections.getOrNull(0)
                if (s1 != null && s1.playlists.isNotEmpty()) {
                    item(key = "title_dz_${s1.id}", contentType = "section_title") {
                        SectionTitle(sectionDisplayTitle(s1), sectionDisplaySubtitle(s1), onClick = { onOpenCatalogSection(s1) })
                    }
                    item(key = "row_dz_${s1.id}", contentType = "deezer_row") {
                        DeezerPlaylistRow(s1.playlists.take(10), onOpenCatalogPlaylist)
                    }
                }
            }
            if (playlists.addedAlbums.isNotEmpty()) {
                item(key = "title_added_albums", contentType = "section_title") {
                    SectionTitle(stringResource(R.string.home_recently_added_albums), onClick = onOpenAddedAlbums)
                }
                item(key = "row_added_albums", contentType = "album_row") {
                    AlbumRow(playlists.addedAlbums.take(HOME_ALBUMS), onOpenAlbum)
                }
            }
            if (deezerEnrichment && deezerContent != null) {
                val remaining = deezerContent?.sections.orEmpty()
                    .filter { it.id != "trending" && it.id != "top_charts" }
                    .drop(1)
                for (section in remaining) {
                    if (section.playlists.isNotEmpty()) {
                        item(key = "title_dz_${section.id}", contentType = "section_title") {
                            SectionTitle(sectionDisplayTitle(section), sectionDisplaySubtitle(section), onClick = { onOpenCatalogSection(section) })
                        }
                        item(key = "row_dz_${section.id}", contentType = "deezer_row") {
                            DeezerPlaylistRow(section.playlists.take(10), onOpenCatalogPlaylist)
                        }
                    }
                }
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
internal fun SectionTitle(text: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "See all")
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun DeezerPlaylistRow(
    playlists: List<CatalogPlaylist>,
    onOpenCatalogPlaylist: (CatalogPlaylist) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            CatalogPlaylistCard(
                playlist = playlist,
                onOpen = { onOpenCatalogPlaylist(playlist) },
                modifier = Modifier.width(DEEZER_CARD_WIDTH),
            )
        }
    }
}

@Composable
internal fun CatalogPlaylistCard(
    playlist: CatalogPlaylist,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clickable(onClick = onOpen)) {
        PlaylistCover(
            coverUrl = playlist.coverUrl,
            mosaicCovers = playlist.mosaicCovers,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
        )
        Text(
            playlist.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            count(playlist.trackCount, "song"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DEEZER_CARD_WIDTH = 130.dp
private val MIX_CARD_WIDTH = 130.dp

/** Made for you: every mix as a card, in a grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixListScreen(
    mixes: List<Mix>,
    playerVm: PlayerViewModel,
    onOpenMix: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    title: String? = null,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text(title ?: stringResource(R.string.home_made_for_you)) },
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
                CollectionCard(mixDisplayTitle(mix), mixDisplaySubtitle(mix), mix.tracks,
                    onOpen = { onOpenMix(mix.id) }, onPlay = { playerVm.playTracks(mix.tracks, 0) })
            }
        }
    }
}

/** Albums in a Home row; their pages show [LibraryViewModel]'s full 30. */
private const val HOME_ALBUMS = 10

@Composable
fun mixDisplayTitle(mix: Mix): String {
    return when {
        mix.id.startsWith("because:") -> {
            val artist = mix.title.removePrefix("Because you listened to ")
            stringResource(R.string.mix_because, artist)
        }
        mix.id.startsWith("genre:") -> {
            val genre = mix.title.removeSuffix(" Mix")
            stringResource(R.string.mix_genre, genre)
        }
        mix.id.startsWith("decade:") -> {
            val decade = mix.title.removeSuffix("s Mix")
            stringResource(R.string.mix_decade, decade)
        }
        mix.id == "rediscover" -> stringResource(R.string.mix_rediscover_title)
        mix.id == "forgotten" -> stringResource(R.string.mix_forgotten_title)
        mix.id == "fresh" -> stringResource(R.string.mix_fresh_title)
        else -> mix.title
    }
}

@Composable
fun mixDisplaySubtitle(mix: Mix): String {
    return when (mix.id) {
        "rediscover" -> stringResource(R.string.mix_rediscover_subtitle)
        "forgotten" -> stringResource(R.string.mix_forgotten_subtitle)
        "fresh" -> stringResource(R.string.mix_fresh_subtitle)
        else -> mix.subtitle
    }
}

@Composable
fun sectionDisplayTitle(section: com.kike.musicplayer.data.catalog.DeezerHomeSection): String {
    return when (section.id) {
        "trending" -> stringResource(R.string.home_trending_deezer)
        "top_charts" -> stringResource(R.string.home_top_charts_deezer)
        "rock" -> stringResource(R.string.catalog_genre_rock)
        "metal" -> stringResource(R.string.catalog_genre_metal)
        "alternative" -> stringResource(R.string.catalog_genre_alternative)
        "classical" -> stringResource(R.string.catalog_genre_classical)
        "acoustic" -> stringResource(R.string.catalog_genre_acoustic)
        "pop" -> stringResource(R.string.catalog_genre_pop)
        "electronic" -> stringResource(R.string.catalog_genre_electronic)
        "jazz" -> stringResource(R.string.catalog_genre_jazz)
        "blues" -> stringResource(R.string.catalog_genre_blues)
        "rnb" -> stringResource(R.string.catalog_genre_rnb)
        "rap" -> stringResource(R.string.catalog_genre_rap)
        "soundtracks" -> stringResource(R.string.catalog_genre_soundtracks)
        "mood_focus" -> stringResource(R.string.catalog_mood_focus)
        "mood_workout" -> stringResource(R.string.catalog_mood_workout)
        "mood_chill" -> stringResource(R.string.catalog_mood_chill)
        "mood_party" -> stringResource(R.string.catalog_mood_party)
        "mood_sleep" -> stringResource(R.string.catalog_mood_sleep)
        "mood_morning" -> stringResource(R.string.catalog_mood_morning)
        else -> section.title
    }
}

@Composable
fun sectionDisplaySubtitle(section: com.kike.musicplayer.data.catalog.DeezerHomeSection): String? {
    return when (section.id) {
        "trending" -> stringResource(R.string.home_trending_deezer_subtitle)
        "top_charts" -> stringResource(R.string.home_top_charts_deezer_subtitle)
        "rock" -> stringResource(R.string.catalog_genre_rock_sub)
        "metal" -> stringResource(R.string.catalog_genre_metal_sub)
        "alternative" -> stringResource(R.string.catalog_genre_alternative_sub)
        "classical" -> stringResource(R.string.catalog_genre_classical_sub)
        "acoustic" -> stringResource(R.string.catalog_genre_acoustic_sub)
        "pop" -> stringResource(R.string.catalog_genre_pop_sub)
        "electronic" -> stringResource(R.string.catalog_genre_electronic_sub)
        "jazz" -> stringResource(R.string.catalog_genre_jazz_sub)
        "blues" -> stringResource(R.string.catalog_genre_blues_sub)
        "rnb" -> stringResource(R.string.catalog_genre_rnb_sub)
        "rap" -> stringResource(R.string.catalog_genre_rap_sub)
        "soundtracks" -> stringResource(R.string.catalog_genre_soundtracks_sub)
        "mood_focus" -> stringResource(R.string.catalog_mood_focus_sub)
        "mood_workout" -> stringResource(R.string.catalog_mood_workout_sub)
        "mood_chill" -> stringResource(R.string.catalog_mood_chill_sub)
        "mood_party" -> stringResource(R.string.catalog_mood_party_sub)
        "mood_sleep" -> stringResource(R.string.catalog_mood_sleep_sub)
        "mood_morning" -> stringResource(R.string.catalog_mood_morning_sub)
        else -> section.subtitle
    }
}

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
        val covers = remember(tracks) { tracks.albumCovers() }
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



