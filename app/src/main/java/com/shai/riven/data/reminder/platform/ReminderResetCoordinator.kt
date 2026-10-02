package com.shai.riven.data.reminder.platform

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.io.File

/** Factory-reset integration for reminder state and every app-owned pending alarm. */
class ReminderResetCoordinator(context: Context) {
    private val appContext = context.applicationContext

    fun eraseAll() {
        appContext.getSystemService(AlarmManager::class.java).cancelAll()
        appContext.getSystemService(NotificationManager::class.java).cancelAll()
        appContext.stopService(Intent(appContext, AlarmRingingService::class.java))

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
