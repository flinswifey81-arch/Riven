package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shai.riven.data.persistence.entity.OpenLoopAuditHistoryEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntityLinkEntity

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
}
