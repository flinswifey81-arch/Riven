package com.shai.riven.data.automaticmemory

import androidx.room.withTransaction
import com.shai.riven.data.attention.AssessImmediateAttentionInput
import com.shai.riven.data.attention.AssessImmediateAttentionResult
import com.shai.riven.data.attention.ImmediateAttentionAnalyzer
import com.shai.riven.data.attention.ImmediateAttentionError
import com.shai.riven.data.attention.ImmediateAttentionService
import com.shai.riven.data.attention.ReadAttentionAssessmentResult
import com.shai.riven.data.candidate.CandidateExtractionError
import com.shai.riven.data.candidate.CandidateExtractionResult
import com.shai.riven.data.candidate.CandidateExtractionService
import com.shai.riven.data.candidate.CandidateMemoryExtractor
import com.shai.riven.data.candidate.ExtractCandidateMemoriesInput
import com.shai.riven.data.candidate.ReadCandidatesForExperienceResult
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AutomaticMemoryJobEntity
import com.shai.riven.data.persistence.entity.ConversationRunEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AutomaticMemoryJobStage
import com.shai.riven.data.persistence.model.AutomaticMemoryJobState
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.memory.MemoryConsolidationDecider
import com.shai.riven.data.memory.MemoryConsolidationResult
import com.shai.riven.data.memory.MemoryConsolidationService
import com.shai.riven.data.memory.OpenLoopLifecycleDecider
import com.shai.riven.data.memory.OpenLoopLifecycleResult
import com.shai.riven.data.memory.OpenLoopLifecycleService
import com.shai.riven.data.validation.CandidateValidationDecider
import com.shai.riven.data.validation.CandidateValidationError
import com.shai.riven.data.validation.CandidateValidationResult
import com.shai.riven.data.validation.CandidateValidationService
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.ValidateCandidateInput
import com.shai.riven.data.validation.validationRecallCorpusFence
import kotlinx.coroutines.CancellationException

interface AutomaticMemoryModel :
    ImmediateAttentionAnalyzer,
    CandidateMemoryExtractor,
    CandidateValidationDecider,
    OpenLoopLifecycleDecider,
    MemoryConsolidationDecider

sealed interface AutomaticMemoryModelFactoryResult {
    data class Ready(val model: AutomaticMemoryModel) : AutomaticMemoryModelFactoryResult
    data class RetryableFailure(val errorCode: String) : AutomaticMemoryModelFactoryResult
    data class Blocked(val errorCode: String) : AutomaticMemoryModelFactoryResult
    data class PermanentFailure(val errorCode: String) : AutomaticMemoryModelFactoryResult
}

internal class AutomaticMemoryModelFailure(
    val errorCode: String,
    val retryable: Boolean,
    val blocked: Boolean = false,
) : RuntimeException(errorCode)

fun interface AutomaticMemoryModelFactory {
    suspend fun create(run: ConversationRunEntity): AutomaticMemoryModelFactoryResult
}

sealed interface AutomaticMemoryJobRunResult {
    data class Succeeded(val jobId: String) : AutomaticMemoryJobRunResult
    data class Excluded(val jobId: String, val reasonCode: String) : AutomaticMemoryJobRunResult
    data class RetryableFailure(val jobId: String, val errorCode: String) : AutomaticMemoryJobRunResult
    data class Blocked(val jobId: String, val errorCode: String) : AutomaticMemoryJobRunResult
    data class PermanentlyFailed(val jobId: String, val errorCode: String) : AutomaticMemoryJobRunResult
    data class NoOp(val jobId: String) : AutomaticMemoryJobRunResult
    data class AlreadyRunning(val jobId: String) : AutomaticMemoryJobRunResult
    data class LeaseLost(val jobId: String) : AutomaticMemoryJobRunResult
}

fun interface AutomaticMemoryJobOperations {
    suspend fun run(jobId: String): AutomaticMemoryJobRunResult
}

