package com.kike.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kike.musicplayer.data.catalog.CatalogAlbum
import com.kike.musicplayer.data.catalog.CatalogPlaylist
import com.kike.musicplayer.data.db.AlbumIndex

sealed interface DiscographyItem {
    val key: String
    val title: String
    val year: Int?

    data class Owned(val album: AlbumIndex) : DiscographyItem {
        override val key: String get() = "owned:${album.key}"
        override val title: String get() = album.title
        override val year: Int? get() = album.year
    }

    data class Missing(val catalogAlbum: CatalogAlbum) : DiscographyItem {
        override val key: String get() = "missing:${catalogAlbum.id}"
        override val title: String get() = catalogAlbum.title
        override val year: Int? get() = catalogAlbum.year
    }
}

/**
 * Complete artist discography: 2-column vertically scrollable grid with filter chips
 * for library albums, missing albums from Deezer/Apple Music, and the complete unified discography.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDiscographyScreen(
    artistName: String,
    initialTab: Int = 0,
    initialSource: Int? = null,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenCatalogAlbum: (CatalogAlbum) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val ownedAlbums by remember(artistName) { libraryVm.artistAlbums(artistName) }.collectAsState(libraryVm.cachedArtistAlbums(artistName))
    val allTracks by remember(artistName) { libraryVm.artistTracks(artistName) }.collectAsState(libraryVm.cachedArtistTracks(artistName))
    val catalogEnabled by libraryVm.catalogEnabled.collectAsState()
    val extraCovers by libraryVm.albumCoverUrls.collectAsState()
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var source by rememberSaveable(artistName) { mutableStateOf(initialSource) }

    val tracks = remember(allTracks, source) { songsFromSource(allTracks, source) }
    val shownAlbums = remember(tracks) { tracks.mapTo(HashSet()) { it.indexAlbum } }
    val filteredOwnedAlbums = remember(ownedAlbums, shownAlbums, source) {
        if (source == null) ownedAlbums else ownedAlbums.filter { it.key in shownAlbums }
    }

    val missingAlbumsState by produceState<List<CatalogAlbum>?>(null, artistName, ownedAlbums.size, catalogEnabled) {
        if (!catalogEnabled) {
            value = emptyList()
        } else {
            val ownedTitles = ownedAlbums.map { it.title }
            value = libraryVm.artistMissingDiscography(artistName, ownedTitles)
        }
    }

    val missingAlbums = missingAlbumsState ?: emptyList()

    val allDiscography = remember(filteredOwnedAlbums, missingAlbums) {
        val ownedItems = filteredOwnedAlbums.map { DiscographyItem.Owned(it) }
        val missingItems = missingAlbums.map { DiscographyItem.Missing(it) }
        (ownedItems + missingItems).sortedWith(
            compareByDescending<DiscographyItem> { it.year ?: 0 }.thenBy { it.title.lowercase() }
        )
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = {
                Column {
                    Text(artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                    Text("Discography", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )

        // Filter chips bar
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("In Library (${filteredOwnedAlbums.size})") },
            )
            if (catalogEnabled) {
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = {
                        val countLabel = if (missingAlbumsState == null) "…" else missingAlbums.size.toString()
                        Text("Missing on Deezer & Apple ($countLabel)")
                    },
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = {
                        val totalLabel = if (missingAlbumsState == null) ownedAlbums.size.toString() else allDiscography.size.toString()
                        Text("All Discography ($totalLabel)")
                    },
                )
            }
        }

        SourceChips(allTracks, source, { source = it }, Modifier.padding(vertical = 4.dp))

        // Action row for owned music
        if (selectedTab == 0 && tracks.isNotEmpty()) {
            PlayShuffleRow(
                onPlay = { playerVm.playTracks(tracks, 0) },
                onShuffle = { playerVm.playTracks(tracks, 0, shuffle = true) },
                enabled = tracks.isNotEmpty(),
            )
        }

        when (selectedTab) {
            0 -> {
                // In Library Tab
                if (filteredOwnedAlbums.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No albums in your library for this artist.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filteredOwnedAlbums, key = { it.key }) { album ->
                            DiscographyOwnedCard(
                                album = album,
                                cover = album.artworkUri ?: extraCovers[album.key],
                                onFetchCover = { libraryVm.albumCover(album.key, album.title, album.artist) },
                                onClick = { onOpenAlbum(album.key) },
                            )
                        }
                    }
                }
            }
            1 -> {
                // Missing releases Tab
                if (missingAlbumsState == null) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (missingAlbums.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "All known studio and live albums are already in your library!",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(missingAlbums, key = { it.id }) { catAlbum ->
                            DiscographyMissingCard(
                                catalogAlbum = catAlbum,
                                onClick = { onOpenCatalogAlbum(catAlbum) },
                            )
                        }
                    }
                }
            }
            2 -> {
                // All Discography Tab
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(allDiscography, key = { it.key }) { item ->
                        when (item) {
                            is DiscographyItem.Owned -> {
                                DiscographyOwnedCard(
                                    album = item.album,
                                    cover = item.album.artworkUri ?: extraCovers[item.album.key],
                                    onFetchCover = { libraryVm.albumCover(item.album.key, item.album.title, item.album.artist) },
                                    onClick = { onOpenAlbum(item.album.key) },
                                )
                            }
                            is DiscographyItem.Missing -> {
                                DiscographyMissingCard(
                                    catalogAlbum = item.catalogAlbum,
                                    onClick = { onOpenCatalogAlbum(item.catalogAlbum) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscographyOwnedCard(
    album: AlbumIndex,
    cover: String?,
    onFetchCover: suspend () -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(album.key) {
        if (cover == null) {
            kotlinx.coroutines.delay(250)
            onFetchCover()
        }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Artwork(cover, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(10.dp))
        Text(
            album.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp),
        )
        val sub = buildList {
            album.year?.let { add(it.toString()) }
            if (album.trackCount > 0) add("${album.trackCount} songs")
        }.joinToString(" · ")
        Text(
            sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiscographyMissingCard(
    catalogAlbum: CatalogAlbum,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Artwork(catalogAlbum.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(10.dp))
        Text(
            catalogAlbum.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp),
        )
        val sourceLabel = if (catalogAlbum.source == CatalogPlaylist.SOURCE_DEEZER) "Deezer" else "Apple"
        val sub = buildList {
            catalogAlbum.year?.let { add(it.toString()) }
            add("On $sourceLabel")
        }.joinToString(" · ")
        Text(
            sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
