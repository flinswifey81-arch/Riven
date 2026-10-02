package com.shai.riven.data.reminder.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shai.riven.data.reminder.ReminderDeliveryMode

class ReminderRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val reason = inputData.getString(INPUT_REASON) ?: "unspecified"
        return try {
            val runtime = ReminderRuntime.from(applicationContext)
            runtime.repository.rescheduleAll(reason)
            val dispatcher = ReminderDeliveryDispatcher.create(applicationContext, runtime)
            runtime.repository.pendingNotificationDeliveries().forEach { pending ->
                dispatcher.recoverPending(pending)
            }
            runtime.repository.ringingDeliveries().forEach { reminder ->
                val token = reminder.deliveryToken ?: return@forEach
                val ringUntilAt = reminder.ringUntilAt ?: 0L
                if (ringUntilAt <= System.currentTimeMillis()) {
                    val delivered = runtime.repository.markRingingAudioStopped(
                        reminder.id,
                        reminder.scheduleRevision,
                        token,
                    ) ?: return@forEach
                    val permissions = ReminderPermissionInspector(applicationContext).snapshot()
                    if (permissions.notificationPermissionGranted &&
                        permissions.notificationsEnabled &&
                        permissions.channelEnabled(ReminderDeliveryMode.NOTIFICATION)
                    ) {
                        runtime.notifier.postReminder(delivered, token)
                    } else {
                        runtime.notifier.cancel(reminder.id)
                    }
                } else {
                    dispatcher.recoverPending(reminder)
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val INPUT_REASON = "reason"
    }
}

object ReminderRecoveryScheduler {
    private const val UNIQUE_WORK_NAME = "riven-reminder-reschedule"

    fun enqueue(context: Context, reason: String) {
        val request = OneTimeWorkRequestBuilder<ReminderRecoveryWorker>()
            .setInputData(workDataOf(ReminderRecoveryWorker.INPUT_REASON to reason.take(80)))
            .addTag("riven-reminder-recovery")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
