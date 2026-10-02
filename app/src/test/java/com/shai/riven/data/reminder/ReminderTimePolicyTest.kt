package com.shai.riven.data.reminder

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTimePolicyTest {
    private val utcNow = Instant.parse("2026-10-02T12:00:00Z")

    @Test
    fun springGapMovesToFirstValidWallTime() {
        val london = ZoneId.of("Europe/London")
        val policy = ReminderTimePolicy(Clock.fixed(utcNow, ZoneOffset.UTC)) { london }

        val resolved = policy.resolveLocalDateTime(
            LocalDateTime.parse("2026-03-29T01:30:00"),
            london,
        )

        assertEquals(LocalDateTime.parse("2026-03-29T02:00:00"), resolved.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(1), resolved.offset)
    }

    @Test
    fun autumnOverlapChoosesEarlierOccurrenceDeterministically() {
        val london = ZoneId.of("Europe/London")
        val policy = ReminderTimePolicy(Clock.fixed(utcNow, ZoneOffset.UTC)) { london }

        val resolved = policy.resolveLocalDateTime(
            LocalDateTime.parse("2026-10-25T01:30:00"),
            london,
        )

        assertEquals(ZoneOffset.ofHours(1), resolved.offset)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), resolved.toInstant())
    }

    @Test
    fun followDevicePolicyUsesCurrentDeviceZoneAfterTimezoneChange() {
        val newYork = ZoneId.of("America/New_York")
        val policy = ReminderTimePolicy(Clock.fixed(utcNow, ZoneOffset.UTC)) { newYork }

        val instant = policy.resolveRequestedInstant(
            LocalDateTime.parse("2026-10-04T09:00:00"),
            ZoneId.of("UTC"),
            ReminderTimeZonePolicy.FOLLOW_DEVICE,
        )

        assertEquals(Instant.parse("2026-10-04T13:00:00Z"), instant)
    }

    @Test
    fun crossMidnightQuietHoursDeferToMorningBoundary() {
        val london = ZoneId.of("Europe/London")
        val policy = ReminderTimePolicy(Clock.fixed(utcNow, ZoneOffset.UTC)) { london }
        val quiet = ReminderQuietHours(
            enabled = true,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 7 * 60,
        )

        val deferred = policy.nextAllowedInstant(
            requested = Instant.parse("2026-10-04T22:30:00Z"),
            quietHours = quiet,
            featureControl = ReminderFeatureControl(ReminderFeature.REMINDERS, true, false),
            zoneId = london,
        )

        assertEquals(Instant.parse("2026-10-05T06:00:00Z"), deferred)
        assertTrue(
            policy.isWithinQuietHours(
                Instant.parse("2026-10-04T22:30:00Z").atZone(london).toLocalTime(),
                quiet,
            ),
        )
    }

    @Test
    fun equalQuietHourBoundariesDefineEmptyInterval() {
        val policy = ReminderTimePolicy(Clock.fixed(utcNow, ZoneOffset.UTC)) { ZoneOffset.UTC }
        val quiet = ReminderQuietHours(
            enabled = true,
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 8 * 60,
        )
        val requested = Instant.parse("2026-10-04T12:00:00Z")

        val allowed = policy.nextAllowedInstant(
            requested = requested,
            quietHours = quiet,
            featureControl = ReminderFeatureControl(ReminderFeature.REMINDERS, true, false),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(requested, allowed)
        assertEquals(false, policy.isWithinQuietHours(requested.atZone(ZoneOffset.UTC).toLocalTime(), quiet))
    }
}
