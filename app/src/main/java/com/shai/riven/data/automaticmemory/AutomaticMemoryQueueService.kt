package com.shai.riven.data.automaticmemory

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.background.MAX_AUTOMATIC_MEMORY_ATTEMPTS
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AutomaticMemoryJobEntity
import com.shai.riven.data.persistence.entity.ConversationRunEntity
import com.shai.riven.data.persistence.model.AutomaticMemoryJobStage
import com.shai.riven.data.persistence.model.AutomaticMemoryJobState
import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.validationRecallCorpusFence
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

data class AutomaticMemoryStatusSnapshot(
    val pending: Int,
    val running: Int,
    val succeeded: Int,
    val excluded: Int,
    val failed: Int,
    val latestErrorCode: String?,
)

sealed interface AutomaticMemoryEnqueueResult {
    data class Enqueued(
        val jobIds: List<String>,
        val schedulingFailedJobIds: List<String>,
    ) : AutomaticMemoryEnqueueResult

    data class Failure(val errorCode: String) : AutomaticMemoryEnqueueResult
}

data class AutomaticMemoryReconciliationResult(
    val inspectedRuns: Int,
    val jobIds: List<String>,
    val schedulingFailedJobIds: List<String>,
    val truncated: Boolean,
)

/**
 * Transactionally derives durable, message-unique automatic-memory work from successful runs.
 * Scheduling is intentionally outside the transaction: pending rows are authoritative and the
 * periodic sweep can recover a process death between commit and WorkManager enqueue.
 */
