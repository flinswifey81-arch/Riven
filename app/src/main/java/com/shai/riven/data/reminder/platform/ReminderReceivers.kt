package com.shai.riven.data.reminder.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderIntents.ACTION_DELIVER) return
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val revision = intent.getLongExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, -1)
        if (revision < 0) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val runtime = ReminderRuntime.from(context)
                ReminderDeliveryDispatcher.create(context, runtime)
                    .dispatchScheduled(reminderId, revision)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val deliveryToken = intent.getStringExtra(ReminderIntents.EXTRA_DELIVERY_TOKEN) ?: return
        val scheduleRevision = intent.getLongExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, -1)
        if (scheduleRevision < 0) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val runtime = ReminderRuntime.from(context)
                // Repository CAS commits first; targeted effects cancel only this token.
                when (intent.action) {
                    ReminderIntents.ACTION_DISMISS ->
                        runtime.repository.dismissDelivery(reminderId, scheduleRevision, deliveryToken)
                    ReminderIntents.ACTION_COMPLETE ->
                        runtime.repository.completeDelivery(reminderId, scheduleRevision, deliveryToken)
                    ReminderIntents.ACTION_SNOOZE -> {
                        val minutes = intent.getIntExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, 10)
                            .coerceIn(1, MAXIMUM_SNOOZE_MINUTES)
                        runtime.repository.snoozeDelivery(
                            reminderId,
                            scheduleRevision,
                            deliveryToken,
                            Duration.ofMinutes(minutes.toLong()),
                        )
                    }
                    else -> null
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val MAXIMUM_SNOOZE_MINUTES = 24 * 60
    }
}

class ReminderRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderRecoveryScheduler.enqueue(context, intent.action ?: "system_broadcast")
    }
}
