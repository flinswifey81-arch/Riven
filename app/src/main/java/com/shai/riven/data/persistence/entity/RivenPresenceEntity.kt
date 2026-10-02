package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "riven_presence_state")
data class RivenPresenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "state_id")
    val id: String,
    @ColumnInfo(name = "actual_room_id")
    val actualRoomId: String,
    @ColumnInfo(name = "semantic_sprite_id")
    val semanticSpriteId: String,
    @ColumnInfo(name = "browsed_room_id")
    val browsedRoomId: String,
    @ColumnInfo(name = "presence_revision")
    val presenceRevision: Long,
    @ColumnInfo(name = "browser_revision")
    val browserRevision: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
