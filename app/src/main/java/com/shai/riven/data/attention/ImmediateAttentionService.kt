package com.shai.riven.data.attention

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionSignalEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignal
import com.shai.riven.data.persistence.model.AttentionSignalPolarity
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MessageRole
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
    private val memoryDao = database.memoryDao()
    private val timelineDao = database.conversationTimelineDao()
    private val attachmentDao = database.attachmentDao()
    private val timelineService = ConversationTimelineService(database)

    suspend fun assess(input: AssessImmediateAttentionInput): AssessImmediateAttentionResult {
        val snapshot = when (val loaded = readSnapshot(input.experienceId)) {
            is SnapshotRead.Success -> loaded.snapshot
            is SnapshotRead.Failure -> return AssessImmediateAttentionResult.Failure(loaded.error)
        }
        val proposal = try {
            analyzer.analyze(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return AssessImmediateAttentionResult.Failure(
                ImmediateAttentionError.AnalyzerFailure(failure::class.java.simpleName),
            )
        }
        validateProposal(proposal)?.let { return AssessImmediateAttentionResult.Failure(it) }

        return try {
            database.withTransaction {
                revalidateSnapshotInCurrentTransaction(snapshot)
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
            ReadAttentionAssessmentResult.Assessment(readDomainAssessment(assessment))
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
            val entity = attentionDao.assessment(experienceId)
                ?: abort(ImmediateAttentionError.MissingAssessment(experienceId))
            if (entity.outcome != AttentionOutcome.FORWARD_FOR_INTERPRETATION) {
                abort(ImmediateAttentionError.AssessmentNotForward(experienceId, entity.outcome))
            }
            val snapshot = buildSnapshotInCurrentTransaction(experienceId)
            ReadActionableForwardAssessmentResult.Actionable(
                assessment = readDomainAssessment(entity),
                snapshot = snapshot,
            )
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
            SnapshotRead.Success(buildSnapshotInCurrentTransaction(experienceId))
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

    private fun buildSnapshotInCurrentTransaction(experienceId: String): ImmediateAttentionSnapshot {
        val experience = memoryDao.experience(experienceId)
            ?: abort(ImmediateAttentionError.MissingExperience(experienceId))
        if (experience.availability != ExperienceAvailability.AVAILABLE) {
            abort(ImmediateAttentionError.ExperienceUnavailable(experienceId))
        }
        if (experience.experienceType == ExperienceType.MANUAL_MEMORY_INTENT) {
            abort(ImmediateAttentionError.AlreadyConsumedManualMemoryIntent(experienceId))
        }

        val sources = memoryDao.messageSourcesForExperience(experienceId)
        var sourceMessage: AttentionSourceMessage? = null
        var context = emptyList<AttentionContextMessage>()
        var timelineRevision: Long? = null
        if (experience.experienceType == ExperienceType.CONVERSATION_MESSAGE && sources.isEmpty()) {
            abort(ImmediateAttentionError.InvalidConversationSourceProvenance(experienceId))
        }
        if (sources.isNotEmpty()) {
            val source = requireValidConversationSource(experienceId, sources)
            val message = timelineDao.message(source.messageId)
                ?: abort(ImmediateAttentionError.MissingConversationSource(experienceId, source.messageId))
            val timeline = activeTimeline(message.conversationId)
            val sourceIndex = timeline.messages.indexOfFirst { it.id == message.id }
            if (sourceIndex == -1) {
                abort(ImmediateAttentionError.InactiveConversationSource(experienceId, message.id))
            }
            sourceMessage = AttentionSourceMessage(
                messageId = message.id,
                conversationId = message.conversationId,
                role = message.role,
                attachments = attachmentDao.availableAttachmentsForMessage(message.id).map { attachment ->
                    AttentionAttachmentMetadata(
                        attachmentId = attachment.id,
                        kind = attachment.kind,
                        mimeType = attachment.mimeType,
                        source = attachment.source,
                    )
                },
            )
            context = boundedContext(timeline.messages.take(sourceIndex))
            timelineRevision = timeline.timelineRevision
        }

        return ImmediateAttentionSnapshot(
            experienceId = experience.id,
            experienceType = experience.experienceType,
            actor = experience.actor,
            sourceContent = experience.sourceContent,
            occurredAt = experience.occurredAt,
            sensitivity = experience.sensitivity,
            sourceMessage = sourceMessage,
            precedingActiveContext = context,
            groundedEntityLinks = memoryDao.entityLinksForExperience(experienceId).map { link ->
                AttentionEntityLink(link.entityId, link.role)
            },
            timelineRevision = timelineRevision,
        )
    }

    private fun revalidateSnapshotInCurrentTransaction(snapshot: ImmediateAttentionSnapshot) {
        val experience = memoryDao.experience(snapshot.experienceId)
            ?: abort(ImmediateAttentionError.MissingExperience(snapshot.experienceId))
        if (experience.availability != ExperienceAvailability.AVAILABLE) {
            abort(ImmediateAttentionError.ExperienceUnavailable(snapshot.experienceId))
        }
        if (experience.experienceType == ExperienceType.MANUAL_MEMORY_INTENT) {
            abort(ImmediateAttentionError.AlreadyConsumedManualMemoryIntent(snapshot.experienceId))
        }
        val storedSources = memoryDao.messageSourcesForExperience(snapshot.experienceId)
        val source = snapshot.sourceMessage
        if (source == null) {
            if (experience.experienceType == ExperienceType.CONVERSATION_MESSAGE || storedSources.isNotEmpty()) {
                abort(ImmediateAttentionError.InvalidConversationSourceProvenance(snapshot.experienceId))
            }
            return
        }
        val storedSource = requireValidConversationSource(snapshot.experienceId, storedSources)
        if (storedSource.messageId != source.messageId) {
            abort(ImmediateAttentionError.InvalidConversationSourceProvenance(snapshot.experienceId))
        }
        val currentMessage = timelineDao.message(source.messageId)
            ?: abort(ImmediateAttentionError.MissingConversationSource(snapshot.experienceId, source.messageId))
        val timeline = activeTimeline(currentMessage.conversationId)
        val expectedRevision = snapshot.timelineRevision
            ?: abort(ImmediateAttentionError.InvalidConversationSourceProvenance(snapshot.experienceId))
        if (timeline.timelineRevision != expectedRevision) {
            abort(
                ImmediateAttentionError.StaleAttentionContext(
                    snapshot.experienceId,
                    expectedRevision,
                    timeline.timelineRevision,
                ),
            )
        }
        if (timeline.messages.none { it.id == source.messageId }) {
            abort(ImmediateAttentionError.InactiveConversationSource(snapshot.experienceId, source.messageId))
        }
    }

    private fun requireValidConversationSource(
        experienceId: String,
        sources: List<ExperienceMessageSourceEntity>,
    ): ExperienceMessageSourceEntity {
        val source = sources.singleOrNull()
        if (source == null ||
            source.sourceOrder != 0 ||
            source.sourceRole != ExperienceMessageSourceRole.PRIMARY ||
            source.characterStart != null ||
            source.characterEnd != null
        ) {
            abort(ImmediateAttentionError.InvalidConversationSourceProvenance(experienceId))
        }
        return source
    }

    private fun activeTimeline(conversationId: String): ConversationTimelineService.ActiveTimelineSnapshot = try {
        timelineService.activeTimelineInCurrentTransaction(conversationId)
    } catch (timelineAbort: ConversationTimelineService.TimelineAbort) {
        throw ImmediateAttentionAbort(
            ImmediateAttentionError.StorageFailure(
                ImmediateAttentionOperation.READ_SNAPSHOT,
                timelineAbort.error::class.java.simpleName,
            ),
        )
    }

    private fun boundedContext(messages: List<com.shai.riven.data.persistence.entity.MessageEntity>): List<AttentionContextMessage> {
        val selected = ArrayDeque<AttentionContextMessage>()
        var characterCount = 0
        for (message in messages.asReversed()) {
            if (message.role == MessageRole.SYSTEM) continue
            if (selected.size == MAX_ATTENTION_CONTEXT_MESSAGES) break
            if (characterCount + message.content.length > MAX_ATTENTION_CONTEXT_CHARS) break
            selected.addFirst(
                AttentionContextMessage(
                    messageId = message.id,
                    role = message.role,
                    content = message.content,
                    createdAt = message.createdAt,
                ),
            )
            characterCount += message.content.length
        }
        return selected.toList()
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

    private fun readDomainAssessment(entity: ExperienceAttentionAssessmentEntity): AttentionAssessment {
        val signals = attentionDao.signals(entity.experienceId)
        return AttentionAssessment(
            experienceId = entity.experienceId,
            outcome = entity.outcome,
            positiveSignals = signals
                .filter { it.polarity == AttentionSignalPolarity.POSITIVE }
                .mapTo(linkedSetOf()) { PositiveAttentionSignal.valueOf(it.signal.name) },
            antiSignals = signals
                .filter { it.polarity == AttentionSignalPolarity.ANTI_SIGNAL }
                .mapTo(linkedSetOf()) { AttentionAntiSignal.valueOf(it.signal.name) },
            revision = entity.revision,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
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

    private class ImmediateAttentionAbort(val error: ImmediateAttentionError) : RuntimeException()
}
