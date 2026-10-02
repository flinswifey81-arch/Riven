package com.shai.riven.data.reminder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmServiceLifecycleTest {
    private data class Session(val key: String)

    @Test
    fun staleLatestStartCannotStopAnAlreadyValidatedActiveAlarm() {
        val lifecycle = AlarmServiceLifecycle<String, Session>(Session::key)
        lifecycle.operationStarted()
        lifecycle.operationStarted()
        lifecycle.enqueue("valid/token", Session("valid/token"))

        val active = lifecycle.startNext(
            startForeground = {},
            onForegroundFailure = { _, _ -> error("unexpected") },
        )

        assertEquals("valid/token", active?.key)
        assertFalse(lifecycle.operationFinished())
        // The stale command has the latest Android startId, but completing its validation cannot
        // stop the service while the unrelated valid token remains active.
        assertFalse(lifecycle.operationFinished())
        assertEquals("valid/token", lifecycle.activeKey)
    }

    @Test
    fun foregroundFailureDropsExactTokenAndAdvancesToNextSession() {
        val lifecycle = AlarmServiceLifecycle<String, Session>(Session::key)
        lifecycle.enqueue("first/token", Session("first/token"))
        lifecycle.enqueue("second/token", Session("second/token"))
        val terminalized = mutableListOf<String>()
        val foregroundStarts = mutableListOf<String>()

        val active = lifecycle.startNext(
            startForeground = { session ->
                if (session.key == "first/token") throw IllegalStateException("blocked")
                foregroundStarts += session.key
            },
            onForegroundFailure = { session, _ -> terminalized += session.key },
        )

        assertEquals(listOf("first/token"), terminalized)
        assertEquals(listOf("second/token"), foregroundStarts)
        assertEquals("second/token", active?.key)
        assertEquals("second/token", lifecycle.activeKey)
        assertNull(lifecycle.removeQueued("first/token"))
    }

    @Test
    fun invalidStartStopsOnlyWhenNoValidationOrSessionRemains() {
        val lifecycle = AlarmServiceLifecycle<String, Session>(Session::key)
        lifecycle.operationStarted()

        assertFalse(lifecycle.shouldStop())
        assertTrue(lifecycle.operationFinished())
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun failedForegroundTokenKeepsServiceAliveUntilTerminalizationFinishes() {
        val lifecycle = AlarmServiceLifecycle<String, Session>(Session::key)
        lifecycle.enqueue("failed/token", Session("failed/token"))

        assertNull(lifecycle.startNext(
            startForeground = { throw IllegalStateException("blocked") },
            onForegroundFailure = { _, _ -> lifecycle.operationStarted() },
        ))

        assertFalse(lifecycle.shouldStop())
        assertTrue(lifecycle.operationFinished())
    }
}
