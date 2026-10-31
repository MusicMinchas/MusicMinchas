package com.kike.musicplayer.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun testSleepTimerStatusFormatting() {
        val status15m = SleepTimerStatus(isActive = true, mode = SleepTimerMode.MINUTES, remainingSeconds = 900, totalSeconds = 900)
        assertEquals("15m 00s", status15m.formattedRemaining)

        val status1m30s = SleepTimerStatus(isActive = true, mode = SleepTimerMode.MINUTES, remainingSeconds = 90, totalSeconds = 900)
        assertEquals("1m 30s", status1m30s.formattedRemaining)

        val status25s = SleepTimerStatus(isActive = true, mode = SleepTimerMode.MINUTES, remainingSeconds = 25, totalSeconds = 900)
        assertEquals("25s", status25s.formattedRemaining)

        val inactive = SleepTimerStatus()
        assertEquals("", inactive.formattedRemaining)
    }

    @Test
    fun testStartAndCancelTimerMinutes() {
        val manager = SleepTimerManager(testScope, Dispatchers.Unconfined)

        assertFalse(manager.status.value.isActive)
        assertEquals(SleepTimerMode.OFF, manager.status.value.mode)

        manager.startTimerMinutes(30)
        assertTrue(manager.status.value.isActive)
        assertEquals(SleepTimerMode.MINUTES, manager.status.value.mode)
        assertEquals(1800L, manager.status.value.totalSeconds)
        assertTrue(manager.status.value.remainingSeconds in 1798L..1800L)

        manager.cancelTimer()
        assertFalse(manager.status.value.isActive)
        assertEquals(SleepTimerMode.OFF, manager.status.value.mode)
        assertEquals(0L, manager.status.value.remainingSeconds)
    }

    @Test
    fun testStartTimerEndOfTrack() {
        val manager = SleepTimerManager(testScope, Dispatchers.Unconfined)

        manager.startTimerEndOfTrack()
        assertTrue(manager.status.value.isActive)
        assertEquals(SleepTimerMode.END_OF_TRACK, manager.status.value.mode)

        manager.cancelTimer()
        assertFalse(manager.status.value.isActive)
    }
}
