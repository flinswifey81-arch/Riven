package com.shai.riven.data.attention

import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel

const val MAX_ATTENTION_CONTEXT_MESSAGES = 12
const val MAX_ATTENTION_CONTEXT_CHARS = 32_768
const val MAX_ATTENTION_FOLLOWING_MESSAGES = 4
const val MAX_ATTENTION_FOLLOWING_CHARS = 8_192

enum class PositiveAttentionSignal {
    IDENTITY,
    PREFERENCE,
    RELATIONSHIP,
    AUTOBIOGRAPHICAL_EVENT,
    SELF_DEVELOPMENT,
    OPEN_LOOP,
    CORRECTION_OR_REVISION,
    REPETITION,
    EMOTIONAL_SIGNIFICANCE,
    PRACTICAL_SIGNIFICANCE,
    SHARED_CULTURE,
}

enum class AttentionAntiSignal {
    CONVERSATIONAL_FILLER,
    ONE_OFF_INCIDENTAL_DETAIL,
    TEMPORARY_STATE_NO_CONTINUING_RELEVANCE,
    MODEL_GENERATED_ASSUMPTION,
    DRAMATIC_OR_NONLITERAL_LANGUAGE,
    DUPLICATE_RESTATEMENT,
}

data class AttentionAttachmentMetadata(
    val attachmentId: String,
    val kind: AttachmentKind,
    val mimeType: String,
    val source: AttachmentSource,
)

data class AttentionContextMessage(
    val messageId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
)

data class AttentionEntityLink(
    val entityId: String,
    val role: EntityLinkRole,
)

data class AttentionSourceMessage(
    val messageId: String,
    val conversationId: String,
    val role: MessageRole,
    val attachments: List<AttentionAttachmentMetadata>,
)

data class ImmediateAttentionSnapshot(
    val experienceId: String,
    val experienceType: ExperienceType,
    val actor: ExperienceActor,
    val sourceContent: String?,
    val occurredAt: Long,
    val sensitivity: SensitivityLevel,
    val sourceMessage: AttentionSourceMessage?,
    val precedingActiveContext: List<AttentionContextMessage>,
    /** Completed-turn hindsight after the source; empty for non-conversation Experiences. */
    val followingActiveContext: List<AttentionContextMessage>,
    val groundedEntityLinks: List<AttentionEntityLink>,
    val timelineRevision: Long?,
)

data class ImmediateAttentionProposal(
    val outcome: AttentionOutcome,
    val positiveSignals: List<PositiveAttentionSignal> = emptyList(),
    val antiSignals: List<AttentionAntiSignal> = emptyList(),
)

fun interface ImmediateAttentionAnalyzer {
    suspend fun analyze(snapshot: ImmediateAttentionSnapshot): ImmediateAttentionProposal
}

data class AssessImmediateAttentionInput(
    val experienceId: String,
    val expectedRevision: Long,
    val assessedAt: Long,
)

data class AttentionAssessment(
    val experienceId: String,
    val outcome: AttentionOutcome,
    val positiveSignals: Set<PositiveAttentionSignal>,
    val antiSignals: Set<AttentionAntiSignal>,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class InvalidAttentionProposalReason {
    DUPLICATE_POSITIVE_SIGNAL,
    DUPLICATE_ANTI_SIGNAL,
    FORWARD_WITHOUT_POSITIVE_SIGNAL,
}

enum class ImmediateAttentionOperation {
    READ_SNAPSHOT,
    PERSIST_ASSESSMENT,
    READ_ASSESSMENT,
    READ_ACTIONABLE_FORWARD,
}

sealed interface ImmediateAttentionError {
    data class MissingExperience(val experienceId: String) : ImmediateAttentionError
    data class ExperienceUnavailable(val experienceId: String) : ImmediateAttentionError
    data class AlreadyConsumedManualMemoryIntent(val experienceId: String) : ImmediateAttentionError
    data class MissingConversationSource(val experienceId: String, val messageId: String) : ImmediateAttentionError
    data class InvalidConversationSourceProvenance(val experienceId: String) : ImmediateAttentionError
    data class InactiveConversationSource(val experienceId: String, val messageId: String) : ImmediateAttentionError
    data class StaleAttentionContext(
        val experienceId: String,
        val expectedTimelineRevision: Long,
        val actualTimelineRevision: Long,
    ) : ImmediateAttentionError

    data class StaleAttentionRevision(val expected: Long, val actual: Long) : ImmediateAttentionError
    data class AttentionRevisionOverflow(val experienceId: String) : ImmediateAttentionError
    data class InvalidAnalyzerProposal(val reason: InvalidAttentionProposalReason) : ImmediateAttentionError
    data class AnalyzerFailure(val causeType: String) : ImmediateAttentionError
    data class MissingAssessment(val experienceId: String) : ImmediateAttentionError
    data class AssessmentNotForward(
        val experienceId: String,
        val outcome: AttentionOutcome,
    ) : ImmediateAttentionError

    data class StorageFailure(
        val operation: ImmediateAttentionOperation,
        val causeType: String,
    ) : ImmediateAttentionError
}

sealed interface AssessImmediateAttentionResult {
    data class Persisted(val assessment: AttentionAssessment) : AssessImmediateAttentionResult
    data class Failure(val error: ImmediateAttentionError) : AssessImmediateAttentionResult
}

sealed interface ReadAttentionAssessmentResult {
    data class Assessment(val assessment: AttentionAssessment) : ReadAttentionAssessmentResult
    data class NoAssessment(val experienceId: String) : ReadAttentionAssessmentResult
    data class Failure(val error: ImmediateAttentionError) : ReadAttentionAssessmentResult
}

sealed interface ReadActionableForwardAssessmentResult {
    data class Actionable(
        val assessment: AttentionAssessment,
        val snapshot: ImmediateAttentionSnapshot,
    ) : ReadActionableForwardAssessmentResult

    data class Failure(val error: ImmediateAttentionError) : ReadActionableForwardAssessmentResult
}
