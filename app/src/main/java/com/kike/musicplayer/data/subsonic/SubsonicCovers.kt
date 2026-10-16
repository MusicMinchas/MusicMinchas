package com.kike.musicplayer.data.subsonic

import coil3.map.Mapper
import coil3.request.Options

/**
 * Server covers are stored as "subsonic:cover/<id>" (no sign-in in the database); images load
 * from the signed getCoverArt URL. Each cover gets its own fixed salt, so its URL stays the same
 * and Coil's caches work.
 */
class SubsonicCoverMapper(private val account: SubsonicAccount) : Mapper<String, String> {
    override fun map(data: String, options: Options): String? {
        if (!data.startsWith(SubsonicLibrary.COVER_PREFIX)) return null
        val login = account.login() ?: return null
        val id = data.removePrefix(SubsonicLibrary.COVER_PREFIX)
        return coverUrl(account.client, login, id)
    }

    /**
     * The same for covers given as an Android Uri: the player screens take them from the playing
     * song's media metadata (MediaMetadata.artworkUri), not from the library row.
     */
    class ForUri(private val strings: SubsonicCoverMapper) : Mapper<android.net.Uri, String> {
        override fun map(data: android.net.Uri, options: Options): String? =
            if (data.scheme == "subsonic") strings.map(data.toString(), options) else null
    }

    companion object {
        /** Big enough for Now Playing; the server scales and caches it. */
        private const val SIZE = 800

        fun coverUrl(client: SubsonicClient, login: SubsonicLogin, id: String): String =
            client.url(login, "getCoverArt", mapOf("id" to id, "size" to "$SIZE"), salt = SubsonicClient.md5("cover:$id").take(12))
                .toString()
    }
}
