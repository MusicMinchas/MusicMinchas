package com.kike.musicplayer.data.drive

import com.kike.musicplayer.data.db.Track
import com.kike.musicplayer.data.local.LocalLibrary
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface DriveApi {
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Query("q") query: String,
        @Query("fields") fields: String = "nextPageToken,files(id,name,mimeType,size)",
        @Query("pageSize") pageSize: Int = 1000,
        @Query("pageToken") pageToken: String? = null,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true,
        @Query("includeItemsFromAllDrives") includeAllDrives: Boolean = true,
    ): DriveFileList

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"
        const val HOST = "www.googleapis.com"

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
    val name: String,
    val mimeType: String?,
    /** Drive sends int64 values as JSON strings. */
    val size: String?,
)

/** Lists every audio file the account can see in Drive. */
class DriveLibrary(private val api: DriveApi) {
    suspend fun scan(): List<Track> {
        val result = mutableListOf<Track>()
        var pageToken: String? = null
        do {
            val page = api.listFiles(query = "trashed = false and mimeType contains 'audio/'", pageToken = pageToken)
            page.files.filter { LocalLibrary.isAudio(it.name, it.mimeType) }.mapTo(result) { file ->
                Track(
                    id = "drive:${file.id}",
                    source = Track.SOURCE_DRIVE,
                    uri = DriveApi.mediaUrl(file.id),
                    fileName = file.name,
                    mimeType = file.mimeType,
                    sizeBytes = file.size?.toLongOrNull(),
                    title = file.name.substringBeforeLast('.'),
                )
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
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
