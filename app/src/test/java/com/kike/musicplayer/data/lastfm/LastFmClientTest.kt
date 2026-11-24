package com.kike.musicplayer.data.lastfm

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LastFmClientTest {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Test
    fun `dynamic credentials update isConfigured and request signing immediately`() = runBlocking {
        var key = ""
        var secret = ""
        val capturedUrls = mutableListOf<String>()

        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedUrls += req.url.toString()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"token":"tok123"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = LastFmClient(http, moshi, { key }, { secret })
        assertFalse(client.isConfigured)

        // Calling while unconfigured throws INVALID_API_KEY without making a network request.
        try {
            client.call("auth.getToken", signed = true)
            fail("Expected LastFmException")
        } catch (e: LastFmException) {
            assertEquals(LastFmException.INVALID_API_KEY, e.code)
            assertTrue(e.isApiKeyError)
            assertTrue(e.isAuthConfigError)
        }
        assertTrue(capturedUrls.isEmpty())

        // Supply credentials dynamically -> call succeeds with expected api_key and api_sig.
        key = "pro-key-1"
        secret = "pro-secret-1"
        assertTrue(client.isConfigured)
        client.call("auth.getToken", signed = true)
        assertEquals(1, capturedUrls.size)
        val expectedSig1 = LastFmClient.sign(mapOf("method" to "auth.getToken", "api_key" to "pro-key-1"), "pro-secret-1")
        assertTrue(capturedUrls[0].contains("api_key=pro-key-1"))
        assertTrue(capturedUrls[0].contains("api_sig=$expectedSig1"))

        // Rotate credentials dynamically -> next call immediately uses the new key & signature.
        key = "pro-key-2"
        secret = "pro-secret-2"
        client.call("auth.getToken", signed = true)
        val expectedSig2 = LastFmClient.sign(mapOf("method" to "auth.getToken", "api_key" to "pro-key-2"), "pro-secret-2")
        assertTrue(capturedUrls[1].contains("api_key=pro-key-2"))
        assertTrue(capturedUrls[1].contains("api_sig=$expectedSig2"))
    }

    @Test
    fun `LastFmException classifies API key, signature, and temporary errors accurately`() {
        assertTrue(LastFmException(LastFmException.INVALID_API_KEY, "Invalid API key").isApiKeyError)
        assertTrue(LastFmException(LastFmException.INVALID_API_KEY, "Invalid API key").isAuthConfigError)
        assertTrue(LastFmException(LastFmException.SUSPENDED_API_KEY, "Suspended API key").isApiKeyError)
        assertTrue(LastFmException(LastFmException.SUSPENDED_API_KEY, "Suspended API key").isAuthConfigError)

        val badSig = LastFmException(LastFmException.INVALID_SIGNATURE, "Invalid method signature")
        assertFalse(badSig.isApiKeyError)
        assertTrue(badSig.isAuthConfigError)

        val badParams = LastFmException(6, "Invalid parameters")
        assertFalse(badParams.isApiKeyError)
        assertFalse(badParams.isAuthConfigError)
        assertFalse(badParams.isTemporary)

        assertTrue(LastFmException(LastFmException.RATE_LIMITED, "Rate limit exceeded").isTemporary)
    }
}
