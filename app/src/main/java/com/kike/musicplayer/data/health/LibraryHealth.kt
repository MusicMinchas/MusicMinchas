package com.kike.musicplayer.data.health

import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.db.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HealthCheckType(val displayName: String, val description: String) {
    MISSING_COVER("Missing Cover Art", "Songs with no album artwork"),
    MISSING_LYRICS("Missing Lyrics", "Songs without cached lyrics"),
    MISSING_GENRE("Missing Genre", "Songs with no genre tag"),
    MISSING_YEAR("Missing Year", "Songs with no release year"),
    MISSING_TRACK_NUMBER("Missing Track Number", "Songs missing track/disc number"),
    MISSING_ARTIST("Missing Artist", "Songs missing artist or album artist"),
    MISSING_ALBUM("Missing Album", "Songs missing album name"),
    LOW_QUALITY("Low Audio Quality", "Lossy tracks with bitrate below 256 kbps"),
    CORRUPTED_OR_DRM("Unplayable / DRM", "Files with zero or negative duration"),
    DUPLICATE("Duplicates", "Multiple files with same title and artist"),
}

data class HealthInspectionCriteria(
    val selectedScope: Int? = null, // null = All Sources
    val enabledChecks: Set<HealthCheckType> = HealthCheckType.values().toSet(),
)

data class TrackHealthIssue(
    val track: Track,
    val issues: Set<HealthCheckType>,
)

data class HealthInspectionReport(
    val totalScanned: Int,
    val issuesByTrack: List<TrackHealthIssue>,
    val issueCounts: Map<HealthCheckType, Int>,
    val timestamp: Long = System.currentTimeMillis(),
)

object LibraryHealthInspector {
    suspend fun inspect(
        db: AppDatabase,
        criteria: HealthInspectionCriteria,
    ): HealthInspectionReport = withContext(Dispatchers.IO) {
        val scopes = if (criteria.selectedScope != null) listOf(criteria.selectedScope) else Track.SCOPES
        val allTracks = db.tracks().getAllForInspection(scopes)

        val tracksWithLyrics = if (HealthCheckType.MISSING_LYRICS in criteria.enabledChecks) {
            db.lyrics().allTrackIds().toSet()
        } else emptySet()

        val enabled = criteria.enabledChecks
        val issuesList = mutableListOf<TrackHealthIssue>()
        val issueCounts = mutableMapOf<HealthCheckType, Int>()

        // Precompute duplicate detection: normalized title + artist
        val duplicatesGroup = if (HealthCheckType.DUPLICATE in enabled) {
            allTracks.filter { it.durationMs >= 0 }
                .groupBy { "${it.title.lowercase().trim()}|${(it.artist ?: it.albumArtist ?: "").lowercase().trim()}" }
                .filter { it.value.size > 1 }
                .values.flatten().map { it.id }.toSet()
        } else emptySet()

        for (track in allTracks) {
            val trackIssues = mutableSetOf<HealthCheckType>()

            if (HealthCheckType.CORRUPTED_OR_DRM in enabled && track.durationMs <= 0) {
                trackIssues += HealthCheckType.CORRUPTED_OR_DRM
            }
            if (HealthCheckType.MISSING_COVER in enabled && track.artworkUri.isNullOrBlank()) {
                trackIssues += HealthCheckType.MISSING_COVER
            }
            if (HealthCheckType.MISSING_LYRICS in enabled && track.id !in tracksWithLyrics && track.durationMs > 0) {
                trackIssues += HealthCheckType.MISSING_LYRICS
            }
            if (HealthCheckType.MISSING_GENRE in enabled && track.genre.isNullOrBlank() && track.indexGenre.isBlank()) {
                trackIssues += HealthCheckType.MISSING_GENRE
            }
            if (HealthCheckType.MISSING_YEAR in enabled && (track.year == null || track.year <= 0)) {
                trackIssues += HealthCheckType.MISSING_YEAR
            }
            if (HealthCheckType.MISSING_TRACK_NUMBER in enabled && (track.trackNumber == null || track.trackNumber <= 0)) {
                trackIssues += HealthCheckType.MISSING_TRACK_NUMBER
            }
            if (HealthCheckType.MISSING_ARTIST in enabled && track.artist.isNullOrBlank() && track.albumArtist.isNullOrBlank()) {
                trackIssues += HealthCheckType.MISSING_ARTIST
            }
            if (HealthCheckType.MISSING_ALBUM in enabled && track.album.isNullOrBlank()) {
                trackIssues += HealthCheckType.MISSING_ALBUM
            }
            if (HealthCheckType.LOW_QUALITY in enabled && track.durationMs > 0) {
                val isLossless = track.fileName.endsWith(".flac", ignoreCase = true) ||
                    track.fileName.endsWith(".alac", ignoreCase = true) ||
                    track.fileName.endsWith(".wav", ignoreCase = true) ||
                    (track.mimeType?.contains("flac", ignoreCase = true) == true) ||
                    (track.mimeType?.contains("alac", ignoreCase = true) == true)
                if (!isLossless) {
                    val bitrate = if (track.sizeBytes != null && track.durationMs > 0) {
                        (track.sizeBytes * 8L / track.durationMs).toInt()
                    } else null
                    if (bitrate != null && bitrate < 256) {
                        trackIssues += HealthCheckType.LOW_QUALITY
                    }
                }
            }
            if (HealthCheckType.DUPLICATE in enabled && track.id in duplicatesGroup) {
                trackIssues += HealthCheckType.DUPLICATE
            }

            if (trackIssues.isNotEmpty()) {
                issuesList += TrackHealthIssue(track, trackIssues)
                for (issue in trackIssues) {
                    issueCounts[issue] = (issueCounts[issue] ?: 0) + 1
                }
            }
        }

        HealthInspectionReport(
            totalScanned = allTracks.size,
            issuesByTrack = issuesList,
            issueCounts = issueCounts,
        )
    }
}
