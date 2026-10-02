package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shai.riven.data.persistence.entity.RivenPresenceEntity

@Dao
interface RivenPresenceDao {
    @Query("SELECT * FROM riven_presence_state WHERE state_id = :stateId")
    fun state(stateId: String): RivenPresenceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfMissing(state: RivenPresenceEntity): Long

    @Query(
        """
        UPDATE riven_presence_state
        SET browsed_room_id = :roomId,
            browser_revision = :nextRevision,
            updated_at = :updatedAt
        WHERE state_id = :stateId AND browser_revision = :expectedRevision
        """,
    )
    fun updateBrowsedRoom(
        stateId: String,
        roomId: String,
        expectedRevision: Long,
        nextRevision: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE riven_presence_state
        SET actual_room_id = :roomId,
            semantic_sprite_id = :spriteId,
            presence_revision = :nextRevision,
            updated_at = :updatedAt
        WHERE state_id = :stateId AND presence_revision = :expectedRevision
        """,
    )
    fun updatePresence(
        stateId: String,
        roomId: String,
        spriteId: String,
        expectedRevision: Long,
        nextRevision: Long,
        updatedAt: Long,
    ): Int
}
