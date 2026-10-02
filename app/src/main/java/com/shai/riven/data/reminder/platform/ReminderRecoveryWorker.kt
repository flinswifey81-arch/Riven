package com.shai.riven.data.reminder.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.core.content.ContextCompat
import com.shai.riven.data.reminder.ReminderFailureCode

class ReminderRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val reason = inputData.getString(INPUT_REASON) ?: "unspecified"
        return try {
            val runtime = ReminderRuntime.from(applicationContext)
            runtime.repository.rescheduleAll(reason)
            runtime.repository.ringingDeliveries().forEach { reminder ->
                val token = reminder.deliveryToken ?: return@forEach
                try {
                    ContextCompat.startForegroundService(
                        applicationContext,
                        AlarmRingingService.intent(
                            context = applicationContext,
                            reminderId = reminder.id,
                            scheduleRevision = reminder.scheduleRevision,
                            deliveryToken = token,
                            title = reminder.title,
                            note = reminder.note,
                        ),
                    )
                } catch (failure: RuntimeException) {
                    runtime.repository.failClaimedDelivery(
                        reminderId = reminder.id,
                        scheduleRevision = reminder.scheduleRevision,
                        deliveryToken = token,
                        code = ReminderFailureCode.SCHEDULER_FAILURE,
                        detail = "Alarm recovery failed with ${failure::class.java.simpleName}.",
                    )
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
