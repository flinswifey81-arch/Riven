package com.shai.riven.data.reminder

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimePolicy(
    private val clock: Clock = Clock.systemUTC(),
    private val deviceZone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun nowMillis(): Long = clock.millis()

    fun currentDeviceZone(): ZoneId = deviceZone()

    fun resolveRequestedInstant(
        localDateTime: LocalDateTime,
        storedZone: ZoneId,
        policy: ReminderTimeZonePolicy,
    ): Instant = resolveLocalDateTime(
        localDateTime = localDateTime,
        zoneId = if (policy == ReminderTimeZonePolicy.FOLLOW_DEVICE) deviceZone() else storedZone,
    ).toInstant()

    fun nextAllowedInstant(
        requested: Instant,
        quietHours: ReminderQuietHours,
        featureControl: ReminderFeatureControl,
        zoneId: ZoneId,
    ): Instant {
        if (!quietHours.enabled || featureControl.allowDuringQuietHours) return requested
        val local = requested.atZone(zoneId)
        if (!isWithinQuietHours(local.toLocalTime(), quietHours)) return requested

        val exitDate = quietHoursExitDate(local.toLocalDate(), local.toLocalTime(), quietHours)
        return resolveLocalDateTime(
            LocalDateTime.of(exitDate, minuteOfDayToLocalTime(quietHours.endMinuteOfDay)),
            zoneId,
        ).toInstant()
    }

    fun coerceFuture(instant: Instant): Instant {
        val earliest = clock.instant().plusMillis(MINIMUM_SCHEDULING_LEAD_MILLIS)
        return if (instant.isBefore(earliest)) earliest else instant
    }

    internal fun resolveLocalDateTime(localDateTime: LocalDateTime, zoneId: ZoneId): ZonedDateTime {
        val rules = zoneId.rules
        val offsets = rules.getValidOffsets(localDateTime)
        return when (offsets.size) {
            0 -> {
                val transition = checkNotNull(rules.getTransition(localDateTime))
                ZonedDateTime.ofLocal(transition.dateTimeAfter, zoneId, transition.offsetAfter)
            }
            1 -> ZonedDateTime.ofLocal(localDateTime, zoneId, offsets.single())
            else -> ZonedDateTime.ofLocal(localDateTime, zoneId, offsets.first())
        }
    }

    internal fun isWithinQuietHours(time: LocalTime, quietHours: ReminderQuietHours): Boolean {
        val minute = time.hour * 60 + time.minute
        val start = quietHours.startMinuteOfDay
        val end = quietHours.endMinuteOfDay
        return when {
            // Equal boundaries define an empty interval, never an all-day quiet period.
            start == end -> false
            start < end -> minute in start until end
            else -> minute >= start || minute < end
        }
    }

    private fun quietHoursExitDate(
        date: LocalDate,
        time: LocalTime,
        quietHours: ReminderQuietHours,
    ): LocalDate {
        val minute = time.hour * 60 + time.minute
        return if (quietHours.startMinuteOfDay > quietHours.endMinuteOfDay &&
            minute >= quietHours.startMinuteOfDay
        ) {
            date.plusDays(1)
        } else {
            date
        }
    }

    private fun minuteOfDayToLocalTime(minute: Int): LocalTime =
        LocalTime.of(minute / 60, minute % 60)

    private companion object {
        const val MINIMUM_SCHEDULING_LEAD_MILLIS = 1_000L
    }
}
