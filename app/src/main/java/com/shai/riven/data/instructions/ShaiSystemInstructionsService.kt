package com.shai.riven.data.instructions

import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ShaiSystemInstructionsEntity
import kotlinx.coroutines.CancellationException

class ShaiSystemInstructionsService(
    private val database: RivenDatabase,
    private val afterStorageWrite: (ShaiSystemInstructionsOperation) -> Unit = {},
) {
    private val dao = database.shaiSystemInstructionsDao()

    suspend fun snapshot(): ShaiSystemInstructionsReadResult = try {
        ShaiSystemInstructionsReadResult.Success(
            dao.instructions(PRIMARY_INSTRUCTION_ID)?.toSnapshot() ?: DEFAULT_SNAPSHOT,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ShaiSystemInstructionsReadResult.Failure(
            failure.toStorageFailure(ShaiSystemInstructionsOperation.READ),
        )
    }

    suspend fun save(input: SaveShaiSystemInstructionsInput): ShaiSystemInstructionsWriteResult =
        executeWrite(ShaiSystemInstructionsOperation.SAVE) {
            val current = dao.instructions(PRIMARY_INSTRUCTION_ID)
            val actualRevision = current?.revision ?: INITIAL_REVISION
            requireExpectedRevision(input.expectedRevision, actualRevision)
            if (actualRevision == Long.MAX_VALUE) abort(ShaiSystemInstructionsError.RevisionOverflow)

            val updated = if (current == null) {
                ShaiSystemInstructionsEntity(
                    id = PRIMARY_INSTRUCTION_ID,
                    content = input.content,
                    isEnabled = input.isEnabled,
                    revision = FIRST_STORED_REVISION,
                    createdAt = input.occurredAt,
                    updatedAt = input.occurredAt,
                ).also(dao::insert)
            } else {
                current.copy(
                    content = input.content,
                    isEnabled = input.isEnabled,
                    revision = current.revision + 1,
                    updatedAt = input.occurredAt,
                ).also { instructions -> check(dao.update(instructions) == 1) }
            }
            afterStorageWrite(ShaiSystemInstructionsOperation.SAVE)
            ShaiSystemInstructionsWriteResult.Saved(updated.toSnapshot())
        }

    suspend fun clear(input: ClearShaiSystemInstructionsInput): ShaiSystemInstructionsWriteResult =
        executeWrite(ShaiSystemInstructionsOperation.CLEAR) {
            val current = dao.instructions(PRIMARY_INSTRUCTION_ID)
            val actualRevision = current?.revision ?: INITIAL_REVISION
            requireExpectedRevision(input.expectedRevision, actualRevision)
            if (current == null) {
                return@executeWrite ShaiSystemInstructionsWriteResult.Cleared(
                    snapshot = DEFAULT_SNAPSHOT,
                    changed = false,
                )
            }
            if (current.revision == Long.MAX_VALUE) abort(ShaiSystemInstructionsError.RevisionOverflow)

            val cleared = current.copy(
                content = "",
                isEnabled = false,
                revision = current.revision + 1,
                updatedAt = input.occurredAt,
            )
            check(dao.update(cleared) == 1)
            afterStorageWrite(ShaiSystemInstructionsOperation.CLEAR)
            ShaiSystemInstructionsWriteResult.Cleared(
                snapshot = cleared.toSnapshot(),
                changed = true,
            )
        }

    private suspend fun executeWrite(
        operation: ShaiSystemInstructionsOperation,
        block: suspend () -> ShaiSystemInstructionsWriteResult,
    ): ShaiSystemInstructionsWriteResult = try {
        database.withTransaction { block() }
    } catch (abort: InstructionsAbort) {
        ShaiSystemInstructionsWriteResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ShaiSystemInstructionsWriteResult.Failure(failure.toStorageFailure(operation))
    }

    private fun requireExpectedRevision(expected: Long, actual: Long) {
        if (expected != actual) {
            abort(ShaiSystemInstructionsError.StaleRevision(expected = expected, actual = actual))
        }
    }

    private fun ShaiSystemInstructionsEntity.toSnapshot() = ShaiSystemInstructionsSnapshot(
        content = content,
        isEnabled = isEnabled,
        revision = revision,
        updatedAt = updatedAt,
    )

    private fun Exception.toStorageFailure(operation: ShaiSystemInstructionsOperation) =
        ShaiSystemInstructionsError.StorageFailure(
            operation = operation,
            causeType = this::class.java.simpleName,
        )

    private fun abort(error: ShaiSystemInstructionsError): Nothing = throw InstructionsAbort(error)

    private class InstructionsAbort(val error: ShaiSystemInstructionsError) : RuntimeException()

    companion object {
        internal const val PRIMARY_INSTRUCTION_ID = "PRIMARY"
        private const val INITIAL_REVISION = 0L
        private const val FIRST_STORED_REVISION = 1L

        val DEFAULT_SNAPSHOT = ShaiSystemInstructionsSnapshot(
            content = "",
            isEnabled = false,
            revision = INITIAL_REVISION,
            updatedAt = null,
        )
    }
}
