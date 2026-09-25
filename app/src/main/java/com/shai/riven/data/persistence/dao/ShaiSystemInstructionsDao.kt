package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.ShaiSystemInstructionsEntity

@Dao
interface ShaiSystemInstructionsDao {
    @Query("SELECT * FROM shai_system_instructions WHERE instruction_id = :instructionId")
    fun instructions(instructionId: String): ShaiSystemInstructionsEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(instructions: ShaiSystemInstructionsEntity)

    @Update
    fun update(instructions: ShaiSystemInstructionsEntity): Int

    @Query("SELECT COUNT(*) FROM shai_system_instructions")
    fun rowCount(): Int
}
