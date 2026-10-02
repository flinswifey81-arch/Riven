package com.shai.riven.data.background

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class WorkManagerRivenBackgroundWorkScheduler(
    private val workManager: WorkManager,
) : RivenBackgroundWorkScheduler {
    override fun enqueueAttachmentCleanup(attachmentId: String): RivenBackgroundScheduleResult =
        enqueueTargeted(
            kind = RivenBackgroundWorkKind.ATTACHMENT_CLEANUP,
            targetId = attachmentId,
            uniqueWorkName = RivenBackgroundWorkNames::attachmentCleanup,
        )

    override fun enqueueAttachmentMaintenanceSweep(): RivenBackgroundScheduleResult =
        enqueueSweep(
            kind = RivenBackgroundWorkKind.ATTACHMENT_MAINTENANCE_SWEEP,
            uniqueWorkName = RivenBackgroundWorkNames.ATTACHMENT_MAINTENANCE,
        )

    override fun enqueueRepairJob(repairJobId: String): RivenBackgroundScheduleResult =
        enqueueTargeted(
            kind = RivenBackgroundWorkKind.REPAIR_JOB,
            targetId = repairJobId,
            uniqueWorkName = RivenBackgroundWorkNames::repairJob,
        )

    override fun enqueueRepairSweep(): RivenBackgroundScheduleResult =
        enqueueSweep(
            kind = RivenBackgroundWorkKind.REPAIR_SWEEP,
            uniqueWorkName = RivenBackgroundWorkNames.REPAIR_SWEEP,
        )

    override fun enqueueAutomaticMemoryJob(
        automaticMemoryJobId: String,
    ): RivenBackgroundScheduleResult = enqueueTargeted(
        kind = RivenBackgroundWorkKind.AUTOMATIC_MEMORY_JOB,
        targetId = automaticMemoryJobId,
        uniqueWorkName = RivenBackgroundWorkNames::automaticMemoryJob,
    )

    override fun enqueueAutomaticMemorySweep(): RivenBackgroundScheduleResult =
        enqueueSweep(
            kind = RivenBackgroundWorkKind.AUTOMATIC_MEMORY_SWEEP,
            uniqueWorkName = RivenBackgroundWorkNames.AUTOMATIC_MEMORY_SWEEP,
        )

    override fun enqueueAutomaticMemoryShortWindowSweep(
        initialDelayMs: Long,
    ): RivenBackgroundScheduleResult = try {
        if (initialDelayMs < 0L) {
            throw InvalidBackgroundWorkRequest(
                RivenBackgroundScheduleError.InvalidWorkRequest(
                    RivenBackgroundWorkKind.AUTOMATIC_MEMORY_SWEEP,
                    NEGATIVE_DELAY_REASON,
                ),
            )
        }
        workManager.enqueueUniqueWork(
            RivenBackgroundWorkNames.AUTOMATIC_MEMORY_SHORT_WINDOW,
            ExistingWorkPolicy.REPLACE,
            createOneTimeRequest(
                kind = RivenBackgroundWorkKind.AUTOMATIC_MEMORY_SWEEP,
                initialDelayMs = initialDelayMs,
            ),
        )
        RivenBackgroundScheduleResult.Enqueued(
            listOf(RivenBackgroundWorkNames.AUTOMATIC_MEMORY_SHORT_WINDOW),
        )
    } catch (invalid: InvalidBackgroundWorkRequest) {
        RivenBackgroundScheduleResult.Failure(invalid.error)
    } catch (failure: Exception) {
        RivenBackgroundScheduleResult.Failure(
            RivenBackgroundScheduleError.SchedulerFailure(
                operation = ENQUEUE_OPERATION,
                causeType = failure::class.java.simpleName,
            ),
        )
    }

    override fun enqueueMemoryLifecycleSweep(): RivenBackgroundScheduleResult =
        enqueueSweep(
            kind = RivenBackgroundWorkKind.MEMORY_LIFECYCLE_SWEEP,
            uniqueWorkName = RivenBackgroundWorkNames.MEMORY_LIFECYCLE_SWEEP,
        )

    override fun ensurePeriodicMaintenance(): RivenBackgroundScheduleResult = try {
        val requests = listOf(
            RivenBackgroundWorkNames.PERIODIC_ATTACHMENT_MAINTENANCE to
                createPeriodicRequest(RivenBackgroundWorkKind.ATTACHMENT_MAINTENANCE_SWEEP),
            RivenBackgroundWorkNames.PERIODIC_REPAIR_SWEEP to
                createPeriodicRequest(RivenBackgroundWorkKind.REPAIR_SWEEP),
            RivenBackgroundWorkNames.PERIODIC_AUTOMATIC_MEMORY_SWEEP to
                createPeriodicRequest(RivenBackgroundWorkKind.AUTOMATIC_MEMORY_SWEEP),
            RivenBackgroundWorkNames.PERIODIC_MEMORY_LIFECYCLE_SWEEP to
                createPeriodicRequest(RivenBackgroundWorkKind.MEMORY_LIFECYCLE_SWEEP),
        )
        requests.forEach { (name, request) ->
            workManager.enqueueUniquePeriodicWork(
                name,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
        RivenBackgroundScheduleResult.Enqueued(requests.map { it.first })
    } catch (failure: Exception) {
        RivenBackgroundScheduleResult.Failure(
            RivenBackgroundScheduleError.SchedulerFailure(
                operation = ENSURE_PERIODIC_OPERATION,
                causeType = failure::class.java.simpleName,
            ),
        )
    }

    internal fun createOneTimeRequest(
        kind: RivenBackgroundWorkKind,
        targetId: String? = null,
        initialDelayMs: Long = 0L,
    ): OneTimeWorkRequest {
        validateRequest(kind, targetId)?.let { throw InvalidBackgroundWorkRequest(it) }
        val data = if (targetId == null) {
            workDataOf(RivenBackgroundWorkData.KIND to kind.name)
        } else {
            workDataOf(
                RivenBackgroundWorkData.KIND to kind.name,
                RivenBackgroundWorkData.TARGET_ID to targetId,
            )
        }
        return OneTimeWorkRequestBuilder<RivenBackgroundWorker>()
            .setInputData(data)
            .apply {
                if (initialDelayMs > 0L) {
                    setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                }
            }
            .apply {
                if (kind == RivenBackgroundWorkKind.AUTOMATIC_MEMORY_JOB) {
                    setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                }
            }
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RIVEN_WORK_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .apply { addStableTags(kind) }
            .build()
    }

    internal fun uniqueNameFor(
        kind: RivenBackgroundWorkKind,
        targetId: String? = null,
    ): String = when (kind) {
        RivenBackgroundWorkKind.ATTACHMENT_CLEANUP ->
            RivenBackgroundWorkNames.attachmentCleanup(checkNotNull(targetId))
        RivenBackgroundWorkKind.ATTACHMENT_MAINTENANCE_SWEEP ->
            RivenBackgroundWorkNames.ATTACHMENT_MAINTENANCE
        RivenBackgroundWorkKind.REPAIR_JOB ->
            RivenBackgroundWorkNames.repairJob(checkNotNull(targetId))
        RivenBackgroundWorkKind.REPAIR_SWEEP -> RivenBackgroundWorkNames.REPAIR_SWEEP
        RivenBackgroundWorkKind.AUTOMATIC_MEMORY_JOB ->
            RivenBackgroundWorkNames.automaticMemoryJob(checkNotNull(targetId))
        RivenBackgroundWorkKind.AUTOMATIC_MEMORY_SWEEP ->
            RivenBackgroundWorkNames.AUTOMATIC_MEMORY_SWEEP
        RivenBackgroundWorkKind.MEMORY_LIFECYCLE_SWEEP ->
            RivenBackgroundWorkNames.MEMORY_LIFECYCLE_SWEEP
    }

    private fun enqueueTargeted(
        kind: RivenBackgroundWorkKind,
        targetId: String,
        uniqueWorkName: (String) -> String,
    ): RivenBackgroundScheduleResult {
        validateTargetId(targetId)?.let { error ->
            return RivenBackgroundScheduleResult.Failure(error)
        }
        return enqueueOneTime(kind, targetId, uniqueWorkName(targetId))
    }

    private fun enqueueSweep(
        kind: RivenBackgroundWorkKind,
        uniqueWorkName: String,
    ): RivenBackgroundScheduleResult = enqueueOneTime(kind, null, uniqueWorkName)

    private fun enqueueOneTime(
        kind: RivenBackgroundWorkKind,
        targetId: String?,
        uniqueWorkName: String,
    ): RivenBackgroundScheduleResult = try {
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            createOneTimeRequest(kind, targetId),
        )
        RivenBackgroundScheduleResult.Enqueued(listOf(uniqueWorkName))
    } catch (invalid: InvalidBackgroundWorkRequest) {
        RivenBackgroundScheduleResult.Failure(invalid.error)
    } catch (failure: Exception) {
        RivenBackgroundScheduleResult.Failure(
            RivenBackgroundScheduleError.SchedulerFailure(
                operation = ENQUEUE_OPERATION,
                causeType = failure::class.java.simpleName,
            ),
        )
    }

    private fun createPeriodicRequest(kind: RivenBackgroundWorkKind): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<RivenBackgroundWorker>(
            RIVEN_PERIODIC_MAINTENANCE_HOURS,
            TimeUnit.HOURS,
        )
            .setInputData(workDataOf(RivenBackgroundWorkData.KIND to kind.name))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RIVEN_WORK_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .apply { addStableTags(kind) }
            .build()

    private fun androidx.work.WorkRequest.Builder<*, *>.addStableTags(
        kind: RivenBackgroundWorkKind,
    ) {
        addTag(RivenBackgroundWorkTags.BACKGROUND)
        addTag(
            when (kind) {
                RivenBackgroundWorkKind.ATTACHMENT_CLEANUP,
                RivenBackgroundWorkKind.ATTACHMENT_MAINTENANCE_SWEEP,
                -> RivenBackgroundWorkTags.ATTACHMENT
                RivenBackgroundWorkKind.REPAIR_JOB,
                RivenBackgroundWorkKind.REPAIR_SWEEP,
                -> RivenBackgroundWorkTags.REPAIR
                RivenBackgroundWorkKind.AUTOMATIC_MEMORY_JOB,
                RivenBackgroundWorkKind.AUTOMATIC_MEMORY_SWEEP,
                -> RivenBackgroundWorkTags.AUTOMATIC_MEMORY
                RivenBackgroundWorkKind.MEMORY_LIFECYCLE_SWEEP ->
                    RivenBackgroundWorkTags.AUTOMATIC_MEMORY
            },
        )
    }

    private fun validateRequest(
        kind: RivenBackgroundWorkKind,
        targetId: String?,
    ): RivenBackgroundScheduleError? = when {
        kind.requiresTargetId && targetId == null ->
            RivenBackgroundScheduleError.InvalidWorkRequest(kind, MISSING_TARGET_REASON)
        !kind.requiresTargetId && targetId != null ->
            RivenBackgroundScheduleError.InvalidWorkRequest(kind, UNEXPECTED_TARGET_REASON)
        targetId != null -> validateTargetId(targetId)
        else -> null
    }

    private fun validateTargetId(targetId: String): RivenBackgroundScheduleError? =
        if (targetId.isBlank() || targetId != targetId.trim()) {
            RivenBackgroundScheduleError.InvalidTargetId
        } else {
            null
        }

    private class InvalidBackgroundWorkRequest(
        val error: RivenBackgroundScheduleError,
    ) : IllegalArgumentException()

    private companion object {
        const val ENQUEUE_OPERATION = "ENQUEUE_UNIQUE_WORK"
        const val ENSURE_PERIODIC_OPERATION = "ENSURE_PERIODIC_WORK"
        const val MISSING_TARGET_REASON = "MISSING_TARGET"
        const val UNEXPECTED_TARGET_REASON = "UNEXPECTED_TARGET"
        const val NEGATIVE_DELAY_REASON = "NEGATIVE_INITIAL_DELAY"
    }
}
