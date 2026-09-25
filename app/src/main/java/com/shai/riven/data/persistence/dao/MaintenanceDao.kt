package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactOpenLoopDependencyEntity
import com.shai.riven.data.persistence.entity.MemoryAuditHistoryEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity

@Dao
interface MaintenanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSuppressionTombstone(tombstone: SuppressionTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifact(artifact: DerivedArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactMemoryDependency(dependency: DerivedArtifactMemoryDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactExperienceDependency(dependency: DerivedArtifactExperienceDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactMessageDependency(dependency: DerivedArtifactMessageDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactOpenLoopDependency(dependency: DerivedArtifactOpenLoopDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRepairJob(job: RepairJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMemoryAuditHistory(auditHistory: MemoryAuditHistoryEntity)

    @Query("SELECT COUNT(*) FROM derived_artifact_memory_dependencies WHERE derived_artifact_id = :artifactId")
    fun memoryDependencyCount(artifactId: String): Int

    @Query("SELECT COUNT(*) FROM derived_artifact_experience_dependencies WHERE derived_artifact_id = :artifactId")
    fun experienceDependencyCount(artifactId: String): Int

    @Query("SELECT COUNT(*) FROM derived_artifact_message_dependencies WHERE derived_artifact_id = :artifactId")
    fun messageDependencyCount(artifactId: String): Int
}
