package com.kike.musicplayer.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.kike.musicplayer.data.db.Track

/**
 * Indexes audio in folders the user picked with the Storage Access Framework. There is no
 * device-wide MediaStore scan: that pulls in ringtones, notification sounds and app audio.
 */
class LocalLibrary(private val context: Context) {

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
    }
}
