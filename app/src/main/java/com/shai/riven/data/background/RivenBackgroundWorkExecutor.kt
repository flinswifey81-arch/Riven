package com.shai.riven.data.background

class RivenBackgroundWorkExecutor(
    private val attachmentMaintenance: AttachmentMaintenanceOperations,
    private val repairJobRunner: RepairJobRunOperations,
    private val repairSweep: RepairSweepOperations,
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
        }
    }
}
