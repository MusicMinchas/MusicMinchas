package com.kike.musicplayer.data

import android.net.Uri
import android.util.Log
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.delete
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
    private val scope: CoroutineScope,
) {
    val tracks: Flow<List<Track>> = dao.observeAll()
    val pendingMetadata: Flow<Int> = dao.observePendingCount()
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
        if (driveAuth.account.value != null && settings.driveFolders.value.isNotEmpty()) refreshDrive()
    }

    suspend fun refreshFolders() = scan("Scanning folders", Track.SOURCE_SAF) {
        settings.safFolders.value.flatMap { local.scanTree(Uri.parse(it)) }.distinctBy { it.id }
    }

    suspend fun refreshDrive() = scan("Scanning Google Drive", Track.SOURCE_DRIVE) {
        drive.scan(settings.driveFolders.value.map { it.id })
    }

    suspend fun listDriveFolders(parentId: String?): List<DriveFolder> = drive.listFolders(parentId)

    suspend fun resolveDriveFolderId(id: String): String = drive.resolveId(id)

    suspend fun removeDrive() {
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
        }
    }

    /**
     * Replaces the rows of one source with a fresh scan. Rows that still exist keep everything the
     * metadata pipeline already learned; only the file-level fields are refreshed.
     */
    private suspend fun scan(label: String, source: String, block: suspend () -> List<Track>) {
        scanMutex.withLock {
            _status.value = "$label…"
            try {
                withContext(Dispatchers.IO) {
                    val scanned = block()
                    val existing = dao.getBySource(source).associateBy { it.id }
                    val merged = scanned.map { fresh ->
                        val old = existing[fresh.id] ?: return@map fresh
                        old.copy(uri = fresh.uri, fileName = fresh.fileName, mimeType = fresh.mimeType, sizeBytes = fresh.sizeBytes)
                    }
                    dao.upsertAll(merged)
                    dao.delete(existing.keys - scanned.mapTo(HashSet()) { it.id })
                }
                _lastError.value = null
            } catch (e: Exception) {
                Log.w(TAG, "$label failed", e)
                _lastError.value = "$label failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _status.value = null
            }
        }
        enrich()
    }

    private companion object {
        const val TAG = "LibraryRepository"
    }
}
