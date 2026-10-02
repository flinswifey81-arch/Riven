package com.shai.riven.data.presence

import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.RivenPresenceEntity
import kotlinx.coroutines.CancellationException

class RivenPresenceService(
    private val database: RivenDatabase,
) {
    private val dao = database.rivenPresenceDao()

    suspend fun initialize(occurredAt: Long): RivenPresenceReadResult = guardedRead {
        database.withTransaction {
            dao.insertIfMissing(defaultEntity(occurredAt))
            decode(requireNotNull(dao.state(PRIMARY_STATE_ID)))
        }
    }

    fun snapshot(): RivenPresenceReadResult = guardedRead {
        val stored = dao.state(PRIMARY_STATE_ID)
            ?: return@guardedRead RivenPresenceReadResult.Failure("Presence state is not initialized.")
        decode(stored)
    }

    suspend fun browse(room: RivenRoom, occurredAt: Long): RivenPresenceWriteResult = guardedWrite {
        database.withTransaction {
            val stored = dao.state(PRIMARY_STATE_ID)
                ?: return@withTransaction RivenPresenceWriteResult.Rejected("Presence state is not initialized.")
            val decoded = decode(stored)
            if (decoded is RivenPresenceReadResult.Failure) {
                return@withTransaction RivenPresenceWriteResult.Rejected(decoded.reason)
            }
            val current = (decoded as RivenPresenceReadResult.Success).snapshot
            if (current.browsedRoom == room) return@withTransaction RivenPresenceWriteResult.Unchanged(current)
            if (stored.browserRevision == Long.MAX_VALUE) {
                return@withTransaction RivenPresenceWriteResult.Rejected("Browser revision is exhausted.")
            }
            val next = stored.browserRevision + 1L
            if (dao.updateBrowsedRoom(PRIMARY_STATE_ID, room.stableId, stored.browserRevision, next, occurredAt) != 1) {
                return@withTransaction RivenPresenceWriteResult.Rejected("Room browsing changed concurrently.")
            }
            val updated = dao.state(PRIMARY_STATE_ID)
                ?: return@withTransaction RivenPresenceWriteResult.Rejected("Presence state disappeared.")
            RivenPresenceWriteResult.Updated((decode(updated) as RivenPresenceReadResult.Success).snapshot)
        }
    }

    suspend fun applyModelControl(
        roomId: String,
        spriteId: String,
        expectedPresenceRevision: Long,
        occurredAt: Long,
    ): RivenPresenceWriteResult {
        val room = RivenRoom.fromStableId(roomId)
            ?: return RivenPresenceWriteResult.Rejected("Unsupported room '$roomId'.")
        val sprite = RivenSemanticSprite.fromStableId(spriteId)
            ?: return RivenPresenceWriteResult.Rejected("Unsupported sprite '$spriteId'.")
        return guardedWrite {
            database.withTransaction {
                val stored = dao.state(PRIMARY_STATE_ID)
                    ?: return@withTransaction RivenPresenceWriteResult.Rejected("Presence state is not initialized.")
                val decoded = decode(stored)
                if (decoded is RivenPresenceReadResult.Failure) {
                    return@withTransaction RivenPresenceWriteResult.Rejected(decoded.reason)
                }
                val current = (decoded as RivenPresenceReadResult.Success).snapshot
                if (stored.presenceRevision != expectedPresenceRevision) {
                    return@withTransaction RivenPresenceWriteResult.Rejected("Presence context is stale.")
                }
                if (current.actualRoom == room && current.semanticSprite == sprite) {
                    return@withTransaction RivenPresenceWriteResult.Unchanged(current)
                }
                if (stored.presenceRevision == Long.MAX_VALUE) {
                    return@withTransaction RivenPresenceWriteResult.Rejected("Presence revision is exhausted.")
                }
                val next = stored.presenceRevision + 1L
                if (dao.updatePresence(
                        PRIMARY_STATE_ID,
                        room.stableId,
                        sprite.stableId,
                        stored.presenceRevision,
                        next,
                        occurredAt,
                    ) != 1
                ) {
                    return@withTransaction RivenPresenceWriteResult.Rejected("Presence changed concurrently.")
                }
                val updated = dao.state(PRIMARY_STATE_ID)
                    ?: return@withTransaction RivenPresenceWriteResult.Rejected("Presence state disappeared.")
                RivenPresenceWriteResult.Updated((decode(updated) as RivenPresenceReadResult.Success).snapshot)
            }
        }
    }

    private fun decode(stored: RivenPresenceEntity): RivenPresenceReadResult {
        val actual = RivenRoom.fromStableId(stored.actualRoomId)
            ?: return RivenPresenceReadResult.Failure("Stored actual room is unsupported.")
        val browsed = RivenRoom.fromStableId(stored.browsedRoomId)
            ?: return RivenPresenceReadResult.Failure("Stored browsed room is unsupported.")
        val sprite = RivenSemanticSprite.fromStableId(stored.semanticSpriteId)
            ?: return RivenPresenceReadResult.Failure("Stored semantic sprite is unsupported.")
        if (stored.presenceRevision < 0L || stored.browserRevision < 0L) {
            return RivenPresenceReadResult.Failure("Stored presence revisions are invalid.")
        }
        return RivenPresenceReadResult.Success(
            RivenPresenceSnapshot(actual, sprite, browsed, stored.presenceRevision, stored.browserRevision),
        )
    }

    private fun defaultEntity(occurredAt: Long) = RivenPresenceEntity(
        id = PRIMARY_STATE_ID,
        actualRoomId = RivenRoom.LIVING_ROOM.stableId,
        semanticSpriteId = RivenSemanticSprite.STANDING_RELAXED.stableId,
        browsedRoomId = RivenRoom.LIVING_ROOM.stableId,
        presenceRevision = 0L,
        browserRevision = 0L,
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )

    private inline fun guardedRead(block: () -> RivenPresenceReadResult): RivenPresenceReadResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RivenPresenceReadResult.Failure(failure::class.java.simpleName)
    }

    private suspend inline fun guardedWrite(
        crossinline block: suspend () -> RivenPresenceWriteResult,
    ): RivenPresenceWriteResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RivenPresenceWriteResult.Rejected(failure::class.java.simpleName)
    }

    companion object {
        const val PRIMARY_STATE_ID = "riven-primary-presence"
    }
}
