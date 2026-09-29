package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionSignalEntity

@Dao
interface ExperienceAttentionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAssessment(assessment: ExperienceAttentionAssessmentEntity)

    @Update
    fun updateAssessment(assessment: ExperienceAttentionAssessmentEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSignal(signal: ExperienceAttentionSignalEntity)

    @Query("DELETE FROM experience_attention_signals WHERE experience_id = :experienceId")
    fun deleteSignals(experienceId: String): Int

    @Query("SELECT * FROM experience_attention_assessments WHERE experience_id = :experienceId")
    fun assessment(experienceId: String): ExperienceAttentionAssessmentEntity?

    @Query(
        "SELECT * FROM experience_attention_signals " +
            "WHERE experience_id = :experienceId ORDER BY polarity, signal",
    )
    fun signals(experienceId: String): List<ExperienceAttentionSignalEntity>
}
