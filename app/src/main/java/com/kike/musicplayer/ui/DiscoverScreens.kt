package com.kike.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.kike.musicplayer.data.db.DiscoverItem
import com.kike.musicplayer.data.discover.Names
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * New releases, concerts in the chosen country and news for the artists the user follows. Fetched
 * when the tab opens (at most every few hours) or on pull-down; what was found last shows at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverTab(
    vm: LibraryViewModel,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenFollowing: () -> Unit,
) {
    val followed by vm.followedArtists.collectAsState()
    val items by vm.discoverItems.collectAsState()
    val refreshing by vm.discoverRefreshing.collectAsState()
    val country by vm.concertCountry.collectAsState()
    val library by vm.library.collectAsState()
    val photos by vm.artistImageUrls.collectAsState()
    val uriHandler = LocalUriHandler.current
    var pickCountry by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshDiscover(force = false) }

    if (followed.isEmpty()) {
        NoneFollowed(vm.suggestedArtists.collectAsState().value, vm::follow)
        return
    }
    val today = LocalDate.now()
    val releases = items.filter { it.kind == DiscoverItem.RELEASE }.sortedByDescending { it.date }
    val concerts = items.filter { it.kind == DiscoverItem.CONCERT && !localDate(it.date).isBefore(today) }
    val news = items.filter { it.kind == DiscoverItem.NEWS }.sortedByDescending { it.date }.take(MAX_NEWS)
    fun open(url: String?) = url?.let { runCatching { uriHandler.openUri(it) } }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refreshDiscover(force = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "New releases", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = onOpenFollowing) {
                        Text("Following ${followed.size}")
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
            }
            item {
                if (releases.isEmpty()) {
                    EmptyNote(if (refreshing) "Looking for releases…" else "No releases in the last 3 months")
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(releases, key = { it.id }) { release ->
                            ReleaseCard(release, photos[release.artist.lowercase()], today) {
                                val inLibrary = library.albums.firstOrNull {
                                    it.artist == release.artist && Names.comparable(it.title) == Names.comparable(release.title)
                                }
                                if (inLibrary != null) onOpenAlbum(inLibrary.key) else open(release.url)
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { pickCountry = true }.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Concerts in ${countryName(country)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.ArrowDropDown, "Change country")
                }
            }
            if (concerts.isEmpty()) {
                item { EmptyNote(if (refreshing) "Looking for concerts…" else "No concerts announced in ${countryName(country)}") }
            }
            items(concerts, key = { it.id }) { concert -> ConcertRow(concert) { open(concert.url) } }
            item { SectionTitle("News") }
            if (news.isEmpty()) {
                item { EmptyNote(if (refreshing) "Looking for news…" else "No news in the last 30 days") }
            }
            items(news, key = { it.id }) { story -> NewsRow(story, photos[story.artist.lowercase()]) { open(story.url) } }
        }
    }
    if (pickCountry) {
        CountryDialog(country, onPick = { pickCountry = false; vm.setConcertCountry(it) }, onDismiss = { pickCountry = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoneFollowed(suggested: List<String>, onFollow: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Default.Explore, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Follow artists to see their news", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "New releases, concerts and news for the artists you follow show up here. Follow an artist from " +
                "their page" + if (suggested.isNotEmpty()) ", or start with the ones you play most:" else ".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            suggested.forEach { name ->
                AssistChip(
                    onClick = { onFollow(name) },
                    label = { Text(name) },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReleaseCard(release: DiscoverItem, artistPhoto: String?, today: LocalDate, onClick: () -> Unit) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box {
            SubcomposeAsyncImage(
                model = release.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                // No cover yet (announced releases often have none): the artist's photo.
                error = {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (artistPhoto != null) AsyncImage(artistPhoto, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                },
                loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) },
            )
            if (release.upcoming) {
                Text(
                    "Upcoming",
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text(release.title, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(release.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        Text(
            listOfNotNull(release.detail, releaseWhen(localDate(release.date), today)).joinToString(" · "),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConcertRow(concert: DiscoverItem, onClick: () -> Unit) {
    val start = Instant.ofEpochMilli(concert.date).atZone(ZoneOffset.UTC)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.width(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                start.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)).uppercase(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                start.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(concert.artist, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            concert.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // The weekday and time, and the event's own name when it isn't just the artist (a festival, a tour).
            val time = if (start.toLocalTime().toSecondOfDay() == 0) null else start.format(DateTimeFormatter.ofPattern("HH:mm"))
            val event = concert.title.takeIf { Names.comparable(it) != Names.comparable(concert.artist) }
            Text(
                listOfNotNull(start.format(DateTimeFormatter.ofPattern(if (start.year == LocalDate.now().year) "EEEE" else "EEEE yyyy", Locale.ENGLISH)), time, event).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewsRow(story: DiscoverItem, artistPhoto: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val placeholder = @Composable {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Newspaper, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The story's picture, else the artist's photo.
        SubcomposeAsyncImage(
            model = story.imageUrl ?: artistPhoto,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            loading = { placeholder() },
            error = {
                if (story.imageUrl != null && artistPhoto != null) {
                    AsyncImage(artistPhoto, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else placeholder()
            },
        )
        Column(Modifier.weight(1f)) {
            Text(story.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(story.detail, relativeTime(story.date), story.artist).joinToString(" · "),
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CountryDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val countries = remember { Locale.getISOCountries().map { it to countryName(it) }.sortedBy { it.second } }
    // Opens at the chosen country, a few rows down.
    val listState = rememberLazyListState((countries.indexOfFirst { it.first == current } - 3).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Concerts in") },
        text = {
            LazyColumn(Modifier.height(420.dp), state = listState) {
                items(countries, key = { it.first }) { (code, name) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = code == current, onClick = null)
                        Text(name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The artists the user follows, to open or unfollow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingScreen(vm: LibraryViewModel, onOpenArtist: (String) -> Unit, onBack: () -> Unit, contentPadding: PaddingValues) {
    val followed by vm.followedArtists.collectAsState()
    val photos by vm.artistImageUrls.collectAsState()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            windowInsets = NoTopInsets,
            title = { Text("Following") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (followed.isEmpty()) EmptyNote("You don't follow any artists yet.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(followed, key = { it }) { name ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenArtist(name) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtistPhoto(photos[name.lowercase()], Modifier.size(48.dp))
                    Text(name, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { vm.unfollow(name) }) { Icon(Icons.Default.Close, "Unfollow $name") }
                }
            }
        }
    }
}

private fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private fun countryName(code: String): String = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)

/** "Out today", "3 days ago", "Out 7 Nov", "12 Sep" (with the year when it isn't this year's). */
private fun releaseWhen(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(date, today)
    val pattern = if (date.year == today.year) "d MMM" else "d MMM yyyy"
    val formatted = date.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    return when {
        days == 0L -> "Out today"
        days < 0 -> "Out $formatted"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        else -> formatted
    }
}

private const val MAX_NEWS = 40
