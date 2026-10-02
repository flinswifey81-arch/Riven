package com.shai.riven.data.reminder.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

data class ReminderPermissionSnapshot(
    val notificationPermissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
)

class ReminderPermissionInspector(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun snapshot(): ReminderPermissionSnapshot = ReminderPermissionSnapshot(
        notificationPermissionGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        notificationsEnabled = notificationManager.areNotificationsEnabled(),
        exactAlarmsAllowed = alarmManager.canScheduleExactAlarms(),
    )

    fun exactAlarmPermissionIntent(): Intent = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}"),
    )

    fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
