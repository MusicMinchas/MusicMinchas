package com.kike.musicplayer.data.metadata

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.inspector.MetadataRetriever
import com.kike.musicplayer.data.Settings
import com.kike.musicplayer.data.db.ArtistInfo
import com.kike.musicplayer.data.db.ArtistInfoDao
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.drive.DriveAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Two-tier metadata pipeline:
 *  1. Local: embedded ID3 / Vorbis / MP4 tags and artwork via MediaMetadataRetriever, several
 *     files in parallel. Drive files are parsed by ExoPlayer's extractors, which only read the header.
 *  2. Online fallback for tracks still missing artist, album or artwork: MusicBrainz search for
 *     the MBIDs, then the Cover Art Archive for the front cover. Albums are looked up once.
 * Results are written to Room in batches, so the library list isn't rebuilt for every track.
 */
/** The container parsed but holds no playable audio track (typically FairPlay DRM, "drms"). */
class NoAudioTrackException : IOException()

@OptIn(UnstableApi::class)
class MetadataEnricher(
    private val context: Context,
    private val dao: TrackDao,
    private val settings: Settings,
    private val driveAuth: DriveAuth,
    private val driveHttp: OkHttpClient,
    private val artistDao: ArtistInfoDao,
    private val musicBrainz: MusicBrainzApi,
    private val coverArt: CoverArtApi,
) {
    data class Progress(val stage: String, val done: Int, val total: Int)

    private data class AlbumMatch(val releaseId: String, val artworkUrl: String?, val year: Int?)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val remoteDataSource = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(driveHttp))

    /**
     * ExoPlayer buffers ahead while it parses (~2 MB per file), but tags and cover art live in the
     * header (~200 KB for iTunes files). Capping each request avoids downloading that extra; a file
     * whose header doesn't fit is retried uncapped.
     */
    private val cappedSourceFactory = DefaultMediaSourceFactory(
        CappedDataSource.Factory(remoteDataSource, HEADER_CAP_BYTES)
    )
    private val uncappedSourceFactory = DefaultMediaSourceFactory(remoteDataSource)

    private val mutex = Mutex()

    init {
        MetadataRetriever.setMaximumParallelRetrievals(PARALLEL_READS)
    }

    private val artworkDir = File(context.filesDir, "artwork").apply { mkdirs() }
    private var lastMbCall = 0L

    /** Processes everything pending. Concurrent calls wait for the running pass and then re-check. */
    suspend fun run() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                // Files whose read failed in earlier versions were marked done with no tags (length 0).
                dao.requeueUnread()
                localPass()
                genrePass()
                if (settings.onlineMetadata.value) {
                    onlinePass()
                    artistPass()
                }
            } finally {
                _progress.value = null
            }
        }
    }

    private suspend fun localPass() = coroutineScope {
        val ids = dao.idsWithState(Track.META_NEW)
        if (ids.isEmpty()) return@coroutineScope
        Log.i(TAG, "Reading tags of ${ids.size} files")
        val driveConnected = driveAuth.account.value != null
        val queue = Channel<String>(Channel.UNLIMITED).apply { ids.forEach { trySend(it) }; close() }
        // Null = the file couldn't be read (timeout, dropped connection...): it stays NEW and is
        // retried on the next run instead of being marked done with empty tags.
        val results = Channel<Track?>(capacity = 64)

        val workers = List(PARALLEL_READS) {
            launch {
                for (id in queue) {
                    val track = dao.get(id) ?: continue
                    if (track.source == Track.SOURCE_DRIVE && !driveConnected) continue
                    val updated = try {
                        extractLocal(track)
                    } catch (e: NoAudioTrackException) {
                        // Not a transient failure: mark it so it is hidden and never retried.
                        Log.w(TAG, "No playable audio in ${track.fileName}")
                        track.copy(durationMs = Track.DURATION_UNPLAYABLE)
                    } catch (e: Exception) {
                        Log.w(TAG, "Tag extraction failed for ${track.fileName}: $e")
                        null
                    }
                    val done = updated != null && (updated.isComplete() || updated.durationMs == Track.DURATION_UNPLAYABLE)
                    results.send(updated?.copy(metadataState = if (done) Track.META_DONE else Track.META_LOCAL_DONE))
                }
            }
        }
        launch {
            workers.joinAll()
            results.close()
        }

        val writer = BatchWriter(maxSize = 50)
        var done = 0
        for (track in results) {
            _progress.value = Progress("Reading tags", ++done, ids.size)
            if (track != null) writer.add(track)
        }
        writer.flush()
    }

    /**
     * Reads just the genre tag of tracks scanned before genres were stored. Same parallel reading
     * as [localPass]; results are written in batches so the library isn't rebuilt per track.
     */
    private suspend fun genrePass() = coroutineScope {
        val ids = dao.idsMissingGenre()
        if (ids.isEmpty()) return@coroutineScope
        Log.i(TAG, "Reading genres of ${ids.size} files")
        val driveConnected = driveAuth.account.value != null
        val queue = Channel<String>(Channel.UNLIMITED).apply { ids.forEach { trySend(it) }; close() }
        val results = Channel<Pair<String, String?>?>(capacity = 64)
        val workers = List(PARALLEL_READS) {
            launch {
                for (id in queue) {
                    val track = dao.get(id) ?: continue
                    if (track.source == Track.SOURCE_DRIVE && !driveConnected) continue
                    // A failed read stays pending (genreRead = 0) and is retried on the next run.
                    val genre = runCatching { readGenre(track) }
                        .onFailure { Log.w(TAG, "Genre read failed for ${track.fileName}: $it") }
                    results.send(genre.getOrNull()?.let { id to it.value })
                }
            }
        }
        launch {
            workers.joinAll()
            results.close()
        }
        val batch = ArrayList<Pair<String, String?>>()
        var done = 0
        for (result in results) {
            _progress.value = Progress("Reading genres", ++done, ids.size)
            if (result != null) batch += result
            if (batch.size >= GENRE_BATCH) {
                dao.setGenres(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) dao.setGenres(batch)
    }

    /** Wraps the genre so "read fine, no genre tag" (value = null) differs from a failed read. */
    private class GenreTag(val value: String?)

    private suspend fun readGenre(track: Track): GenreTag =
        if (track.source == Track.SOURCE_DRIVE) {
            GenreTag(readRemoteTags(track).genre)
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, Uri.parse(track.uri))
                GenreTag(normalizeGenre(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)))
            } finally {
                mmr.release()
            }
        }

    private suspend fun onlinePass() {
        val ids = dao.idsWithState(Track.META_LOCAL_DONE)
        val albums = HashMap<String, AlbumMatch?>()
        val writer = BatchWriter(maxSize = 20)
        try {
            ids.forEachIndexed { i, id ->
                if (!settings.onlineMetadata.value) return
                _progress.value = Progress("Looking up MusicBrainz", i, ids.size)
                val track = dao.get(id) ?: return@forEachIndexed
                val updated = try {
                    lookupOnline(track, albums)
                } catch (e: IOException) {
                    // Offline or MusicBrainz unreachable: keep the rest pending and stop for now.
                    Log.w(TAG, "Online lookup stopped: $e")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Online lookup failed for ${track.fileName}", e)
                    track
                }
                writer.add(updated.copy(metadataState = Track.META_DONE))
            }
        } finally {
            writer.flush()
        }
    }

    /**
     * Genre tags per artist for the recommender: one MusicBrainz artist search each, results kept
     * (also when nothing is found), so only new artists are looked up later.
     */
    private suspend fun artistPass() {
        val known = artistDao.names().toHashSet()
        val names = dao.artistNames().map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filter { it.lowercase(Locale.ROOT) !in known && !it.equals("Various Artists", ignoreCase = true) && !it.startsWith("Bench Artist ") }
        names.forEachIndexed { i, name ->
            if (!settings.onlineMetadata.value) return
            _progress.value = Progress("Fetching genres", i, names.size)
            val artist = try {
                mbCall { musicBrainz.searchArtists("artist:${quote(name)}").artists }
                    .firstOrNull { (it.score ?: 0) >= MIN_SCORE && similar(it.name, name) }
            } catch (e: IOException) {
                Log.w(TAG, "Genre lookup stopped: $e")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Genre lookup failed for $name", e)
                null
            }
            val tags = artist?.tags.orEmpty()
                .filter { it.count > 0 }
                .sortedByDescending { it.count }
                .map { it.name.lowercase(Locale.ROOT).trim() }
                .filter { it.length > 2 && it !in NON_GENRE_TAGS }
                .distinct()
                .take(8)
            artistDao.upsert(ArtistInfo(name.lowercase(Locale.ROOT), artist?.id, tags.joinToString(","), System.currentTimeMillis()))
        }
    }

    private fun Track.isComplete() = artist != null && album != null && artworkUri != null

    /** Collects updated tracks and writes them together, at most every couple of seconds. */
    private inner class BatchWriter(private val maxSize: Int) {
        private val batch = ArrayList<Track>()
        private var lastFlush = System.currentTimeMillis()

        suspend fun add(track: Track) {
            batch += track
            if (batch.size >= maxSize || System.currentTimeMillis() - lastFlush > FLUSH_INTERVAL_MS) flush()
        }

        suspend fun flush() {
            if (batch.isNotEmpty()) {
                dao.upsertAll(batch.toList())
                batch.clear()
            }
            lastFlush = System.currentTimeMillis()
        }
    }

    // ---- Tier 1: tags embedded in the file ----

    /** Tags read from a file. [picture] is only called when the album has no stored artwork yet. */
    private class Tags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val trackNumber: Int?,
        val discNumber: Int?,
        val year: Int?,
        val durationMs: Long?,
        val genre: String?,
        val picture: () -> ByteArray?,
    )

    private suspend fun extractLocal(track: Track): Track =
        if (track.source == Track.SOURCE_DRIVE) {
            mergeTags(track, readRemoteTags(track))
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, Uri.parse(track.uri))
                mergeTags(track, readRetrieverTags(mmr))
            } finally {
                mmr.release()
            }
        }

    /** Device files: the platform retriever is fast on local storage. */
    private fun readRetrieverTags(mmr: MediaMetadataRetriever): Tags {
        fun tag(key: Int) = mmr.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
        return Tags(
            title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            trackNumber = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.leadingInt(),
            discNumber = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.leadingInt(),
            year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR)?.leadingInt()
                ?: tag(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.toIntOrNull(),
            durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            genre = normalizeGenre(tag(MediaMetadataRetriever.METADATA_KEY_GENRE)),
            picture = { mmr.embeddedPicture },
        )
    }

    /**
     * Drive files: ExoPlayer's own extractors (ID3, Vorbis comments, MP4 ilst) over OkHttp. They
     * stop once the header is parsed, whereas MediaMetadataRetriever keeps reading the whole file.
     */
    private suspend fun readRemoteTags(track: Track): Tags {
        // MP3s without a Xing/VBRI header only get a duration from the total file size, which the
        // cap hides. Uncapped, the read still stops once the header is parsed (~1-2 MB).
        val capped = if (track.mimeType == MimeTypes.AUDIO_MPEG) null
        else runCatching { retrieve(track, cappedSourceFactory) }.getOrNull()
        return capped ?: retrieve(track, uncappedSourceFactory)
            ?: throw NoAudioTrackException()
    }

    /** Null when the extractor found no tracks (e.g. the capped read ended before the header did). */
    private suspend fun retrieve(track: Track, factory: DefaultMediaSourceFactory): Tags? {
        MetadataRetriever.Builder(context, MediaItem.fromUri(track.uri))
            .setMediaSourceFactory(factory)
            .build()
            .use { r ->
                val groups = r.retrieveTrackGroups().await()
                if (groups.length == 0) return null
                val durationUs = runCatching { r.retrieveDurationUs().await() }.getOrNull()
                val builder = MediaMetadata.Builder()
                val frames = mutableListOf<TextInformationFrame>()
                for (g in 0 until groups.length) {
                    val group = groups[g]
                    for (i in 0 until group.length) group.getFormat(i).metadata?.let { metadata ->
                        builder.populateFromMetadata(metadata)
                        for (e in 0 until metadata.length()) (metadata[e] as? TextInformationFrame)?.let(frames::add)
                    }
                }
                val md = builder.build()
                // ID3v2.2 frames Media3 doesn't map; iTunes writes v2.2 tags in the MP3s it encodes.
                fun v22(id: String) = frames.firstOrNull { it.id == id }?.values?.firstOrNull()
                fun CharSequence?.clean() = this?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                return Tags(
                    title = md.title.clean(),
                    artist = md.artist.clean(),
                    album = md.albumTitle.clean(),
                    albumArtist = md.albumArtist.clean(),
                    trackNumber = md.trackNumber,
                    discNumber = md.discNumber ?: v22("TPA")?.substringBefore('/')?.trim()?.toIntOrNull(),
                    year = md.recordingYear ?: md.releaseYear,
                    durationMs = durationUs?.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000 },
                    genre = normalizeGenre(md.genre?.toString() ?: v22("TCO")),
                    picture = { md.artworkData },
                )
            }
    }

    private fun mergeTags(track: Track, tags: Tags): Track {
        val artist = tags.artist ?: track.artist
        val album = tags.album ?: track.album
        val albumArtist = tags.albumArtist ?: track.albumArtist
        var artwork = track.artworkUri
        if (artwork == null) {
            // One file per album when we know the album, so a 20-track album stores one image.
            val key = if (album != null) Track.albumKeyOf(album, albumArtist ?: artist) else track.id
            val file = File(artworkDir, sha1(key) + ".img")
            if (file.exists()) {
                artwork = Uri.fromFile(file).toString()
            } else {
                tags.picture()?.let { bytes ->
                    // Parallel workers may extract the same album; write atomically.
                    val tmp = File.createTempFile("art", ".tmp", artworkDir)
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(file)) tmp.delete()
                    artwork = Uri.fromFile(file).toString()
                }
            }
        }
        return track.copy(
            title = tags.title ?: track.title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = tags.trackNumber ?: track.trackNumber,
            discNumber = tags.discNumber ?: track.discNumber,
            year = tags.year ?: track.year,
            durationMs = tags.durationMs ?: track.durationMs,
            genre = tags.genre ?: track.genre,
            genreRead = true,
            artworkUri = artwork,
        )
    }

    // ---- Tier 2: MusicBrainz + Cover Art Archive ----

    private suspend fun lookupOnline(track: Track, albums: MutableMap<String, AlbumMatch?>): Track {
        val albumArtist = track.albumArtist ?: track.artist
        if (track.artist != null && track.album != null && albumArtist != null) {
            // Tags are fine, only the cover is missing: one release lookup per album.
            val key = track.albumKey
            val match = if (albums.containsKey(key)) albums[key] else findAlbum(track.album, albumArtist).also { albums[key] = it }
            return if (match == null) track else track.copy(
                artworkUri = match.artworkUrl,
                mbReleaseId = match.releaseId,
                year = track.year ?: match.year,
            )
        }
        return lookupRecording(track)
    }

    private suspend fun findAlbum(album: String, artist: String): AlbumMatch? {
        val releases = mbCall {
            musicBrainz.searchReleases("release:${quote(album)} AND artist:${quote(artist)}").releases
        }.filter { (it.score ?: 0) >= MIN_SCORE }
        // The best-scored release may have no cover in the archive; try a few.
        for (release in releases.take(3)) {
            findCover(release)?.let { return AlbumMatch(release.id, it, release.year()) }
        }
        return releases.firstOrNull()?.let { AlbumMatch(it.id, null, it.year()) }
    }

    private suspend fun lookupRecording(track: Track): Track {
        // Untagged files are often named "Artist - Title"; use that when tags are missing.
        val fromName = track.fileName.substringBeforeLast('.').replace(Regex("^\\d+[ .\\-_]+"), "")
            .split(" - ", limit = 2).takeIf { it.size == 2 }
        val artist = track.artist ?: track.albumArtist ?: fromName?.get(0)?.trim()
        val title = if (track.artist == null && fromName != null) fromName[1].trim() else track.title
        if (artist.isNullOrBlank() || title.isBlank()) return track

        val query = buildString {
            append("recording:").append(quote(title)).append(" AND artist:").append(quote(artist))
            track.album?.let { append(" AND release:").append(quote(it)) }
        }
        var recordings = mbCall { musicBrainz.searchRecordings(query).recordings }
        if (recordings.isEmpty() && track.album != null) {
            // The album name in the tags may not match MusicBrainz; retry without it.
            recordings = mbCall {
                musicBrainz.searchRecordings("recording:${quote(title)} AND artist:${quote(artist)}").recordings
            }
        }
        var recording = bestRecording(recordings.filter { (it.score ?: 0) >= MIN_SCORE })
        if (recording == null) {
            // Misspelled names ("Dark Tranquility") match nothing exactly; try fuzzy terms, but only
            // accept a result spelled almost the same, as fuzzy search also finds unrelated songs.
            recording = bestRecording(mbCall {
                musicBrainz.searchRecordings("recording:(${fuzzy(title)}) AND artist:(${fuzzy(artist)})").recordings
            }.filter {
                (it.score ?: 0) >= MIN_SCORE && similar(it.title, title) && similar(it.artistName.orEmpty(), artist)
            })
        }
        if (recording == null) return track
        val release = pickRelease(recording, track.album)

        val artwork = track.artworkUri ?: release?.let { findCover(it) }
        return track.copy(
            title = if (track.artist == null) recording.title else track.title,
            artist = track.artist ?: recording.artistName,
            album = track.album ?: release?.title,
            albumArtist = track.albumArtist ?: release?.artistCredit?.joinToString("") { it.name + (it.joinphrase ?: "") },
            year = track.year ?: release?.year(),
            durationMs = if (track.durationMs > 0) track.durationMs else recording.length ?: 0,
            artworkUri = artwork,
            mbRecordingId = recording.id,
            mbReleaseId = release?.id,
        )
    }

    /** Among equally good matches, prefer a real album track over e.g. the music-video recording. */
    private fun bestRecording(candidates: List<MbRecording>): MbRecording? =
        candidates.firstOrNull { it.video != true && !it.releases.isNullOrEmpty() } ?: candidates.firstOrNull()

    /**
     * The album the track most likely comes from: the one named in the tags, else the earliest
     * official studio album (not a compilation or live album), else any official release.
     */
    private fun pickRelease(recording: MbRecording, knownAlbum: String?): MbRelease? {
        val releases = recording.releases.orEmpty()
        val byDate = compareBy<MbRelease> { it.date.isNullOrBlank() }.thenBy { it.date }
        return releases.firstOrNull { knownAlbum != null && it.title.equals(knownAlbum, ignoreCase = true) }
            ?: releases.filter {
                it.status == "Official" && it.releaseGroup?.primaryType == "Album" && it.releaseGroup.secondaryTypes.isNullOrEmpty()
            }.minWithOrNull(byDate)
            ?: releases.filter { it.status == "Official" }.minWithOrNull(byDate)
            ?: releases.firstOrNull()
    }

    private suspend fun findCover(release: MbRelease): String? {
        suspend fun front(fetch: suspend () -> CaaListing): String? = try {
            fetch().images.let { images -> images.firstOrNull { it.front } ?: images.firstOrNull() }?.bestUrl
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        }
        return front { coverArt.release(release.id) }
            ?: release.releaseGroup?.let { rg -> front { coverArt.releaseGroup(rg.id) } }
    }

    private fun MbRelease.year() = date?.take(4)?.toIntOrNull()

    /** MusicBrainz allows one request per second per client. */
    private suspend fun <T> mbCall(block: suspend () -> T): T {
        val wait = lastMbCall + MB_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            return block()
        } catch (e: HttpException) {
            if (e.code() != 503) throw e
            delay(5_000) // rate limited: back off and try once more
            return block()
        } finally {
            lastMbCall = System.currentTimeMillis()
        }
    }

    companion object {
        private const val TAG = "MetadataEnricher"
        private const val PARALLEL_READS = 12
        private const val HEADER_CAP_BYTES = 1024 * 1024L
        private const val FLUSH_INTERVAL_MS = 2_000L
        private const val MIN_SCORE = 90
        private const val GENRE_BATCH = 100

        /**
         * ID3v1 genre numbers, still found in old MP3s as "(17)" or "17": the 80 standard ones, then
         * Winamp's extensions (80-191), which iTunes writes too (e.g. "(91)" is Gothic Rock).
         */
        private val ID3_GENRES = listOf(
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
            "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop",
            "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
            "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
            "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial",
            "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40",
            "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave",
            "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical",
            "Rock & Roll", "Hard Rock",
            "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic",
            "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock",
            "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson",
            "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire",
            "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul",
            "Freestyle", "Duet", "Punk Rock", "Drum Solo", "A cappella", "Euro-House", "Dance Hall", "Goa",
            "Drum & Bass", "Club-House", "Hardcore Techno", "Terror", "Indie", "BritPop", "Afro-Punk",
            "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
            "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "JPop",
            "Synthpop", "Abstract", "Art Rock", "Baroque", "Bhangra", "Big Beat", "Breakbeat", "Chillout",
            "Downtempo", "Dub", "EBM", "Eclectic", "Electro", "Electroclash", "Emo", "Experimental", "Garage",
            "Global", "IDM", "Illbient", "Industro-Goth", "Jam Band", "Krautrock", "Leftfield", "Lounge",
            "Math Rock", "New Romantic", "Nu-Breakz", "Post-Punk", "Post-Rock", "Psytrance", "Shoegaze",
            "Space Rock", "Trop Rock", "World Music", "Neoclassical", "Audiobook", "Audio Theatre",
            "Neue Deutsche Welle", "Podcast", "Indie Rock", "G-Funk", "Dubstep", "Garage Rock", "Psybient",
        )

        /** First genre of a tag value, with ID3v1 numbers ("(17)", "17") turned into names. */
        fun normalizeGenre(raw: String?): String? {
            val first = raw?.split(';', '\u0000')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
            // "(17)", "(17)Rock" or a bare "17" (but not e.g. "80s Pop").
            Regex("^\\((\\d+)\\)(.*)$|^(\\d+)$").matchEntire(first)?.let { m ->
                val rest = m.groupValues[2].trim()
                if (rest.isNotEmpty()) return rest.replace(Regex("\\s+"), " ")
                val number = m.groupValues[1].ifEmpty { m.groupValues[3] }.toIntOrNull()
                return number?.let { ID3_GENRES.getOrNull(it) }
            }
            return first.replace(Regex("\\s+"), " ")
        }

        /** Community tags that describe places or opinions rather than the music. */
        private val NON_GENRE_TAGS = setOf(
            "seen live", "favorites", "favourites", "favorite", "awesome", "love", "beautiful", "male vocalists",
            "female vocalists", "vocalists", "composer", "producer", "rapper", "dj", "band", "group",
            "american", "usa", "united states", "british", "uk", "english", "england", "scottish", "welsh", "irish",
            "canadian", "australian", "swedish", "sweden", "norwegian", "norway", "finnish", "finland", "danish",
            "denmark", "icelandic", "german", "germany", "dutch", "netherlands", "belgian", "french", "france",
            "italian", "italy", "spanish", "spain", "español", "portuguese", "brazilian", "brazil", "mexican",
            "argentinian", "japanese", "japan", "korean", "chinese", "russian", "polish", "greek", "austrian",
            "swiss", "gothenburg", "stockholm", "london", "los angeles", "new york", "california", "seattle",
            "1960s", "1970s", "1980s", "1990s", "2000s", "2010s", "2020s", "60s", "70s", "80s", "90s", "00s",
        )
        private const val MB_INTERVAL_MS = 1_100L

        /** Lucene phrase with special characters escaped. */
        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** Each word as a Lucene fuzzy term ("dark~ tranquility~"), special characters dropped. */
        private fun fuzzy(s: String) =
            s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ") { "$it~" }

        /** True when the two names differ by at most ~15% of their letters (case/punctuation ignored). */
        private fun similar(a: String, b: String): Boolean {
            val x = a.lowercase().filter { it.isLetterOrDigit() }
            val y = b.lowercase().filter { it.isLetterOrDigit() }
            if (x.isEmpty() || y.isEmpty()) return false
            return editDistance(x, y) <= maxOf(1, (maxOf(x.length, y.length) * 0.15).toInt())
        }

        private fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }

        private fun String.leadingInt() = Regex("^\\d+").find(this)?.value?.toIntOrNull()

        private fun sha1(s: String) =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
