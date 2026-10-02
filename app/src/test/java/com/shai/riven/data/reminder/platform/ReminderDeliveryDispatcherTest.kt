package com.shai.riven.data.reminder.platform

import android.app.Notification
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderDeliveryClaim
import com.shai.riven.data.reminder.ReminderDraft
import com.shai.riven.data.reminder.ReminderEventKind
import com.shai.riven.data.reminder.ReminderFailureCode
import com.shai.riven.data.reminder.ReminderOperationResult
import com.shai.riven.data.reminder.ReminderPlatformScheduler
import com.shai.riven.data.reminder.ReminderRepository
import com.shai.riven.data.reminder.ReminderScheduleRequest
import com.shai.riven.data.reminder.ReminderScheduleResult
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderStatus
import com.shai.riven.data.reminder.ReminderTimePolicy
import com.shai.riven.data.reminder.ReminderTimeZonePolicy
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderDeliveryDispatcherTest {
    private lateinit var context: Context
    private lateinit var database: ReminderDatabase
    private lateinit var repository: ReminderRepository
    private lateinit var sink: RecordingNotificationSink
    private lateinit var permissions: MutablePermissionSource
    private lateinit var scheduler: RecordingScheduler
    private var idCounter = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = RecordingScheduler()
        repository = ReminderRepository(
            dao = database.reminderDao(),
            scheduler = scheduler,
            timePolicy = ReminderTimePolicy(
                Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC),
            ) { ZoneOffset.UTC },
            newId = { "dispatcher-${++idCounter}" },
        )
        sink = RecordingNotificationSink()
        permissions = MutablePermissionSource(allAllowed())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deathAfterClaimLeavesDurableOutboxForIdempotentRecovery() = runBlocking {
        val created = createNotification("after claim")
        val interrupted = dispatcher().apply {
            hooks = ReminderDeliveryDispatchHooks(afterClaim = { throw SimulatedDeath() })
        }

        assertSimulatedDeath {
            interrupted.dispatchScheduled(created.id, created.scheduleRevision)
        }
        val pending = repository.pendingNotificationDeliveries().single()
        assertEquals(ReminderStatus.DELIVERING, pending.status)
        assertEquals(0, sink.reminderPosts)

        dispatcher().recoverPending(pending)

        assertEquals(1, sink.reminderPosts)
        assertEquals(ReminderStatus.DELIVERED, current(created.id).status)
    }

    @Test
    fun deathAfterNotifyBeforeAckRepostsSameOutboxAndAcknowledges() = runBlocking {
        val created = createNotification("after notify")
        val interrupted = dispatcher().apply {
            hooks = ReminderDeliveryDispatchHooks(
                afterNotificationPostedBeforeAck = { throw SimulatedDeath() },
            )
        }

        assertSimulatedDeath {
            interrupted.dispatchScheduled(created.id, created.scheduleRevision)
        }
        val pending = repository.pendingNotificationDeliveries().single()
        assertEquals(1, sink.reminderPosts)
        assertEquals(ReminderStatus.DELIVERING, pending.status)

        dispatcher().recoverPending(pending)

        assertEquals(2, sink.reminderPosts)
        assertEquals(listOf(created.id, created.id), sink.postedIds)
        assertEquals(ReminderStatus.DELIVERED, current(created.id).status)
    }

    @Test
    fun channelRevocationAfterSchedulingFailsTruthfullyAtDispatch() = runBlocking {
        val created = createNotification("revoked channel")
        permissions.current = allAllowed().copy(reminderChannelEnabled = false)

        dispatcher().dispatchScheduled(created.id, created.scheduleRevision)

        val failed = current(created.id)
        assertEquals(ReminderStatus.FAILED, failed.status)
        assertEquals(ReminderFailureCode.NOTIFICATIONS_DISABLED, failed.lastFailureCode)
        assertEquals(0, sink.reminderPosts)
    }

    @Test
    fun notificationUsesStableIdAndOnlyAlertsOnceAcrossOutboxReplay() = runBlocking {
        val reminder = createNotification("only once")
        val notifier = ReminderNotifier(context)
        val notification = notifier.reminderNotification(reminder, "stable-token")

        assertEquals(notifier.notificationId(reminder.id), notifier.notificationId(reminder.id))
        assertNotEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun deathDuringAlarmTimeoutHandoffRecoversDurableNotificationOutbox() = runBlocking {
        val created = createAlarm("timeout handoff")
        val claim = repository.claimDelivery(
            created.id,
            created.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed
        val delivering = checkNotNull(repository.prepareRingingTimeoutNotification(
            created.id,
            created.scheduleRevision,
            claim.deliveryToken,
        ))
        val interrupted = dispatcher().apply {
            hooks = ReminderDeliveryDispatchHooks(
                afterNotificationPostedBeforeAck = { throw SimulatedDeath() },
            )
        }

        assertEquals(ReminderStatus.DELIVERING, delivering.status)
        assertSimulatedDeath { interrupted.recoverPending(delivering) }
        assertEquals(ReminderStatus.DELIVERING, current(created.id).status)
        assertEquals(1, sink.reminderPosts)

        dispatcher().recoverPending(repository.pendingNotificationDeliveries().single())

        assertEquals(2, sink.reminderPosts)
        assertEquals(ReminderStatus.DELIVERED, current(created.id).status)
    }

    @Test
    fun failedAlarmTimeoutNotificationIsTerminalAndNeverReschedulesAudibleAlarm() = runBlocking {
        val created = createAlarm("failed timeout handoff")
        val claim = repository.claimDelivery(
            created.id,
            created.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed
        val delivering = checkNotNull(repository.prepareRingingTimeoutNotification(
            created.id,
            created.scheduleRevision,
            claim.deliveryToken,
        ))
        sink.reminderPostSucceeds = false

        dispatcher().recoverPending(delivering)
        val recoveryReport = repository.rescheduleAll("application_startup")
        val staleClaim = repository.claimDelivery(created.id, created.scheduleRevision)

        assertEquals(ReminderStatus.DELIVERED, current(created.id).status)
        assertEquals(ReminderFailureCode.NOTIFICATION_PERMISSION_REQUIRED, current(created.id).lastFailureCode)
        assertEquals(1, sink.reminderPosts)
        assertEquals(0, recoveryReport.scheduled)
        assertEquals(1, scheduler.requests.size)
        assertEquals(ReminderDeliveryClaim.IgnoredDuplicateOrStale, staleClaim)
        assertEquals(
            1,
            database.reminderDao().eventCount(
                created.id,
                ReminderEventKind.DELIVERY_CLAIMED.name,
            ),
        )
        assertEquals(
            1,
            database.reminderDao().eventCount(
                created.id,
                ReminderEventKind.FOLLOWUP_NOTIFICATION_FAILED.name,
            ),
        )
    }

    @Test
    fun startupRecoveryDoesNotRetryOrReRingTerminalTimeoutFollowupFailure() = runBlocking {
        val created = createAlarm("bounded timeout recovery")
        val claim = repository.claimDelivery(
            created.id,
            created.scheduleRevision,
        ) as ReminderDeliveryClaim.Claimed
        val delivering = checkNotNull(repository.prepareRingingTimeoutNotification(
            created.id,
            created.scheduleRevision,
            claim.deliveryToken,
        ))
        sink.reminderPostSucceeds = false
        val dispatcher = dispatcher()
        dispatcher.recoverPending(delivering)
        val postsBeforeStartup = sink.reminderPosts

        ReminderRecoveryCoordinator(
            repository = repository,
            dispatcher = dispatcher,
            nowMillis = { Instant.parse("2026-10-03T12:00:00Z").toEpochMilli() },
        ).recover("application_startup")

        assertEquals(ReminderStatus.DELIVERED, current(created.id).status)
        assertTrue(repository.pendingNotificationDeliveries().isEmpty())
        assertTrue(repository.ringingDeliveries().isEmpty())
        assertEquals(postsBeforeStartup, sink.reminderPosts)
        assertEquals(1, scheduler.requests.size)
        assertEquals(
            1,
            database.reminderDao().eventCount(
                created.id,
                ReminderEventKind.DELIVERY_CLAIMED.name,
            ),
        )
    }

    private suspend fun createNotification(title: String): ReminderSnapshot =
        (repository.create(
            ReminderDraft(
                title = title,
                localDateTime = LocalDateTime.parse("2026-10-04T09:00:00"),
                zoneId = ZoneOffset.UTC,
                timeZonePolicy = ReminderTimeZonePolicy.FIXED_ZONE,
                deliveryMode = ReminderDeliveryMode.NOTIFICATION,
            ),
        ) as ReminderOperationResult.Success).reminder

    private suspend fun createAlarm(title: String): ReminderSnapshot =
        (repository.create(
            ReminderDraft(
                title = title,
                localDateTime = LocalDateTime.parse("2026-10-04T09:00:00"),
                zoneId = ZoneOffset.UTC,
                timeZonePolicy = ReminderTimeZonePolicy.FIXED_ZONE,
                deliveryMode = ReminderDeliveryMode.AUDIBLE_ALARM,
            ),
        ) as ReminderOperationResult.Success).reminder

    private suspend fun current(id: String): ReminderSnapshot =
        repository.observeReminders().first().single { it.id == id }

    private fun dispatcher() = ReminderDeliveryDispatcher(
        repository = repository,
        notifier = sink,
        permissions = permissions,
        startAlarmService = { _, _ -> error("not an alarm") },
        stopAlarmSession = { _, _ -> },
    )

    private suspend fun assertSimulatedDeath(block: suspend () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: SimulatedDeath) {
            threw = true
        }
        assertTrue(threw)
    }

    private class SimulatedDeath : RuntimeException()

    private class RecordingNotificationSink : ReminderNotificationSink {
        var reminderPosts = 0
        var reminderPostSucceeds = true
        val postedIds = mutableListOf<String>()

        override fun postReminder(reminder: ReminderSnapshot, deliveryToken: String): Boolean {
            reminderPosts++
            postedIds += reminder.id
            return reminderPostSucceeds
        }

        override fun postAlarm(reminder: ReminderSnapshot, deliveryToken: String): Boolean = true

        override fun cancel(reminderId: String) = Unit
    }

    private class MutablePermissionSource(
        var current: ReminderPermissionSnapshot,
    ) : ReminderPermissionSource {
        override fun snapshot(): ReminderPermissionSnapshot = current
    }

    private class RecordingScheduler : ReminderPlatformScheduler {
        val requests = mutableListOf<ReminderScheduleRequest>()

        override fun schedule(request: ReminderScheduleRequest): ReminderScheduleResult =
            ReminderScheduleResult.Scheduled.also { requests += request }

        override fun cancel(reminderId: String) = Unit
    }

    private fun allAllowed() = ReminderPermissionSnapshot(
        notificationPermissionGranted = true,
        notificationsEnabled = true,
        exactAlarmsAllowed = true,
        reminderChannelEnabled = true,
        alarmChannelEnabled = true,
    )
}
