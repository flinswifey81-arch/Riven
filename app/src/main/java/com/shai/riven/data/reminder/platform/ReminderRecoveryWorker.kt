package com.shai.riven.data.reminder.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shai.riven.data.reminder.ReminderRepository
import com.shai.riven.data.reset.RivenStartupMutationGate

internal class ReminderRecoveryCoordinator(
    private val repository: ReminderRepository,
    private val dispatcher: ReminderDeliveryDispatcher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun recover(reason: String) {
        repository.rescheduleAll(reason)
        repository.pendingNotificationDeliveries().forEach { pending ->
            dispatcher.recoverPending(pending)
        }
        repository.ringingDeliveries().forEach { reminder ->
            val token = reminder.deliveryToken ?: return@forEach
            val ringUntilAt = reminder.ringUntilAt ?: 0L
            if (ringUntilAt <= nowMillis()) {
                val delivering = repository.prepareRingingTimeoutNotification(
                    reminder.id,
                    reminder.scheduleRevision,
                    token,
                ) ?: return@forEach
                dispatcher.recoverPending(delivering)
            } else {
                dispatcher.recoverPending(reminder)
            }
        }
    }
}

class ReminderRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = runRecovery {
        val reason = inputData.getString(INPUT_REASON) ?: "unspecified"
        val runtime = ReminderRuntime.from(applicationContext)
        val dispatcher = ReminderDeliveryDispatcher.create(applicationContext, runtime)
        ReminderRecoveryCoordinator(runtime.repository, dispatcher).recover(reason)
    }

    internal suspend fun runRecovery(recover: suspend () -> Unit): Result {
        if (RivenStartupMutationGate.isPending(applicationContext)) return Result.retry()
        return try {
            recover()
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
