package com.shai.riven.data.background

import com.shai.riven.data.persistence.dao.MaintenanceDao
import com.shai.riven.data.persistence.model.RepairJobState
import kotlinx.coroutines.CancellationException

sealed interface RepairSweepResult {
    data class Completed(
        val scheduledRepairJobIds: List<String>,
        val schedulingFailureJobIds: List<String>,
        val moreWorkRemaining: Boolean,
    ) : RepairSweepResult

    data class RetryableFailure(val errorCode: String) : RepairSweepResult
}
fun interface RepairSweepOperations {
    fun runSweep(): RepairSweepResult
}

class RepairSweepService(
    private val maintenanceDao: MaintenanceDao,
    private val handlerRegistry: RepairJobHandlerRegistry,
    private val scheduler: RivenBackgroundWorkScheduler,
    private val itemLimit: Int = DEFAULT_REPAIR_SWEEP_LIMIT,
) : RepairSweepOperations {
    init {
        require(itemLimit > 0) { "Repair sweep limit must be positive" }
    }

    override fun runSweep(): RepairSweepResult {
        val supportedTypes = handlerRegistry.supportedTypes.sortedBy { it.name }
        if (supportedTypes.isEmpty()) {
            return RepairSweepResult.Completed(
                scheduledRepairJobIds = emptyList(),
                schedulingFailureJobIds = emptyList(),
                moreWorkRemaining = false,
            )
        }
        val jobs = try {
            maintenanceDao.pendingRepairJobsForTypes(
                pendingState = RepairJobState.PENDING,
                supportedTypes = supportedTypes,
                limit = itemLimit + 1,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RepairSweepResult.RetryableFailure(
                READ_SWEEP_PREFIX + failure::class.java.simpleName.safeCodeFragment(),
            )
        }

        val scheduled = mutableListOf<String>()
        val failed = mutableListOf<String>()
        jobs.take(itemLimit).forEach { job ->
            when (scheduler.enqueueRepairJob(job.id)) {
                is RivenBackgroundScheduleResult.Enqueued -> scheduled += job.id
                is RivenBackgroundScheduleResult.Failure -> failed += job.id
            }
        }
        return RepairSweepResult.Completed(
            scheduledRepairJobIds = scheduled,
            schedulingFailureJobIds = failed,
            moreWorkRemaining = jobs.size > itemLimit,
        )
    }

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { UNKNOWN_ERROR }.take(MAX_CODE_LENGTH)

    private companion object {
        const val READ_SWEEP_PREFIX = "READ_REPAIR_SWEEP_"
        const val UNKNOWN_ERROR = "Exception"
        const val MAX_CODE_LENGTH = 40
    }
}
