package com.shai.riven.data.reminder.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderResetCoordinatorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ReminderDatabase.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(ReminderDatabase.DATABASE_NAME)
    }

    @Test
    fun factoryResetCancelsAlarmAndLeavesNoDurableReminderGhost() {
        assertResetCancelsAlarmAndLeavesNoDurableReminderGhost(
            ReminderResetCoordinator(context),
        )
    }

    @Test
    fun factoryResetCancelsAlarmBeforeAndroidCancelAllApi() {
        assertResetCancelsAlarmAndLeavesNoDurableReminderGhost(
            ReminderResetCoordinator(context, platformSdkInt = 33),
        )
    }

    private fun assertResetCancelsAlarmAndLeavesNoDurableReminderGhost(
        coordinator: ReminderResetCoordinator,
    ) {
        val database = ReminderDatabase.build(context)
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO local_reminders (reminder_id, title, note, feature_key, " +
                    "delivery_mode, sound_kind, custom_sound_uri, requested_local_date_time, " +
                    "time_zone_id, time_zone_policy, requested_trigger_at, scheduled_trigger_at, " +
                    "status, schedule_revision, delivery_token, last_failure_code, " +
                    "last_failure_detail, created_at, updated_at, finished_at) " +
                    "VALUES ('reset-me', 'Reset me', NULL, 'REMINDERS', 'AUDIBLE_ALARM', " +
                    "'SYSTEM_DEFAULT', NULL, '2026-10-04T09:00', 'UTC', 'FIXED_ZONE', " +
                    "1000, 1000, 'SCHEDULED', 1, NULL, NULL, NULL, 1, 1, NULL)",
            )
        } finally {
            database.close()
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderDeliveryReceiver::class.java)
                .setAction(ReminderIntents.ACTION_DELIVER)
                .setData(AndroidReminderScheduler(context).reminderDataUri("reset-me")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.set(AlarmManager.RTC_WAKEUP, 1_000, pendingIntent)
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)

        coordinator.eraseAll()

        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
        val fresh = ReminderDatabase.build(context)
        try {
            val count = fresh.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM local_reminders",
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertEquals(0, count)
        } finally {
            fresh.close()
        }
    }
}
