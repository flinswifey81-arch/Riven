package com.shai.riven.data.validation

import androidx.room.withTransaction
import com.shai.riven.data.automaticmemory.AutomaticMemoryModelFailure
import com.shai.riven.data.memory.AdmitCandidateMemoryInput
import com.shai.riven.data.memory.CorrectMemoryInput
import com.shai.riven.data.memory.DisputeMemoryInput
import com.shai.riven.data.memory.MemoryEvidenceInput
import com.shai.riven.data.memory.MemoryTransactionService
import com.shai.riven.data.memory.MemoryWriteResult
import com.shai.riven.data.memory.RefineMemoryInput
import com.shai.riven.data.memory.ReinforceMemorySetInput
import com.shai.riven.data.memory.ReinforcementEvidenceInput
import com.shai.riven.data.memory.SupersedeMemoryInput
import com.shai.riven.data.memory.ValidatedMemoryInput
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.TemporalState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

class CandidateValidationService(
    private val database: RivenDatabase,
    retriever: ValidationMemoryRetriever? = null,
    private val decider: CandidateValidationDecider,
    private val memoryIdGenerator: ValidatedMemoryIdGenerator =
        ValidatedMemoryIdGenerator { UUID.randomUUID().toString() },
    private val memoryTransactions: MemoryTransactionService = MemoryTransactionService(database),
) : AutoCloseable {
    private val retriever = retriever ?: TargetedValidationMemoryRetriever(database)
    private val ownedRetriever = if (retriever == null) this.retriever as? AutoCloseable else null
    private val grounding = CandidateValidationGrounding(database)
    private val validationRecallFence = database.validationRecallCorpusFence()
    private val closed = AtomicBoolean(false)

    suspend fun validate(input: ValidateCandidateInput): CandidateValidationResult {
        if (closed.get()) {
            return CandidateValidationResult.Failure(
                CandidateValidationError.ValidationRecallUnavailable(ValidationRecallReadiness.NOT_READY),
            )
        }
        database.requireTopLevelValidationRecallMutation()
        val initial = try {
            database.withTransaction {
                val context = grounding.readCandidateInCurrentTransaction(input.candidateId)
                if (grounding.hasActiveSuppressionInCurrentTransaction(context)) {
                    requireWriteSuccess(memoryTransactions.consumeCandidateInCurrentTransaction(input.candidateId))
                    InitialRead.Suppressed
                } else {
                    val admittedMemoryIds = grounding.admittedMemoryIdsInCurrentTransaction(context)
                    if (admittedMemoryIds.isNotEmpty()) {
                        requireWriteSuccess(memoryTransactions.consumeCandidateInCurrentTransaction(input.candidateId))
                        InitialRead.AlreadyAdmitted(admittedMemoryIds)
                    } else {
                        InitialRead.Ready(context)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (abort: CandidateValidationAbort) {
            return CandidateValidationResult.Failure(abort.error)
        } catch (failure: Exception) {
            return storageFailure(CandidateValidationOperation.READ_GROUNDED_CANDIDATE, failure)
        }
        if (initial == InitialRead.Suppressed) {
            return CandidateValidationResult.SuppressedCandidateDiscarded(input.candidateId)
        }
        if (initial is InitialRead.AlreadyAdmitted) {
            return CandidateValidationResult.AlreadyAdmittedCandidateDiscarded(
                input.candidateId,
                initial.memoryIds,
            )
        }
        val initialContext = (initial as InitialRead.Ready).context

        val query = ValidationMemoryQuery(
            candidateId = initialContext.candidate.candidateId,
            proposedMeaning = initialContext.candidate.proposedMeaning,
            proposedKind = initialContext.candidate.proposedKind,
            proposedScope = initialContext.candidate.proposedScope,
            proposedEpistemicBasis = initialContext.candidate.proposedEpistemicBasis,
            proposedCertainty = initialContext.candidate.proposedCertainty,
            sensitivity = initialContext.candidate.sensitivity,
            groundedEntityIds = initialContext.groundedEntityIds,
            sourceExperienceIds = initialContext.evidence.map { it.experienceId },
            seedAttention = initialContext.seedAttention,
        )
        val retrieval = try {
            retriever.retrieve(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            throw failure
        } catch (failure: Exception) {
            return CandidateValidationResult.Failure(
                CandidateValidationError.RetrieverFailure(failure::class.java.simpleName),
            )
        }
        validateRecallAvailability(initialContext.candidate.candidateId, retrieval)?.let {
            return CandidateValidationResult.Failure(it)
        }
        validateRetrieval(retrieval)?.let { return CandidateValidationResult.Failure(it) }

        val initialMemories = try {
            database.withTransaction {
                if (!validationRecallFence.matches(retrieval.generation)) {
                    abort(CandidateValidationError.StaleValidationRecall(input.candidateId))
                }
                grounding.hydrateMemoriesInCurrentTransaction(retrieval.memoryIds)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (abort: CandidateValidationAbort) {
            return CandidateValidationResult.Failure(abort.error)
        } catch (failure: Exception) {
            return storageFailure(CandidateValidationOperation.HYDRATE_RELATED_MEMORIES, failure)
        }
        val snapshot = CandidateValidationSnapshot(
            candidate = initialContext.candidate,
            evidence = initialContext.evidence,
            seedAttention = initialContext.seedAttention,
            relatedMemories = initialMemories,
        )
        val decision = try {
            decider.decide(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            throw failure
        } catch (failure: Exception) {
            return CandidateValidationResult.Failure(
                CandidateValidationError.ValidatorFailure(failure::class.java.simpleName),
            )
        }
        validateDecision(initialContext, initialMemories, decision)?.let {
            return CandidateValidationResult.Failure(it)
        }
        val newMemoryId = if (decision.outcome.createsMemory()) {
            try {
                generateMemoryId()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (abort: CandidateValidationAbort) {
                return CandidateValidationResult.Failure(abort.error)
            }
        } else null

        val applyInTransaction: suspend (ValidationRecallMutationToken?) -> CandidateValidationResult = { mutation ->
            database.withTransaction {
                val generationMatches = if (mutation == null) {
                    validationRecallFence.matches(retrieval.generation)
                } else {
                    validationRecallFence.matchesDuringMutation(retrieval.generation, mutation)
                }
                if (!generationMatches) {
                    abort(CandidateValidationError.StaleValidationRecall(input.candidateId))
                }
                val currentContext = try {
                    grounding.readCandidateInCurrentTransaction(input.candidateId)
                } catch (_: CandidateValidationAbort) {
                    abort(CandidateValidationError.StaleValidationContext(input.candidateId))
                }
                if (currentContext != initialContext) {
                    abort(CandidateValidationError.StaleValidationContext(input.candidateId))
                }
                if (grounding.hasActiveSuppressionInCurrentTransaction(currentContext)) {
                    requireWriteSuccess(memoryTransactions.consumeCandidateInCurrentTransaction(input.candidateId))
                    return@withTransaction CandidateValidationResult.SuppressedCandidateDiscarded(input.candidateId)
                }
                val admittedMemoryIds = grounding.admittedMemoryIdsInCurrentTransaction(currentContext)
                if (admittedMemoryIds.isNotEmpty()) {
                    requireWriteSuccess(memoryTransactions.consumeCandidateInCurrentTransaction(input.candidateId))
                    return@withTransaction CandidateValidationResult.AlreadyAdmittedCandidateDiscarded(
                        input.candidateId,
                        admittedMemoryIds,
                    )
                }
                val currentMemories = try {
                    grounding.hydrateMemoriesInCurrentTransaction(retrieval.memoryIds)
                } catch (_: CandidateValidationAbort) {
                    abort(CandidateValidationError.StaleValidationContext(input.candidateId))
                }
                if (currentMemories != initialMemories) {
                    abort(CandidateValidationError.StaleValidationContext(input.candidateId))
                }
                applyDecision(input, currentContext, decision, newMemoryId, mutation)
            }
        }
        return try {
            if (decision.outcome.mutatesCanonicalMemory()) {
                validationRecallFence.withCanonicalMutation(
                    change = { result -> ValidationRecallCorpusChange.memoryIds(result.affectedMemoryIds()) },
                ) { mutation ->
                    applyInTransaction(mutation)
                }
            } else {
                applyInTransaction(null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (abort: CandidateValidationAbort) {
            CandidateValidationResult.Failure(abort.error)
        } catch (failure: Exception) {
            storageFailure(CandidateValidationOperation.APPLY_DECISION, failure)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ownedRetriever?.close()
        }
    }

    private fun generateMemoryId(): String {
        val memoryId = try {
            memoryIdGenerator.nextId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            abort(
                CandidateValidationError.MemoryIdGenerationFailure(
                    MemoryIdGenerationFailureReason.INVALID_FORMAT,
                ),
            )
        }
        val reason = when {
            memoryId.isBlank() -> MemoryIdGenerationFailureReason.BLANK
            memoryId.length > MAX_VALIDATED_MEMORY_ID_CHARS -> MemoryIdGenerationFailureReason.TOO_LONG
            !MEMORY_ID_PATTERN.matches(memoryId) -> MemoryIdGenerationFailureReason.INVALID_FORMAT
            else -> null
        }
        if (reason != null) abort(CandidateValidationError.MemoryIdGenerationFailure(reason))
        return memoryId
    }

    private fun validateRetrieval(retrieval: ValidationMemoryRetrieval): CandidateValidationError? {
        if (retrieval.memoryIds.size > MAX_VALIDATION_RELATED_MEMORIES) {
            return CandidateValidationError.TooManyRelatedMemories(retrieval.memoryIds.size)
        }
        if (retrieval.memoryIds.any { it.isBlank() }) {
            return CandidateValidationError.InvalidRetrieverResult(InvalidRetrieverResultReason.BLANK_MEMORY_ID)
        }
        if (retrieval.memoryIds.distinct().size != retrieval.memoryIds.size) {
            return CandidateValidationError.InvalidRetrieverResult(InvalidRetrieverResultReason.DUPLICATE_MEMORY_ID)
        }
        return null
    }

    private fun validateRecallAvailability(
        candidateId: String,
        retrieval: ValidationMemoryRetrieval,
    ): CandidateValidationError? {
        if (retrieval.readiness != ValidationRecallReadiness.READY) {
            return CandidateValidationError.ValidationRecallUnavailable(retrieval.readiness)
        }
        val generation = retrieval.generation
        if (
            generation == null ||
            generation.algorithmVersion != TARGETED_VALIDATION_RECALL_ALGORITHM_VERSION ||
            !validationRecallFence.matches(generation)
        ) {
            return CandidateValidationError.StaleValidationRecall(candidateId)
        }
        return null
    }

    private fun validateDecision(
        context: GroundedCandidateValidationContext,
        relatedMemories: List<ValidationMemorySnapshot>,
        decision: CandidateValidationDecision,
    ): CandidateValidationError? {
        val expectedTargets = when (decision.outcome) {
            CandidateValidationOutcome.ACCEPT_NEW,
            CandidateValidationOutcome.DEFER,
            CandidateValidationOutcome.REJECT,
            -> 0..0
            CandidateValidationOutcome.REINFORCE_EXISTING,
            CandidateValidationOutcome.REFINE_EXISTING,
            CandidateValidationOutcome.SUPERSEDE_EXISTING,
            CandidateValidationOutcome.CORRECT_EXISTING,
            CandidateValidationOutcome.DISPUTE_EXISTING,
            -> 1..1
            CandidateValidationOutcome.MERGE -> 2..MAX_VALIDATION_RELATED_MEMORIES
        }
        if (decision.targetMemoryIds.size !in expectedTargets) {
            return invalidDecision(InvalidValidationDecisionReason.INVALID_TARGET_COUNT)
        }
        if (decision.targetMemoryIds.distinct().size != decision.targetMemoryIds.size) {
            return invalidDecision(InvalidValidationDecisionReason.DUPLICATE_TARGET)
        }
        val memoryById = relatedMemories.associateBy { it.memoryId }
        decision.targetMemoryIds.firstOrNull { it !in memoryById }?.let {
            return CandidateValidationError.TargetNotRetrieved(it)
        }
        val targets = decision.targetMemoryIds.map { memoryById.getValue(it) }
        if (decision.outcome in TARGETED_SCOPE_OUTCOMES && targets.any { it.scope != context.candidate.proposedScope }) {
            return CandidateValidationError.InvalidTargetMemory(
                targets.first { it.scope != context.candidate.proposedScope }.memoryId,
            )
        }
        if (decision.outcome in MATCHING_KIND_OUTCOMES && targets.any { it.kind != context.candidate.proposedKind }) {
            return CandidateValidationError.InvalidTargetMemory(
                targets.first { it.kind != context.candidate.proposedKind }.memoryId,
            )
        }
        if (decision.outcome in TARGETED_SCOPE_OUTCOMES && targets.any { !it.isMutableUnderstanding() }) {
            return CandidateValidationError.InvalidTargetMemory(
                targets.first { !it.isMutableUnderstanding() }.memoryId,
            )
        }
        if (
            decision.outcome == CandidateValidationOutcome.SUPERSEDE_EXISTING ||
            decision.outcome == CandidateValidationOutcome.REFINE_EXISTING
        ) {
            targets.firstOrNull { it.truthState != MemoryTruthState.SUPPORTED || it.temporalState != TemporalState.CURRENT }
                ?.let { return CandidateValidationError.InvalidTargetMemory(it.memoryId) }
        }

        val createsMemory = decision.outcome.createsMemory()
        if (createsMemory && decision.admission == null) {
            return invalidDecision(InvalidValidationDecisionReason.MISSING_ADMISSION_METADATA)
        }
        if (!createsMemory && decision.admission != null) {
            return invalidDecision(InvalidValidationDecisionReason.UNEXPECTED_ADMISSION_METADATA)
        }
        if (
            decision.outcome == CandidateValidationOutcome.ACCEPT_NEW &&
            context.candidate.proposedScope == MemoryScope.RIVEN &&
            context.candidate.proposedKind == MemoryKind.SELF_DEVELOPMENT &&
            context.candidate.proposedEpistemicBasis == EpistemicBasis.DIRECT_RIVEN_EXPERIENCE &&
            context.evidence.map { it.experienceId to it.lineageKey }.distinct().size < 2
        ) {
            return invalidDecision(InvalidValidationDecisionReason.UNSUPPORTED_SELF_ASSERTION)
        }
        decision.admission?.let { admission ->
            if (!admission.hasValidTemporalMetadata()) {
                return invalidDecision(InvalidValidationDecisionReason.INVALID_TEMPORAL_METADATA)
            }
            val sensitivity = admission.sensitivity ?: context.sensitivityFloor
            if (sensitivity.sensitivityRank() < context.sensitivityFloor.sensitivityRank()) {
                return invalidDecision(InvalidValidationDecisionReason.SENSITIVITY_DOWNGRADE)
            }
            if (admission.entityLinks.distinct().size != admission.entityLinks.size) {
                return invalidDecision(InvalidValidationDecisionReason.DUPLICATE_ENTITY_LINK)
            }
            val groundedEntities = context.groundedEntityIds +
                relatedMemories.flatMap { it.entityLinks }.map { it.entityId }
            if (admission.entityLinks.any { it.entityId !in groundedEntities }) {
                return invalidDecision(InvalidValidationDecisionReason.UNGROUNDED_ENTITY)
            }
        }

        val kindMayChange = decision.outcome == CandidateValidationOutcome.REFINE_EXISTING ||
            decision.outcome == CandidateValidationOutcome.CORRECT_EXISTING
        val kindChanged = kindMayChange && targets.single().kind != context.candidate.proposedKind
        if (kindChanged && decision.classificationChangeReason == null) {
            return invalidDecision(InvalidValidationDecisionReason.INVALID_CLASSIFICATION_CHANGE)
        }
        if (!kindChanged && decision.classificationChangeReason != null) {
            return invalidDecision(InvalidValidationDecisionReason.INVALID_CLASSIFICATION_CHANGE)
        }
        if (decision.outcome == CandidateValidationOutcome.REFINE_EXISTING) {
            if (decision.refinementDisposition == null) {
                return invalidDecision(InvalidValidationDecisionReason.MISSING_REFINEMENT_DISPOSITION)
            }
        } else if (decision.refinementDisposition != null) {
            return invalidDecision(InvalidValidationDecisionReason.UNEXPECTED_REFINEMENT_DISPOSITION)
        }
        when (decision.outcome) {
            CandidateValidationOutcome.DEFER -> {
                if (decision.deferState !in DEFER_STATES) {
                    return invalidDecision(InvalidValidationDecisionReason.INVALID_DEFER_STATE)
                }
                if (decision.deferReason == null) {
                    return invalidDecision(InvalidValidationDecisionReason.MISSING_DEFER_REASON)
                }
                if (decision.rejectReason != null) {
                    return invalidDecision(InvalidValidationDecisionReason.UNEXPECTED_REASON)
                }
            }
            CandidateValidationOutcome.REJECT -> {
                if (decision.rejectReason == null) {
                    return invalidDecision(InvalidValidationDecisionReason.MISSING_REJECT_REASON)
                }
                if (decision.deferState != null || decision.deferReason != null) {
                    return invalidDecision(InvalidValidationDecisionReason.UNEXPECTED_REASON)
                }
            }
            else -> if (
                decision.deferState != null || decision.deferReason != null || decision.rejectReason != null
            ) {
                return invalidDecision(InvalidValidationDecisionReason.UNEXPECTED_REASON)
            }
        }
        if (decision.outcome == CandidateValidationOutcome.REINFORCE_EXISTING) {
            if (context.evidence.any { it.role == CandidateEvidenceRole.CONTRADICTING || it.role == CandidateEvidenceRole.CORRECTING }) {
                return invalidDecision(InvalidValidationDecisionReason.UNEXPECTED_REASON)
            }
            val existing = targets.single().evidence
            if (context.evidence.any { candidateEvidence ->
                    existing.any {
                        it.experienceId == candidateEvidence.experienceId || it.lineageKey == candidateEvidence.lineageKey
                    }
                }
            ) {
                return CandidateValidationError.NoIndependentEvidence(targets.single().memoryId)
            }
        }
        return null
    }

    private fun applyDecision(
        input: ValidateCandidateInput,
        context: GroundedCandidateValidationContext,
        decision: CandidateValidationDecision,
        newMemoryId: String?,
        mutation: ValidationRecallMutationToken?,
    ): CandidateValidationResult {
        val candidateId = context.candidate.candidateId
        val seedExperienceId = context.seedEvidence.experienceId
        val targetId = decision.targetMemoryIds.singleOrNull()
        return when (decision.outcome) {
            CandidateValidationOutcome.ACCEPT_NEW -> {
                val memoryId = requireNotNull(newMemoryId)
                requireWriteSuccess(
                    memoryTransactions.admitCandidateInCurrentTransaction(
                        context.toAdmitInput(memoryId, input.validatedAt, requireNotNull(decision.admission)),
                        requireNotNull(mutation),
                    ),
                )
                CandidateValidationResult.AcceptedNew(candidateId, memoryId)
            }
            CandidateValidationOutcome.REINFORCE_EXISTING -> {
                val memoryId = requireNotNull(targetId)
                requireWriteSuccess(
                    memoryTransactions.reinforceInCurrentTransaction(
                        ReinforceMemorySetInput(
                            memoryId = memoryId,
                            evidence = context.evidence.map { evidence ->
                                ReinforcementEvidenceInput(
                                    experienceId = evidence.experienceId,
                                    epistemicBasis = context.candidate.proposedEpistemicBasis,
                                    sourceCertainty = context.candidate.proposedCertainty,
                                    lineageKey = evidence.lineageKey,
                                )
                            },
                            confirmedAt = context.evidence.maxOf { it.grounding.occurredAt },
                            occurredAt = input.validatedAt,
                            triggeringExperienceId = seedExperienceId,
                        ),
                        requireNotNull(mutation),
                    ),
                )
                requireWriteSuccess(memoryTransactions.consumeCandidateInCurrentTransaction(candidateId))
                CandidateValidationResult.ReinforcedExisting(candidateId, memoryId)
            }
            CandidateValidationOutcome.REFINE_EXISTING -> {
                val oldMemoryId = requireNotNull(targetId)
                val memoryId = requireNotNull(newMemoryId)
                requireWriteSuccess(
                    memoryTransactions.refineInCurrentTransaction(
                        RefineMemoryInput(
                            broaderMemoryId = oldMemoryId,
                            refinement = context.toValidatedInput(
                                memoryId,
                                input.validatedAt,
                                requireNotNull(decision.admission),
                            ).copy(validUntil = null),
                            disposition = requireNotNull(decision.refinementDisposition),
                            occurredAt = input.validatedAt,
                            triggeringExperienceId = seedExperienceId,
                        ),
                        requireNotNull(mutation),
                    ),
                )
                requireWriteSuccess(memoryTransactions.consumeCandidateInCurrentTransaction(candidateId))
                CandidateValidationResult.Refined(candidateId, oldMemoryId, memoryId)
            }
            CandidateValidationOutcome.SUPERSEDE_EXISTING -> {
                val oldMemoryId = requireNotNull(targetId)
                val memoryId = requireNotNull(newMemoryId)
                requireWriteSuccess(
                    memoryTransactions.supersedeInCurrentTransaction(
                        SupersedeMemoryInput(
                            historicalMemoryId = oldMemoryId,
                            replacement = context.toValidatedInput(
                                memoryId,
                                input.validatedAt,
                                requireNotNull(decision.admission),
                            ).copy(validUntil = null),
                            occurredAt = input.validatedAt,
                            triggeringExperienceId = seedExperienceId,
                        ),
                        requireNotNull(mutation),
                    ),
                )
                requireWriteSuccess(memoryTransactions.consumeCandidateInCurrentTransaction(candidateId))
                CandidateValidationResult.Superseded(candidateId, oldMemoryId, memoryId)
            }
            CandidateValidationOutcome.CORRECT_EXISTING -> {
                val oldMemoryId = requireNotNull(targetId)
                val memoryId = requireNotNull(newMemoryId)
                requireWriteSuccess(
                    memoryTransactions.correctInCurrentTransaction(
                        CorrectMemoryInput(
                            inaccurateMemoryId = oldMemoryId,
                            replacement = context.toValidatedInput(
                                memoryId,
                                input.validatedAt,
                                requireNotNull(decision.admission),
                            ),
                            occurredAt = input.validatedAt,
                            triggeringExperienceId = seedExperienceId,
                        ),
                        requireNotNull(mutation),
                    ),
                )
                requireWriteSuccess(memoryTransactions.consumeCandidateInCurrentTransaction(candidateId))
                CandidateValidationResult.Corrected(candidateId, oldMemoryId, memoryId)
            }
            CandidateValidationOutcome.DISPUTE_EXISTING -> {
                val existingMemoryId = requireNotNull(targetId)
                val competingMemoryId = requireNotNull(newMemoryId)
                requireWriteSuccess(
                    memoryTransactions.admitCandidateInCurrentTransaction(
                        context.toAdmitInput(
                            competingMemoryId,
                            input.validatedAt,
                            requireNotNull(decision.admission),
                        ),
                        requireNotNull(mutation),
                    ),
                )
                requireWriteSuccess(
                    memoryTransactions.disputeInCurrentTransaction(
                        DisputeMemoryInput(
                            memoryId = existingMemoryId,
                            competingMemoryId = competingMemoryId,
                            occurredAt = input.validatedAt,
                            triggeringExperienceId = seedExperienceId,
                        ),
                        requireNotNull(mutation),
                    ),
                )
                CandidateValidationResult.Disputed(
                    candidateId,
                    existingMemoryId,
                    competingMemoryId,
                )
            }
            CandidateValidationOutcome.DEFER -> {
                val nextState = requireNotNull(decision.deferState)
                val reason = requireNotNull(decision.deferReason)
                requireWriteSuccess(
                    memoryTransactions.transitionCandidateInCurrentTransaction(
                        candidateId,
                        CandidateMemoryState.READY_FOR_VALIDATION,
                        nextState,
                        input.validatedAt,
                    ),
                )
                CandidateValidationResult.Deferred(candidateId, nextState, reason)
            }
            CandidateValidationOutcome.REJECT -> {
                val reason = requireNotNull(decision.rejectReason)
                requireWriteSuccess(
                    memoryTransactions.transitionCandidateInCurrentTransaction(
                        candidateId,
                        CandidateMemoryState.READY_FOR_VALIDATION,
                        CandidateMemoryState.REJECTED,
                        input.validatedAt,
                    ),
                )
                CandidateValidationResult.Rejected(candidateId, reason)
            }
            CandidateValidationOutcome.MERGE -> {
                requireWriteSuccess(
                    memoryTransactions.transitionCandidateInCurrentTransaction(
                        candidateId,
                        CandidateMemoryState.READY_FOR_VALIDATION,
                        CandidateMemoryState.TENTATIVE,
                        input.validatedAt,
                    ),
                )
                CandidateValidationResult.MergeDeferredForConsolidation(
                    candidateId,
                    decision.targetMemoryIds,
                )
            }
        }
    }

    private fun GroundedCandidateValidationContext.toAdmitInput(
        memoryId: String,
        occurredAt: Long,
        admission: ValidationAdmissionMetadata,
    ): AdmitCandidateMemoryInput = AdmitCandidateMemoryInput(
        candidateId = candidate.candidateId,
        memoryId = memoryId,
        learnedAt = occurredAt,
        occurredAt = occurredAt,
        temporalState = admission.temporalState,
        validFrom = admission.validFrom,
        validUntil = admission.validUntil,
        lastConfirmedAt = evidence.maxOf { it.grounding.occurredAt },
        sensitivity = admission.sensitivity?.maxWithFloor(sensitivityFloor) ?: sensitivityFloor,
        significance = admission.significance,
        entityLinks = admission.entityLinks,
    )

    private fun GroundedCandidateValidationContext.toValidatedInput(
        memoryId: String,
        occurredAt: Long,
        admission: ValidationAdmissionMetadata,
    ): ValidatedMemoryInput = ValidatedMemoryInput(
        memoryId = memoryId,
        kind = candidate.proposedKind,
        scope = candidate.proposedScope,
        meaning = candidate.proposedMeaning,
        epistemicBasis = candidate.proposedEpistemicBasis,
        certainty = candidate.proposedCertainty,
        learnedAt = occurredAt,
        sensitivity = admission.sensitivity?.maxWithFloor(sensitivityFloor) ?: sensitivityFloor,
        evidence = evidence.map { source ->
            MemoryEvidenceInput(
                experienceId = source.experienceId,
                role = source.role.toMemoryEvidenceRole(),
                epistemicBasis = candidate.proposedEpistemicBasis,
                sourceCertainty = candidate.proposedCertainty,
                lineageKey = source.lineageKey,
            )
        },
        temporalState = admission.temporalState,
        validFrom = admission.validFrom,
        validUntil = admission.validUntil,
        lastConfirmedAt = evidence.maxOf { it.grounding.occurredAt },
        significance = admission.significance,
        entityLinks = admission.entityLinks,
    )

    private fun ValidationAdmissionMetadata.hasValidTemporalMetadata(): Boolean = when (temporalState) {
        TemporalState.CURRENT -> validUntil == null
        TemporalState.TIME_BOUNDED -> validFrom != null && validUntil != null && validFrom <= validUntil
        TemporalState.ATEMPORAL,
        TemporalState.UNKNOWN,
        -> validFrom == null && validUntil == null
        TemporalState.HISTORICAL -> validFrom == null || validUntil == null || validFrom <= validUntil
    }

    private fun ValidationMemorySnapshot.isMutableUnderstanding(): Boolean =
        truthState != MemoryTruthState.CORRECTED_FALSE &&
            retentionState != MemoryRetentionState.FORGOTTEN &&
            lifecycleState != MemoryLifecycleState.SUPERSEDED

    private fun CandidateEvidenceRole.toMemoryEvidenceRole(): EvidenceRole = when (this) {
        CandidateEvidenceRole.SEED,
        CandidateEvidenceRole.SUPPORTING,
        CandidateEvidenceRole.CONTEXT,
        -> EvidenceRole.SUPPORTS
        CandidateEvidenceRole.CONTRADICTING -> EvidenceRole.CONTRADICTS
        CandidateEvidenceRole.CORRECTING -> EvidenceRole.CORRECTS
    }

    private fun SensitivityLevel.maxWithFloor(floor: SensitivityLevel): SensitivityLevel =
        if (sensitivityRank() >= floor.sensitivityRank()) this else floor

    private fun requireWriteSuccess(result: MemoryWriteResult) {
        if (result is MemoryWriteResult.Failure) {
            abort(CandidateValidationError.TransactionFailure(result.error::class.java.simpleName))
        }
    }

    private fun invalidDecision(reason: InvalidValidationDecisionReason): CandidateValidationError =
        CandidateValidationError.InvalidValidationDecision(reason)

    private fun abort(error: CandidateValidationError): Nothing = throw CandidateValidationAbort(error)

    private fun storageFailure(
        operation: CandidateValidationOperation,
        failure: Exception,
    ): CandidateValidationResult.Failure = CandidateValidationResult.Failure(
        CandidateValidationError.StorageFailure(operation, failure::class.java.simpleName),
    )

    private sealed interface InitialRead {
        data class Ready(val context: GroundedCandidateValidationContext) : InitialRead
        data class AlreadyAdmitted(val memoryIds: List<String>) : InitialRead
        data object Suppressed : InitialRead
    }

    private companion object {
        val MEMORY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        val DEFER_STATES = setOf(CandidateMemoryState.PENDING_CONTEXT, CandidateMemoryState.TENTATIVE)
        val TARGETED_SCOPE_OUTCOMES = setOf(
            CandidateValidationOutcome.REINFORCE_EXISTING,
            CandidateValidationOutcome.REFINE_EXISTING,
            CandidateValidationOutcome.SUPERSEDE_EXISTING,
            CandidateValidationOutcome.CORRECT_EXISTING,
            CandidateValidationOutcome.DISPUTE_EXISTING,
        )
        val MATCHING_KIND_OUTCOMES = setOf(
            CandidateValidationOutcome.REINFORCE_EXISTING,
            CandidateValidationOutcome.SUPERSEDE_EXISTING,
            CandidateValidationOutcome.DISPUTE_EXISTING,
        )
    }
}

private fun CandidateValidationOutcome.createsMemory(): Boolean = when (this) {
    CandidateValidationOutcome.ACCEPT_NEW,
    CandidateValidationOutcome.REFINE_EXISTING,
    CandidateValidationOutcome.SUPERSEDE_EXISTING,
    CandidateValidationOutcome.CORRECT_EXISTING,
    CandidateValidationOutcome.DISPUTE_EXISTING,
    -> true
    else -> false
}

private fun CandidateValidationOutcome.mutatesCanonicalMemory(): Boolean = when (this) {
    CandidateValidationOutcome.ACCEPT_NEW,
    CandidateValidationOutcome.REINFORCE_EXISTING,
    CandidateValidationOutcome.REFINE_EXISTING,
    CandidateValidationOutcome.SUPERSEDE_EXISTING,
    CandidateValidationOutcome.CORRECT_EXISTING,
    CandidateValidationOutcome.DISPUTE_EXISTING,
    -> true
    CandidateValidationOutcome.MERGE,
    CandidateValidationOutcome.DEFER,
    CandidateValidationOutcome.REJECT,
    -> false
}

private fun CandidateValidationResult.affectedMemoryIds(): Set<String> = when (this) {
    is CandidateValidationResult.AcceptedNew -> setOf(memoryId)
    is CandidateValidationResult.ReinforcedExisting -> setOf(memoryId)
    is CandidateValidationResult.Refined -> setOf(oldMemoryId, newMemoryId)
    is CandidateValidationResult.Superseded -> setOf(oldMemoryId, newMemoryId)
    is CandidateValidationResult.Corrected -> setOf(oldMemoryId, newMemoryId)
    is CandidateValidationResult.Disputed -> setOf(existingMemoryId, competingMemoryId)
    else -> emptySet()
}
