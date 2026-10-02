package com.shai.riven.data.reminder.platform

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.io.File

/** Factory-reset integration for reminder state and every app-owned pending alarm. */
class ReminderResetCoordinator(
    context: Context,
    private val platformSdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val appContext = context.applicationContext

    fun cancelDeliveries() {
        if (platformSdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            appContext.getSystemService(AlarmManager::class.java).cancelAll()
        } else {
            reminderIds().forEach(AndroidReminderScheduler(appContext)::cancel)
        }
        appContext.getSystemService(NotificationManager::class.java).cancelAll()
        appContext.stopService(Intent(appContext, AlarmRingingService::class.java))
    }

    private fun reminderIds(): List<String> {
        val database = appContext.getDatabasePath(ReminderDatabase.DATABASE_NAME)
        if (!database.isFile) return emptyList()
        return SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            sqlite.rawQuery("SELECT reminder_id FROM local_reminders", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }
    }

    fun eraseAll() {
        cancelDeliveries()

        val database = appContext.getDatabasePath(ReminderDatabase.DATABASE_NAME)
        val sidecars = listOf(
            database,
            File(database.parentFile, database.name + "-wal"),
            File(database.parentFile, database.name + "-shm"),
        )
        appContext.deleteDatabase(ReminderDatabase.DATABASE_NAME)
        sidecars.forEach { file ->
            if (file.exists() && !file.delete()) error("Reminder reset file could not be deleted")
        }
    }
}
