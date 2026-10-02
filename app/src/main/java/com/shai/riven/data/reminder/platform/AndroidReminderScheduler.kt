package com.shai.riven.data.reminder.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderFailureCode
import com.shai.riven.data.reminder.ReminderPlatformScheduler
import com.shai.riven.data.reminder.ReminderScheduleRequest
import com.shai.riven.data.reminder.ReminderScheduleResult

class AndroidReminderScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java),
    private val permissions: ReminderPermissionInspector = ReminderPermissionInspector(context),
) : ReminderPlatformScheduler {
    override fun schedule(request: ReminderScheduleRequest): ReminderScheduleResult {
        val permission = permissions.snapshot()
        if (!permission.notificationPermissionGranted) {
            return ReminderScheduleResult.PermissionRequired(
                ReminderFailureCode.NOTIFICATION_PERMISSION_REQUIRED,
                "Notification permission is required before this reminder can be delivered.",
            )
        }
        if (!permission.notificationsEnabled) {
            return ReminderScheduleResult.PermissionRequired(
                ReminderFailureCode.NOTIFICATIONS_DISABLED,
                "Notifications are disabled for Riven in Android settings.",
            )
        }
        if (request.deliveryMode == ReminderDeliveryMode.AUDIBLE_ALARM &&
            !permission.exactAlarmsAllowed
        ) {
            return ReminderScheduleResult.PermissionRequired(
                ReminderFailureCode.EXACT_ALARM_PERMISSION_REQUIRED,
                "Android has not allowed Riven to schedule exact alarms.",
            )
        }

        return try {
            val pendingIntent = checkNotNull(deliveryPendingIntent(
                reminderId = request.reminderId,
                scheduleRevision = request.scheduleRevision,
                deliveryMode = request.deliveryMode,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            if (request.deliveryMode == ReminderDeliveryMode.AUDIBLE_ALARM) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    request.triggerAtMillis,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    request.triggerAtMillis,
                    pendingIntent,
                )
            }
            ReminderScheduleResult.Scheduled
        } catch (_: SecurityException) {
            ReminderScheduleResult.PermissionRequired(
                if (request.deliveryMode == ReminderDeliveryMode.AUDIBLE_ALARM) {
                    ReminderFailureCode.EXACT_ALARM_PERMISSION_REQUIRED
                } else {
                    ReminderFailureCode.NOTIFICATION_PERMISSION_REQUIRED
                },
                "Android rejected the alarm because the required permission is unavailable.",
            )
        } catch (failure: RuntimeException) {
            ReminderScheduleResult.Failure(
                "AlarmManager failed with ${failure::class.java.simpleName}.",
            )
        }
    }

    override fun cancel(reminderId: String) {
        val pendingIntent = deliveryPendingIntent(
            reminderId = reminderId,
            scheduleRevision = 0,
            deliveryMode = ReminderDeliveryMode.NOTIFICATION,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun deliveryPendingIntent(
        reminderId: String,
        scheduleRevision: Long,
        deliveryMode: ReminderDeliveryMode,
        flags: Int,
    ): PendingIntent? {
        val intent = Intent(context, ReminderDeliveryReceiver::class.java)
            .setAction(ReminderIntents.ACTION_DELIVER)
            .setData(reminderDataUri(reminderId))
            .putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminderId)
            .putExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, scheduleRevision)
            .putExtra(ReminderIntents.EXTRA_DELIVERY_MODE, deliveryMode.name)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    internal fun reminderDataUri(reminderId: String): Uri = Uri.Builder()
        .scheme("riven")
        .authority("reminder")
        .appendPath(reminderId)
        .build()
}

internal object ReminderIntents {
    const val ACTION_DELIVER = "com.shai.riven.reminder.action.DELIVER"
    const val ACTION_DISMISS = "com.shai.riven.reminder.action.DISMISS"
    const val ACTION_COMPLETE = "com.shai.riven.reminder.action.COMPLETE"
    const val ACTION_SNOOZE = "com.shai.riven.reminder.action.SNOOZE"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_SCHEDULE_REVISION = "schedule_revision"
    const val EXTRA_DELIVERY_MODE = "delivery_mode"
    const val EXTRA_DELIVERY_TOKEN = "delivery_token"
    const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
}
