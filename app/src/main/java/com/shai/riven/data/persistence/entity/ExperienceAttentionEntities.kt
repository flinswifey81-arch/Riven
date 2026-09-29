package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignal
import com.shai.riven.data.persistence.model.AttentionSignalPolarity

@Entity(
    tableName = "experience_attention_assessments",
    primaryKeys = ["experience_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["experience_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class ExperienceAttentionAssessmentEntity(
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    val outcome: AttentionOutcome,
    val revision: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "experience_attention_signals",
    primaryKeys = ["experience_id", "signal", "polarity"],
    foreignKeys = [
        ForeignKey(
            entity = ExperienceAttentionAssessmentEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["experience_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["signal"])],
)
data class ExperienceAttentionSignalEntity(
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    val signal: AttentionSignal,
    val polarity: AttentionSignalPolarity,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
