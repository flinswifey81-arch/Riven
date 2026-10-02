package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceEntityLinkEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryAccessibilityBand
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertEntity(entity: KnownEntityEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertExperience(experience: ExperienceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertExperienceEntityLink(link: ExperienceEntityLinkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertExperienceMessageSource(source: ExperienceMessageSourceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCandidateMemory(candidate: CandidateMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCandidateMemoryEvidence(evidence: CandidateMemoryEvidenceEntity)

    @Update
    fun updateCandidateMemory(candidate: CandidateMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMemory(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMemoryEvidence(evidence: MemoryEvidenceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMemoryRelationship(relationship: MemoryRelationshipEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMemoryEntityLink(link: MemoryEntityLinkEntity)

    @Update
    fun updateMemory(memory: MemoryEntity)

    @Delete
    fun deleteMemory(memory: MemoryEntity)

    @Delete
    fun deleteExperience(experience: ExperienceEntity)

    @Query("SELECT COUNT(*) FROM experiences WHERE experience_id = :experienceId")
    fun experienceCount(experienceId: String): Int

    @Query("SELECT * FROM experiences WHERE experience_id = :experienceId")
    fun experience(experienceId: String): ExperienceEntity?

    @Query("UPDATE experiences SET availability = :availability WHERE experience_id = :experienceId")
    fun updateExperienceAvailability(
        experienceId: String,
        availability: ExperienceAvailability,
    ): Int

    @Query("SELECT MAX(event_order) FROM experiences")
    fun maximumEventOrder(): Long?

    @Query("SELECT COUNT(*) FROM experiences")
    fun experienceCount(): Int

    @Query("SELECT COUNT(*) FROM candidate_memories")
    fun candidateMemoryCount(): Int

    @Query("SELECT COUNT(*) FROM memory_evidence WHERE memory_id = :memoryId")
    fun memoryEvidenceCount(memoryId: String): Int

    @Query("SELECT COUNT(*) FROM memory_entity_links WHERE memory_id = :memoryId")
    fun memoryEntityLinkCount(memoryId: String): Int

    @Query("SELECT * FROM memory_entity_links WHERE memory_id = :memoryId ORDER BY entity_id, role")
    fun entityLinksForMemory(memoryId: String): List<MemoryEntityLinkEntity>

    @Query("SELECT * FROM experience_message_sources WHERE experience_id = :experienceId ORDER BY source_order")
    fun messageSourcesForExperience(experienceId: String): List<ExperienceMessageSourceEntity>

    @Query(
        """
        SELECT experiences.* FROM experiences
        INNER JOIN experience_message_sources
            ON experience_message_sources.experience_id = experiences.experience_id
        WHERE experience_message_sources.message_id = :messageId
          AND experiences.experience_type IN ('CONVERSATION_MESSAGE', 'TOOL_RESULT')
        ORDER BY experiences.event_order
        """,
    )
    fun canonicalConversationExperiencesForMessage(messageId: String): List<ExperienceEntity>

    @Query("SELECT * FROM experience_entity_links WHERE experience_id = :experienceId ORDER BY entity_id, role")
    fun entityLinksForExperience(experienceId: String): List<ExperienceEntityLinkEntity>

    @Query("SELECT COUNT(*) FROM candidate_memory_evidence WHERE candidate_memory_id = :candidateMemoryId")
    fun candidateMemoryEvidenceCount(candidateMemoryId: String): Int

    @Query("SELECT * FROM candidate_memories WHERE candidate_memory_id = :candidateMemoryId")
    fun candidateMemory(candidateMemoryId: String): CandidateMemoryEntity?

    @Query("SELECT * FROM candidate_memory_evidence WHERE candidate_memory_id = :candidateMemoryId ORDER BY evidence_order")
    fun candidateEvidence(candidateMemoryId: String): List<CandidateMemoryEvidenceEntity>

    @Query(
        "DELETE FROM candidate_memory_evidence " +
            "WHERE candidate_memory_id = :candidateMemoryId AND experience_id = :experienceId",
    )
    fun deleteCandidateEvidence(candidateMemoryId: String, experienceId: String): Int

    @Query(
        """
        SELECT candidate_memory_evidence.* FROM candidate_memory_evidence
        INNER JOIN candidate_memories
            ON candidate_memories.candidate_memory_id = candidate_memory_evidence.candidate_memory_id
        WHERE candidate_memory_evidence.experience_id = :experienceId
          AND candidate_memory_evidence.lineage_key = :lineageKey
          AND candidate_memory_evidence.role = 'SEED'
        ORDER BY candidate_memories.created_at, candidate_memories.candidate_memory_id
        """,
    )
    fun candidateSeedEvidenceByLineage(
        experienceId: String,
        lineageKey: String,
    ): List<CandidateMemoryEvidenceEntity>

    @Query(
        """
        SELECT candidate_memory_evidence.* FROM candidate_memory_evidence
        INNER JOIN candidate_memories
            ON candidate_memories.candidate_memory_id = candidate_memory_evidence.candidate_memory_id
        WHERE candidate_memory_evidence.experience_id = :experienceId
          AND candidate_memory_evidence.role = 'SEED'
        ORDER BY candidate_memories.created_at, candidate_memories.candidate_memory_id
        """,
    )
    fun candidateSeedEvidenceForExperience(experienceId: String): List<CandidateMemoryEvidenceEntity>

    @Query("SELECT COUNT(*) FROM candidate_memory_evidence WHERE candidate_memory_id = :candidateMemoryId AND role = :role")
    fun candidateEvidenceRoleCount(candidateMemoryId: String, role: CandidateEvidenceRole): Int

    @Query("SELECT COUNT(*) FROM candidate_memory_evidence WHERE candidate_memory_id = :candidateMemoryId AND experience_id = :experienceId")
    fun candidateEvidenceExists(candidateMemoryId: String, experienceId: String): Int

    @Query("DELETE FROM candidate_memories WHERE candidate_memory_id = :candidateMemoryId")
    fun deleteCandidateMemory(candidateMemoryId: String): Int

    @Query("SELECT * FROM memories WHERE memory_id = :memoryId")
    fun memory(memoryId: String): MemoryEntity?

    @Query("SELECT COUNT(*) FROM memories")
    fun memoryCount(): Int

    @Query("SELECT * FROM memories ORDER BY updated_at DESC, memory_id LIMIT :limit")
    fun recentMemories(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memory_evidence WHERE memory_id = :memoryId ORDER BY created_at, experience_id")
    fun evidenceForMemory(memoryId: String): List<MemoryEvidenceEntity>

    @Query("SELECT * FROM memory_evidence WHERE experience_id = :experienceId ORDER BY memory_id, created_at")
    fun memoryEvidenceForExperience(experienceId: String): List<MemoryEvidenceEntity>

    @Query("SELECT DISTINCT memory_id FROM memory_evidence WHERE experience_id = :experienceId ORDER BY memory_id")
    fun memoryIdsForExperience(experienceId: String): List<String>

    @Query(
        """
        SELECT * FROM memory_relationships
        WHERE source_memory_id = :memoryId OR target_memory_id = :memoryId
        ORDER BY created_at, source_memory_id, target_memory_id, relationship_type
        """,
    )
    fun directRelationshipsForMemory(memoryId: String): List<MemoryRelationshipEntity>

    @Query(
        """
        SELECT DISTINCT memories.* FROM memories
        INNER JOIN memory_relationships
            ON memory_relationships.source_memory_id = memories.memory_id
        WHERE memory_relationships.target_memory_id IN (:sourceMemoryIds)
          AND memory_relationships.relationship_type = 'DERIVED_FROM'
          AND memories.epistemic_basis = 'CONSOLIDATION'
        ORDER BY memories.memory_id
        LIMIT :limit
        """,
    )
    fun consolidatedDependentsOf(sourceMemoryIds: List<String>, limit: Int): List<MemoryEntity>

    @Query(
        "DELETE FROM memory_evidence WHERE memory_id = :memoryId AND experience_id = :experienceId",
    )
    fun deleteMemoryEvidence(memoryId: String, experienceId: String): Int

    @Query(
        """
        DELETE FROM memory_relationships
        WHERE source_memory_id = :sourceMemoryId
          AND target_memory_id = :targetMemoryId
          AND relationship_type = :relationshipType
        """,
    )
    fun deleteMemoryRelationship(
        sourceMemoryId: String,
        targetMemoryId: String,
        relationshipType: MemoryRelationshipType,
    ): Int

    @Query("SELECT COUNT(*) FROM memory_evidence WHERE memory_id = :memoryId AND experience_id = :experienceId")
    fun memoryEvidenceExists(memoryId: String, experienceId: String): Int

    @Query("SELECT COUNT(*) FROM memory_evidence WHERE memory_id = :memoryId AND lineage_key = :lineageKey")
    fun memoryEvidenceLineageExists(memoryId: String, lineageKey: String): Int

    @Query("SELECT DISTINCT experience_id FROM memory_evidence WHERE memory_id IN (:memoryIds)")
    fun experienceIdsForMemories(memoryIds: List<String>): List<String>

    @Query("SELECT DISTINCT message_id FROM experience_message_sources WHERE experience_id IN (:experienceIds)")
    fun messageIdsForExperiences(experienceIds: List<String>): List<String>

    @Query("SELECT open_loop_id FROM open_loops WHERE related_memory_id IN (:memoryIds)")
    fun openLoopIdsForMemories(memoryIds: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM entities WHERE entity_id = :entityId")
    fun entityCount(entityId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM memory_relationships
        WHERE source_memory_id = :sourceMemoryId
          AND target_memory_id = :targetMemoryId
          AND relationship_type = :relationshipType
        """,
    )
    fun memoryRelationshipCount(
        sourceMemoryId: String,
        targetMemoryId: String,
        relationshipType: MemoryRelationshipType,
    ): Int

    @Query(
        """
        SELECT memories.memory_id AS memoryId,
               kind,
               scope,
               substr(meaning, 1, :maxMeaningCharsPlusOne) AS meaning,
               length(meaning) AS meaningLength,
               epistemic_basis AS epistemicBasis,
               certainty,
               truth_state AS truthState,
               retention_state AS retentionState,
               lifecycle_state AS lifecycleState,
               temporal_state AS temporalState,
               learned_at AS learnedAt,
               valid_from AS validFrom,
               valid_until AS validUntil,
               last_confirmed_at AS lastConfirmedAt,
               autobiographical_significance AS autobiographicalSignificance,
               relationship_significance AS relationshipSignificance,
               emotional_significance AS emotionalSignificance,
               practical_significance AS practicalSignificance,
               identity_significance AS identitySignificance,
               sensitivity,
               updated_at AS updatedAt,
               COALESCE(memory_accessibility.band, 'ORDINARY') AS accessibilityBand
        FROM memories
        LEFT JOIN memory_accessibility ON memory_accessibility.memory_id = memories.memory_id
        WHERE memories.memory_id > :afterMemoryId
          AND memories.truth_state != 'UNSUPPORTED'
          AND memories.lifecycle_state != 'REASSESSMENT_PENDING'
          AND EXISTS (
              SELECT 1 FROM memory_evidence
              INNER JOIN experiences
                  ON experiences.experience_id = memory_evidence.experience_id
              WHERE memory_evidence.memory_id = memories.memory_id
                AND experiences.availability = 'AVAILABLE'
          )
        ORDER BY memories.memory_id
        LIMIT :limit
        """,
    )
    fun conversationalRecallMemoryPage(
        afterMemoryId: String,
        limit: Int,
        maxMeaningCharsPlusOne: Int,
    ): List<ConversationalRecallMemoryRow>

    @Query(
        """
        SELECT memories.memory_id AS memoryId,
               kind,
               scope,
               substr(meaning, 1, :maxMeaningCharsPlusOne) AS meaning,
               length(meaning) AS meaningLength,
               epistemic_basis AS epistemicBasis,
               certainty,
               truth_state AS truthState,
               retention_state AS retentionState,
               lifecycle_state AS lifecycleState,
               temporal_state AS temporalState,
               learned_at AS learnedAt,
               valid_from AS validFrom,
               valid_until AS validUntil,
               last_confirmed_at AS lastConfirmedAt,
               autobiographical_significance AS autobiographicalSignificance,
               relationship_significance AS relationshipSignificance,
               emotional_significance AS emotionalSignificance,
               practical_significance AS practicalSignificance,
               identity_significance AS identitySignificance,
               sensitivity,
               updated_at AS updatedAt,
               COALESCE(memory_accessibility.band, 'ORDINARY') AS accessibilityBand
        FROM memories
        LEFT JOIN memory_accessibility ON memory_accessibility.memory_id = memories.memory_id
        WHERE memories.memory_id IN (:memoryIds)
          AND memories.truth_state != 'UNSUPPORTED'
          AND memories.lifecycle_state != 'REASSESSMENT_PENDING'
          AND EXISTS (
              SELECT 1 FROM memory_evidence
              INNER JOIN experiences
                  ON experiences.experience_id = memory_evidence.experience_id
              WHERE memory_evidence.memory_id = memories.memory_id
                AND experiences.availability = 'AVAILABLE'
          )
        ORDER BY memories.memory_id
        """,
    )
    fun conversationalRecallMemoryRows(
        memoryIds: List<String>,
        maxMeaningCharsPlusOne: Int,
    ): List<ConversationalRecallMemoryRow>

    /**
     * Keyset-paged canonical corpus read for the rebuildable validation-recall index. The primary
     * key bounds every page; callers must still enforce a total corpus capacity.
     */
    @Query(
        """
        SELECT memory_id AS memoryId,
               kind,
               scope,
               substr(meaning, 1, :maxMeaningCharsPlusOne) AS meaning,
               length(meaning) AS meaningLength,
               truth_state AS truthState,
               retention_state AS retentionState,
               lifecycle_state AS lifecycleState,
               sensitivity
        FROM memories
        WHERE memory_id > :afterMemoryId
          AND truth_state != 'UNSUPPORTED'
          AND lifecycle_state != 'REASSESSMENT_PENDING'
          AND EXISTS (
              SELECT 1 FROM memory_evidence
              INNER JOIN experiences
                  ON experiences.experience_id = memory_evidence.experience_id
              WHERE memory_evidence.memory_id = memories.memory_id
                AND experiences.availability = 'AVAILABLE'
          )
        ORDER BY memory_id
        LIMIT :limit
        """,
    )
    fun validationRecallMemoryPage(
        afterMemoryId: String,
        limit: Int,
        maxMeaningCharsPlusOne: Int,
    ): List<ValidationRecallMemoryRow>

    @Query(
        """
        SELECT memory_id AS memoryId,
               kind,
               scope,
               substr(meaning, 1, :maxMeaningCharsPlusOne) AS meaning,
               length(meaning) AS meaningLength,
               truth_state AS truthState,
               retention_state AS retentionState,
               lifecycle_state AS lifecycleState,
               sensitivity
        FROM memories
        WHERE memory_id IN (:memoryIds)
          AND truth_state != 'UNSUPPORTED'
          AND lifecycle_state != 'REASSESSMENT_PENDING'
          AND EXISTS (
              SELECT 1 FROM memory_evidence
              INNER JOIN experiences
                  ON experiences.experience_id = memory_evidence.experience_id
              WHERE memory_evidence.memory_id = memories.memory_id
                AND experiences.availability = 'AVAILABLE'
          )
        ORDER BY memory_id
        """,
    )
    fun validationRecallMemoryRows(
        memoryIds: List<String>,
        maxMeaningCharsPlusOne: Int,
    ): List<ValidationRecallMemoryRow>

    @Query(
        """
        SELECT memory_evidence.memory_id AS memoryId,
               memory_evidence.experience_id AS sourceExperienceId,
               CASE
                   WHEN memories.retention_state != 'FORGOTTEN'
                    AND memories.sensitivity = 'STANDARD'
                    AND experiences.availability = 'AVAILABLE'
                    AND experiences.sensitivity = 'STANDARD'
                   THEN substr(experiences.source_content, 1, :maxSourceCharsPlusOne)
                   ELSE NULL
               END AS sourceContent,
               CASE
                   WHEN memories.retention_state != 'FORGOTTEN'
                    AND memories.sensitivity = 'STANDARD'
                    AND experiences.availability = 'AVAILABLE'
                    AND experiences.sensitivity = 'STANDARD'
                   THEN length(experiences.source_content)
                   ELSE NULL
               END AS sourceLength,
               experiences.availability AS sourceAvailability,
               experiences.sensitivity AS sourceSensitivity
        FROM memory_evidence
        INNER JOIN experiences
            ON experiences.experience_id = memory_evidence.experience_id
        INNER JOIN memories
            ON memories.memory_id = memory_evidence.memory_id
        WHERE memory_evidence.memory_id IN (:memoryIds)
        ORDER BY memory_evidence.memory_id, memory_evidence.experience_id
        LIMIT :limit
        """,
    )
    fun validationRecallEvidenceRows(
        memoryIds: List<String>,
        limit: Int,
        maxSourceCharsPlusOne: Int,
    ): List<ValidationRecallEvidenceRow>

    @Query(
        """
        SELECT memory_id AS memoryId, entity_id AS entityId
        FROM memory_entity_links
        WHERE memory_id IN (:memoryIds)
        ORDER BY memory_id, entity_id, role
        LIMIT :limit
        """,
    )
    fun validationRecallMemoryEntityRows(
        memoryIds: List<String>,
        limit: Int,
    ): List<ValidationRecallEntityRow>

    @Query(
        """
        SELECT * FROM memory_relationships
        WHERE source_memory_id IN (:memoryIds)
        ORDER BY source_memory_id, target_memory_id, relationship_type
        LIMIT :limit
        """,
    )
    fun validationRecallOutgoingRelationships(
        memoryIds: List<String>,
        limit: Int,
    ): List<MemoryRelationshipEntity>

    @Query(
        """
        SELECT * FROM memory_relationships
        WHERE target_memory_id IN (:memoryIds)
        LIMIT :limit
        """,
    )
    fun validationRecallIncomingRelationships(
        memoryIds: List<String>,
        limit: Int,
    ): List<MemoryRelationshipEntity>
}

data class ConversationalRecallMemoryRow(
    val memoryId: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val meaning: String,
    val meaningLength: Long,
    val epistemicBasis: EpistemicBasis,
    val certainty: MemoryCertainty,
    val truthState: MemoryTruthState,
    val retentionState: MemoryRetentionState,
    val lifecycleState: MemoryLifecycleState,
    val temporalState: TemporalState,
    val learnedAt: Long,
    val validFrom: Long?,
    val validUntil: Long?,
    val lastConfirmedAt: Long?,
    val autobiographicalSignificance: SignificanceLevel?,
    val relationshipSignificance: SignificanceLevel?,
    val emotionalSignificance: SignificanceLevel?,
    val practicalSignificance: SignificanceLevel?,
    val identitySignificance: SignificanceLevel?,
    val sensitivity: SensitivityLevel,
    val updatedAt: Long,
    val accessibilityBand: MemoryAccessibilityBand = MemoryAccessibilityBand.ORDINARY,
)

data class ValidationRecallEvidenceRow(
    val memoryId: String,
    val sourceExperienceId: String,
    val sourceContent: String?,
    val sourceLength: Long?,
    val sourceAvailability: ExperienceAvailability,
    val sourceSensitivity: SensitivityLevel,
)

data class ValidationRecallMemoryRow(
    val memoryId: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val meaning: String,
    val meaningLength: Long,
    val truthState: MemoryTruthState,
    val retentionState: MemoryRetentionState,
    val lifecycleState: MemoryLifecycleState,
    val sensitivity: SensitivityLevel,
)

data class ValidationRecallEntityRow(
    val memoryId: String,
    val entityId: String,
)
