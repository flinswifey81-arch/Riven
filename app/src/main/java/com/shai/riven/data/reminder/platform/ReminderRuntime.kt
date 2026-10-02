package com.shai.riven.data.reminder.platform

import android.content.Context
import com.shai.riven.data.reminder.ReminderRepository
import com.shai.riven.data.reminder.ReminderTimePolicy
import com.shai.riven.data.reminder.persistence.ReminderDatabase

class ReminderRuntime private constructor(
    val database: ReminderDatabase,
    val repository: ReminderRepository,
    val notifier: ReminderNotifier,
) {
    companion object {
        @Volatile
        private var instance: ReminderRuntime? = null

        fun from(context: Context): ReminderRuntime = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): ReminderRuntime {
            ReminderNotificationChannels.ensureCreated(context)
            val database = ReminderDatabase.build(context)
            return ReminderRuntime(
                database = database,
                repository = ReminderRepository(
                    dao = database.reminderDao(),
                    scheduler = AndroidReminderScheduler(context),
                    timePolicy = ReminderTimePolicy(),
                ),
                notifier = ReminderNotifier(context),
            )
        }
    }
}
