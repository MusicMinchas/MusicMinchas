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

    companion object {
        /** Big enough for Now Playing; the server scales and caches it. */
        private const val SIZE = 800

        fun coverUrl(client: SubsonicClient, login: SubsonicLogin, id: String): String =
            client.url(login, "getCoverArt", mapOf("id" to id, "size" to "$SIZE"), salt = SubsonicClient.md5("cover:$id").take(12))
                .toString()
    }
}
