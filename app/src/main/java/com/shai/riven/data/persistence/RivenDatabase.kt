package com.shai.riven.data.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.shai.riven.data.persistence.dao.ConversationDao
import com.shai.riven.data.persistence.dao.ConversationTimelineDao
import com.shai.riven.data.persistence.dao.MaintenanceDao
import com.shai.riven.data.persistence.dao.MemoryDao
import com.shai.riven.data.persistence.dao.OpenLoopDao
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactOpenLoopDependencyEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceEntityLinkEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.MemoryAuditHistoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity
import com.shai.riven.data.persistence.entity.OpenLoopAuditHistoryEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntityLinkEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.SignificanceLevelConverters

@TypeConverters(SignificanceLevelConverters::class)
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        KnownEntityEntity::class,
        ExperienceEntity::class,
        ExperienceMessageSourceEntity::class,
        MemoryEntity::class,
        MemoryEvidenceEntity::class,
        MemoryRelationshipEntity::class,
        MemoryEntityLinkEntity::class,
        ExperienceEntityLinkEntity::class,
        CandidateMemoryEntity::class,
        CandidateMemoryEvidenceEntity::class,
        OpenLoopEntity::class,
        OpenLoopEntityLinkEntity::class,
        SuppressionTombstoneEntity::class,
        DerivedArtifactEntity::class,
        DerivedArtifactMemoryDependencyEntity::class,
        DerivedArtifactExperienceDependencyEntity::class,
        DerivedArtifactMessageDependencyEntity::class,
        DerivedArtifactOpenLoopDependencyEntity::class,
        RepairJobEntity::class,
        MemoryAuditHistoryEntity::class,
        OpenLoopAuditHistoryEntity::class,
        MessageParentEdgeEntity::class,
        ConversationTimelineHeadEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class RivenDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    abstract fun conversationTimelineDao(): ConversationTimelineDao

    abstract fun memoryDao(): MemoryDao

    abstract fun openLoopDao(): OpenLoopDao

    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val DATABASE_NAME = "riven.db"

        fun build(context: Context): RivenDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                RivenDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
