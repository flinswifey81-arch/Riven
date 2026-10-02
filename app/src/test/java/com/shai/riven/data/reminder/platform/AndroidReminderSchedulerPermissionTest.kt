package com.shai.riven.data.reminder.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderFailureCode
import com.shai.riven.data.reminder.ReminderScheduleRequest
import com.shai.riven.data.reminder.ReminderScheduleResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidReminderSchedulerPermissionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun enabledGloballyButReminderChannelBlockedRejectsNotificationSchedule() {
        val scheduler = AndroidReminderScheduler(
            context = context,
            permissions = FixedPermissions(
                allowed().copy(reminderChannelEnabled = false),
            ),
        )

        val result = scheduler.schedule(request(ReminderDeliveryMode.NOTIFICATION))

        assertEquals(
            ReminderScheduleResult.PermissionRequired(
                ReminderFailureCode.NOTIFICATIONS_DISABLED,
                "The Riven reminders notification channel is disabled in Android settings.",
            ),
            result,
        )
    }

    @Test
    fun enabledGloballyButAlarmChannelBlockedRejectsAudibleSchedule() {
        val scheduler = AndroidReminderScheduler(
            context = context,
            permissions = FixedPermissions(
                allowed().copy(alarmChannelEnabled = false),
            ),
        )

        val result = scheduler.schedule(request(ReminderDeliveryMode.AUDIBLE_ALARM))

        assertEquals(
            ReminderScheduleResult.PermissionRequired(
                ReminderFailureCode.NOTIFICATIONS_DISABLED,
                "The Riven alarms notification channel is disabled in Android settings.",
            ),
            result,
        )
    }

    private fun request(mode: ReminderDeliveryMode) = ReminderScheduleRequest(
        reminderId = "channel-test",
        scheduleRevision = 1,
        triggerAtMillis = 1_000,
        deliveryMode = mode,
    )

    private fun allowed() = ReminderPermissionSnapshot(
        notificationPermissionGranted = true,
        notificationsEnabled = true,
        exactAlarmsAllowed = true,
        reminderChannelEnabled = true,
        alarmChannelEnabled = true,
    )

    private class FixedPermissions(
        private val snapshot: ReminderPermissionSnapshot,
    ) : ReminderPermissionSource {
        override fun snapshot(): ReminderPermissionSnapshot = snapshot
    }
}
