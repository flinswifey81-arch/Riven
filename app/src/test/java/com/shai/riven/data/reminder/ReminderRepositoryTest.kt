package com.shai.riven.data.reminder

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderRepositoryTest {
    private lateinit var database: ReminderDatabase
    private lateinit var scheduler: RecordingScheduler
    private lateinit var repository: ReminderRepository
    private var idCounter = 0
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = RecordingScheduler()
        repository = ReminderRepository(
            dao = database.reminderDao(),
            scheduler = scheduler,
            timePolicy = ReminderTimePolicy(Clock.fixed(now, ZoneOffset.UTC)) { ZoneOffset.UTC },
            newId = { "id-${++idCounter}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createEditAndCancelRescheduleOneStableReminder() = runBlocking {
        val created = repository.create(draft("First")) as ReminderOperationResult.Success

        assertEquals(1, created.reminder.scheduleRevision)
        assertEquals(1, scheduler.schedules.size)

        val edited = repository.edit(
            created.reminder.id,
            draft("Revised", LocalDateTime.parse("2026-10-04T10:00:00")),
        ) as ReminderOperationResult.Success
        assertEquals(2, edited.reminder.scheduleRevision)
        assertEquals(listOf(created.reminder.id), scheduler.cancellations)
        assertEquals(2, scheduler.schedules.size)

        val cancelled = repository.cancel(created.reminder.id) as ReminderOperationResult.Success
        assertEquals(ReminderStatus.CANCELLED, cancelled.reminder.status)
        assertNull(cancelled.reminder.deliveryToken)
        assertEquals(2, scheduler.cancellations.count { it == created.reminder.id })
    }

    @Test
    fun duplicateDeliveryBroadcastClaimsExactlyOneEvent() = runBlocking {
        val created = repository.create(draft("One delivery")) as ReminderOperationResult.Success

        val first = repository.claimDelivery(created.reminder.id, created.reminder.scheduleRevision)
        val duplicate = repository.claimDelivery(created.reminder.id, created.reminder.scheduleRevision)

        assertTrue(first is ReminderDeliveryClaim.Claimed)
        assertEquals(ReminderDeliveryClaim.IgnoredDuplicateOrStale, duplicate)
        assertEquals(
            1,
            database.reminderDao().eventCount(created.reminder.id, ReminderEventKind.DELIVERED.name),
        )
    }

    @Test
    fun snoozeStopsCurrentTokenAndSchedulesARevisedOccurrence() = runBlocking {
        val created = repository.create(
            draft("Wake up", deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM),
        ) as ReminderOperationResult.Success
        val claim = repository.claimDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed

        val snoozed = repository.snoozeDelivery(
            created.reminder.id,
            claim.deliveryToken,
            Duration.ofMinutes(10),
        ) as ReminderOperationResult.Success

        assertEquals(ReminderStatus.SNOOZED, snoozed.reminder.status)
        assertEquals(2, snoozed.reminder.scheduleRevision)
        assertNull(snoozed.reminder.deliveryToken)
        assertEquals(now.plus(Duration.ofMinutes(10)).toEpochMilli(), snoozed.reminder.requestedTriggerAt)
        assertEquals(2, scheduler.schedules.size)
    }

    @Test
    fun permissionDenialIsStoredAsFailureInsteadOfScheduledSuccess() = runBlocking {
        scheduler.nextResult = ReminderScheduleResult.PermissionRequired(
            ReminderFailureCode.EXACT_ALARM_PERMISSION_REQUIRED,
            "Exact alarms denied",
        )

        val result = repository.create(
            draft("Wake up", deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM),
        ) as ReminderOperationResult.Failure

        assertEquals(ReminderFailureCode.EXACT_ALARM_PERMISSION_REQUIRED, result.code)
        assertEquals(ReminderStatus.FAILED, result.reminder?.status)
        assertNull(result.reminder?.scheduledTriggerAt)
    }

    @Test
    fun rebootRecoveryIsIdempotentAndLeavesOneLatestSchedule() = runBlocking {
        val created = repository.create(draft("After reboot")) as ReminderOperationResult.Success

        val first = repository.rescheduleAll("BOOT_COMPLETED")
        val second = repository.rescheduleAll("BOOT_COMPLETED")
        val current = repository.observeReminders().first().single()

        assertEquals(1, first.scheduled)
        assertEquals(1, second.scheduled)
        assertEquals(3, current.scheduleRevision)
        assertEquals(current.scheduleRevision, scheduler.active.getValue(created.reminder.id).scheduleRevision)
        assertEquals(2, scheduler.cancellations.count { it == created.reminder.id })
    }

    @Test
    fun deliberateDismissalIsTerminalAndExcludedFromRecovery() = runBlocking {
        val created = repository.create(draft("No nagging")) as ReminderOperationResult.Success
        val claim = repository.claimDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed

        val dismissed = repository.dismissDelivery(
            created.reminder.id,
            claim.deliveryToken,
        ) as ReminderOperationResult.Success
        val report = repository.rescheduleAll("BOOT_COMPLETED")

        assertEquals(ReminderStatus.DISMISSED, dismissed.reminder.status)
        assertEquals(0, report.scheduled)
        assertTrue(created.reminder.id !in scheduler.active)
    }

    @Test
    fun processRecoveryReusesClaimedRingingTokenWithoutSchedulingAnotherEvent() = runBlocking {
        val created = repository.create(
            draft("Keep ringing", deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM),
        ) as ReminderOperationResult.Success
        val claim = repository.claimDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed

        val report = repository.rescheduleAll("application_process_restart")
        val ringing = repository.ringingDeliveries().single()

        assertEquals(0, report.scheduled)
        assertEquals(claim.deliveryToken, ringing.deliveryToken)
        assertEquals(ReminderStatus.RINGING, ringing.status)
        assertEquals(
            1,
            database.reminderDao().eventCount(created.reminder.id, ReminderEventKind.DELIVERED.name),
        )
    }

    private fun draft(
        title: String,
        localDateTime: LocalDateTime = LocalDateTime.parse("2026-10-04T09:00:00"),
        deliveryMode: ReminderDeliveryMode = ReminderDeliveryMode.NOTIFICATION,
    ) = ReminderDraft(
        title = title,
        localDateTime = localDateTime,
        zoneId = ZoneOffset.UTC,
        timeZonePolicy = ReminderTimeZonePolicy.FIXED_ZONE,
        deliveryMode = deliveryMode,
    )

    private class RecordingScheduler : ReminderPlatformScheduler {
        val schedules = mutableListOf<ReminderScheduleRequest>()
        val cancellations = mutableListOf<String>()
        val active = mutableMapOf<String, ReminderScheduleRequest>()
        var nextResult: ReminderScheduleResult = ReminderScheduleResult.Scheduled

        override fun schedule(request: ReminderScheduleRequest): ReminderScheduleResult {
            schedules += request
            val result = nextResult
            nextResult = ReminderScheduleResult.Scheduled
            if (result == ReminderScheduleResult.Scheduled) active[request.reminderId] = request
            return result
        }

        override fun cancel(reminderId: String) {
            cancellations += reminderId
            active.remove(reminderId)
        }
    }
}