class AutomaticMemoryQueueService(
    private val database: RivenDatabase,
    private val scheduler: RivenBackgroundWorkScheduler,
) {
    private val automaticMemoryDao = database.automaticMemoryDao()
    private val runDao = database.conversationRunDao()
    private val memoryDao = database.memoryDao()
    private val validationRecallFence = database.validationRecallCorpusFence()

    suspend fun ensureForSucceededRun(
        runId: String,
        sourceTimelineRevision: Long,
        occurredAt: Long,
    ): AutomaticMemoryEnqueueResult {
        val transaction = try {
            val run = runDao.run(runId)
                ?: return AutomaticMemoryEnqueueResult.Failure("RUN_NOT_FOUND")
            if (run.state != ConversationRunState.SUCCEEDED || sourceTimelineRevision < 0L) {
                return AutomaticMemoryEnqueueResult.Failure("RUN_NOT_SUCCEEDED")
            }
            val write: suspend () -> QueueWrite = {
                database.withTransaction {
                    val affectedMemoryIds = run.regenerateOfMessageId
                        ?.let { discardedMessageId ->
                            excludeDiscardedMessageInCurrentTransaction(discardedMessageId, occurredAt)
                        }
                        .orEmpty()
                    val shortWindowJobs = requeueShortWindowJobsInCurrentTransaction(
                        conversationId = run.conversationId,
                        sourceTimelineRevision = sourceTimelineRevision,
                        occurredAt = occurredAt,
                    )
                    val newJobs = listOf(run.userMessageId, run.assistantMessageId).map { messageId ->
                        ensureMessageJobInCurrentTransaction(run, messageId, sourceTimelineRevision, occurredAt)
                    }
                    QueueWrite((shortWindowJobs + newJobs).distinctBy { it.id }, affectedMemoryIds)
                }
            }
            if (run.regenerateOfMessageId == null) {
                write()
            } else {
                validationRecallFence.withCanonicalMutation(
                    change = { result -> ValidationRecallCorpusChange.memoryIds(result.affectedMemoryIds) },
                ) { write() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return AutomaticMemoryEnqueueResult.Failure(
                "QUEUE_${failure::class.java.simpleName.safeCodeFragment()}",
            )
        }

        val jobs = transaction.jobs
        val failed = schedule(jobs)
        return AutomaticMemoryEnqueueResult.Enqueued(
            jobIds = jobs.map { it.id },
            schedulingFailedJobIds = failed,
        )
    }

    suspend fun reconcileSucceededRuns(
        limit: Int,
        occurredAt: Long,
    ): AutomaticMemoryReconciliationResult {
        require(limit > 0)
        val runs = runDao.succeededRunsMissingAutomaticMemoryJobs(
            succeededState = ConversationRunState.SUCCEEDED,
            limit = limit + 1,
        )
        val jobIds = linkedSetOf<String>()
        val failures = linkedSetOf<String>()
        runs.take(limit).forEach { run ->
            val revision = if (run.reservedTimelineRevision == Long.MAX_VALUE) {
                run.reservedTimelineRevision
            } else {
                run.reservedTimelineRevision + 1L
            }
            when (val result = ensureForSucceededRun(run.runId, revision, occurredAt)) {
                is AutomaticMemoryEnqueueResult.Enqueued -> {
                    jobIds += result.jobIds
                    failures += result.schedulingFailedJobIds
                }
                is AutomaticMemoryEnqueueResult.Failure -> Unit
            }
        }
        return AutomaticMemoryReconciliationResult(
            inspectedRuns = minOf(runs.size, limit),
            jobIds = jobIds.toList(),
            schedulingFailedJobIds = failures.toList(),
            truncated = runs.size > limit,
        )
    }

    fun schedulePending(limit: Int): List<String> {
        require(limit > 0)
        val jobs = automaticMemoryDao.jobsInState(AutomaticMemoryJobState.PENDING, limit)
        return schedule(jobs)
    }

    fun status(): AutomaticMemoryStatusSnapshot = AutomaticMemoryStatusSnapshot(
        pending = automaticMemoryDao.countInState(AutomaticMemoryJobState.PENDING),
        running = automaticMemoryDao.countInState(AutomaticMemoryJobState.RUNNING),
        succeeded = automaticMemoryDao.countInState(AutomaticMemoryJobState.SUCCEEDED),
        excluded = automaticMemoryDao.countInState(AutomaticMemoryJobState.EXCLUDED),
        failed = automaticMemoryDao.countInState(AutomaticMemoryJobState.FAILED),
        latestErrorCode = automaticMemoryDao.latestErrorCode(),
    )

    private fun ensureMessageJobInCurrentTransaction(
        run: ConversationRunEntity,
        messageId: String,
        sourceTimelineRevision: Long,
        occurredAt: Long,
    ): AutomaticMemoryJobEntity {
        automaticMemoryDao.jobForMessage(messageId)?.let { return it }
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
            state = AutomaticMemoryJobState.PENDING,
            nextStage = AutomaticMemoryJobStage.ATTENTION,
            attemptCount = 0,
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
        try {
            automaticMemoryDao.insertJob(job)
        } catch (_: SQLiteConstraintException) {
            return requireNotNull(automaticMemoryDao.jobForMessage(messageId))
        }
        return job
    }

    private fun excludeDiscardedMessageInCurrentTransaction(
        messageId: String,
        occurredAt: Long,
    ): Set<String> {
        val affectedMemoryIds = sortedSetOf<String>()
        memoryDao.canonicalConversationExperiencesForMessage(messageId).forEach { experience ->
            affectedMemoryIds += memoryDao.memoryIdsForExperience(experience.id)
            if (experience.availability == ExperienceAvailability.AVAILABLE) {
                check(
                    memoryDao.updateExperienceAvailability(
                        experience.id,
                        ExperienceAvailability.EXCLUDED,
                    ) == 1,
                )
            }
        }
        automaticMemoryDao.excludeForMessage(
            messageId = messageId,
            excludedState = AutomaticMemoryJobState.EXCLUDED,
            completeStage = AutomaticMemoryJobStage.COMPLETE,
            updatedAt = occurredAt,
            reasonCode = DISCARDED_BRANCH_CODE,
        )
        return affectedMemoryIds
    }

    private fun requeueShortWindowJobsInCurrentTransaction(
        conversationId: String,
        sourceTimelineRevision: Long,
        occurredAt: Long,
    ): List<AutomaticMemoryJobEntity> = automaticMemoryDao.shortWindowJobsForConversation(
        conversationId = conversationId,
        succeededState = AutomaticMemoryJobState.SUCCEEDED,
        maxAttempts = MAX_AUTOMATIC_MEMORY_ATTEMPTS,
        contextRevision = sourceTimelineRevision,
        limit = MAX_SHORT_WINDOW_REQUEUES_PER_TURN,
    ).mapNotNull { job ->
        if (automaticMemoryDao.requeueForShortWindow(
                jobId = job.id,
                succeededState = AutomaticMemoryJobState.SUCCEEDED,
                pendingState = AutomaticMemoryJobState.PENDING,
                attentionStage = AutomaticMemoryJobStage.ATTENTION,
                expectedAttemptCount = job.attemptCount,
                expectedContextRevision = job.sourceTimelineRevision,
                contextRevision = sourceTimelineRevision,
                updatedAt = occurredAt,
                reasonCode = SHORT_WINDOW_CONTEXT_CODE,
            ) == 1
        ) {
            job.copy(
                state = AutomaticMemoryJobState.PENDING,
                nextStage = AutomaticMemoryJobStage.ATTENTION,
                sourceTimelineRevision = sourceTimelineRevision,
                updatedAt = occurredAt,
                lastErrorCode = SHORT_WINDOW_CONTEXT_CODE,
            )
        } else {
            null
        }
    }

    private fun schedule(jobs: List<AutomaticMemoryJobEntity>): List<String> = jobs
        .filter { it.state == AutomaticMemoryJobState.PENDING }
        .mapNotNull { job ->
            when (scheduler.enqueueAutomaticMemoryJob(job.id)) {
                is RivenBackgroundScheduleResult.Enqueued -> null
                is RivenBackgroundScheduleResult.Failure -> job.id
            }
        }

    private fun automaticMemoryJobId(messageId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(messageId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "automatic-memory-$digest"
    }

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "Exception" }.take(40)

    companion object {
        const val DISCARDED_BRANCH_CODE = "DISCARDED_REGENERATED_BRANCH"
        const val SHORT_WINDOW_CONTEXT_CODE = "SHORT_WINDOW_CONTEXT_AVAILABLE"
        const val MAX_SHORT_WINDOW_REQUEUES_PER_TURN = 8
    }

    private data class QueueWrite(
        val jobs: List<AutomaticMemoryJobEntity>,
        val affectedMemoryIds: Set<String>,
    )
}
