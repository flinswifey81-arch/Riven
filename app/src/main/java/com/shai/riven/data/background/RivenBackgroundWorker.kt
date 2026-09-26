package com.shai.riven.data.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class RivenBackgroundWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(RivenBackgroundWorkData.KIND)
            ?.let { encoded ->
                runCatching { RivenBackgroundWorkKind.valueOf(encoded) }.getOrNull()
            }
            ?: return Result.failure()
        val targetId = inputData.getString(RivenBackgroundWorkData.TARGET_ID)
        if (kind.requiresTargetId && (targetId == null || targetId.isBlank() || targetId != targetId.trim())) {
            return Result.failure()
        }
        if (!kind.requiresTargetId && targetId != null) {
            return Result.failure()
        }

        val runtime = try {
            RivenBackgroundWorkRuntime.create(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
        return runtime.use {
            try {
                mapOutcome(runtime.executor.execute(kind, targetId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.retry()
            }
        }
    }

    internal companion object {
        fun mapOutcome(outcome: RivenBackgroundExecutionOutcome): Result = when (outcome) {
            RivenBackgroundExecutionOutcome.Completed -> Result.success()
            RivenBackgroundExecutionOutcome.Retryable -> Result.retry()
            RivenBackgroundExecutionOutcome.Failed -> Result.failure()
        }
    }
}
