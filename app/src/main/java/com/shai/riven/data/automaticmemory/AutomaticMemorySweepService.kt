package com.shai.riven.data.automaticmemory

import com.shai.riven.data.background.DEFAULT_AUTOMATIC_MEMORY_SWEEP_LIMIT
import kotlinx.coroutines.CancellationException

sealed interface AutomaticMemorySweepResult {
    data class Completed(
        val reconciledJobIds: List<String>,
        val schedulingFailedJobIds: List<String>,
        val moreWorkRemaining: Boolean,
    ) : AutomaticMemorySweepResult

    data class RetryableFailure(val errorCode: String) : AutomaticMemorySweepResult
}

fun interface AutomaticMemorySweepOperations {
    suspend fun runSweep(): AutomaticMemorySweepResult
}

class AutomaticMemorySweepService(
    private val queue: AutomaticMemoryQueueService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = DEFAULT_AUTOMATIC_MEMORY_SWEEP_LIMIT,
) : AutomaticMemorySweepOperations {
    init {
        require(limit > 0)
    }

    override suspend fun runSweep(): AutomaticMemorySweepResult = try {
        val reconciled = queue.reconcileSucceededRuns(limit, clock())
        val shortWindow = queue.requeueDueShortWindow(clock(), limit)
        val schedulingFailures = linkedSetOf<String>().apply {
            addAll(reconciled.schedulingFailedJobIds)
            addAll(shortWindow.schedulingFailedJobIds)
            addAll(queue.schedulePending(limit))
        }
        val shortWindowSchedule = queue.reconcileShortWindowSchedule(clock())
        val shortWindowScheduleFailed =
            shortWindowSchedule is com.shai.riven.data.background.RivenBackgroundScheduleResult.Failure
        AutomaticMemorySweepResult.Completed(
            reconciledJobIds = reconciled.jobIds,
            schedulingFailedJobIds = schedulingFailures.toList(),
            moreWorkRemaining = reconciled.truncated ||
                shortWindow.moreWorkRemaining ||
                shortWindowScheduleFailed,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AutomaticMemorySweepResult.RetryableFailure(
            "AUTOMATIC_MEMORY_SWEEP_${failure::class.java.simpleName.safeCodeFragment()}",
        )
    }

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "Exception" }.take(40)
}
