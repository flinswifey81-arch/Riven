package com.shai.riven.data.background

import java.security.MessageDigest

enum class RivenBackgroundWorkKind(val requiresTargetId: Boolean) {
    ATTACHMENT_CLEANUP(requiresTargetId = true),
    ATTACHMENT_MAINTENANCE_SWEEP(requiresTargetId = false),
    REPAIR_JOB(requiresTargetId = true),
    REPAIR_SWEEP(requiresTargetId = false),
}

fun interface RivenBackgroundClock {
    fun now(): Long
}

object SystemRivenBackgroundClock : RivenBackgroundClock {
    override fun now(): Long = System.currentTimeMillis()
}

sealed interface RivenBackgroundScheduleError {
    data object InvalidTargetId : RivenBackgroundScheduleError

    data class InvalidWorkRequest(
        val kind: RivenBackgroundWorkKind,
        val reasonCode: String,
    ) : RivenBackgroundScheduleError

    data class UnsupportedWorkKind(val kind: String) : RivenBackgroundScheduleError

    data class SchedulerFailure(
        val operation: String,
        val causeType: String,
    ) : RivenBackgroundScheduleError
}

sealed interface RivenBackgroundScheduleResult {
    data class Enqueued(val uniqueWorkNames: List<String>) : RivenBackgroundScheduleResult
    data class Failure(val error: RivenBackgroundScheduleError) : RivenBackgroundScheduleResult
}

interface RivenBackgroundWorkScheduler {
    fun enqueueAttachmentCleanup(attachmentId: String): RivenBackgroundScheduleResult

    fun enqueueAttachmentMaintenanceSweep(): RivenBackgroundScheduleResult

    fun enqueueRepairJob(repairJobId: String): RivenBackgroundScheduleResult

    fun enqueueRepairSweep(): RivenBackgroundScheduleResult

    fun ensurePeriodicMaintenance(): RivenBackgroundScheduleResult
}

sealed interface RivenBackgroundExecutionOutcome {
    data object Completed : RivenBackgroundExecutionOutcome
    data object Retryable : RivenBackgroundExecutionOutcome
    data object Failed : RivenBackgroundExecutionOutcome
}

internal object RivenBackgroundWorkData {
    const val KIND = "riven.work.kind"
    const val TARGET_ID = "riven.work.target_id"
}

internal object RivenBackgroundWorkTags {
    const val BACKGROUND = "RIVEN_BACKGROUND"
    const val ATTACHMENT = "RIVEN_ATTACHMENT_WORK"
    const val REPAIR = "RIVEN_REPAIR_WORK"
}

internal object RivenBackgroundWorkNames {
    const val ATTACHMENT_MAINTENANCE = "riven.attachment.maintenance"
    const val REPAIR_SWEEP = "riven.repair.sweep"
    const val PERIODIC_ATTACHMENT_MAINTENANCE = "riven.attachment.maintenance.periodic"
    const val PERIODIC_REPAIR_SWEEP = "riven.repair.sweep.periodic"

    fun attachmentCleanup(attachmentId: String): String =
        "riven.attachment.cleanup.${stableTargetHash(attachmentId)}"

    fun repairJob(repairJobId: String): String =
        "riven.repair.job.${stableTargetHash(repairJobId)}"
}

internal fun stableTargetHash(targetId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(targetId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

const val ATTACHMENT_STAGING_STALE_AFTER_MS = 60L * 60L * 1_000L
const val DEFAULT_ATTACHMENT_MAINTENANCE_LIMIT = 50
const val DEFAULT_REPAIR_SWEEP_LIMIT = 50
const val MAX_REPAIR_ATTEMPTS = 5
const val REPAIR_RUNNING_LEASE_MS = 15L * 60L * 1_000L
const val RIVEN_WORK_BACKOFF_SECONDS = 30L
const val RIVEN_PERIODIC_MAINTENANCE_HOURS = 6L
