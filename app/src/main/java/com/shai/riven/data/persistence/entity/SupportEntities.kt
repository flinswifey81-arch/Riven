package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.MemoryAuditAction
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.OpenLoopAuditAction
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SuppressionKind

@Entity(
    tableName = "candidate_memories",
    indices = [
        Index(value = ["state", "created_at"]),
    ],
)
data class CandidateMemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "candidate_memory_id")
    val id: String,
    @ColumnInfo(name = "proposed_kind")
    val proposedKind: MemoryKind,
    @ColumnInfo(name = "proposed_scope")
    val proposedScope: MemoryScope,
    @ColumnInfo(name = "proposed_meaning")
    val proposedMeaning: String,
    @ColumnInfo(name = "proposed_epistemic_basis")
    val proposedEpistemicBasis: EpistemicBasis,
    @ColumnInfo(name = "proposed_certainty")
    val proposedCertainty: MemoryCertainty,
    val state: CandidateMemoryState,
    val sensitivity: SensitivityLevel,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "candidate_memory_evidence",
    primaryKeys = ["candidate_memory_id", "experience_id"],
    foreignKeys = [
        ForeignKey(
            entity = CandidateMemoryEntity::class,
            parentColumns = ["candidate_memory_id"],
            childColumns = ["candidate_memory_id"],
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
        Index(value = ["candidate_memory_id", "evidence_order"], unique = true),
        Index(value = ["experience_id"]),
        Index(value = ["lineage_key"]),
    ],
)
data class CandidateMemoryEvidenceEntity(
    @ColumnInfo(name = "candidate_memory_id")
    val candidateMemoryId: String,
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    @ColumnInfo(name = "evidence_order")
    val evidenceOrder: Int,
    val role: CandidateEvidenceRole,
    @ColumnInfo(name = "lineage_key")
    val lineageKey: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "open_loops",
    foreignKeys = [
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["creation_experience_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["resolution_experience_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["memory_id"],
            childColumns = ["related_memory_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["creation_experience_id"]),
        Index(value = ["resolution_experience_id"]),
        Index(value = ["related_memory_id"], unique = true),
        Index(value = ["state", "due_at"]),
    ],
)
data class OpenLoopEntity(
    @PrimaryKey
    @ColumnInfo(name = "open_loop_id")
    val id: String,
    @ColumnInfo(name = "creation_experience_id")
    val creationExperienceId: String,
    @ColumnInfo(name = "resolution_experience_id")
    val resolutionExperienceId: String? = null,
    @ColumnInfo(name = "related_memory_id")
    val relatedMemoryId: String? = null,
    val title: String,
    val description: String? = null,
    val state: OpenLoopState,
    @ColumnInfo(name = "opened_at")
    val openedAt: Long,
    @ColumnInfo(name = "due_at")
    val dueAt: Long? = null,
    @ColumnInfo(name = "closed_at")
    val closedAt: Long? = null,
    val sensitivity: SensitivityLevel,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "open_loop_entity_links",
    primaryKeys = ["open_loop_id", "entity_id", "role"],
    foreignKeys = [
        ForeignKey(
            entity = OpenLoopEntity::class,
            parentColumns = ["open_loop_id"],
            childColumns = ["open_loop_id"],
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
data class OpenLoopEntityLinkEntity(
    @ColumnInfo(name = "open_loop_id")
    val openLoopId: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val role: EntityLinkRole,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "suppression_tombstones",
    indices = [
        Index(value = ["source_lineage_hash"], unique = true),
        Index(value = ["is_active", "created_at"]),
    ],
)
data class SuppressionTombstoneEntity(
    @PrimaryKey
    @ColumnInfo(name = "tombstone_id")
    val id: String,
    val kind: SuppressionKind,
    @ColumnInfo(name = "source_lineage_hash")
    val sourceLineageHash: String,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null,
    @ColumnInfo(name = "format_version")
    val formatVersion: Int,
)

@Entity(
    tableName = "derived_artifacts",
    indices = [Index(value = ["artifact_type", "state"])],
)
data class DerivedArtifactEntity(
    @PrimaryKey
    @ColumnInfo(name = "derived_artifact_id")
    val id: String,
    @ColumnInfo(name = "artifact_type")
    val artifactType: DerivedArtifactType,
    val state: DerivedArtifactState,
    @ColumnInfo(name = "producer_version")
    val producerVersion: String,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long,
    @ColumnInfo(name = "artifact_hash")
    val artifactHash: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "invalidated_at")
    val invalidatedAt: Long? = null,
)

@Entity(
    tableName = "derived_artifact_memory_dependencies",
    primaryKeys = ["derived_artifact_id", "memory_id"],
    foreignKeys = [
        ForeignKey(
            entity = DerivedArtifactEntity::class,
            parentColumns = ["derived_artifact_id"],
            childColumns = ["derived_artifact_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["memory_id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["memory_id"])],
)
data class DerivedArtifactMemoryDependencyEntity(
    @ColumnInfo(name = "derived_artifact_id")
    val derivedArtifactId: String,
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "derived_artifact_experience_dependencies",
    primaryKeys = ["derived_artifact_id", "experience_id"],
    foreignKeys = [
        ForeignKey(
            entity = DerivedArtifactEntity::class,
            parentColumns = ["derived_artifact_id"],
            childColumns = ["derived_artifact_id"],
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
    indices = [Index(value = ["experience_id"])],
)
data class DerivedArtifactExperienceDependencyEntity(
    @ColumnInfo(name = "derived_artifact_id")
    val derivedArtifactId: String,
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "derived_artifact_message_dependencies",
    primaryKeys = ["derived_artifact_id", "message_id"],
    foreignKeys = [
        ForeignKey(
            entity = DerivedArtifactEntity::class,
            parentColumns = ["derived_artifact_id"],
            childColumns = ["derived_artifact_id"],
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
    indices = [Index(value = ["message_id"])],
)
data class DerivedArtifactMessageDependencyEntity(
    @ColumnInfo(name = "derived_artifact_id")
    val derivedArtifactId: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "derived_artifact_open_loop_dependencies",
    primaryKeys = ["derived_artifact_id", "open_loop_id"],
    foreignKeys = [
        ForeignKey(
            entity = DerivedArtifactEntity::class,
            parentColumns = ["derived_artifact_id"],
            childColumns = ["derived_artifact_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OpenLoopEntity::class,
            parentColumns = ["open_loop_id"],
            childColumns = ["open_loop_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["open_loop_id"])],
)
data class DerivedArtifactOpenLoopDependencyEntity(
    @ColumnInfo(name = "derived_artifact_id")
    val derivedArtifactId: String,
    @ColumnInfo(name = "open_loop_id")
    val openLoopId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "repair_jobs",
    indices = [
        Index(value = ["state", "created_at"]),
        Index(value = ["target_type", "target_id"]),
    ],
)
data class RepairJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "repair_job_id")
    val id: String,
    @ColumnInfo(name = "job_type")
    val jobType: RepairJobType,
    val state: RepairJobState,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,
)

@Entity(
    tableName = "memory_audit_history",
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["memory_id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["triggering_experience_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["memory_id", "occurred_at"]),
        Index(value = ["triggering_experience_id"]),
    ],
)
data class MemoryAuditHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "memory_audit_id")
    val id: String,
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    val action: MemoryAuditAction,
    @ColumnInfo(name = "triggering_experience_id")
    val triggeringExperienceId: String? = null,
    @ColumnInfo(name = "from_truth_state")
    val fromTruthState: MemoryTruthState? = null,
    @ColumnInfo(name = "to_truth_state")
    val toTruthState: MemoryTruthState? = null,
    @ColumnInfo(name = "from_retention_state")
    val fromRetentionState: MemoryRetentionState? = null,
    @ColumnInfo(name = "to_retention_state")
    val toRetentionState: MemoryRetentionState? = null,
    @ColumnInfo(name = "from_certainty")
    val fromCertainty: MemoryCertainty? = null,
    @ColumnInfo(name = "to_certainty")
    val toCertainty: MemoryCertainty? = null,
    @ColumnInfo(name = "from_lifecycle_state")
    val fromLifecycleState: MemoryLifecycleState? = null,
    @ColumnInfo(name = "to_lifecycle_state")
    val toLifecycleState: MemoryLifecycleState? = null,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
)

@Entity(
    tableName = "open_loop_audit_history",
    foreignKeys = [
        ForeignKey(
            entity = OpenLoopEntity::class,
            parentColumns = ["open_loop_id"],
            childColumns = ["open_loop_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["triggering_experience_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["open_loop_id", "occurred_at"]),
        Index(value = ["triggering_experience_id"]),
    ],
)
data class OpenLoopAuditHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "open_loop_audit_id")
    val id: String,
    @ColumnInfo(name = "open_loop_id")
    val openLoopId: String,
    val action: OpenLoopAuditAction,
    @ColumnInfo(name = "triggering_experience_id")
    val triggeringExperienceId: String? = null,
    @ColumnInfo(name = "from_state")
    val fromState: OpenLoopState? = null,
    @ColumnInfo(name = "to_state")
    val toState: OpenLoopState? = null,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
)