/** Runs a bounded, restart-resumable Experience -> Attention -> Candidate -> Validation pipeline. */
class AutomaticMemoryJobRunner(
    private val database: RivenDatabase,
    private val modelFactory: AutomaticMemoryModelFactory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = com.shai.riven.data.background.MAX_AUTOMATIC_MEMORY_ATTEMPTS,
    private val runningLeaseMs: Long = com.shai.riven.data.background.AUTOMATIC_MEMORY_RUNNING_LEASE_MS,
    /** Rearms the replaceable five-minute wake from the same completion timestamp used by due checks. */
    private val scheduleShortWindowSweep: () -> Unit = {},
) : AutomaticMemoryJobOperations {
    private val dao = database.automaticMemoryDao()
    private val memoryDao = database.memoryDao()
    private val runDao = database.conversationRunDao()
    private val timeline = ConversationTimelineService(database)
    private val validationRecallFence = database.validationRecallCorpusFence()

    init {
        require(maxAttempts > 0)
        require(runningLeaseMs > 0L)
    }

    override suspend fun run(jobId: String): AutomaticMemoryJobRunResult {
        val claimed = when (val result = claim(jobId)) {
            is ClaimResult.Claimed -> result.job
            is ClaimResult.Completed -> return result.result
        }
        val run = runDao.run(claimed.originatingRunId)
            ?: return fail(claimed, "MISSING_ORIGINATING_RUN", retryable = false)
        when (val source = sourceEligibility(claimed)) {
            SourceEligibility.Eligible -> Unit
            is SourceEligibility.Excluded -> return exclude(claimed, source.reasonCode)
            is SourceEligibility.Retryable -> return fail(claimed, source.errorCode, retryable = true)
        }
        if (claimed.nextStage == AutomaticMemoryJobStage.COMPLETE) {
            return finishSucceeded(claimed)
        }

        val model = when (val created = modelFactory.create(run)) {
            is AutomaticMemoryModelFactoryResult.Ready -> created.model
            is AutomaticMemoryModelFactoryResult.RetryableFailure ->
                return fail(claimed, created.errorCode, retryable = true)
            is AutomaticMemoryModelFactoryResult.Blocked ->
                return block(claimed, created.errorCode)
            is AutomaticMemoryModelFactoryResult.PermanentFailure ->
                return fail(claimed, created.errorCode, retryable = false)
        }
        val attention = ImmediateAttentionService(database, model)
        val extraction = CandidateExtractionService(database, model)
        val validation = CandidateValidationService(database, decider = model)
        try {
            var stage = claimed.nextStage
            if (
                stage == AutomaticMemoryJobStage.ATTENTION ||
                stage == AutomaticMemoryJobStage.REFRESH_ATTENTION
            ) {
                val forceRefresh = stage == AutomaticMemoryJobStage.REFRESH_ATTENTION
                val result = ensureAttention(claimed, attention, forceRefresh)
                if (result != null) return finishStageFailure(claimed, result)
                val nextStage = if (forceRefresh) {
                    AutomaticMemoryJobStage.REFRESH_EXTRACTION
                } else {
                    AutomaticMemoryJobStage.EXTRACTION
                }
                if (!advance(claimed, stage, nextStage)) {
                    return AutomaticMemoryJobRunResult.LeaseLost(claimed.id)
                }
                stage = nextStage
            }
            if (
                stage == AutomaticMemoryJobStage.EXTRACTION ||
                stage == AutomaticMemoryJobStage.REFRESH_EXTRACTION
            ) {
                val forceRefresh = stage == AutomaticMemoryJobStage.REFRESH_EXTRACTION
                val outcome = attention.readAssessment(claimed.sourceExperienceId)
                val assessment = (outcome as? ReadAttentionAssessmentResult.Assessment)?.assessment
                    ?: return fail(claimed, "ATTENTION_CHECK_FAILED", retryable = true)
                if (assessment.outcome == AttentionOutcome.FORWARD_FOR_INTERPRETATION) {
                    val result = ensureExtraction(claimed, extraction, forceRefresh)
                    if (result != null) return finishStageFailure(claimed, result)
                    if (!advance(claimed, stage, AutomaticMemoryJobStage.VALIDATION)) {
                        return AutomaticMemoryJobRunResult.LeaseLost(claimed.id)
                    }
                    stage = AutomaticMemoryJobStage.VALIDATION
                } else {
                    if (!advance(claimed, stage, AutomaticMemoryJobStage.CONSOLIDATION)) {
                        return AutomaticMemoryJobRunResult.LeaseLost(claimed.id)
                    }
                    stage = AutomaticMemoryJobStage.CONSOLIDATION
                }
            }
            if (stage == AutomaticMemoryJobStage.VALIDATION) {
                val result = ensureValidation(claimed, extraction, validation)
                if (result != null) return finishStageFailure(claimed, result)
                if (!advance(claimed, stage, AutomaticMemoryJobStage.OPEN_LOOP)) {
                    return AutomaticMemoryJobRunResult.LeaseLost(claimed.id)
                }
                stage = AutomaticMemoryJobStage.OPEN_LOOP
            }
            if (stage == AutomaticMemoryJobStage.OPEN_LOOP) {
                when (
                    val result = OpenLoopLifecycleService(database, model, clock = clock)
                        .process(claimed.sourceExperienceId)
                ) {
                    is OpenLoopLifecycleResult.Failure ->
                        return finishStageFailure(
                            claimed,
                            if (result.retryable) StageFailure.Retryable(result.errorCode)
                            else StageFailure.Permanent(result.errorCode),
                        )
                    else -> Unit
                }
                if (!advance(claimed, stage, AutomaticMemoryJobStage.CONSOLIDATION)) {
                    return AutomaticMemoryJobRunResult.LeaseLost(claimed.id)
                }
                stage = AutomaticMemoryJobStage.CONSOLIDATION
            }
            if (stage == AutomaticMemoryJobStage.CONSOLIDATION) {
                when (
                    val result = MemoryConsolidationService(
                        database = database,
                        decider = model,
                        profileId = run.profileId,
                        clock = clock,
                    ).consolidate()
                ) {
                    is MemoryConsolidationResult.Failure ->
                        return finishStageFailure(
                            claimed,
                            if (result.retryable) StageFailure.Retryable(result.errorCode)
                            else StageFailure.Permanent(result.errorCode),
                        )
                    else -> Unit
                }
                if (!advance(claimed, stage, AutomaticMemoryJobStage.COMPLETE)) {
                    return AutomaticMemoryJobRunResult.LeaseLost(claimed.id)
                }
                stage = AutomaticMemoryJobStage.COMPLETE
            }
            check(stage == AutomaticMemoryJobStage.COMPLETE)
            return finishSucceeded(claimed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            return if (failure.blocked) {
                block(claimed, failure.errorCode)
            } else {
                fail(claimed, failure.errorCode, retryable = failure.retryable)
            }
        } catch (failure: Exception) {
            return fail(
                claimed,
                "PIPELINE_${failure::class.java.simpleName.safeCodeFragment()}",
                retryable = true,
            )
        } finally {
            validation.close()
        }
    }

    private suspend fun ensureAttention(
        job: AutomaticMemoryJobEntity,
        service: ImmediateAttentionService,
        forceRefresh: Boolean,
    ): StageFailure? = when (val existing = service.readAssessment(job.sourceExperienceId)) {
        is ReadAttentionAssessmentResult.Assessment -> if (forceRefresh) {
            assessAttention(job, service, existing.assessment.revision)
        } else {
            null
        }
        is ReadAttentionAssessmentResult.NoAssessment -> when (
            val failure = assessAttention(job, service, expectedRevision = 0L)
        ) {
            null -> null
            else -> failure
        }
        is ReadAttentionAssessmentResult.Failure -> existing.error.toStageFailure()
    }

    private suspend fun assessAttention(
        job: AutomaticMemoryJobEntity,
        service: ImmediateAttentionService,
        expectedRevision: Long,
    ): StageFailure? = when (
        val assessed = service.assess(
            AssessImmediateAttentionInput(
                experienceId = job.sourceExperienceId,
                expectedRevision = expectedRevision,
                assessedAt = clock(),
            ),
        )
    ) {
        is AssessImmediateAttentionResult.Persisted -> null
        is AssessImmediateAttentionResult.Failure -> assessed.error.toStageFailure()
    }

    private suspend fun ensureExtraction(
        job: AutomaticMemoryJobEntity,
        service: CandidateExtractionService,
        forceRefresh: Boolean,
    ): StageFailure? {
        val existing = service.readCandidatesSeededByExperience(job.sourceExperienceId)
        when (existing) {
            is ReadCandidatesForExperienceResult.Failure -> return existing.error.toStageFailure()
            is ReadCandidatesForExperienceResult.Candidates -> if (
                existing.candidates.isNotEmpty() &&
                !forceRefresh
            ) {
                return null
            }
        }
        return when (
            val result = service.extract(
                ExtractCandidateMemoriesInput(job.sourceExperienceId, clock()),
            )
        ) {
            is CandidateExtractionResult.Extracted,
            is CandidateExtractionResult.NoCandidates,
            -> null
            is CandidateExtractionResult.Failure -> result.error.toStageFailure()
        }
    }

    private suspend fun ensureValidation(
        job: AutomaticMemoryJobEntity,
        extraction: CandidateExtractionService,
        validation: CandidateValidationService,
    ): StageFailure? {
        val candidates = when (
            val read = extraction.readCandidatesSeededByExperience(job.sourceExperienceId)
        ) {
            is ReadCandidatesForExperienceResult.Candidates -> read.candidates
            is ReadCandidatesForExperienceResult.Failure -> return read.error.toStageFailure()
        }
        candidates
            .filter { it.state == CandidateMemoryState.READY_FOR_VALIDATION }
            .sortedBy { it.candidateId }
            .forEach { candidate ->
                when (val result = validation.validate(ValidateCandidateInput(candidate.candidateId, clock()))) {
                    is CandidateValidationResult.Failure -> return result.error.toStageFailure()
                    else -> Unit
                }
            }
        return null
    }

    private suspend fun sourceEligibility(job: AutomaticMemoryJobEntity): SourceEligibility {
        val experience = memoryDao.experience(job.sourceExperienceId)
            ?: return SourceEligibility.Excluded("MISSING_SOURCE_EXPERIENCE")
        if (experience.availability != ExperienceAvailability.AVAILABLE) {
            return SourceEligibility.Excluded("SOURCE_${experience.availability.name}")
        }
        val source = memoryDao.messageSourcesForExperience(job.sourceExperienceId).singleOrNull()
            ?: return SourceEligibility.Excluded("INVALID_SOURCE_PROVENANCE")
        if (source.messageId != job.sourceMessageId) {
            return SourceEligibility.Excluded("SOURCE_MESSAGE_MISMATCH")
        }
        return when (val active = timeline.activeTimeline(runDao.run(job.originatingRunId)?.conversationId.orEmpty())) {
            is TimelineReadResult.Success -> if (active.messages.any { it.id == job.sourceMessageId }) {
                SourceEligibility.Eligible
            } else {
                SourceEligibility.Excluded("INACTIVE_SOURCE_MESSAGE")
            }
            is TimelineReadResult.Failure -> SourceEligibility.Retryable("TIMELINE_UNAVAILABLE")
        }
    }

    private suspend fun claim(jobId: String): ClaimResult = database.withTransaction {
        val current = dao.job(jobId)
            ?: return@withTransaction ClaimResult.Completed(AutomaticMemoryJobRunResult.NoOp(jobId))
        when (current.state) {
            AutomaticMemoryJobState.PENDING -> {
                val now = clock()
                if (current.attemptCount >= maxAttempts) {
                    dao.failExhausted(
                        current.id,
                        AutomaticMemoryJobState.PENDING,
                        AutomaticMemoryJobState.FAILED,
                        current.attemptCount,
                        now,
                        ATTEMPT_BUDGET_EXHAUSTED,
                    )
                    return@withTransaction ClaimResult.Completed(
                        AutomaticMemoryJobRunResult.PermanentlyFailed(
                            current.id,
                            ATTEMPT_BUDGET_EXHAUSTED,
                        ),
                    )
                }
                if (dao.claimPending(
                        current.id,
                        AutomaticMemoryJobState.PENDING,
                        AutomaticMemoryJobState.RUNNING,
                        current.attemptCount,
                        now,
                    ) == 1
                ) {
                    ClaimResult.Claimed(
                        current.copy(
                            state = AutomaticMemoryJobState.RUNNING,
                            attemptCount = current.attemptCount + 1,
                            updatedAt = now,
                            lastErrorCode = current.lastErrorCode,
                        ),
                    )
                } else {
                    ClaimResult.Completed(AutomaticMemoryJobRunResult.AlreadyRunning(jobId))
                }
            }
            AutomaticMemoryJobState.RUNNING -> {
                val now = clock()
                if (current.updatedAt > now - runningLeaseMs) {
                    ClaimResult.Completed(AutomaticMemoryJobRunResult.AlreadyRunning(jobId))
                } else if (current.attemptCount >= maxAttempts) {
                    dao.failExhausted(
                        current.id,
                        AutomaticMemoryJobState.RUNNING,
                        AutomaticMemoryJobState.FAILED,
                        current.attemptCount,
                        now,
                        ATTEMPT_BUDGET_EXHAUSTED,
                    )
                    ClaimResult.Completed(
                        AutomaticMemoryJobRunResult.PermanentlyFailed(
                            current.id,
                            ATTEMPT_BUDGET_EXHAUSTED,
                        ),
                    )
                } else if (dao.reclaimStaleRunning(
                        current.id,
                        AutomaticMemoryJobState.RUNNING,
                        current.attemptCount,
                        current.updatedAt,
                        now - runningLeaseMs,
                        now,
                    ) == 1
                ) {
                    ClaimResult.Claimed(
                        current.copy(
                            attemptCount = current.attemptCount + 1,
                            updatedAt = now,
                            lastErrorCode = current.lastErrorCode,
                        ),
                    )
                } else {
                    ClaimResult.Completed(AutomaticMemoryJobRunResult.AlreadyRunning(jobId))
                }
            }
            AutomaticMemoryJobState.SUCCEEDED,
            AutomaticMemoryJobState.EXCLUDED,
            AutomaticMemoryJobState.FAILED,
            -> ClaimResult.Completed(AutomaticMemoryJobRunResult.NoOp(jobId))
        }
    }

    private fun advance(
        job: AutomaticMemoryJobEntity,
        expected: AutomaticMemoryJobStage,
        next: AutomaticMemoryJobStage,
    ): Boolean = dao.advanceStage(
        jobId = job.id,
        runningState = AutomaticMemoryJobState.RUNNING,
        expectedStage = expected,
        nextStage = next,
        expectedAttemptCount = job.attemptCount,
        updatedAt = clock(),
    ) == 1

    private fun finishSucceeded(job: AutomaticMemoryJobEntity): AutomaticMemoryJobRunResult {
        val completedAt = clock()
        return if (dao.finishClaimed(
                job.id,
                AutomaticMemoryJobState.RUNNING,
                AutomaticMemoryJobState.SUCCEEDED,
                AutomaticMemoryJobStage.COMPLETE,
                job.attemptCount,
                completedAt,
                null,
            ) == 1
        ) {
            if (dao.shortWindowEligibleJobCount(job.id, AutomaticMemoryJobState.SUCCEEDED) != 0) {
                runCatching(scheduleShortWindowSweep)
            }
            AutomaticMemoryJobRunResult.Succeeded(job.id)
        } else {
            AutomaticMemoryJobRunResult.LeaseLost(job.id)
        }
    }

    private suspend fun exclude(
        job: AutomaticMemoryJobEntity,
        reasonCode: String,
    ): AutomaticMemoryJobRunResult = validationRecallFence.withCanonicalMutation(
        change = { write -> ValidationRecallCorpusChange.memoryIds(write.affectedMemoryIds) },
    ) {
        database.withTransaction {
            val affectedMemoryIds = memoryDao.memoryIdsForExperience(job.sourceExperienceId)
            val experience = memoryDao.experience(job.sourceExperienceId)
            if (experience?.availability == ExperienceAvailability.AVAILABLE) {
                check(
                    memoryDao.updateExperienceAvailability(
                        job.sourceExperienceId,
                        ExperienceAvailability.EXCLUDED,
                    ) == 1,
                )
            }
            val code = reasonCode.safeErrorCode()
            val result = if (dao.finishClaimed(
                    job.id,
                    AutomaticMemoryJobState.RUNNING,
                    AutomaticMemoryJobState.EXCLUDED,
                    AutomaticMemoryJobStage.COMPLETE,
                    job.attemptCount,
                    clock(),
                    code,
                ) == 1
            ) {
                AutomaticMemoryJobRunResult.Excluded(job.id, code)
            } else {
                AutomaticMemoryJobRunResult.LeaseLost(job.id)
            }
            ExclusionWrite(result, affectedMemoryIds)
        }
    }.result

    private suspend fun finishStageFailure(
        job: AutomaticMemoryJobEntity,
        failure: StageFailure,
    ): AutomaticMemoryJobRunResult = when (failure) {
        is StageFailure.Excluded -> exclude(job, failure.errorCode)
        is StageFailure.Retryable -> fail(job, failure.errorCode, retryable = true)
        is StageFailure.Permanent -> fail(job, failure.errorCode, retryable = false)
    }

    private fun fail(
        job: AutomaticMemoryJobEntity,
        errorCode: String,
        retryable: Boolean,
    ): AutomaticMemoryJobRunResult {
        val code = errorCode.safeErrorCode()
        val canRetry = retryable && job.attemptCount < maxAttempts
        val changed = if (canRetry) {
            dao.releaseForRetry(
                job.id,
                AutomaticMemoryJobState.RUNNING,
                AutomaticMemoryJobState.PENDING,
                job.attemptCount,
                clock(),
                code,
            )
        } else {
            val persistedStage = dao.job(job.id)?.nextStage ?: job.nextStage
            dao.finishClaimed(
                job.id,
                AutomaticMemoryJobState.RUNNING,
                AutomaticMemoryJobState.FAILED,
                persistedStage,
                job.attemptCount,
                clock(),
                code,
            )
        }
        if (changed != 1) return AutomaticMemoryJobRunResult.LeaseLost(job.id)
        return if (canRetry) {
            AutomaticMemoryJobRunResult.RetryableFailure(job.id, code)
        } else {
            AutomaticMemoryJobRunResult.PermanentlyFailed(job.id, code)
        }
    }

    private fun block(
        job: AutomaticMemoryJobEntity,
        errorCode: String,
    ): AutomaticMemoryJobRunResult {
        val code = errorCode.safeErrorCode()
        val changed = dao.releaseBlocked(
            jobId = job.id,
            runningState = AutomaticMemoryJobState.RUNNING,
            pendingState = AutomaticMemoryJobState.PENDING,
            expectedAttemptCount = job.attemptCount,
            updatedAt = clock(),
            lastErrorCode = code,
        )
        return if (changed == 1) {
            AutomaticMemoryJobRunResult.Blocked(job.id, code)
        } else {
            AutomaticMemoryJobRunResult.LeaseLost(job.id)
        }
    }

    private fun ImmediateAttentionError.toStageFailure(): StageFailure = when (this) {
        is ImmediateAttentionError.ExperienceUnavailable,
        is ImmediateAttentionError.InactiveConversationSource,
        is ImmediateAttentionError.AlreadyConsumedManualMemoryIntent,
        -> StageFailure.Excluded("ATTENTION_${this::class.java.simpleName}")
        else -> StageFailure.Retryable("ATTENTION_${this::class.java.simpleName}")
    }

    private fun CandidateExtractionError.toStageFailure(): StageFailure = when (this) {
        is CandidateExtractionError.ExperienceUnavailable,
        is CandidateExtractionError.InactiveConversationSource,
        is CandidateExtractionError.AlreadyConsumedManualMemoryIntent,
        -> StageFailure.Excluded("EXTRACTION_${this::class.java.simpleName}")
        else -> StageFailure.Retryable("EXTRACTION_${this::class.java.simpleName}")
    }

    private fun CandidateValidationError.toStageFailure(): StageFailure = when (this) {
        is CandidateValidationError.ExperienceUnavailable,
        is CandidateValidationError.InactiveConversationEvidence,
        -> StageFailure.Excluded("VALIDATION_${this::class.java.simpleName}")
        else -> StageFailure.Retryable("VALIDATION_${this::class.java.simpleName}")
    }

    private fun String.safeErrorCode(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "UNKNOWN" }.take(64)

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "Exception" }.take(40)

    private sealed interface ClaimResult {
        data class Claimed(val job: AutomaticMemoryJobEntity) : ClaimResult
        data class Completed(val result: AutomaticMemoryJobRunResult) : ClaimResult
    }

    private data class ExclusionWrite(
        val result: AutomaticMemoryJobRunResult,
        val affectedMemoryIds: List<String>,
    )

    private sealed interface SourceEligibility {
        data object Eligible : SourceEligibility
        data class Excluded(val reasonCode: String) : SourceEligibility
        data class Retryable(val errorCode: String) : SourceEligibility
    }

    private sealed interface StageFailure {
        data class Excluded(val errorCode: String) : StageFailure
        data class Retryable(val errorCode: String) : StageFailure
        data class Permanent(val errorCode: String) : StageFailure
    }

    private companion object {
        const val ATTEMPT_BUDGET_EXHAUSTED = "ATTEMPT_BUDGET_EXHAUSTED"
    }
}
