package com.shai.riven.data.background

import com.shai.riven.data.attachment.AttachmentCleanupResult
import com.shai.riven.data.attachment.AttachmentError
import com.shai.riven.data.attachment.AttachmentService
import com.shai.riven.data.persistence.dao.AttachmentDao
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.model.AttachmentState
import kotlinx.coroutines.CancellationException

enum class AttachmentCleanupNoOpReason {
    MISSING,
    FRESH_STAGING,
    AVAILABLE,
    STATE_CHANGED,
    REFERENCED,
    DERIVED_DEPENDENCY,
}

sealed interface TargetedAttachmentCleanupResult {
    data class Removed(val attachmentId: String) : TargetedAttachmentCleanupResult

    data class NoOp(
        val attachmentId: String,
        val reason: AttachmentCleanupNoOpReason,
    ) : TargetedAttachmentCleanupResult

    data class RetryableFailure(
        val attachmentId: String,
        val errorCode: String,
    ) : TargetedAttachmentCleanupResult
}

sealed interface AttachmentMaintenanceResult {
    data class Completed(
        val removedAttachmentIds: List<String>,
        val retryableAttachmentIds: List<String>,
        val skippedAttachmentIds: List<String>,
        val moreWorkRemaining: Boolean,
    ) : AttachmentMaintenanceResult

    data class RetryableFailure(val errorCode: String) : AttachmentMaintenanceResult
}

interface AttachmentMaintenanceOperations {
    suspend fun cleanupTarget(attachmentId: String): TargetedAttachmentCleanupResult

    suspend fun runMaintenance(): AttachmentMaintenanceResult
}

