package com.shai.riven.data.automaticmemory

import android.database.sqlite.SQLiteConstraintException
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AutomaticMemoryJobEntity
import com.shai.riven.data.persistence.entity.ConversationRunEntity
import com.shai.riven.data.persistence.model.AutomaticMemoryJobStage
import com.shai.riven.data.persistence.model.AutomaticMemoryJobState
import com.shai.riven.data.persistence.model.ExperienceAvailability
import java.security.MessageDigest

/** Writes exclusion barriers while the caller owns the surrounding Room transaction. */
internal class AutomaticMemoryExclusionWriter(database: RivenDatabase) {
    private val automaticMemoryDao = database.automaticMemoryDao()
    private val memoryDao = database.memoryDao()

    fun excludeRunInCurrentTransaction(
        run: ConversationRunEntity,
        sourceTimelineRevision: Long,
        occurredAt: Long,
        reasonCode: String,
    ): List<AutomaticMemoryJobEntity> = listOf(run.userMessageId, run.assistantMessageId).map { messageId ->
        excludeMessageInCurrentTransaction(
            run = run,
            messageId = messageId,
            sourceTimelineRevision = sourceTimelineRevision,
            occurredAt = occurredAt,
            reasonCode = reasonCode,
        )
    }

    fun excludeMessageInCurrentTransaction(
        run: ConversationRunEntity,
        messageId: String,
        sourceTimelineRevision: Long,
        occurredAt: Long,
        reasonCode: String,
    ): AutomaticMemoryJobEntity =
        automaticMemoryDao.jobForMessage(messageId)?.let { existing ->
            excludeExistingMessageJobInCurrentTransaction(existing, occurredAt, reasonCode)
        } ?: excludedMessageJobInCurrentTransaction(
            run = run,
            messageId = messageId,
            sourceTimelineRevision = sourceTimelineRevision,
            occurredAt = occurredAt,
            reasonCode = reasonCode,
        )

    fun exclusionReasonForMessageInCurrentTransaction(messageId: String): String? =
        automaticMemoryDao.jobForMessage(messageId)
            ?.takeIf { it.state == AutomaticMemoryJobState.EXCLUDED }
            ?.lastErrorCode
            ?.takeIf(String::isNotBlank)

    private fun excludedMessageJobInCurrentTransaction(
        run: ConversationRunEntity,
        messageId: String,
        sourceTimelineRevision: Long,
        occurredAt: Long,
        reasonCode: String,
    ): AutomaticMemoryJobEntity {
        val experiences = memoryDao.canonicalConversationExperiencesForMessage(messageId)
        require(experiences.size == 1) { "Canonical experience is missing or duplicated" }
        val experience = experiences.single()
        require(experience.availability == ExperienceAvailability.AVAILABLE) {
            "Canonical experience is unavailable"
        }
        val job = AutomaticMemoryJobEntity(
            id = automaticMemoryJobId(messageId),
            originatingRunId = run.runId,
            sourceMessageId = messageId,
            sourceExperienceId = experience.id,
            sourceTimelineRevision = sourceTimelineRevision,
            state = AutomaticMemoryJobState.EXCLUDED,
            nextStage = AutomaticMemoryJobStage.COMPLETE,
            attemptCount = 0,
            createdAt = occurredAt,
            updatedAt = occurredAt,
            lastErrorCode = reasonCode.safeAutomaticMemoryCodeFragment(),
        )
        try {
            automaticMemoryDao.insertJob(job)
        } catch (_: SQLiteConstraintException) {
            return excludeExistingMessageJobInCurrentTransaction(
                existing = requireNotNull(automaticMemoryDao.jobForMessage(messageId)),
                occurredAt = occurredAt,
                reasonCode = reasonCode,
            )
        }
        return job
    }

    private fun excludeExistingMessageJobInCurrentTransaction(
        existing: AutomaticMemoryJobEntity,
        occurredAt: Long,
        reasonCode: String,
    ): AutomaticMemoryJobEntity {
        if (existing.state != AutomaticMemoryJobState.EXCLUDED) {
            check(
                automaticMemoryDao.excludeForMessage(
                    messageId = existing.sourceMessageId,
                    excludedState = AutomaticMemoryJobState.EXCLUDED,
                    completeStage = AutomaticMemoryJobStage.COMPLETE,
                    updatedAt = occurredAt,
                    reasonCode = reasonCode.safeAutomaticMemoryCodeFragment(),
                ) == 1,
            )
        }
        return requireNotNull(automaticMemoryDao.jobForMessage(existing.sourceMessageId))
    }
}

internal fun automaticMemoryJobId(messageId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(messageId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return "automatic-memory-$digest"
}

internal fun String.safeAutomaticMemoryCodeFragment(): String =
    filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "Exception" }.take(40)
