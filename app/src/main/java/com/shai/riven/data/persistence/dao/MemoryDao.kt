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

    @Query("SELECT COUNT(*) FROM memory_evidence WHERE memory_id = :memoryId")
    fun memoryEvidenceCount(memoryId: String): Int

    @Query("SELECT COUNT(*) FROM memory_entity_links WHERE memory_id = :memoryId")
    fun memoryEntityLinkCount(memoryId: String): Int

    @Query("SELECT * FROM experience_message_sources WHERE experience_id = :experienceId ORDER BY source_order")
    fun messageSourcesForExperience(experienceId: String): List<ExperienceMessageSourceEntity>

    @Query("SELECT COUNT(*) FROM candidate_memory_evidence WHERE candidate_memory_id = :candidateMemoryId")
    fun candidateMemoryEvidenceCount(candidateMemoryId: String): Int

    @Query("SELECT * FROM candidate_memories WHERE candidate_memory_id = :candidateMemoryId")
    fun candidateMemory(candidateMemoryId: String): CandidateMemoryEntity?

    @Query("SELECT * FROM candidate_memory_evidence WHERE candidate_memory_id = :candidateMemoryId ORDER BY evidence_order")
    fun candidateEvidence(candidateMemoryId: String): List<CandidateMemoryEvidenceEntity>

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

    @Query("SELECT COUNT(*) FROM memory_evidence WHERE memory_id = :memoryId AND experience_id = :experienceId")
    fun memoryEvidenceExists(memoryId: String, experienceId: String): Int

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
