package com.kike.musicplayer.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Test

class LastFmSignatureTest {
    @Test
    fun `signature matches Last_fm's documented example`() {
        // md5("api_keyxxxxxxxxmethodauth.getSessiontokenxxxxxxxmysecret") from https://www.last.fm/api/webauth
        val params = mapOf("method" to "auth.getSession", "token" to "xxxxxxx", "api_key" to "xxxxxxxx")
        assertEquals("68afb32bee072407a63b6c41f3e1e2b4", LastFmClient.sign(params, "mysecret"))
    }

    @Test
    fun `format and callback are not signed`() {
        val params = mapOf("method" to "auth.getSession", "token" to "xxxxxxx", "api_key" to "xxxxxxxx")
        assertEquals(
            LastFmClient.sign(params, "mysecret"),
            LastFmClient.sign(params + ("format" to "json") + ("callback" to "cb"), "mysecret"),
        )
    }
}
