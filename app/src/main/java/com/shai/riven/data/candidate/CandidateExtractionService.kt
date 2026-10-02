package com.shai.riven.data.candidate

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.automaticmemory.AutomaticMemoryModelFailure
import com.shai.riven.data.attention.ImmediateAttentionAbort
import com.shai.riven.data.attention.ImmediateAttentionError
import com.shai.riven.data.attention.ImmediateAttentionGrounding
import com.shai.riven.data.attention.PositiveAttentionSignal
import com.shai.riven.data.attention.ReadActionableForwardAssessmentResult
import com.shai.riven.data.memory.CandidateEvidenceWriteInput
import com.shai.riven.data.memory.CreateCandidateMemoryInput
import com.shai.riven.data.memory.InvalidCandidateCreationReason
import com.shai.riven.data.memory.MAX_CANDIDATE_ID_CHARS
import com.shai.riven.data.memory.MAX_CANDIDATE_MEANING_CHARS
import com.shai.riven.data.memory.MemoryTransactionService
import com.shai.riven.data.memory.MemoryWriteError
import com.shai.riven.data.memory.MemoryWriteResult
import com.shai.riven.data.memory.sourceClaimSuppressionHash
import com.shai.riven.data.memory.sourceIdentityHash
import com.shai.riven.data.memory.sourceLineageHash
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.MessageRole
import java.util.UUID
import kotlinx.coroutines.CancellationException

