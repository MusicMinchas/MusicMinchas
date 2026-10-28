package com.kike.musicplayer.data.subsonic

import com.kike.musicplayer.data.AutoSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SubsonicSyncOptimizationTest {
    @Test
    fun pageSizeIsOptimizedToOneThousand() {
        val field = SubsonicLibrary::class.java.getDeclaredField("PAGE")
        field.isAccessible = true
        assertEquals(1000, field.get(null))
    }

    @Test
    fun serverSyncStateTracksIndexingProgress() {
        val sync = ServerSync(running = true, listed = 5000, total = 200000L, isIndexing = true)
        assertTrue(sync.running)
        assertTrue(sync.isIndexing)
        assertEquals(5000, sync.listed)
        assertEquals(200000L, sync.total)
    }

    @Test
    fun autoSyncStateTracksStatus() {
        val state = AutoSyncState(enabled = true, running = false, lastRun = 12345L, summary = "Up to date")
        assertTrue(state.enabled)
        assertFalse(state.running)
        assertEquals(12345L, state.lastRun)
        assertEquals("Up to date", state.summary)
    }

    @Test
    fun describeFallbackProvidesHumanReadableStrings() {
        val unknownHost = UnknownHostException("Unable to resolve host music.mklabs.dk")
        val desc = SubsonicAccount.describe(unknownHost, null)
        assertTrue(desc.contains("Navidrome not found") || desc.contains("internet connection"))

        val timeout = SocketTimeoutException("Read timed out")
        assertEquals("Navidrome didn't answer", SubsonicAccount.describe(timeout, null))

        val connect = ConnectException("Connection refused")
        assertEquals("Navidrome didn't answer", SubsonicAccount.describe(connect, null))

        val subsonicAuth = SubsonicException(SubsonicException.WRONG_CREDENTIALS, "Wrong")
        assertEquals("Wrong user name or password", SubsonicAccount.describe(subsonicAuth, null))

        val genericIo = IOException("Premature EOF")
        assertEquals("Premature EOF", SubsonicAccount.describe(genericIo, null))
    }
}
