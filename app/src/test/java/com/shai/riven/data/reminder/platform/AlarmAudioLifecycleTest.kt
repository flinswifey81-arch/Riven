package com.shai.riven.data.reminder.platform

import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderFeature
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderSoundKind
import com.shai.riven.data.reminder.ReminderStatus
import com.shai.riven.data.reminder.ReminderTimeZonePolicy
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmAudioLifecycleTest {
    @Test
    fun replacementAndBackgroundCleanupReleaseAudioExactlyOncePerSession() {
        val engine = RecordingAudioEngine()
        val lifecycle = AlarmAudioLifecycle(engine)

        lifecycle.start(reminder())
        lifecycle.start(reminder())
        lifecycle.stop()
        lifecycle.stop()

        assertEquals(2, engine.playCount)
        assertEquals(2, engine.stopCount)
    }

    private fun reminder() = ReminderSnapshot(
        id = "reminder-1",
        title = "Wake up",
        note = null,
        feature = ReminderFeature.REMINDERS,
        localDateTime = LocalDateTime.parse("2026-10-04T09:00:00"),
        zoneId = ZoneOffset.UTC,
        timeZonePolicy = ReminderTimeZonePolicy.FIXED_ZONE,
        requestedTriggerAt = 1,
        scheduledTriggerAt = 1,
        deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM,
        soundKind = ReminderSoundKind.SYSTEM_DEFAULT,
        customSoundUri = null,
        status = ReminderStatus.RINGING,
        scheduleRevision = 1,
        deliveryToken = "delivery-1",
        lastFailureCode = null,
        lastFailureDetail = null,
    )

    private class RecordingAudioEngine : AlarmAudioEngine {
        var playCount = 0
        var stopCount = 0

        override fun play(reminder: ReminderSnapshot): AlarmAudioResult {
            playCount++
            return AlarmAudioResult.Started
        }

        override fun playSystemFallback(): AlarmAudioResult = AlarmAudioResult.Started

        override fun stop() {
            stopCount++
        }
    }
}
