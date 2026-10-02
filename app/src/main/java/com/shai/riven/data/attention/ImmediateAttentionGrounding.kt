package com.shai.riven.data.attention

import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignalPolarity
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MessageRole

/**
 * Canonical grounded Attention snapshot and actionability checks shared by Attention assessment
 * and the next Candidate Extraction stage. Every method must be called inside a caller-owned Room
 * transaction.
 */
internal class ImmediateAttentionGrounding(
    private val database: RivenDatabase,
) {
    private val attentionDao = database.experienceAttentionDao()
    private val memoryDao = database.memoryDao()
    private val timelineDao = database.conversationTimelineDao()
    private val attachmentDao = database.attachmentDao()
    private val timelineService = ConversationTimelineService(database)

    fun readActionableForwardInCurrentTransaction(
        experienceId: String,
    ): ReadActionableForwardAssessmentResult.Actionable {
        if (memoryDao.experience(experienceId) == null) {
            abort(ImmediateAttentionError.MissingExperience(experienceId))
        }
        val entity = attentionDao.assessment(experienceId)
            ?: abort(ImmediateAttentionError.MissingAssessment(experienceId))
        if (entity.outcome != AttentionOutcome.FORWARD_FOR_INTERPRETATION) {
            abort(ImmediateAttentionError.AssessmentNotForward(experienceId, entity.outcome))
        }
        return ReadActionableForwardAssessmentResult.Actionable(
            assessment = readDomainAssessment(entity),
            snapshot = readSnapshotInCurrentTransaction(experienceId),
        )
    }

    fun revalidateActionableForwardInCurrentTransaction(
        expectedAssessment: AttentionAssessment,
        snapshot: ImmediateAttentionSnapshot,
    ) {
        revalidateSnapshotInCurrentTransaction(snapshot)
        val current = attentionDao.assessment(snapshot.experienceId)
            ?: abort(ImmediateAttentionError.MissingAssessment(snapshot.experienceId))
        if (current.outcome != AttentionOutcome.FORWARD_FOR_INTERPRETATION) {
            abort(ImmediateAttentionError.AssessmentNotForward(snapshot.experienceId, current.outcome))
        }
        if (current.revision != expectedAssessment.revision) {
            abort(
                ImmediateAttentionError.StaleAttentionRevision(
                    expected = expectedAssessment.revision,
                    actual = current.revision,
                ),
            )
        }
    }

    fun readSnapshotInCurrentTransaction(experienceId: String): ImmediateAttentionSnapshot {
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
        var followingContext = emptyList<AttentionContextMessage>()
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
            followingContext = boundedFollowingContext(timeline.messages.drop(sourceIndex + 1))
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
            followingActiveContext = followingContext,
            groundedEntityLinks = memoryDao.entityLinksForExperience(experienceId).map { link ->
                AttentionEntityLink(link.entityId, link.role)
            },
            timelineRevision = timelineRevision,
        )
    }

    fun revalidateSnapshotInCurrentTransaction(snapshot: ImmediateAttentionSnapshot) {
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

    fun readDomainAssessment(entity: ExperienceAttentionAssessmentEntity): AttentionAssessment {
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

    private fun boundedContext(
        messages: List<com.shai.riven.data.persistence.entity.MessageEntity>,
    ): List<AttentionContextMessage> {
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

    private fun boundedFollowingContext(
        messages: List<com.shai.riven.data.persistence.entity.MessageEntity>,
    ): List<AttentionContextMessage> {
        val selected = mutableListOf<AttentionContextMessage>()
        var characterCount = 0
        for (message in messages) {
            if (message.role == MessageRole.SYSTEM) continue
            if (selected.size == MAX_ATTENTION_FOLLOWING_MESSAGES) break
            if (characterCount + message.content.length > MAX_ATTENTION_FOLLOWING_CHARS) break
            selected += AttentionContextMessage(
                messageId = message.id,
                role = message.role,
                content = message.content,
                createdAt = message.createdAt,
            )
            characterCount += message.content.length
        }
        return selected
    }

    private fun abort(error: ImmediateAttentionError): Nothing = throw ImmediateAttentionAbort(error)
}

internal class ImmediateAttentionAbort(val error: ImmediateAttentionError) : RuntimeException()
