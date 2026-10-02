package com.shai.riven.data.reminder.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.shai.riven.data.reminder.ReminderDeliveryClaim
import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderFailureCode
import com.shai.riven.data.reminder.ReminderOperationResult
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
                when (val claim = runtime.repository.claimDelivery(reminderId, revision)) {
                    is ReminderDeliveryClaim.Claimed -> {
                        if (claim.reminder.deliveryMode == ReminderDeliveryMode.AUDIBLE_ALARM) {
                            startRingingService(context, runtime, claim)
                        } else if (!runtime.notifier.postReminder(claim.reminder, claim.deliveryToken)) {
                            runtime.repository.failClaimedDelivery(
                                reminderId = claim.reminder.id,
                                scheduleRevision = claim.reminder.scheduleRevision,
                                deliveryToken = claim.deliveryToken,
                                code = ReminderFailureCode.NOTIFICATION_PERMISSION_REQUIRED,
                                detail = "Android blocked the reminder notification at delivery time.",
                            )
                        }
                    }
                    is ReminderDeliveryClaim.Deferred -> runtime.notifier.cancel(claim.reminder.id)
                    is ReminderDeliveryClaim.Suppressed -> runtime.notifier.cancel(claim.reminder.id)
                    ReminderDeliveryClaim.IgnoredDuplicateOrStale -> Unit
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun startRingingService(
        context: Context,
        runtime: ReminderRuntime,
        claim: ReminderDeliveryClaim.Claimed,
    ) {
        val serviceIntent = AlarmRingingService.intent(
            context = context,
            reminderId = claim.reminder.id,
            scheduleRevision = claim.reminder.scheduleRevision,
            deliveryToken = claim.deliveryToken,
            title = claim.reminder.title,
            note = claim.reminder.note,
        )
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (failure: RuntimeException) {
            runtime.repository.failClaimedDelivery(
                reminderId = claim.reminder.id,
                scheduleRevision = claim.reminder.scheduleRevision,
                deliveryToken = claim.deliveryToken,
                code = ReminderFailureCode.SCHEDULER_FAILURE,
                detail = "Android could not start alarm playback: ${failure::class.java.simpleName}.",
            )
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val deliveryToken = intent.getStringExtra(ReminderIntents.EXTRA_DELIVERY_TOKEN) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val runtime = ReminderRuntime.from(context)
                val result = when (intent.action) {
                    ReminderIntents.ACTION_DISMISS ->
                        runtime.repository.dismissDelivery(reminderId, deliveryToken)
                    ReminderIntents.ACTION_COMPLETE ->
                        runtime.repository.completeDelivery(reminderId, deliveryToken)
                    ReminderIntents.ACTION_SNOOZE -> {
                        val minutes = intent.getIntExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, 10)
                            .coerceIn(1, MAXIMUM_SNOOZE_MINUTES)
                        runtime.repository.snoozeDelivery(
                            reminderId,
                            deliveryToken,
                            Duration.ofMinutes(minutes.toLong()),
                        )
                    }
                    else -> null
                }
                if (result is ReminderOperationResult.Success) {
                    runtime.notifier.cancel(reminderId)
                    context.stopService(Intent(context, AlarmRingingService::class.java))
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
