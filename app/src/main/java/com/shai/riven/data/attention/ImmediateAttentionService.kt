package com.shai.riven.data.attention

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.automaticmemory.AutomaticMemoryModelFailure
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionSignalEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignal
import com.shai.riven.data.persistence.model.AttentionSignalPolarity
import kotlinx.coroutines.CancellationException

/**
 * Persists structured Immediate Attention workflow state only. Semantic suppression belongs to
 * later candidate extraction/admission, where proposed retained meaning exists to compare.
 */
class ImmediateAttentionService(
    private val database: RivenDatabase,
    private val analyzer: ImmediateAttentionAnalyzer,
) {
    private val attentionDao = database.experienceAttentionDao()
    private val grounding = ImmediateAttentionGrounding(database)

    suspend fun assess(input: AssessImmediateAttentionInput): AssessImmediateAttentionResult {
        val snapshot = when (val loaded = readSnapshot(input.experienceId)) {
            is SnapshotRead.Success -> loaded.snapshot
            is SnapshotRead.Failure -> return AssessImmediateAttentionResult.Failure(loaded.error)
        }
        val proposal = try {
            analyzer.analyze(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            throw failure
        } catch (failure: Exception) {
            return AssessImmediateAttentionResult.Failure(
                ImmediateAttentionError.AnalyzerFailure(failure::class.java.simpleName),
            )
        }
        validateProposal(proposal)?.let { return AssessImmediateAttentionResult.Failure(it) }

        return try {
            database.withTransaction {
                grounding.revalidateSnapshotInCurrentTransaction(snapshot)
                val current = attentionDao.assessment(input.experienceId)
                val actualRevision = current?.revision ?: 0L
                if (actualRevision != input.expectedRevision) {
                    abort(ImmediateAttentionError.StaleAttentionRevision(input.expectedRevision, actualRevision))
                }
                if (actualRevision == Long.MAX_VALUE) {
                    abort(ImmediateAttentionError.AttentionRevisionOverflow(input.experienceId))
                }
                val next = ExperienceAttentionAssessmentEntity(
                    experienceId = input.experienceId,
                    outcome = proposal.outcome,
                    revision = actualRevision + 1L,
                    createdAt = current?.createdAt ?: input.assessedAt,
                    updatedAt = input.assessedAt,
                )
                if (current == null) {
                    attentionDao.insertAssessment(next)
                } else if (attentionDao.updateAssessment(next) != 1) {
                    abort(
                        ImmediateAttentionError.StorageFailure(
                            ImmediateAttentionOperation.PERSIST_ASSESSMENT,
                            "AssessmentUpdateCount",
                        ),
                    )
                }
                attentionDao.deleteSignals(input.experienceId)
                proposal.positiveSignals.forEach { signal ->
                    attentionDao.insertSignal(
                        ExperienceAttentionSignalEntity(
                            experienceId = input.experienceId,
                            signal = AttentionSignal.valueOf(signal.name),
                            polarity = AttentionSignalPolarity.POSITIVE,
                            createdAt = input.assessedAt,
                        ),
                    )
                }
                proposal.antiSignals.forEach { signal ->
                    attentionDao.insertSignal(
                        ExperienceAttentionSignalEntity(
                            experienceId = input.experienceId,
                            signal = AttentionSignal.valueOf(signal.name),
                            polarity = AttentionSignalPolarity.ANTI_SIGNAL,
                            createdAt = input.assessedAt,
                        ),
                    )
                }
                AssessImmediateAttentionResult.Persisted(next.toDomain(proposal))
            }
        } catch (attentionAbort: ImmediateAttentionAbort) {
            AssessImmediateAttentionResult.Failure(attentionAbort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (constraint: SQLiteConstraintException) {
            AssessImmediateAttentionResult.Failure(
                ImmediateAttentionError.StorageFailure(
                    ImmediateAttentionOperation.PERSIST_ASSESSMENT,
                    constraint::class.java.simpleName,
                ),
            )
        } catch (failure: Exception) {
            AssessImmediateAttentionResult.Failure(
                ImmediateAttentionError.StorageFailure(
                    ImmediateAttentionOperation.PERSIST_ASSESSMENT,
                    failure::class.java.simpleName,
                ),
            )
        }
    }

    suspend fun readAssessment(experienceId: String): ReadAttentionAssessmentResult = try {
        database.withTransaction {
            val assessment = attentionDao.assessment(experienceId)
                ?: return@withTransaction ReadAttentionAssessmentResult.NoAssessment(experienceId)
            ReadAttentionAssessmentResult.Assessment(grounding.readDomainAssessment(assessment))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ReadAttentionAssessmentResult.Failure(
            ImmediateAttentionError.StorageFailure(
                ImmediateAttentionOperation.READ_ASSESSMENT,
                failure::class.java.simpleName,
            ),
        )
    }

    suspend fun readActionableForwardAssessment(
        experienceId: String,
    ): ReadActionableForwardAssessmentResult = try {
        database.withTransaction {
            grounding.readActionableForwardInCurrentTransaction(experienceId)
        }
    } catch (attentionAbort: ImmediateAttentionAbort) {
        ReadActionableForwardAssessmentResult.Failure(attentionAbort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ReadActionableForwardAssessmentResult.Failure(
            ImmediateAttentionError.StorageFailure(
                ImmediateAttentionOperation.READ_ACTIONABLE_FORWARD,
                failure::class.java.simpleName,
            ),
        )
    }

    private suspend fun readSnapshot(experienceId: String): SnapshotRead = try {
        database.withTransaction {
            SnapshotRead.Success(grounding.readSnapshotInCurrentTransaction(experienceId))
        }
    } catch (attentionAbort: ImmediateAttentionAbort) {
        SnapshotRead.Failure(attentionAbort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        SnapshotRead.Failure(
            ImmediateAttentionError.StorageFailure(
                ImmediateAttentionOperation.READ_SNAPSHOT,
                failure::class.java.simpleName,
            ),
        )
    }

    private fun validateProposal(proposal: ImmediateAttentionProposal): ImmediateAttentionError? {
        if (proposal.positiveSignals.size != proposal.positiveSignals.toSet().size) {
            return ImmediateAttentionError.InvalidAnalyzerProposal(
                InvalidAttentionProposalReason.DUPLICATE_POSITIVE_SIGNAL,
            )
        }
        if (proposal.antiSignals.size != proposal.antiSignals.toSet().size) {
            return ImmediateAttentionError.InvalidAnalyzerProposal(
                InvalidAttentionProposalReason.DUPLICATE_ANTI_SIGNAL,
            )
        }
        if (proposal.outcome == AttentionOutcome.FORWARD_FOR_INTERPRETATION &&
            proposal.positiveSignals.isEmpty()
        ) {
            return ImmediateAttentionError.InvalidAnalyzerProposal(
                InvalidAttentionProposalReason.FORWARD_WITHOUT_POSITIVE_SIGNAL,
            )
        }
        return null
    }

    private fun ExperienceAttentionAssessmentEntity.toDomain(
        proposal: ImmediateAttentionProposal,
    ) = AttentionAssessment(
        experienceId = experienceId,
        outcome = outcome,
        positiveSignals = proposal.positiveSignals.toCollection(linkedSetOf()),
        antiSignals = proposal.antiSignals.toCollection(linkedSetOf()),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun abort(error: ImmediateAttentionError): Nothing = throw ImmediateAttentionAbort(error)

    private sealed interface SnapshotRead {
        data class Success(val snapshot: ImmediateAttentionSnapshot) : SnapshotRead
        data class Failure(val error: ImmediateAttentionError) : SnapshotRead
    }

}
