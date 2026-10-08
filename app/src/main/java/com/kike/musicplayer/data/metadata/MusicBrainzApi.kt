package com.kike.musicplayer.data.metadata

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** https://musicbrainz.org/doc/MusicBrainz_API — max 1 request/second, User-Agent required. */
interface MusicBrainzApi {
    @GET("ws/2/recording")
    suspend fun searchRecordings(
        @Query("query") query: String,
        @Query("limit") limit: Int = 10,
        @Query("fmt") format: String = "json",
    ): MbRecordingSearch

    @GET("ws/2/release")
    suspend fun searchReleases(
        @Query("query") query: String,
        @Query("limit") limit: Int = 5,
        @Query("fmt") format: String = "json",
    ): MbReleaseSearch

    companion object {
        const val BASE_URL = "https://musicbrainz.org/"
    }
}

data class MbRecordingSearch(val recordings: List<MbRecording> = emptyList())

data class MbReleaseSearch(val releases: List<MbRelease> = emptyList())

data class MbRecording(
    val id: String,
    val score: Int? = null,
    val title: String,
    val length: Long? = null,
    @Json(name = "artist-credit") val artistCredit: List<MbArtistCredit>? = null,
    val releases: List<MbRelease>? = null,
    /** Music-video recordings belong to no album, so they carry no cover. */
    val video: Boolean? = null,
) {
    val artistName: String?
        get() = artistCredit?.joinToString("") { it.name + (it.joinphrase ?: "") }?.takeIf { it.isNotBlank() }
}

data class MbArtistCredit(val name: String, val joinphrase: String? = null)

data class MbRelease(
    val id: String,
    val title: String,
    /** Only set in release search results. */
    val score: Int? = null,
    val status: String? = null,
    val date: String? = null,
    @Json(name = "artist-credit") val artistCredit: List<MbArtistCredit>? = null,
    @Json(name = "release-group") val releaseGroup: MbReleaseGroup? = null,
)

data class MbReleaseGroup(
    val id: String,
    @Json(name = "primary-type") val primaryType: String? = null,
    /** e.g. "Compilation", "Live", "Remix"; empty for an original studio album. */
    @Json(name = "secondary-types") val secondaryTypes: List<String>? = null,
)

/** https://musicbrainz.org/doc/Cover_Art_Archive/API */
interface CoverArtApi {
    @GET("release/{mbid}")
    suspend fun release(@Path("mbid") releaseId: String): CaaListing

    @GET("release-group/{mbid}")
    suspend fun releaseGroup(@Path("mbid") releaseGroupId: String): CaaListing

    companion object {
        const val BASE_URL = "https://coverartarchive.org/"
    }
}

data class CaaListing(val images: List<CaaImage> = emptyList())

data class CaaImage(
    val front: Boolean = false,
    val image: String,
    val thumbnails: Map<String, String> = emptyMap(),
) {
    /** The 1200 px rendition is plenty for a phone and much lighter than some originals. */
    val bestUrl: String get() = (thumbnails["1200"] ?: thumbnails["large"] ?: image).replace("http://", "https://")
}
