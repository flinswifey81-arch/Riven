package com.shai.riven.data.reminder.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.shai.riven.data.reminder.ReminderDeliveryMode

data class ReminderPermissionSnapshot(
    val notificationPermissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
    val reminderChannelEnabled: Boolean,
    val alarmChannelEnabled: Boolean,
) {
    fun channelEnabled(mode: ReminderDeliveryMode): Boolean = when (mode) {
        ReminderDeliveryMode.NOTIFICATION -> reminderChannelEnabled
        ReminderDeliveryMode.AUDIBLE_ALARM -> alarmChannelEnabled
    }
}

interface ReminderPermissionSource {
    fun snapshot(): ReminderPermissionSnapshot
}

class ReminderPermissionInspector(private val context: Context) : ReminderPermissionSource {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun snapshot(): ReminderPermissionSnapshot = ReminderPermissionSnapshot(
        notificationPermissionGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        notificationsEnabled = notificationManager.areNotificationsEnabled(),
        exactAlarmsAllowed = alarmManager.canScheduleExactAlarms(),
        reminderChannelEnabled = channelEnabled(ReminderNotificationChannels.REMINDERS),
        alarmChannelEnabled = channelEnabled(ReminderNotificationChannels.ALARMS),
    )

    private fun channelEnabled(channelId: String): Boolean =
        notificationManager.getNotificationChannel(channelId)?.importance !=
            NotificationManager.IMPORTANCE_NONE

    fun exactAlarmPermissionIntent(): Intent = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}"),
    )

    fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
