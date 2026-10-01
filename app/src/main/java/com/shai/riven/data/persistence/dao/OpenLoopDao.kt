package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shai.riven.data.persistence.entity.OpenLoopAuditHistoryEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntityLinkEntity
import com.shai.riven.data.persistence.model.OpenLoopState

@Dao
interface OpenLoopDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertOpenLoop(openLoop: OpenLoopEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAuditHistory(auditHistory: OpenLoopAuditHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertEntityLink(link: OpenLoopEntityLinkEntity)

    @Query("SELECT COUNT(*) FROM open_loop_entity_links WHERE open_loop_id = :openLoopId")
    fun entityLinkCount(openLoopId: String): Int

    @Query(
        """
        SELECT open_loops.* FROM open_loops
        INNER JOIN experiences
            ON experiences.experience_id = open_loops.creation_experience_id
        WHERE open_loops.state IN (:states)
          AND experiences.availability = 'AVAILABLE'
        ORDER BY
            CASE open_loops.state
                WHEN 'BLOCKED' THEN 0
                WHEN 'ACTIVE' THEN 1
                WHEN 'WAITING' THEN 2
                ELSE 3
            END,
            CASE WHEN open_loops.due_at IS NULL THEN 1 ELSE 0 END,
            open_loops.due_at,
            open_loops.updated_at DESC,
            open_loops.open_loop_id
        LIMIT :limit
        """,
    )
    fun conversationalContextCandidates(
        states: List<OpenLoopState>,
        limit: Int,
    ): List<OpenLoopEntity>

    @Query(
        """
        SELECT open_loops.* FROM open_loops
        INNER JOIN experiences
            ON experiences.experience_id = open_loops.creation_experience_id
        WHERE open_loops.open_loop_id IN (:openLoopIds)
          AND open_loops.state IN (:states)
          AND experiences.availability = 'AVAILABLE'
        ORDER BY open_loops.open_loop_id
        """,
    )
    fun conversationalContextByIds(
        openLoopIds: List<String>,
        states: List<OpenLoopState>,
    ): List<OpenLoopEntity>

    @Query(
        """
        SELECT * FROM open_loop_entity_links
        WHERE open_loop_id IN (:openLoopIds)
        ORDER BY open_loop_id, entity_id, role
        LIMIT :limit
        """,
    )
    fun conversationalContextEntityLinks(
        openLoopIds: List<String>,
        limit: Int,
    ): List<OpenLoopEntityLinkEntity>
}
