package com.shai.riven.data.memory.intent

import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState
import java.util.UUID

const val MAX_MANUAL_MEMORY_MEANING_CHARS = 65_536
const val MAX_MANUAL_MEMORY_ID_CHARS = 512

enum class ManualMemoryIntentKind {
    REMEMBER,
    CORRECT,
    FORGET,
    DELETE,
}

data class ManualMemorySignificanceInput(
    val autobiographical: SignificanceLevel? = null,
    val relationship: SignificanceLevel? = null,
    val emotional: SignificanceLevel? = null,
    val practical: SignificanceLevel? = null,
    val identity: SignificanceLevel? = null,
)

data class ManualMemoryEntityLinkInput(
    val entityId: String,
    val role: EntityLinkRole,
)

data class ManualMemoryRelationshipInput(
    val targetMemoryId: String,
    val relationshipType: MemoryRelationshipType,
)

data class ManualRememberMemoryInput(
    val meaning: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val certainty: MemoryCertainty,
    val sensitivity: SensitivityLevel,
    val occurredAt: Long,
    val temporalState: TemporalState = TemporalState.CURRENT,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val significance: ManualMemorySignificanceInput = ManualMemorySignificanceInput(),
    val entityLinks: List<ManualMemoryEntityLinkInput> = emptyList(),
    val relationships: List<ManualMemoryRelationshipInput> = emptyList(),
    val memoryId: String? = null,
    val experienceId: String? = null,
)

data class ManualCorrectMemoryInput(
    val memoryId: String,
    val replacementMeaning: String,
    val occurredAt: Long,
    val replacementMemoryId: String? = null,
    val experienceId: String? = null,
    val certainty: MemoryCertainty? = null,
)

data class ManualForgetMemoryInput(
    val memoryId: String,
    val occurredAt: Long,
)

data class ManualDeleteMemoryInput(
    val memoryId: String,
    val occurredAt: Long,
)

enum class ManualMemoryMaintenanceScheduling {
    NOT_REQUIRED,
    ENQUEUED,
    FAILED,
}

sealed interface ManualMemoryIntentResult {
    data class Remembered(
        val memoryId: String,
        val experienceId: String,
        val maintenanceScheduling: ManualMemoryMaintenanceScheduling,
    ) : ManualMemoryIntentResult

    data class Corrected(
        val inaccurateMemoryId: String,
        val replacementMemoryId: String,
        val experienceId: String,
        val maintenanceScheduling: ManualMemoryMaintenanceScheduling,
    ) : ManualMemoryIntentResult

    data class Forgotten(
        val memoryId: String,
        val maintenanceScheduling: ManualMemoryMaintenanceScheduling,
    ) : ManualMemoryIntentResult

    data class Deleted(
        val memoryId: String,
        val suppressionTombstoneIds: Set<String>,
        val maintenanceScheduling: ManualMemoryMaintenanceScheduling,
    ) : ManualMemoryIntentResult

    data class Failure(val error: ManualMemoryIntentError) : ManualMemoryIntentResult
}

enum class InvalidManualMeaningReason {
    BLANK,
    TOO_LONG,
}

enum class ManualMemoryIdField {
    MEMORY_ID,
    REPLACEMENT_MEMORY_ID,
    EXPERIENCE_ID,
    ENTITY_ID,
    RELATED_MEMORY_ID,
}

enum class ManualMemoryReferenceType {
    ENTITY,
    RELATED_MEMORY,
    EXPERIENCE,
}

enum class ManualSafeDeleteFailureCode {
    CANONICAL_REJECTION,
    STORAGE,
}

sealed interface ManualMemoryIntentError {
    data class InvalidMeaning(val reason: InvalidManualMeaningReason) : ManualMemoryIntentError

    data object InvalidTemporalWindow : ManualMemoryIntentError

    data class InvalidId(val field: ManualMemoryIdField) : ManualMemoryIntentError

    data class MemoryNotFound(val memoryId: String) : ManualMemoryIntentError

    data class MemoryAlreadyExists(val memoryId: String) : ManualMemoryIntentError

    data class ExperienceAlreadyExists(val experienceId: String) : ManualMemoryIntentError

    data object EventOrderOverflow : ManualMemoryIntentError

    data class IllegalMemoryState(val memoryId: String) : ManualMemoryIntentError

    data class InvalidReference(
        val type: ManualMemoryReferenceType,
        val id: String,
    ) : ManualMemoryIntentError

    data class MemoryWriteFailure(val operation: ManualMemoryIntentKind) : ManualMemoryIntentError

    data class SafeDeleteFailure(val code: ManualSafeDeleteFailureCode) : ManualMemoryIntentError

    data class StorageFailure(val operation: ManualMemoryIntentKind) : ManualMemoryIntentError
}

interface ManualMemoryIntentIdGenerator {
    fun nextExperienceId(): String

    fun nextMemoryId(): String
}

object UuidManualMemoryIntentIdGenerator : ManualMemoryIntentIdGenerator {
    override fun nextExperienceId(): String = UUID.randomUUID().toString()

    override fun nextMemoryId(): String = UUID.randomUUID().toString()
}
