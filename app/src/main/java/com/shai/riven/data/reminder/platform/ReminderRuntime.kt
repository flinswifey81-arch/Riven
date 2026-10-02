package com.shai.riven.data.reminder.platform

import android.content.Context
import com.shai.riven.data.reminder.ReminderRepository
import com.shai.riven.data.reminder.ReminderDeliveryEffects
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
            val notifier = ReminderNotifier(context)
            return ReminderRuntime(
                database = database,
                repository = ReminderRepository(
                    dao = database.reminderDao(),
                    scheduler = AndroidReminderScheduler(context),
                    deliveryEffects = AndroidReminderDeliveryEffects(notifier),
                    timePolicy = ReminderTimePolicy(),
                ),
                notifier = notifier,
            )
        }
    }
}

private class AndroidReminderDeliveryEffects(
    private val notifier: ReminderNotifier,
) : ReminderDeliveryEffects {
    override fun cancelDelivery(reminderId: String, deliveryToken: String?) {
        notifier.cancel(reminderId)
        deliveryToken?.let { AlarmPlaybackControl.stop(reminderId, it) }
    }
}