class AttachmentMaintenanceService(
    private val attachmentDao: AttachmentDao,
    private val attachmentService: AttachmentService,
    private val clock: RivenBackgroundClock = SystemRivenBackgroundClock,
    private val staleAfterMs: Long = ATTACHMENT_STAGING_STALE_AFTER_MS,
    private val itemLimit: Int = DEFAULT_ATTACHMENT_MAINTENANCE_LIMIT,
) : AttachmentMaintenanceOperations {
    init {
        require(staleAfterMs > 0) { "Staging age threshold must be positive" }
        require(itemLimit > 0) { "Attachment maintenance limit must be positive" }
    }

    override suspend fun cleanupTarget(attachmentId: String): TargetedAttachmentCleanupResult {
        val attachment = try {
            attachmentDao.attachment(attachmentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return TargetedAttachmentCleanupResult.RetryableFailure(
                attachmentId,
                failure.safeOperationalCode(READ_ATTACHMENT_PREFIX),
            )
        } ?: return TargetedAttachmentCleanupResult.NoOp(
            attachmentId,
            AttachmentCleanupNoOpReason.MISSING,
        )

        return when (attachment.state) {
            AttachmentState.DELETE_PENDING -> cleanupDeletePending(attachment)
            AttachmentState.STAGING -> {
                val now = clock.now()
                if (attachment.updatedAt > now - staleAfterMs) {
                    TargetedAttachmentCleanupResult.NoOp(
                        attachment.id,
                        AttachmentCleanupNoOpReason.FRESH_STAGING,
                    )
                } else {
                    cleanupStaleStaging(attachment, now)
                }
            }
            AttachmentState.AVAILABLE -> TargetedAttachmentCleanupResult.NoOp(
                attachment.id,
                AttachmentCleanupNoOpReason.AVAILABLE,
            )
        }
    }

    override suspend fun runMaintenance(): AttachmentMaintenanceResult {
        val now = clock.now()
        val staleBefore = now - staleAfterMs
        val candidates = try {
            attachmentDao.maintenanceCandidates(
                deletePendingState = AttachmentState.DELETE_PENDING,
                stagingState = AttachmentState.STAGING,
                staleBefore = staleBefore,
                limit = itemLimit + 1,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return AttachmentMaintenanceResult.RetryableFailure(
                failure.safeOperationalCode(READ_SWEEP_PREFIX),
            )
        }

        val removed = mutableListOf<String>()
        val retryable = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        candidates.take(itemLimit).forEach { candidate ->
            // Reload canonical state so a concurrent state/timestamp change wins over the sweep snapshot.
            val result = cleanupTarget(candidate.id)
            when (result) {
                is TargetedAttachmentCleanupResult.Removed -> removed += result.attachmentId
                is TargetedAttachmentCleanupResult.RetryableFailure -> retryable += result.attachmentId
                is TargetedAttachmentCleanupResult.NoOp -> skipped += result.attachmentId
            }
        }
        return AttachmentMaintenanceResult.Completed(
            removedAttachmentIds = removed,
            retryableAttachmentIds = retryable,
            skippedAttachmentIds = skipped,
            moreWorkRemaining = candidates.size > itemLimit,
        )
    }

    private suspend fun cleanupDeletePending(
        attachment: AttachmentEntity,
    ): TargetedAttachmentCleanupResult = classify(
        attachment.id,
        attachmentService.finalizePendingDeletion(attachment.id),
    )

    private suspend fun cleanupStaleStaging(
        attachment: AttachmentEntity,
        now: Long,
    ): TargetedAttachmentCleanupResult = classify(
        attachment.id,
        attachmentService.cleanupStagingAttachment(attachment.id, occurredAt = now),
    )

    private fun classify(
        attachmentId: String,
        result: AttachmentCleanupResult,
    ): TargetedAttachmentCleanupResult = when (result) {
        is AttachmentCleanupResult.Removed -> TargetedAttachmentCleanupResult.Removed(attachmentId)
        is AttachmentCleanupResult.Failure -> when (val error = result.error) {
            is AttachmentError.BlobDeleteFailure -> TargetedAttachmentCleanupResult.RetryableFailure(
                attachmentId,
                BLOB_DELETE_PREFIX + error.causeType.safeCodeFragment(),
            )
            is AttachmentError.StorageFailure -> TargetedAttachmentCleanupResult.RetryableFailure(
                attachmentId,
                STORAGE_PREFIX + error.causeType.safeCodeFragment(),
            )
            is AttachmentError.MissingAttachment -> TargetedAttachmentCleanupResult.NoOp(
                attachmentId,
                AttachmentCleanupNoOpReason.MISSING,
            )
            is AttachmentError.AttachmentUnavailable -> TargetedAttachmentCleanupResult.NoOp(
                attachmentId,
                AttachmentCleanupNoOpReason.STATE_CHANGED,
            )
            is AttachmentError.AttachmentStillReferenced -> TargetedAttachmentCleanupResult.NoOp(
                attachmentId,
                AttachmentCleanupNoOpReason.REFERENCED,
            )
            is AttachmentError.AttachmentHasDerivedDependencies -> TargetedAttachmentCleanupResult.NoOp(
                attachmentId,
                AttachmentCleanupNoOpReason.DERIVED_DEPENDENCY,
            )
            else -> TargetedAttachmentCleanupResult.RetryableFailure(
                attachmentId,
                UNEXPECTED_ATTACHMENT_ERROR,
            )
        }
    }

    private fun Exception.safeOperationalCode(prefix: String): String =
        prefix + this::class.java.simpleName.safeCodeFragment()

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { UNKNOWN_ERROR }.take(MAX_CODE_FRAGMENT_LENGTH)

    private companion object {
        const val READ_ATTACHMENT_PREFIX = "READ_ATTACHMENT_"
        const val READ_SWEEP_PREFIX = "READ_ATTACHMENT_SWEEP_"
        const val BLOB_DELETE_PREFIX = "BLOB_DELETE_"
        const val STORAGE_PREFIX = "ATTACHMENT_STORAGE_"
        const val UNEXPECTED_ATTACHMENT_ERROR = "UNEXPECTED_ATTACHMENT_ERROR"
        const val UNKNOWN_ERROR = "Unknown"
        const val MAX_CODE_FRAGMENT_LENGTH = 48
    }
}
