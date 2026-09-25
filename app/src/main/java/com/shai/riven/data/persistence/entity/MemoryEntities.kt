package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shai.riven.data.persistence.model.EntityKind
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState

@Entity(
    tableName = "entities",
    indices = [
        Index(value = ["normalized_name"]),
        Index(value = ["kind"]),
    ],
)
data class KnownEntityEntity(
    @PrimaryKey
    @ColumnInfo(name = "entity_id")
    val id: String,
    val kind: EntityKind,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "experiences",
    foreignKeys = [
        ForeignKey(
            entity = KnownEntityEntity::class,
            parentColumns = ["entity_id"],
            childColumns = ["actor_entity_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["event_order"], unique = true),
        Index(value = ["occurred_at"]),
        Index(value = ["experience_type"]),
        Index(value = ["actor_entity_id"]),
    ],
)
data class ExperienceEntity(
    @PrimaryKey
    @ColumnInfo(name = "experience_id")
    val id: String,
    @ColumnInfo(name = "event_order")
    val eventOrder: Long,
    @ColumnInfo(name = "experience_type")
    val experienceType: ExperienceType,
    val actor: ExperienceActor,
    @ColumnInfo(name = "actor_entity_id")
    val actorEntityId: String? = null,
    @ColumnInfo(name = "source_content")
    val sourceContent: String? = null,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long,
    val sensitivity: SensitivityLevel,
    val availability: ExperienceAvailability,
)

@Entity(
    tableName = "experience_message_sources",
    primaryKeys = ["experience_id", "message_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["experience_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["experience_id", "source_order"], unique = true),
        Index(value = ["message_id"]),
    ],
)
data class ExperienceMessageSourceEntity(
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "source_order")
    val sourceOrder: Int,
    @ColumnInfo(name = "source_role")
    val sourceRole: ExperienceMessageSourceRole,
    @ColumnInfo(name = "character_start")
    val characterStart: Int? = null,
    @ColumnInfo(name = "character_end")
    val characterEnd: Int? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["kind", "scope"]),
        Index(value = ["truth_state", "retention_state", "lifecycle_state"]),
        Index(value = ["temporal_state", "valid_from", "valid_until"]),
        Index(value = ["created_at"]),
    ],
)
data class MemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "memory_id")
    val id: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val meaning: String,
    @ColumnInfo(name = "epistemic_basis")
    val epistemicBasis: EpistemicBasis,
    val certainty: MemoryCertainty,
    @ColumnInfo(name = "truth_state")
    val truthState: MemoryTruthState,
    @ColumnInfo(name = "retention_state")
    val retentionState: MemoryRetentionState,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: MemoryLifecycleState,
    @ColumnInfo(name = "temporal_state")
    val temporalState: TemporalState,
    @ColumnInfo(name = "learned_at")
    val learnedAt: Long,
    @ColumnInfo(name = "valid_from")
    val validFrom: Long? = null,
    @ColumnInfo(name = "valid_until")
    val validUntil: Long? = null,
    @ColumnInfo(name = "last_confirmed_at")
    val lastConfirmedAt: Long? = null,
    @ColumnInfo(name = "autobiographical_significance")
    val autobiographicalSignificance: SignificanceLevel? = null,
    @ColumnInfo(name = "relationship_significance")
    val relationshipSignificance: SignificanceLevel? = null,
    @ColumnInfo(name = "emotional_significance")
    val emotionalSignificance: SignificanceLevel? = null,
    @ColumnInfo(name = "practical_significance")
    val practicalSignificance: SignificanceLevel? = null,
    @ColumnInfo(name = "identity_significance")
    val identitySignificance: SignificanceLevel? = null,
    val sensitivity: SensitivityLevel,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "memory_evidence",
    primaryKeys = ["memory_id", "experience_id"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["memory_id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["experience_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["experience_id"]),
        Index(value = ["lineage_key"]),
    ],
)
data class MemoryEvidenceEntity(
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    val role: EvidenceRole,
    @ColumnInfo(name = "epistemic_basis")
    val epistemicBasis: EpistemicBasis,
    @ColumnInfo(name = "source_certainty")
    val sourceCertainty: MemoryCertainty,
    @ColumnInfo(name = "lineage_key")
    val lineageKey: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "memory_relationships",
    primaryKeys = ["source_memory_id", "target_memory_id", "relationship_type"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["memory_id"],
            childColumns = ["source_memory_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["memory_id"],
            childColumns = ["target_memory_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["created_by_experience_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["target_memory_id"]),
        Index(value = ["created_by_experience_id"]),
    ],
)
data class MemoryRelationshipEntity(
    @ColumnInfo(name = "source_memory_id")
    val sourceMemoryId: String,
    @ColumnInfo(name = "target_memory_id")
    val targetMemoryId: String,
    @ColumnInfo(name = "relationship_type")
    val relationshipType: MemoryRelationshipType,
    @ColumnInfo(name = "created_by_experience_id")
    val createdByExperienceId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "memory_entity_links",
    primaryKeys = ["memory_id", "entity_id", "role"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["memory_id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnownEntityEntity::class,
            parentColumns = ["entity_id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["entity_id"])],
)
data class MemoryEntityLinkEntity(
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val role: EntityLinkRole,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "experience_entity_links",
    primaryKeys = ["experience_id", "entity_id", "role"],
    foreignKeys = [
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["experience_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnownEntityEntity::class,
            parentColumns = ["entity_id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["entity_id"])],
)
data class ExperienceEntityLinkEntity(
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val role: EntityLinkRole,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
