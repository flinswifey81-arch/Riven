package com.shai.riven.data.background

import com.shai.riven.data.automaticmemory.AutomaticMemoryJobOperations
import com.shai.riven.data.automaticmemory.AutomaticMemoryJobRunResult
import com.shai.riven.data.automaticmemory.AutomaticMemorySweepOperations
import com.shai.riven.data.automaticmemory.AutomaticMemorySweepResult

class RivenBackgroundWorkExecutor(
    private val attachmentMaintenance: AttachmentMaintenanceOperations,
    private val repairJobRunner: RepairJobRunOperations,
    private val repairSweep: RepairSweepOperations,
    private val automaticMemoryJobRunner: AutomaticMemoryJobOperations =
        AutomaticMemoryJobOperations { AutomaticMemoryJobRunResult.NoOp(it) },
    private val automaticMemorySweep: AutomaticMemorySweepOperations =
        AutomaticMemorySweepOperations {
            AutomaticMemorySweepResult.Completed(emptyList(), emptyList(), false)
        },
) {
    suspend fun execute(
        kind: RivenBackgroundWorkKind,
        targetId: String?,
    ): RivenBackgroundExecutionOutcome {
        return when (kind) {
        RivenBackgroundWorkKind.ATTACHMENT_CLEANUP -> {
            val target = targetId ?: return RivenBackgroundExecutionOutcome.Failed
            when (attachmentMaintenance.cleanupTarget(target)) {
                is TargetedAttachmentCleanupResult.RetryableFailure ->
                    RivenBackgroundExecutionOutcome.Retryable
                is TargetedAttachmentCleanupResult.Removed,
                is TargetedAttachmentCleanupResult.NoOp,
                -> RivenBackgroundExecutionOutcome.Completed
            }
        }
        RivenBackgroundWorkKind.ATTACHMENT_MAINTENANCE_SWEEP -> {
            if (targetId != null) return RivenBackgroundExecutionOutcome.Failed
            when (val result = attachmentMaintenance.runMaintenance()) {
                is AttachmentMaintenanceResult.RetryableFailure ->
                    RivenBackgroundExecutionOutcome.Retryable
                is AttachmentMaintenanceResult.Completed ->
                    if (result.retryableAttachmentIds.isNotEmpty() || result.moreWorkRemaining) {
                        RivenBackgroundExecutionOutcome.Retryable
                    } else {
                        RivenBackgroundExecutionOutcome.Completed
                    }
            }
        }
        RivenBackgroundWorkKind.REPAIR_JOB -> {
            val target = targetId ?: return RivenBackgroundExecutionOutcome.Failed
            when (repairJobRunner.run(target)) {
                is RepairJobRunResult.RetryableFailure,
                is RepairJobRunResult.StorageFailure,
                is RepairJobRunResult.AlreadyRunning,
                -> RivenBackgroundExecutionOutcome.Retryable
                is RepairJobRunResult.Succeeded,
                is RepairJobRunResult.PermanentlyFailed,
                is RepairJobRunResult.LeaseLost,
                is RepairJobRunResult.NoOp,
                -> RivenBackgroundExecutionOutcome.Completed
            }
        }
        RivenBackgroundWorkKind.REPAIR_SWEEP -> {
            if (targetId != null) return RivenBackgroundExecutionOutcome.Failed
            when (val result = repairSweep.runSweep()) {
                is RepairSweepResult.RetryableFailure -> RivenBackgroundExecutionOutcome.Retryable
                is RepairSweepResult.Completed ->
                    if (result.schedulingFailureJobIds.isNotEmpty() || result.moreWorkRemaining) {
                        RivenBackgroundExecutionOutcome.Retryable
                    } else {
                        RivenBackgroundExecutionOutcome.Completed
                    }
            }
        }
        RivenBackgroundWorkKind.AUTOMATIC_MEMORY_JOB -> {
            val target = targetId ?: return RivenBackgroundExecutionOutcome.Failed
            when (automaticMemoryJobRunner.run(target)) {
                is AutomaticMemoryJobRunResult.RetryableFailure,
                is AutomaticMemoryJobRunResult.AlreadyRunning,
                -> RivenBackgroundExecutionOutcome.Retryable
                is AutomaticMemoryJobRunResult.Succeeded,
                is AutomaticMemoryJobRunResult.Excluded,
                is AutomaticMemoryJobRunResult.PermanentlyFailed,
                is AutomaticMemoryJobRunResult.Blocked,
                is AutomaticMemoryJobRunResult.NoOp,
                is AutomaticMemoryJobRunResult.LeaseLost,
                -> RivenBackgroundExecutionOutcome.Completed
            }
        }
        RivenBackgroundWorkKind.AUTOMATIC_MEMORY_SWEEP -> {
            if (targetId != null) return RivenBackgroundExecutionOutcome.Failed
            when (val result = automaticMemorySweep.runSweep()) {
                is AutomaticMemorySweepResult.RetryableFailure -> RivenBackgroundExecutionOutcome.Retryable
                is AutomaticMemorySweepResult.Completed ->
                    if (result.schedulingFailedJobIds.isNotEmpty() || result.moreWorkRemaining) {
                        RivenBackgroundExecutionOutcome.Retryable
                    } else {
                        RivenBackgroundExecutionOutcome.Completed
                    }
            }
        }
        }
    }
}
