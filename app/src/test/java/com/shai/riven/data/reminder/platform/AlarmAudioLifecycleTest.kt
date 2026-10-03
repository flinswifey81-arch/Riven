package com.shai.riven.data.reminder.platform

import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderFeature
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderSoundKind
import com.shai.riven.data.reminder.ReminderStatus
import com.shai.riven.data.reminder.ReminderTimeZonePolicy
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmAudioLifecycleTest {
    @Test
    fun bundledRivenVoiceIsDefaultWhileCustomSelectionRemainsExact() {
        val bundled = "android.resource://com.shai.riven/123"
        val custom = "content://user-selected/alarm"

        assertEquals(
            bundled,
            selectAlarmAudioSource(ReminderSoundKind.SYSTEM_DEFAULT, custom, bundled),
        )
        assertEquals(
            custom,
            selectAlarmAudioSource(ReminderSoundKind.CUSTOM_URI, custom, bundled),
        )
        assertNull(selectAlarmAudioSource(ReminderSoundKind.CUSTOM_URI, null, bundled))
    }

    @Test
    fun bundledDefaultFailureAttemptsSystemFallbackExactlyOnceWithoutLooping() {
        val failure = AlarmAudioResult.Failure(
            com.shai.riven.data.reminder.ReminderFailureCode.AUDIO_SOURCE_UNAVAILABLE,
            "Injected failure",
        )
        val engine = RecordingAudioEngine(primaryResult = failure, fallbackResult = failure)
        val lifecycle = AlarmAudioLifecycle(engine)

        val attempt = lifecycle.startWithSystemFallback(reminder())

        assertTrue(attempt.primary is AlarmAudioResult.Failure)
        assertTrue(attempt.systemFallback is AlarmAudioResult.Failure)
        assertEquals(1, engine.playCount)
        assertEquals(1, engine.fallbackCount)
    }

    @Test
    fun thrownPrimaryFailureBecomesFailureAndSystemFallbackStartsExactlyOnce() {
        val engine = RecordingAudioEngine(primaryException = IllegalStateException("primary exploded"))
        val lifecycle = AlarmAudioLifecycle(engine)

        val attempt = lifecycle.startWithSystemFallback(reminder())

        assertTrue(attempt.primary is AlarmAudioResult.Failure)
        assertEquals(AlarmAudioResult.Started, attempt.systemFallback)
        assertEquals(1, engine.playCount)
        assertEquals(1, engine.fallbackCount)
        lifecycle.stop()
        assertEquals(1, engine.stopCount)
    }

    @Test
    fun thrownPrimaryAndFallbackFailuresBecomeTruthfulTerminalAttempt() = runBlocking {
        val engine = RecordingAudioEngine(
            primaryException = IllegalStateException("primary exploded"),
            fallbackException = SecurityException("fallback blocked"),
        )
        val lifecycle = AlarmAudioLifecycle(engine)

        val attempt = lifecycle.startWithSystemFallback(reminder())
        val warnings = mutableListOf<AlarmAudioResult.Failure>()
        var terminalFailure: AlarmAudioResult.Failure? = null
        val started = settleAlarmAudioStartAttempt(
            attempt = attempt,
            recordWarning = { warnings += it },
            terminalize = { terminalFailure = it },
        )

        assertFalse(started)
        assertEquals(2, warnings.size)
        assertEquals(1, engine.playCount)
        assertEquals(1, engine.fallbackCount)
        assertTrue(terminalFailure?.detail?.contains("both failed") == true)
        lifecycle.stop()
        assertEquals(0, engine.stopCount)
    }

    @Test
    fun cancellationFromAudioEngineIsNotConvertedIntoFallback() {
        val engine = RecordingAudioEngine(primaryException = CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            AlarmAudioLifecycle(engine).startWithSystemFallback(reminder())
        }

        assertEquals(1, engine.playCount)
        assertEquals(0, engine.fallbackCount)
    }

    @Test
    fun unpublishedMediaCandidateIsReleasedWhenPreparationThrows() {
        val candidate = RecordingCandidate()

        assertThrows(IllegalArgumentException::class.java) {
            publishPreparedAlarmAudioResource(
                create = { candidate },
                prepareAndStart = { throw IllegalArgumentException("bad source") },
                publish = { it.published = true },
                release = { it.released = true },
            )
        }

        assertFalse(candidate.published)
        assertTrue(candidate.released)
    }

    @Test
    fun replacementAndBackgroundCleanupReleaseAudioExactlyOncePerSession() {
        val engine = RecordingAudioEngine()
        val lifecycle = AlarmAudioLifecycle(engine)

        lifecycle.startWithSystemFallback(reminder())
        lifecycle.startWithSystemFallback(reminder())
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
        ringUntilAt = 600_000,
        lastFailureCode = null,
        lastFailureDetail = null,
    )

    private class RecordingAudioEngine(
        private val primaryResult: AlarmAudioResult = AlarmAudioResult.Started,
        private val fallbackResult: AlarmAudioResult = AlarmAudioResult.Started,
        private val primaryException: Exception? = null,
        private val fallbackException: Exception? = null,
    ) : AlarmAudioEngine {
        var playCount = 0
        var fallbackCount = 0
        var stopCount = 0

        override fun play(reminder: ReminderSnapshot): AlarmAudioResult {
            playCount++
            primaryException?.let { throw it }
            return primaryResult
        }

        override fun playSystemFallback(): AlarmAudioResult {
            fallbackCount++
            fallbackException?.let { throw it }
            return fallbackResult
        }

        override fun stop() {
            stopCount++
        }
    }

    private class RecordingCandidate {
        var published = false
        var released = false
    }
}
