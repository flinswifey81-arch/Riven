package com.shai.riven.data.reminder.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.shai.riven.MainActivity
import com.shai.riven.R
import com.shai.riven.data.reminder.ReminderSnapshot

object ReminderNotificationChannels {
    const val REMINDERS = "riven_reminders"
    const val ALARMS = "riven_audible_alarms"

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val reminderSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(
                REMINDERS,
                "Riven reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Scheduled reminder notifications"
                setSound(reminderSound, audioAttributes)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setBypassDnd(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALARMS,
                "Riven alarms",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Audible alarms with snooze and dismiss controls"
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setBypassDnd(false)
            },
        )
    }
}

class ReminderNotifier(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun postReminder(reminder: ReminderSnapshot, deliveryToken: String): Boolean = try {
        notificationManager.notify(
            notificationId(reminder.id),
            buildNotification(
                reminderId = reminder.id,
                title = reminder.title,
                note = reminder.note,
                scheduleRevision = reminder.scheduleRevision,
                deliveryToken = deliveryToken,
                ringing = false,
            ),
        )
        true
    } catch (_: SecurityException) {
        false
    }

    fun alarmNotification(reminder: ReminderSnapshot, deliveryToken: String): Notification =
        alarmNotification(
            reminderId = reminder.id,
            title = reminder.title,
            note = reminder.note,
            scheduleRevision = reminder.scheduleRevision,
            deliveryToken = deliveryToken,
        )

    fun alarmNotification(
        reminderId: String,
        title: String,
        note: String?,
        scheduleRevision: Long,
        deliveryToken: String,
    ): Notification = buildNotification(
        reminderId = reminderId,
        title = title,
        note = note,
        scheduleRevision = scheduleRevision,
        deliveryToken = deliveryToken,
        ringing = true,
    )

    fun cancel(reminderId: String) {
        notificationManager.cancel(notificationId(reminderId))
    }

    fun notificationId(reminderId: String): Int = reminderId.hashCode() and 0x7fffffff

    private fun buildNotification(
        reminderId: String,
        title: String,
        note: String?,
        scheduleRevision: Long,
        deliveryToken: String,
        ringing: Boolean,
    ): Notification {
        val dismiss = actionPendingIntent(
            reminderId,
            scheduleRevision,
            deliveryToken,
            ReminderIntents.ACTION_DISMISS,
        )
        val complete = actionPendingIntent(
            reminderId,
            scheduleRevision,
            deliveryToken,
            ReminderIntents.ACTION_COMPLETE,
        )
        val snooze = actionPendingIntent(
            reminderId,
            scheduleRevision,
            deliveryToken,
            ReminderIntents.ACTION_SNOOZE,
            snoozeMinutes = DEFAULT_SNOOZE_MINUTES,
        )
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("riven://reminders/$reminderId")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(
            context,
            if (ringing) ReminderNotificationChannels.ALARMS else ReminderNotificationChannels.REMINDERS,
        )
            .setSmallIcon(R.drawable.ic_stat_riven_alarm)
            .setContentTitle(title)
            .setContentText(note ?: if (ringing) "Riven alarm" else "Riven reminder")
            .setContentIntent(openApp)
            .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (ringing) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(!ringing)
            .setOngoing(ringing)
            .setOnlyAlertOnce(ringing)
            .setSilent(ringing)
            .setDeleteIntent(dismiss)
            .addAction(0, "Snooze 10m", snooze)
            .addAction(0, "Complete", complete)
            .addAction(0, "Dismiss", dismiss)
            .build()
    }

    private fun actionPendingIntent(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        action: String,
        snoozeMinutes: Int? = null,
    ): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData(
                Uri.Builder()
                    .scheme("riven")
                    .authority("reminder-action")
                    .appendPath(reminderId)
                    .appendPath(action.substringAfterLast('.').lowercase())
                    .build(),
            )
            .putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminderId)
            .putExtra(ReminderIntents.EXTRA_DELIVERY_TOKEN, deliveryToken)
            .putExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, scheduleRevision)
        snoozeMinutes?.let { intent.putExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, it) }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10
    }
}
