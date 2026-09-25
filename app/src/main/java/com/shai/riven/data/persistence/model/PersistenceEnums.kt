package com.shai.riven.data.persistence.model

import androidx.room.TypeConverter

enum class ConversationStatus {
    ACTIVE,
    ARCHIVED,
}

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

enum class MessageDeliveryState {
    PERSISTED,
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class ExperienceType {
    CONVERSATION_MESSAGE,
    TOOL_RESULT,
    ACTION_STATE,
    SHARED_EVENT,
    CORRECTION,
    REMINDER_STATE,
    OTHER,
}

enum class ExperienceActor {
    SHAI,
    RIVEN,
    TOOL,
    SYSTEM,
    OTHER,
}

enum class ExperienceAvailability {
    AVAILABLE,
    DELETED,
}

enum class ExperienceMessageSourceRole {
    PRIMARY,
    SUPPORTING,
    CONTEXT,
    CORRECTING,
}

enum class EntityKind {
    PERSON,
    PLACE,
    SUBJECT,
    WORK,
    PROJECT,
    OBJECT,
    OTHER,
}

enum class MemoryKind {
    SEMANTIC,
    EPISODIC,
    RELATIONSHIP,
    SELF_DEVELOPMENT,
}

enum class MemoryScope {
    SHAI,
    RIVEN,
    SHARED,
    OTHER,
    MULTI_SCOPE,
}

enum class EpistemicBasis {
    DIRECT_USER_STATEMENT,
    DIRECT_RIVEN_EXPERIENCE,
    TOOL_OBSERVATION,
    EXPLICIT_CORRECTION,
    INFERENCE,
    CONSOLIDATION,
}

enum class MemoryCertainty {
    CERTAIN,
    PROBABLE,
    UNCERTAIN,
    DISPUTED,
}

enum class MemoryTruthState {
    SUPPORTED,
    DISPUTED,
    CORRECTED_FALSE,
}

enum class MemoryRetentionState {
    ACTIVE,
    DORMANT,
    FORGOTTEN,
}

enum class MemoryLifecycleState {
    VALIDATED,
    SUPERSEDED,
    RESOLVED,
}

enum class TemporalState {
    CURRENT,
    HISTORICAL,
    TIME_BOUNDED,
    ATEMPORAL,
    UNKNOWN,
}

enum class SignificanceLevel {
    NONE,
    LOW,
    MODERATE,
    HIGH,
    CORE,
}

class SignificanceLevelConverters {
    @TypeConverter
    fun fromSignificanceLevel(value: SignificanceLevel?): String? = value?.name

    @TypeConverter
    fun toSignificanceLevel(value: String?): SignificanceLevel? =
        value?.let(SignificanceLevel::valueOf)
}

enum class SensitivityLevel {
    STANDARD,
    SENSITIVE,
    HIGHLY_SENSITIVE,
}

enum class EvidenceRole {
    SUPPORTS,
    CONTRADICTS,
    REFINES,
    CORRECTS,
    RESOLVES,
}

enum class MemoryRelationshipType {
    DERIVED_FROM,
    REINFORCES,
    REFINES,
    SUPERSEDES,
    CORRECTS,
    CONTRADICTS,
    CONTEXTUAL_VARIANT_OF,
    PART_OF,
    SHARED_CULTURE,
    RELATED_TO,
}

enum class EntityLinkRole {
    ABOUT,
    INVOLVES,
    ACTOR,
    SUBJECT,
}

enum class CandidateMemoryState {
    PENDING_CONTEXT,
    TENTATIVE,
    READY_FOR_VALIDATION,
    ACCEPTED,
    REJECTED,
}

enum class CandidateEvidenceRole {
    SEED,
    SUPPORTING,
    CONTEXT,
    CONTRADICTING,
    CORRECTING,
}

enum class OpenLoopState {
    PLANNED,
    ACTIVE,
    WAITING,
    BLOCKED,
    COMPLETED,
    ABANDONED,
    EXPIRED,
}

enum class SuppressionKind {
    FORGET,
    DELETE,
}

enum class DerivedArtifactType {
    SUMMARY,
    EMBEDDING,
    INDEX,
    CACHE,
    SEARCH_DOCUMENT,
}

enum class DerivedArtifactState {
    CURRENT,
    STALE,
    INVALIDATED,
    REBUILD_PENDING,
}

enum class RepairJobType {
    REASSESS_PROVENANCE,
    INVALIDATE_DERIVED,
    REBUILD_DERIVED,
    PROPAGATE_CORRECTION,
    PROPAGATE_DELETION,
}

enum class RepairJobState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

enum class MemoryAuditAction {
    CREATED,
    STATE_CHANGED,
    CORRECTED,
    REFINED,
    SUPERSEDED,
    FORGOTTEN,
    REACTIVATED,
}

enum class OpenLoopAuditAction {
    CREATED,
    STATE_CHANGED,
    DUE_DATE_CHANGED,
    LINKED_MEMORY,
    UNLINKED_MEMORY,
}
