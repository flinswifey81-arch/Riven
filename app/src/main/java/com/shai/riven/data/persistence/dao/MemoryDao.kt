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
import com.shai.riven.data.persistence.model.MemoryRelationshipType

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

    @Query("SELECT * FROM memory_evidence WHERE memory_id = :memoryId ORDER BY created_at, experience_id")
    fun evidenceForMemory(memoryId: String): List<MemoryEvidenceEntity>

    @Query(
        """
        SELECT * FROM memory_relationships
        WHERE source_memory_id = :memoryId OR target_memory_id = :memoryId
        ORDER BY created_at, source_memory_id, target_memory_id, relationship_type
        """,
    )
    fun directRelationshipsForMemory(memoryId: String): List<MemoryRelationshipEntity>

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
}
