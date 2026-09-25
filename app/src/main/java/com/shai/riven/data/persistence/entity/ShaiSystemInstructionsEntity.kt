package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shai_system_instructions")
data class ShaiSystemInstructionsEntity(
    @PrimaryKey
    @ColumnInfo(name = "instruction_id")
    val id: String,
    val content: String,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean,
    val revision: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
