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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    private lateinit var deliveryEffects: RecordingDeliveryEffects
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
        deliveryEffects = RecordingDeliveryEffects()
        repository = ReminderRepository(
            dao = database.reminderDao(),
            scheduler = scheduler,
            deliveryEffects = deliveryEffects,
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
            database.reminderDao().eventCount(created.reminder.id, ReminderEventKind.DELIVERY_CLAIMED.name),
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
            created.reminder.scheduleRevision,
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
            created.reminder.scheduleRevision,
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
            now.plusMillis(AUDIBLE_ALARM_MAX_RING_MILLIS).toEpochMilli(),
            ringing.ringUntilAt,
        )
        assertEquals(
            1,
            database.reminderDao().eventCount(created.reminder.id, ReminderEventKind.DELIVERY_CLAIMED.name),
        )
    }

    @Test
    fun staleActionRevisionCannotMutateMatchingDeliveryToken() = runBlocking {
        val created = repository.create(
            draft("Revision guarded", deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM),
        ) as ReminderOperationResult.Success
        val claim = repository.claimDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed

        val result = repository.dismissDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision + 1,
            claim.deliveryToken,
        )
        val current = repository.ringingDeliveries().single()

        assertTrue(result is ReminderOperationResult.Failure)
        assertEquals(ReminderStatus.RINGING, current.status)
        assertEquals(claim.deliveryToken, current.deliveryToken)
        assertTrue(deliveryEffects.cancellations.isEmpty())
    }

    @Test
    fun claimedOldBroadcastCannotPostAfterEditAndOnlyLatestScheduleRemains() = runBlocking {
        val created = repository.create(draft("Race edit")) as ReminderOperationResult.Success
        val claim = repository.claimDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed

        val edited = repository.edit(
            created.reminder.id,
            draft("Edited", LocalDateTime.parse("2026-10-05T10:00:00")),
        ) as ReminderOperationResult.Success

        assertNull(repository.isCurrentDelivery(created.reminder.id, 1, claim.deliveryToken))
        assertEquals(2, edited.reminder.scheduleRevision)
        assertEquals(2, scheduler.active.getValue(created.reminder.id).scheduleRevision)
        assertTrue(deliveryEffects.cancellations.contains(created.reminder.id to claim.deliveryToken))
    }

    @Test
    fun editReadBeforeClaimRetriesWinningTokenAndStopsActivatedAudio() = runBlocking {
        val created = repository.create(
            draft("Edit race", deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM),
        ) as ReminderOperationResult.Success
        val initialRead = CompletableDeferred<Unit>()
        val resumeMutation = CompletableDeferred<Unit>()
        repository.hooks = ReminderRepositoryHooks {
            initialRead.complete(Unit)
            resumeMutation.await()
        }

        val edit = async {
            repository.edit(
                created.reminder.id,
                draft("Edited after claim", deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM),
            )
        }
        initialRead.await()
        val claim = repository.claimDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed
        deliveryEffects.activate(created.reminder.id, claim.deliveryToken)
        assertEquals(
            created.reminder.id to claim.deliveryToken,
            deliveryEffects.audioActivated.await(),
        )
        resumeMutation.complete(Unit)

        val edited = edit.await() as ReminderOperationResult.Success

        assertEquals("Edited after claim", edited.reminder.title)
        assertEquals(ReminderStatus.SCHEDULED, edited.reminder.status)
        assertTrue(deliveryEffects.activeTokens.isEmpty())
        assertTrue(deliveryEffects.cancellations.contains(created.reminder.id to claim.deliveryToken))
    }

    @Test
    fun cancelWinningRaceMakesQueuedOldBroadcastStale() = runBlocking {
        val created = repository.create(draft("Race cancel")) as ReminderOperationResult.Success

        val cancelled = repository.cancel(created.reminder.id) as ReminderOperationResult.Success
        val stale = repository.claimDelivery(created.reminder.id, created.reminder.scheduleRevision)

        assertEquals(ReminderStatus.CANCELLED, cancelled.reminder.status)
        assertEquals(ReminderDeliveryClaim.IgnoredDuplicateOrStale, stale)
        assertTrue(created.reminder.id !in scheduler.active)
    }

    @Test
    fun completeAfterClaimInvalidatesTokenAndStopsOnlyThatDelivery() = runBlocking {
        val created = repository.create(
            draft("Race complete", deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM),
        ) as ReminderOperationResult.Success
        val claim = repository.claimDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed

        val completed = repository.complete(created.reminder.id) as ReminderOperationResult.Success

        assertEquals(ReminderStatus.COMPLETED, completed.reminder.status)
        assertEquals(2, completed.reminder.scheduleRevision)
        assertNull(repository.isCurrentDelivery(created.reminder.id, 1, claim.deliveryToken))
        assertTrue(deliveryEffects.cancellations.contains(created.reminder.id to claim.deliveryToken))
    }

    @Test
    fun completeReadBeforeClaimRetriesWinningTokenAndStopsActivatedAudio() = runBlocking {
        assertReadBeforeClaimFinishRace(completing = true)
    }

    @Test
    fun cancelReadBeforeClaimRetriesWinningTokenAndStopsActivatedAudio() = runBlocking {
        assertReadBeforeClaimFinishRace(completing = false)
    }

    @Test
    fun rescheduleWinningRaceLeavesExactlyOneLatestPlatformSchedule() = runBlocking {
        val created = repository.create(draft("Race recovery")) as ReminderOperationResult.Success

        repository.rescheduleAll("timezone_changed")
        val stale = repository.claimDelivery(created.reminder.id, created.reminder.scheduleRevision)

        assertEquals(ReminderDeliveryClaim.IgnoredDuplicateOrStale, stale)
        assertEquals(2, scheduler.active.getValue(created.reminder.id).scheduleRevision)
        assertEquals(1, scheduler.active.size)
    }

    @Test
    fun snoozeDeadlineRemainsAbsoluteAcrossDeviceTimezoneChange() = runBlocking {
        var deviceZone: ZoneId = ZoneOffset.UTC
        val timezoneAware = ReminderRepository(
            dao = database.reminderDao(),
            scheduler = scheduler,
            deliveryEffects = deliveryEffects,
            timePolicy = ReminderTimePolicy(Clock.fixed(now, ZoneOffset.UTC)) { deviceZone },
            newId = { "tz-${++idCounter}" },
        )
        val created = timezoneAware.create(
            ReminderDraft(
                title = "Absolute snooze",
                localDateTime = LocalDateTime.parse("2026-10-04T09:00:00"),
                zoneId = ZoneOffset.UTC,
                timeZonePolicy = ReminderTimeZonePolicy.FOLLOW_DEVICE,
                deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM,
            ),
        ) as ReminderOperationResult.Success
        val claim = timezoneAware.claimDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed
        val snoozed = timezoneAware.snoozeDelivery(
            created.reminder.id,
            created.reminder.scheduleRevision,
            claim.deliveryToken,
            Duration.ofMinutes(10),
        ) as ReminderOperationResult.Success
        deviceZone = ZoneId.of("Pacific/Auckland")

        timezoneAware.rescheduleAll("timezone_changed")
        val recovered = timezoneAware.observeReminders().first().single()

        assertEquals(snoozed.reminder.requestedTriggerAt, recovered.requestedTriggerAt)
        assertEquals(snoozed.reminder.scheduledTriggerAt, recovered.scheduledTriggerAt)
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

    private suspend fun assertReadBeforeClaimFinishRace(completing: Boolean) {
        val created = repository.create(
            draft("Finish race", deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM),
        ) as ReminderOperationResult.Success
        val initialRead = CompletableDeferred<Unit>()
        val resumeMutation = CompletableDeferred<Unit>()
        repository.hooks = ReminderRepositoryHooks {
            initialRead.complete(Unit)
            resumeMutation.await()
        }

        kotlinx.coroutines.coroutineScope {
            val finish = async {
                if (completing) {
                    repository.complete(created.reminder.id)
                } else {
                    repository.cancel(created.reminder.id)
                }
            }
            initialRead.await()
            val claim = repository.claimDelivery(
                created.reminder.id,
                created.reminder.scheduleRevision,
            ) as ReminderDeliveryClaim.Claimed
            deliveryEffects.activate(created.reminder.id, claim.deliveryToken)
            assertEquals(
                created.reminder.id to claim.deliveryToken,
                deliveryEffects.audioActivated.await(),
            )
            resumeMutation.complete(Unit)

            val finished = finish.await() as ReminderOperationResult.Success
            assertEquals(
                if (completing) ReminderStatus.COMPLETED else ReminderStatus.CANCELLED,
                finished.reminder.status,
            )
            assertTrue(deliveryEffects.activeTokens.isEmpty())
            assertTrue(deliveryEffects.cancellations.contains(created.reminder.id to claim.deliveryToken))
        }
    }

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

    private class RecordingDeliveryEffects : ReminderDeliveryEffects {
        val cancellations = mutableListOf<Pair<String, String?>>()
        val activeTokens = mutableSetOf<Pair<String, String>>()
        val audioActivated = CompletableDeferred<Pair<String, String>>()

        fun activate(reminderId: String, deliveryToken: String) {
            val key = reminderId to deliveryToken
            activeTokens += key
            audioActivated.complete(key)
        }

        override fun cancelDelivery(reminderId: String, deliveryToken: String?) {
            cancellations += reminderId to deliveryToken
            deliveryToken?.let { activeTokens.remove(reminderId to it) }
        }
    }
}