class CandidateExtractionService(
    private val database: RivenDatabase,
    private val extractor: CandidateMemoryExtractor,
    private val candidateIdGenerator: CandidateIdGenerator = CandidateIdGenerator { UUID.randomUUID().toString() },
    private val memoryTransactionService: MemoryTransactionService = MemoryTransactionService(database),
) {
    private val grounding = ImmediateAttentionGrounding(database)
    private val memoryDao = database.memoryDao()
    private val maintenanceDao = database.maintenanceDao()

    suspend fun extract(input: ExtractCandidateMemoriesInput): CandidateExtractionResult {
        val actionable = try {
            database.withTransaction {
                grounding.readActionableForwardInCurrentTransaction(input.experienceId)
            }
        } catch (abort: ImmediateAttentionAbort) {
            return CandidateExtractionResult.Failure(mapAttentionError(abort.error, input.experienceId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            throw failure
        } catch (failure: Exception) {
            return CandidateExtractionResult.Failure(
                CandidateExtractionError.StorageFailure(
                    CandidateExtractionOperation.READ_ACTIONABLE_ATTENTION,
                    failure::class.java.simpleName,
                ),
            )
        }

        val snapshot = actionable.toExtractionSnapshot()
        val proposal = try {
            extractor.extract(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            throw failure
        } catch (failure: Exception) {
            return CandidateExtractionResult.Failure(
                CandidateExtractionError.ExtractorFailure(failure::class.java.simpleName),
            )
        }
        val prepared = try {
            validateAndPrepare(snapshot, proposal)
        } catch (abort: CandidateExtractionAbort) {
            return CandidateExtractionResult.Failure(abort.error)
        }

        return try {
            database.withTransaction {
                grounding.revalidateActionableForwardInCurrentTransaction(
                    expectedAssessment = actionable.assessment,
                    snapshot = actionable.snapshot,
                )
                if (prepared.isEmpty()) {
                    return@withTransaction CandidateExtractionResult.NoCandidates(
                        experienceId = input.experienceId,
                        attentionRevision = actionable.assessment.revision,
                    )
                }

                val createdIds = mutableListOf<String>()
                val existingIds = mutableListOf<String>()
                val existingMemoryIds = mutableListOf<String>()
                val admittedEvidence = memoryDao.memoryEvidenceForExperience(input.experienceId)
                var suppressedCount = 0
                prepared.forEach { candidate ->
                    val claimSuppressionHash = sourceClaimSuppressionHash(
                        input.experienceId,
                        candidate.lineageKey,
                    )
                    val exactLineageHash = sourceLineageHash(input.experienceId, candidate.lineageKey)
                    if (
                        maintenanceDao.suppressionTombstone(claimSuppressionHash)?.isActive == true ||
                        maintenanceDao.suppressionTombstone(exactLineageHash)?.isActive == true ||
                        maintenanceDao.activeSuppressionCoverageOverlapCount(
                            sourceIdentityHash(input.experienceId),
                            candidate.sourceAnchor.startOffset,
                            candidate.sourceAnchor.endOffsetExclusive,
                        ) != 0
                    ) {
                        suppressedCount += 1
                        return@forEach
                    }

                    val admittedMemoryId = admittedEvidence.firstOrNull { evidence ->
                            sourceClaimSuppressionHash(evidence.experienceId, evidence.lineageKey) ==
                                claimSuppressionHash
                        }
                        ?.memoryId
                    if (admittedMemoryId != null) {
                        existingMemoryIds += admittedMemoryId
                        return@forEach
                    }

                    val existingId = resolveExistingCandidate(input.experienceId, candidate, input.extractedAt)
                    if (existingId != null) {
                        existingIds += existingId
                        return@forEach
                    }

                    val candidateId = nextCandidateId()
                    val write = memoryTransactionService.createCandidateInCurrentTransaction(
                        CreateCandidateMemoryInput(
                            candidateId = candidateId,
                            proposedKind = candidate.proposal.proposedKind,
                            proposedScope = candidate.proposal.proposedScope,
                            proposedMeaning = candidate.proposal.proposedMeaning,
                            proposedEpistemicBasis = candidate.proposal.proposedEpistemicBasis,
                            proposedCertainty = candidate.proposal.proposedCertainty,
                            state = candidate.proposal.proposedState,
                            sensitivity = candidate.proposal.proposedSensitivity,
                            seedEvidence = CandidateEvidenceWriteInput(
                                candidateId = candidateId,
                                experienceId = input.experienceId,
                                evidenceOrder = 0,
                                role = CandidateEvidenceRole.SEED,
                                lineageKey = candidate.lineageKey,
                                createdAt = input.extractedAt,
                            ),
                            occurredAt = input.extractedAt,
                        ),
                    )
                    when (write) {
                        is MemoryWriteResult.Success -> createdIds += candidateId
                        is MemoryWriteResult.Failure -> abort(mapMemoryWriteError(write.error, candidate.index))
                    }
                }
                CandidateExtractionResult.Extracted(
                    experienceId = input.experienceId,
                    attentionRevision = actionable.assessment.revision,
                    createdCandidateIds = createdIds,
                    existingCandidateIds = existingIds,
                    existingMemoryIds = existingMemoryIds.distinct(),
                    suppressedLineageCount = suppressedCount,
                )
            }
        } catch (abort: ImmediateAttentionAbort) {
            CandidateExtractionResult.Failure(mapAttentionError(abort.error, input.experienceId))
        } catch (abort: CandidateExtractionAbort) {
            CandidateExtractionResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (constraint: SQLiteConstraintException) {
            CandidateExtractionResult.Failure(
                CandidateExtractionError.StorageFailure(
                    CandidateExtractionOperation.PERSIST_CANDIDATES,
                    constraint::class.java.simpleName,
                ),
            )
        } catch (failure: Exception) {
            CandidateExtractionResult.Failure(
                CandidateExtractionError.StorageFailure(
                    CandidateExtractionOperation.PERSIST_CANDIDATES,
                    failure::class.java.simpleName,
                ),
            )
        }
    }

    suspend fun readCandidatesSeededByExperience(
        experienceId: String,
    ): ReadCandidatesForExperienceResult = try {
        database.withTransaction {
            if (memoryDao.experience(experienceId) == null) {
                abort(CandidateExtractionError.MissingExperience(experienceId))
            }
            val snapshots = memoryDao.candidateSeedEvidenceForExperience(experienceId).map { evidence ->
                val candidate = memoryDao.candidateMemory(evidence.candidateMemoryId)
                    ?: abort(
                        CandidateExtractionError.CandidateLineageConflict(
                            evidence.lineageKey,
                            CandidateLineageConflictReason.MISSING_CANDIDATE,
                        ),
                    )
                candidate.toSnapshot(evidence.lineageKey)
            }
            ReadCandidatesForExperienceResult.Candidates(experienceId, snapshots)
        }
    } catch (abort: CandidateExtractionAbort) {
        ReadCandidatesForExperienceResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ReadCandidatesForExperienceResult.Failure(
            CandidateExtractionError.StorageFailure(
                CandidateExtractionOperation.READ_CANDIDATES,
                failure::class.java.simpleName,
            ),
        )
    }

    private fun validateAndPrepare(
        snapshot: CandidateExtractionSnapshot,
        proposal: CandidateExtractionProposal,
    ): List<PreparedCandidate> {
        if (proposal.candidates.size > MAX_CANDIDATES_PER_EXTRACTION) {
            abort(
                CandidateExtractionError.TooManyCandidates(
                    MAX_CANDIDATES_PER_EXTRACTION,
                    proposal.candidates.size,
                ),
            )
        }
        val sourceClaims = candidateSourceClaims(snapshot.sourceContent).associateBy { it.id }
        val firstIndexByClaim = mutableMapOf<String, Int>()
        return proposal.candidates.mapIndexed { index, candidate ->
            when {
                candidate.proposedMeaning.isBlank() -> abort(
                    CandidateExtractionError.InvalidCandidateProposal(
                        index,
                        InvalidCandidateProposalReason.BLANK_MEANING,
                    ),
                )
                candidate.proposedMeaning.length > MAX_CANDIDATE_MEANING_CHARS -> abort(
                    CandidateExtractionError.InvalidCandidateProposal(
                        index,
                        InvalidCandidateProposalReason.MEANING_TOO_LONG,
                    ),
                )
                candidate.proposedState !in ALLOWED_PROPOSAL_STATES -> abort(
                    CandidateExtractionError.InvalidCandidateProposal(
                        index,
                        InvalidCandidateProposalReason.DISALLOWED_STATE,
                    ),
                )
                candidate.sourceClaimId.isBlank() -> abort(
                    CandidateExtractionError.InvalidCandidateProposal(
                        index,
                        InvalidCandidateProposalReason.MISSING_SOURCE_CLAIM_ID,
                    ),
                )
                candidate.sourceClaimId !in sourceClaims -> abort(
                    CandidateExtractionError.InvalidCandidateProposal(
                        index,
                        InvalidCandidateProposalReason.UNKNOWN_SOURCE_CLAIM_ID,
                    ),
                )
                candidate.sourceAnchor.text.isBlank() -> abort(
                    CandidateExtractionError.InvalidCandidateProposal(
                        index,
                        InvalidCandidateProposalReason.MISSING_SOURCE_ANCHOR,
                    ),
                )
                candidate.sourceAnchor.occurrence < 0 -> abort(
                    CandidateExtractionError.InvalidCandidateProposal(
                        index,
                        InvalidCandidateProposalReason.INVALID_SOURCE_ANCHOR_OCCURRENCE,
                    ),
                )
            }
            validateEpistemicBasis(snapshot, candidate, index)
            val semanticClaimKey = candidateSemanticClaimKey(snapshot.experienceId, candidate)
            firstIndexByClaim.putIfAbsent(semanticClaimKey, index)?.let { firstIndex ->
                abort(CandidateExtractionError.DuplicateCandidateProposal(firstIndex, index))
            }
            val sourceAnchor = resolveCandidateSourceAnchor(
                checkNotNull(sourceClaims[candidate.sourceClaimId]),
                candidate.sourceAnchor,
            ) ?: abort(
                CandidateExtractionError.InvalidCandidateProposal(
                    index,
                    InvalidCandidateProposalReason.SOURCE_ANCHOR_NOT_FOUND,
                ),
            )
            val lineageKey = candidateClaimLineageKey(snapshot.experienceId, candidate, sourceAnchor)
            PreparedCandidate(index, candidate, lineageKey, sourceAnchor)
        }
    }

    private fun validateEpistemicBasis(
        snapshot: CandidateExtractionSnapshot,
        proposal: CandidateMemoryProposal,
        index: Int,
    ) {
        val sourceRole = snapshot.sourceMessage?.role
        val shaiGrounded = snapshot.actor == ExperienceActor.SHAI &&
            (sourceRole == null || sourceRole == MessageRole.USER)
        val rivenGrounded = snapshot.actor == ExperienceActor.RIVEN &&
            (sourceRole == null || sourceRole == MessageRole.ASSISTANT)
        val toolGrounded = snapshot.actor == ExperienceActor.TOOL &&
            (sourceRole == null || sourceRole == MessageRole.TOOL)
        val failure = when (proposal.proposedEpistemicBasis) {
            EpistemicBasis.DIRECT_USER_STATEMENT -> if (shaiGrounded) null else {
                InvalidEpistemicBasisReason.SOURCE_ACTOR_MISMATCH
            }
            EpistemicBasis.DIRECT_RIVEN_EXPERIENCE -> if (rivenGrounded) null else {
                InvalidEpistemicBasisReason.SOURCE_ACTOR_MISMATCH
            }
            EpistemicBasis.TOOL_OBSERVATION -> if (toolGrounded) null else {
                InvalidEpistemicBasisReason.SOURCE_ACTOR_MISMATCH
            }
            EpistemicBasis.EXPLICIT_CORRECTION -> when {
                !shaiGrounded && !rivenGrounded -> InvalidEpistemicBasisReason.SOURCE_ACTOR_MISMATCH
                PositiveAttentionSignal.CORRECTION_OR_REVISION !in snapshot.positiveSignals -> {
                    InvalidEpistemicBasisReason.CORRECTION_SIGNAL_REQUIRED
                }
                else -> null
            }
            EpistemicBasis.INFERENCE -> null
            EpistemicBasis.CONSOLIDATION -> InvalidEpistemicBasisReason.CONSOLIDATION_NOT_ALLOWED
        }
        failure?.let { reason ->
            abort(
                CandidateExtractionError.InvalidEpistemicBasis(
                    proposalIndex = index,
                    basis = proposal.proposedEpistemicBasis,
                    reason = reason,
                ),
            )
        }
    }

    private fun resolveExistingCandidate(
        experienceId: String,
        prepared: PreparedCandidate,
        updatedAt: Long,
    ): String? {
        val claimHash = sourceClaimSuppressionHash(experienceId, prepared.lineageKey)
        val seeds = memoryDao.candidateSeedEvidenceForExperience(experienceId)
            .filter { evidence ->
                sourceClaimSuppressionHash(evidence.experienceId, evidence.lineageKey) == claimHash
            }
        if (seeds.isEmpty()) return null
        if (seeds.size != 1) {
            abort(
                CandidateExtractionError.CandidateLineageConflict(
                    prepared.lineageKey,
                    CandidateLineageConflictReason.MULTIPLE_CANDIDATES,
                ),
            )
        }
        val candidate = memoryDao.candidateMemory(seeds.single().candidateMemoryId)
            ?: abort(
                CandidateExtractionError.CandidateLineageConflict(
                    prepared.lineageKey,
                    CandidateLineageConflictReason.MISSING_CANDIDATE,
                ),
            )
        if (candidate.state == CandidateMemoryState.ACCEPTED) {
            abort(
                CandidateExtractionError.CandidateLineageConflict(
                    prepared.lineageKey,
                    CandidateLineageConflictReason.ACCEPTED_RESIDUE,
                ),
            )
        }
        if (!candidate.matchesStableClaim(prepared.proposal)) {
            abort(
                CandidateExtractionError.CandidateLineageConflict(
                    prepared.lineageKey,
                    CandidateLineageConflictReason.PROPOSAL_MISMATCH,
                ),
            )
        }
        val nextState = if (
            candidate.state.progressRank() < prepared.proposal.proposedState.progressRank()
        ) {
            prepared.proposal.proposedState
        } else {
            candidate.state
        }
        if (
            candidate.proposedCertainty != prepared.proposal.proposedCertainty ||
            candidate.sensitivity != prepared.proposal.proposedSensitivity ||
            candidate.state != nextState
        ) {
            when (
                val refreshed = memoryTransactionService.refreshCandidateInCurrentTransaction(
                    candidateId = candidate.id,
                    expectedState = candidate.state,
                    nextState = nextState,
                    proposedCertainty = prepared.proposal.proposedCertainty,
                    sensitivity = prepared.proposal.proposedSensitivity,
                    updatedAt = updatedAt,
                )
            ) {
                is MemoryWriteResult.Success -> Unit
                is MemoryWriteResult.Failure -> abort(mapMemoryWriteError(refreshed.error, prepared.index))
            }
        }
        return candidate.id
    }

    private fun nextCandidateId(): String {
        val id = try {
            candidateIdGenerator.nextId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            abort(
                CandidateExtractionError.CandidateIdGenerationFailure(
                    CandidateIdGenerationFailureReason.GENERATOR_EXCEPTION,
                    failure::class.java.simpleName,
                ),
            )
        }
        if (id.isBlank()) {
            abort(
                CandidateExtractionError.CandidateIdGenerationFailure(
                    CandidateIdGenerationFailureReason.BLANK_ID,
                ),
            )
        }
        if (id.length > MAX_CANDIDATE_ID_CHARS) {
            abort(
                CandidateExtractionError.CandidateIdGenerationFailure(
                    CandidateIdGenerationFailureReason.ID_TOO_LONG,
                ),
            )
        }
        return id
    }

    private fun mapAttentionError(
        error: ImmediateAttentionError,
        experienceId: String,
    ): CandidateExtractionError = when (error) {
        is ImmediateAttentionError.MissingExperience -> CandidateExtractionError.MissingExperience(error.experienceId)
        is ImmediateAttentionError.ExperienceUnavailable -> {
            CandidateExtractionError.ExperienceUnavailable(error.experienceId)
        }
        is ImmediateAttentionError.AlreadyConsumedManualMemoryIntent -> {
            CandidateExtractionError.AlreadyConsumedManualMemoryIntent(error.experienceId)
        }
        is ImmediateAttentionError.MissingConversationSource -> {
            CandidateExtractionError.MissingConversationSource(error.experienceId, error.messageId)
        }
        is ImmediateAttentionError.InvalidConversationSourceProvenance -> {
            CandidateExtractionError.InvalidConversationSourceProvenance(error.experienceId)
        }
        is ImmediateAttentionError.InactiveConversationSource -> {
            CandidateExtractionError.InactiveConversationSource(error.experienceId, error.messageId)
        }
        is ImmediateAttentionError.StaleAttentionContext -> {
            CandidateExtractionError.StaleCandidateExtractionContext(
                experienceId = error.experienceId,
                reason = StaleCandidateExtractionReason.TIMELINE_CHANGED,
                expectedRevision = error.expectedTimelineRevision,
                actualRevision = error.actualTimelineRevision,
            )
        }
        is ImmediateAttentionError.StaleAttentionRevision -> {
            CandidateExtractionError.StaleCandidateExtractionContext(
                experienceId = experienceId,
                reason = StaleCandidateExtractionReason.ATTENTION_REVISION_CHANGED,
                expectedRevision = error.expected,
                actualRevision = error.actual,
            )
        }
        is ImmediateAttentionError.MissingAssessment -> CandidateExtractionError.AttentionNotFound(error.experienceId)
        is ImmediateAttentionError.AssessmentNotForward -> {
            CandidateExtractionError.AttentionNotForward(error.experienceId, error.outcome)
        }
        is ImmediateAttentionError.StorageFailure -> CandidateExtractionError.StorageFailure(
            CandidateExtractionOperation.READ_ACTIONABLE_ATTENTION,
            error.causeType,
        )
        else -> CandidateExtractionError.StorageFailure(
            CandidateExtractionOperation.READ_ACTIONABLE_ATTENTION,
            error::class.java.simpleName,
        )
    }

    private fun mapMemoryWriteError(
        error: MemoryWriteError,
        proposalIndex: Int,
    ): CandidateExtractionError = when (error) {
        is MemoryWriteError.CandidateAlreadyExists -> CandidateExtractionError.CandidateIdCollision(error.candidateId)
        is MemoryWriteError.ExperienceNotFound -> CandidateExtractionError.MissingExperience(error.experienceId)
        is MemoryWriteError.ExperienceUnavailable -> CandidateExtractionError.ExperienceUnavailable(error.experienceId)
        is MemoryWriteError.InvalidCandidateCreation -> CandidateExtractionError.InvalidCandidateProposal(
            proposalIndex,
            when (error.reason) {
                InvalidCandidateCreationReason.BLANK_MEANING -> InvalidCandidateProposalReason.BLANK_MEANING
                InvalidCandidateCreationReason.MEANING_TOO_LONG -> InvalidCandidateProposalReason.MEANING_TOO_LONG
                else -> InvalidCandidateProposalReason.DISALLOWED_STATE
            },
        )
        is MemoryWriteError.StorageFailure -> CandidateExtractionError.StorageFailure(
            CandidateExtractionOperation.PERSIST_CANDIDATES,
            error.causeType,
        )
        else -> CandidateExtractionError.StorageFailure(
            CandidateExtractionOperation.PERSIST_CANDIDATES,
            error::class.java.simpleName,
        )
    }

    private fun ReadActionableForwardAssessmentResult.Actionable.toExtractionSnapshot() =
        CandidateExtractionSnapshot(
            experienceId = snapshot.experienceId,
            experienceType = snapshot.experienceType,
            actor = snapshot.actor,
            sourceContent = snapshot.sourceContent,
            occurredAt = snapshot.occurredAt,
            sensitivity = snapshot.sensitivity,
            sourceMessage = snapshot.sourceMessage,
            precedingActiveContext = snapshot.precedingActiveContext,
            followingActiveContext = snapshot.followingActiveContext,
            groundedEntityLinks = snapshot.groundedEntityLinks,
            attentionRevision = assessment.revision,
            attentionOutcome = assessment.outcome,
            positiveSignals = assessment.positiveSignals,
            antiSignals = assessment.antiSignals,
        )

    private fun CandidateMemoryEntity.matchesStableClaim(proposal: CandidateMemoryProposal): Boolean =
        proposedKind == proposal.proposedKind &&
            proposedScope == proposal.proposedScope &&
            proposedMeaning == proposal.proposedMeaning &&
            proposedEpistemicBasis == proposal.proposedEpistemicBasis

    private fun CandidateMemoryEntity.toSnapshot(lineageKey: String) = CandidateMemorySnapshot(
        candidateId = id,
        proposedKind = proposedKind,
        proposedScope = proposedScope,
        proposedMeaning = proposedMeaning,
        proposedEpistemicBasis = proposedEpistemicBasis,
        proposedCertainty = proposedCertainty,
        state = state,
        sensitivity = sensitivity,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lineageKey = lineageKey,
    )

    private fun CandidateMemoryState.progressRank(): Int = when (this) {
        CandidateMemoryState.PENDING_CONTEXT -> 0
        CandidateMemoryState.TENTATIVE -> 1
        CandidateMemoryState.READY_FOR_VALIDATION -> 2
        else -> Int.MAX_VALUE
    }

    private fun abort(error: CandidateExtractionError): Nothing = throw CandidateExtractionAbort(error)

    private data class PreparedCandidate(
        val index: Int,
        val proposal: CandidateMemoryProposal,
        val lineageKey: String,
        val sourceAnchor: ResolvedCandidateSourceAnchor,
    )

    private class CandidateExtractionAbort(val error: CandidateExtractionError) : RuntimeException()

    private companion object {
        val ALLOWED_PROPOSAL_STATES = setOf(
            CandidateMemoryState.PENDING_CONTEXT,
            CandidateMemoryState.TENTATIVE,
            CandidateMemoryState.READY_FOR_VALIDATION,
        )
    }
}
