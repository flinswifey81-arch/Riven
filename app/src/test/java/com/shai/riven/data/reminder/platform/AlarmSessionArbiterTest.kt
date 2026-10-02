package com.shai.riven.data.reminder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSessionArbiterTest {
    @Test
    fun simultaneousAlarmsQueueWithoutReplacingActiveSession() {
        val arbiter = AlarmSessionArbiter<String, String>()

        assertTrue(arbiter.enqueue("alarm-a/token-a", "A"))
        assertTrue(arbiter.enqueue("alarm-b/token-b", "B"))
        assertEquals("A", arbiter.activateNext())
        assertNull(arbiter.activateNext())
        assertEquals("alarm-a/token-a", arbiter.activeKey)

        assertTrue(arbiter.complete("alarm-a/token-a"))
        assertEquals("B", arbiter.activateNext())
        assertEquals("alarm-b/token-b", arbiter.activeKey)
    }

    @Test
    fun outOfOrderActionRemovesOnlyMatchingQueuedToken() {
        val arbiter = AlarmSessionArbiter<String, String>()
        arbiter.enqueue("alarm-a/token-a", "A")
        arbiter.enqueue("alarm-b/token-b", "B")
        arbiter.activateNext()

        val removed = arbiter.remove("alarm-b/token-b")

        assertEquals("B", removed?.value)
        assertFalse(checkNotNull(removed).wasActive)
        assertEquals("alarm-a/token-a", arbiter.activeKey)
        assertNull(arbiter.remove("alarm-b/old-token"))
    }

    @Test
    fun activeOwnershipRemainsUntilCleanupCompletes() {
        val arbiter = AlarmSessionArbiter<String, String>()
        arbiter.enqueue("alarm-a/token-a", "A")
        arbiter.enqueue("alarm-b/token-b", "B")
        arbiter.activateNext()

        assertTrue(arbiter.isActive("alarm-a/token-a"))
        assertNull(arbiter.removeQueued("alarm-a/token-a"))
        assertNull(arbiter.activateNext())
        assertTrue(arbiter.complete("alarm-a/token-a"))
        assertEquals("B", arbiter.activateNext())
    }

    @Test
    fun restartNearOrAfterExpiryNeverRenewsOriginalDeadline() {
        val ringUntil = 10_000L

        assertEquals(250L, remainingRingMillis(ringUntil, 9_750L))
        assertEquals(0L, remainingRingMillis(ringUntil, 10_001L))
        assertEquals(0L, remainingRingMillis(ringUntil, 50_000L))
    }
}
