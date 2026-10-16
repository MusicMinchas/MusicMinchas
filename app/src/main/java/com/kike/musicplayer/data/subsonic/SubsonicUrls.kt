package com.kike.musicplayer.data.subsonic

/**
 * Server songs and covers are stored as "subsonic:<song id>" and "subsonic:cover/<id>", without any
 * sign-in; this turns them into signed URLs just before they're opened (playback, Sonos, downloads,
 * notification artwork), so the database never holds credentials and a new password just works.
 */
class SubsonicUrls(private val account: SubsonicAccount) {
    /** The http(s) URL behind a "subsonic:" song or cover URI; null for other URIs or when signed out. */
    fun resolve(uri: String): String? {
        if (!uri.startsWith(PREFIX)) return null
        val login = account.login() ?: return null
        return if (uri.startsWith(SubsonicLibrary.COVER_PREFIX)) {
            SubsonicCoverMapper.coverUrl(account.client, login, uri.removePrefix(SubsonicLibrary.COVER_PREFIX))
        } else {
            // The original file, as stored on the server (no transcoding).
            account.client.url(login, "stream", mapOf("id" to uri.removePrefix(PREFIX), "format" to "raw")).toString()
        }
    }

    /** The original file of a server song, for downloading it to the phone. */
    fun download(uri: String): String? {
        if (!uri.startsWith(PREFIX) || uri.startsWith(SubsonicLibrary.COVER_PREFIX)) return null
        val login = account.login() ?: return null
        return account.client.url(login, "download", mapOf("id" to uri.removePrefix(PREFIX))).toString()
    }

    companion object {
        const val PREFIX = "subsonic:"
    }
}
