package com.kike.musicplayer.data

import android.net.Uri
import android.util.Log
import com.kike.musicplayer.data.db.ReplacedTrack
import com.kike.musicplayer.data.db.ReplacementDao
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.delete
import com.kike.musicplayer.data.download.Downloader
import com.kike.musicplayer.data.drive.DriveAuth
import com.kike.musicplayer.data.drive.DriveFolder
import com.kike.musicplayer.data.drive.DriveLibrary
import com.kike.musicplayer.data.local.LocalLibrary
import com.kike.musicplayer.data.metadata.MetadataEnricher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps the Room track table in sync with every source and kicks off metadata enrichment. */
class LibraryRepository(
    private val dao: TrackDao,
    private val settings: Settings,
    private val local: LocalLibrary,
    private val drive: DriveLibrary,
    private val driveAuth: DriveAuth,
    private val enricher: MetadataEnricher,
    private val replacements: ReplacementDao,
    private val downloader: Downloader,
    private val scope: CoroutineScope,
    private val indexOnly: Boolean = false,
) {
    /** Every song, for the screens that still group the library in memory (none in the bench "indexonly" mode). */
    val tracks: Flow<List<Track>> = if (indexOnly) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.observeAll()
    val pendingMetadata: Flow<Int> = dao.observePendingCount()
    val unplayableCount: Flow<Int> = dao.observeUnplayableCount()
    val metadataProgress = enricher.progress

    private val _status = MutableStateFlow<String?>(null)
    /** Human-readable scan status, null when idle. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val scanMutex = Mutex()
    private var enrichJob: Job? = null

    fun refreshAll() = scope.launch {
        // Earlier versions scanned the whole device through MediaStore; only picked folders count now.
        dao.deleteSource(Track.SOURCE_MEDIASTORE)
        refreshFolders()
        // With no Drive folders chosen yet, leave existing Drive rows alone (they keep their scanned
        // tags); the first folder the user picks prunes everything outside it.
        if (driveAuth.account.value != null && settings.driveFolders.value.isNotEmpty() && driveSyncDue()) refreshDrive()
    }

    /** Whether the start-up sync should list Drive again, per the user's sync setting. */
    private fun driveSyncDue(): Boolean = when (settings.syncMode.value) {
        DriveSyncMode.OFF -> false
        DriveSyncMode.APP_START -> true
        DriveSyncMode.DAILY -> System.currentTimeMillis() - settings.lastDriveSync.value >= DAY_MS
    }

    suspend fun refreshFolders() = scan("Scanning folders", Track.SOURCE_SAF) {
        settings.safFolders.value.flatMap { local.scanTree(Uri.parse(it)) }.distinctBy { it.id }
    }

    suspend fun refreshDrive() {
        val ok = scan("Scanning Google Drive", Track.SOURCE_DRIVE) {
            drive.scan(settings.driveFolders.value.map { it.id })
        }
        if (ok) settings.setLastDriveSync(System.currentTimeMillis())
    }

    suspend fun listDriveFolders(parentId: String?): List<DriveFolder> = drive.listFolders(parentId)

    suspend fun resolveDriveFolderId(id: String): String = drive.resolveId(id)

    /**
     * Disconnects Google Drive and removes its songs from the library. Plays, favourites and playlist
     * entries stay (keyed by Drive file id) and return if the same files are added again; kept
     * downloads are linked again then ([Downloader.relink]).
     */
    suspend fun removeDrive(deleteDownloads: Boolean) {
        if (deleteDownloads) downloader.deleteAll()
        driveAuth.disconnect()
        settings.clearDriveFolders()
        dao.deleteSource(Track.SOURCE_DRIVE)
    }

    fun retryIncompleteMetadata() = scope.launch {
        dao.requeueIncomplete()
        enrich()
    }

    fun enrich() {
        if (enrichJob?.isActive == true) return
        enrichJob = scope.launch {
            try {
                enricher.run()
            } catch (e: Exception) {
                Log.w(TAG, "Metadata enrichment failed", e)
            }
            // The new files' tags are known now: carry replaced songs' history over to them.
            runCatching { pairReplacements() }.onFailure { Log.w(TAG, "Pairing replaced songs failed", it) }
            runCatching { downloader.relink() }
                .onSuccess { if (it > 0) Log.i(TAG, "Linked $it songs to files already downloaded") }
                .onFailure { Log.w(TAG, "Linking downloaded files failed", it) }
        }
    }

    /**
     * Songs a sync removed get paired with the same songs in files it added (see [Replacements]):
     * plays, favourites, scrobble links and the saved queue move to the new file, and a download of
     * the old one is replaced by one of the new. Waits while some added files' tags are unread.
     */
    private suspend fun pairReplacements() {
        val removed = replacements.all()
        if (removed.isEmpty()) return
        val since = removed.minOf { it.removedAt } - SYNC_WINDOW_MS
        if (replacements.unreadSince(since) > 0) return // tags still to come; next pass
        val pairs = Replacements.pair(removed, replacements.addedSince(since))
        for ((old, new) in pairs) {
            Log.i(TAG, "${old.title} (${old.oldId}) is now ${new.fileName}")
            replacements.moveSong(old.oldId, new.id)
            if (old.localUri != null) {
                downloader.deleteFile(old.localUri)
                downloader.download(listOf(new))
            }
        }
        replacements.delete(removed.map { it.oldId })
    }

    /**
     * Replaces the rows of one source with a fresh scan. Rows that still exist keep everything the
     * metadata pipeline already learned; only the file-level fields are refreshed. A file whose
     * content changed (Drive checksum, else size) is read again, and downloaded again if it was.
     */
    private suspend fun scan(label: String, source: String, block: suspend () -> List<Track>): Boolean {
        var ok = false
        scanMutex.withLock {
            _status.value = "$label…"
            try {
                withContext(Dispatchers.IO) {
                    val scanned = block()
                    val existing = dao.getBySource(source).associateBy { it.id }
                    var redownload = false
                    val merged = scanned.map { fresh ->
                        val old = existing[fresh.id] ?: return@map fresh
                        // Changed content (tags, cover, a new version): read it again. The first sync
                        // with checksums only records them; sizes decide until then.
                        val changed = if (old.checksum != null && fresh.checksum != null) old.checksum != fresh.checksum
                        else old.sizeBytes != null && fresh.sizeBytes != null && old.sizeBytes != fresh.sizeBytes
                        if (changed) {
                            val onPhone = old.downloadState == Track.DOWNLOADED || old.downloadState == Track.DOWNLOAD_QUEUED
                            if (onPhone) redownload = true
                            // The old copy keeps playing until the new one is down; the downloader replaces it.
                            return@map fresh.copy(localUri = old.localUri, downloadState = if (onPhone) Track.DOWNLOAD_QUEUED else old.downloadState)
                        }
                        old.copy(uri = fresh.uri, fileName = fresh.fileName, mimeType = fresh.mimeType, sizeBytes = fresh.sizeBytes, checksum = fresh.checksum)
                    }
                    val removedIds = existing.keys - scanned.mapTo(HashSet()) { it.id }
                    // Songs gone while others arrived may be the same songs in new files: remember them
                    // until the new files' tags are read (pairReplacements).
                    if (removedIds.isNotEmpty() && scanned.any { it.id !in existing }) {
                        val now = System.currentTimeMillis()
                        replacements.insertAll(removedIds.mapNotNull { existing[it] }.map { t ->
                            ReplacedTrack(
                                oldId = t.id, source = t.source, artist = t.artist, albumArtist = t.albumArtist, album = t.album,
                                title = t.title, trackNumber = t.trackNumber, discNumber = t.discNumber,
                                localUri = t.localUri.takeIf { t.downloadState == Track.DOWNLOADED }, removedAt = now,
                            )
                        })
                    }
                    dao.upsertAll(merged)
                    dao.delete(removedIds)
                    if (redownload) downloader.resume()
                }
                _lastError.value = null
                ok = true
            } catch (e: Exception) {
                Log.w(TAG, "$label failed", e)
                _lastError.value = "$label failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _status.value = null
            }
        }
        enrich()
        return ok
    }

    private companion object {
        /** New files of a sync are dated just before its removals are noted. */
        const val SYNC_WINDOW_MS = 30 * 60_000L
        const val TAG = "LibraryRepository"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
