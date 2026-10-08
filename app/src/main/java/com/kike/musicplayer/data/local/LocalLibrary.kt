package com.kike.musicplayer.data.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.kike.musicplayer.data.db.Track

/** Indexes audio on the device: the shared MediaStore collection plus folders granted through SAF. */
class LocalLibrary(private val context: Context) {

    fun scanMediaStore(): List<Track> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
        )
        val result = mutableListOf<Track>()
        context.contentResolver.query(
            collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                // MediaStore encodes disc and track together, e.g. 2005 = disc 2, track 5.
                val rawTrack = c.getInt(6)
                result += Track(
                    id = "ms:$id",
                    source = Track.SOURCE_MEDIASTORE,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    fileName = name,
                    mimeType = c.getString(9),
                    sizeBytes = c.getLong(10),
                    title = c.getString(2).cleanTag() ?: name.substringBeforeLast('.'),
                    artist = c.getString(3).cleanTag(),
                    album = c.getString(4).cleanTag(),
                    albumArtist = c.getString(5).cleanTag(),
                    trackNumber = (rawTrack % 1000).takeIf { it > 0 },
                    discNumber = (rawTrack / 1000).takeIf { it > 0 },
                    year = c.getInt(7).takeIf { it > 0 },
                    durationMs = c.getLong(8),
                )
            }
        }
        return result
    }

    /** Walks a folder tree the user granted with ACTION_OPEN_DOCUMENT_TREE. */
    fun scanTree(treeUri: Uri): List<Track> {
        val result = mutableListOf<Track>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri)))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pending.removeFirst())
            try {
                context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending += docId
                        } else if (isAudio(name, mime)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            result += Track(
                                id = "saf:$docUri",
                                source = Track.SOURCE_SAF,
                                uri = docUri.toString(),
                                fileName = name,
                                mimeType = mime,
                                sizeBytes = if (c.isNull(3)) null else c.getLong(3),
                                title = name.substringBeforeLast('.'),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list $children", e)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "LocalLibrary"
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "alac")

        private val PLAYLIST_MIME_TYPES = setOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls")

        fun isAudio(name: String, mime: String?): Boolean =
            mime !in PLAYLIST_MIME_TYPES && (
                mime?.startsWith("audio/") == true ||
                    name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
                )

        /** MediaStore reports missing tags as "<unknown>". */
        fun String?.cleanTag(): String? =
            this?.trim()?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }
    }
}
