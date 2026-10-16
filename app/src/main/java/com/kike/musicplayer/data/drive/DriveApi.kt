package com.kike.musicplayer.data.drive

import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.local.LocalLibrary
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface DriveApi {
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Query("q") query: String,
        @Query("fields") fields: String = "nextPageToken,files(id,name,mimeType,size,parents)",
        @Query("pageSize") pageSize: Int = 1000,
        @Query("pageToken") pageToken: String? = null,
        @Query("orderBy") orderBy: String? = null,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true,
        @Query("includeItemsFromAllDrives") includeAllDrives: Boolean = true,
    ): DriveFileList

    @GET("drive/v3/files/{id}")
    suspend fun getFile(
        @Path("id") id: String,
        @Query("fields") fields: String = "id,name",
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true,
    ): DriveFile

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"
        const val HOST = "www.googleapis.com"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"

        /** Direct media URL. Drive honours Range requests, so ExoPlayer streams and seeks it in chunks. */
        fun mediaUrl(fileId: String) = "${BASE_URL}drive/v3/files/$fileId?alt=media&supportsAllDrives=true"
    }
}

data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

data class DriveFile(
    val id: String,
    /** Absent when the request's `fields` don't ask for it (e.g. the folder-ancestry listing). */
    val name: String = "",
    val mimeType: String? = null,
    /** Computed by Drive for uploaded files; changes whenever the content does. */
    val md5Checksum: String? = null,
    /** Drive sends int64 values as JSON strings. */
    val size: String? = null,
    val parents: List<String>? = null,
)

/** A Drive folder the user can pick as a music source. */
data class DriveFolder(val id: String, val name: String)

class DriveLibrary(private val api: DriveApi) {

    /** Sub-folders of [parentId] ("root" for My Drive), or folders shared with the user when null. */
    suspend fun listFolders(parentId: String?): List<DriveFolder> {
        val scope = if (parentId == null) "sharedWithMe" else "'${escape(parentId)}' in parents"
        return listAll("$scope and mimeType = '${DriveApi.FOLDER_MIME}' and trashed = false", "nextPageToken,files(id,name)", orderBy = "name")
            .map { DriveFolder(it.id, it.name) }
    }

    /** Resolves the "root" alias to My Drive's real folder ID, which is what files list as their parent. */
    suspend fun resolveId(id: String): String = api.getFile(id).id

    /**
     * Audio files anywhere below [folderIds]. Rather than walking the tree folder by folder (one
     * request each), it lists all folders and all audio files once and filters by ancestry locally.
     */
    suspend fun scan(folderIds: Collection<String>): List<Track> {
        if (folderIds.isEmpty()) return emptyList()
        val selected = folderIds.toSet()
        val parentsOf = listAll("mimeType = '${DriveApi.FOLDER_MIME}' and trashed = false", "nextPageToken,files(id,parents)")
            .associate { it.id to it.parents.orEmpty() }
        val memo = HashMap<String, Boolean>()

        fun isInside(folderId: String, depth: Int = 0): Boolean {
            if (folderId in selected) return true
            if (depth > MAX_DEPTH) return false // guards against cycles in odd shared structures
            memo[folderId]?.let { return it }
            val inside = parentsOf[folderId].orEmpty().any { isInside(it, depth + 1) }
            memo[folderId] = inside
            return inside
        }

        return listAll("trashed = false and mimeType contains 'audio/'", "nextPageToken,files(id,name,mimeType,size,parents,md5Checksum)")
            .filter { file -> LocalLibrary.isAudio(file.name, file.mimeType) && file.parents.orEmpty().any { isInside(it) } }
            .map { file ->
                Track(
                    id = "drive:${file.id}",
                    source = Track.SOURCE_DRIVE,
                    uri = DriveApi.mediaUrl(file.id),
                    fileName = file.name,
                    mimeType = file.mimeType,
                    sizeBytes = file.size?.toLongOrNull(),
                    title = file.name.substringBeforeLast('.'),
                    checksum = file.md5Checksum,
                )
            }
    }

    private suspend fun listAll(query: String, fields: String, orderBy: String? = null): List<DriveFile> {
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val token = pageToken
            val page = retrying { api.listFiles(query = query, fields = fields, pageToken = token, orderBy = orderBy) }
            result += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    /** A page that times out (Drive sometimes answers slowly) is asked for again, not the whole sync failed. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        repeat(LIST_ATTEMPTS - 1) { attempt ->
            try {
                return block()
            } catch (e: java.io.InterruptedIOException) {
                Log.w("DriveLibrary", "Drive listing timed out (attempt ${attempt + 1}), trying again", e)
                delay(2_000L * (attempt + 1))
            }
        }
        return block()
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val LIST_ATTEMPTS = 3

        fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    }
}

/**
 * Adds the OAuth bearer token to every googleapis.com request (API calls and ExoPlayer's media
 * requests alike) and retries once with a fresh token on 401.
 */
class DriveAuthInterceptor(private val auth: DriveAuth) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != DriveApi.HOST) return chain.proceed(request)
        val token = auth.tokenBlocking() ?: return chain.proceed(request)
        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        if (response.code != 401) return response
        response.close()
        val fresh = auth.tokenBlocking(forceRefresh = true) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $fresh").build())
    }
}
