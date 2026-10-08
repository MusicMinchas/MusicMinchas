package com.kike.musicplayer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.db.TrackDao
import com.kike.musicplayer.data.db.delete
import com.kike.musicplayer.data.drive.DriveAuth
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
    private val context: Context,
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

    fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshAll() = scope.launch {
        refreshDevice()
        refreshFolders()
        if (driveAuth.account.value != null) refreshDrive()
    }

    suspend fun refreshDevice() = scan("Scanning device", Track.SOURCE_MEDIASTORE) {
        if (hasAudioPermission()) local.scanMediaStore() else emptyList()
    }

    suspend fun refreshFolders() = scan("Scanning folders", Track.SOURCE_SAF) {
        // Skip files the MediaStore scan already found (same name and size) to avoid duplicates.
        val known = dao.getBySource(Track.SOURCE_MEDIASTORE).mapTo(HashSet()) { it.fileName to it.sizeBytes }
        settings.safFolders.value.flatMap { local.scanTree(Uri.parse(it)) }
            .filter { (it.fileName to it.sizeBytes) !in known }
            .distinctBy { it.id }
    }

    suspend fun refreshDrive() = scan("Scanning Google Drive", Track.SOURCE_DRIVE) { drive.scan() }

    suspend fun removeDrive() {
        driveAuth.disconnect()
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
                        if (source == Track.SOURCE_MEDIASTORE && old.metadataState == Track.META_NEW) fresh
                        else old.copy(uri = fresh.uri, fileName = fresh.fileName, mimeType = fresh.mimeType, sizeBytes = fresh.sizeBytes)
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
