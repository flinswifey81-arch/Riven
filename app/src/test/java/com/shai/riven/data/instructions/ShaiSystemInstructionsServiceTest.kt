package com.shai.riven.data.instructions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ShaiSystemInstructionsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShaiSystemInstructionsServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var service: ShaiSystemInstructionsService
    private lateinit var contextSource: ShaiSystemInstructionsContextSource

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = ShaiSystemInstructionsService(database)
        contextSource = ShaiSystemInstructionsContextSource(service)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun defaultSnapshotDoesNotCreateRow() = runBlocking {
        assertEquals(DEFAULT_SNAPSHOT, readSnapshot())
        assertEquals(ShaiSystemInstructionsContextResult.NoFragment, contextSource.currentFragment())
        assertEquals(0, database.shaiSystemInstructionsDao().rowCount())
    }

    @Test
    fun firstSavePreservesExactContentAndCreatesRevisionOne() = runBlocking {
        val content = "  Keep my phrasing.\n\nSecond paragraph — Unicode 💜.\n{user} **literal Markdown**: fuck.  "

        val saved = assertSaved(
            service.save(
                SaveShaiSystemInstructionsInput(
                    content = content,
                    isEnabled = true,
                    expectedRevision = 0,
                    occurredAt = 11,
                ),
            ),
        )

        assertEquals(content, saved.content)
        assertTrue(saved.isEnabled)
        assertEquals(1L, saved.revision)
        assertEquals(11L, saved.updatedAt)
        val entity = primaryEntity()
        assertEquals(content, entity.content)
        assertEquals(11L, entity.createdAt)
        assertEquals(11L, entity.updatedAt)
        assertEquals(ShaiSystemInstructionsService.PRIMARY_INSTRUCTION_ID, entity.id)
        assertEquals(1, database.shaiSystemInstructionsDao().rowCount())
    }

    @Test
    fun firstSaveWithNonzeroExpectedRevisionIsStaleAndCreatesNoRow() = runBlocking {
        val error = assertWriteFailure(
            service.save(
                SaveShaiSystemInstructionsInput(
                    content = "must not be inserted",
                    isEnabled = true,
                    expectedRevision = 1,
                    occurredAt = 11,
                ),
            ),
        )

        assertEquals(ShaiSystemInstructionsError.StaleRevision(expected = 1, actual = 0), error)
        assertEquals(0, database.shaiSystemInstructionsDao().rowCount())
    }

    @Test
    fun secondSaveReplacesOldContentAndPreservesCreatedAt() = runBlocking {
        save("old private instruction", true, expectedRevision = 0, occurredAt = 10)

        val saved = assertSaved(
            service.save(
                SaveShaiSystemInstructionsInput(
                    content = "replacement instruction",
                    isEnabled = true,
                    expectedRevision = 1,
                    occurredAt = 20,
                ),
            ),
        )

        assertEquals("replacement instruction", saved.content)
        assertFalse(primaryEntity().content.contains("old private instruction"))
        assertEquals(2L, saved.revision)
        assertEquals(10L, primaryEntity().createdAt)
        assertEquals(20L, primaryEntity().updatedAt)
        assertEquals(1, database.shaiSystemInstructionsDao().rowCount())
    }

    @Test
    fun staleSaveLeavesEntireRecordUnchanged() = runBlocking {
        save("revision one", true, expectedRevision = 0, occurredAt = 10)
        save("revision two", false, expectedRevision = 1, occurredAt = 20)
        val before = primaryEntity()

        val error = assertWriteFailure(
            service.save(
                SaveShaiSystemInstructionsInput(
                    content = "stale overwrite",
                    isEnabled = true,
                    expectedRevision = 1,
                    occurredAt = 30,
                ),
            ),
        )

        assertEquals(ShaiSystemInstructionsError.StaleRevision(expected = 1, actual = 2), error)
        assertEquals(before, primaryEntity())
    }

    @Test
    fun contextSourceHonorsEnabledBlankAndReenabledStatesWithoutTransformingContent() = runBlocking {
        val content = "  Exact active instruction\nwith spacing.  "
        save(content, true, expectedRevision = 0, occurredAt = 1)
        val firstFragment = assertContextFragment(contextSource.currentFragment())
        assertEquals(RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS, firstFragment.layer)
        assertEquals(content, firstFragment.content)
        assertEquals(1L, firstFragment.revision)

        save(content, false, expectedRevision = 1, occurredAt = 2)
        assertEquals(ShaiSystemInstructionsContextResult.NoFragment, contextSource.currentFragment())
        assertEquals(content, readSnapshot().content)

        save(content, true, expectedRevision = 2, occurredAt = 3)
        val reenabled = assertContextFragment(contextSource.currentFragment())
        assertEquals(content, reenabled.content)
        assertEquals(3L, reenabled.revision)

        save("   \n\t", true, expectedRevision = 3, occurredAt = 4)
        assertEquals(ShaiSystemInstructionsContextResult.NoFragment, contextSource.currentFragment())
        assertEquals(
            listOf(
                RivenContextLayer.APP_INVARIANTS_SAFETY_AND_TOOL_TRUTH,
                RivenContextLayer.LOCKED_RIVEN_PERSONALITY_AND_IDENTITY_CANON,
                RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS,
                RivenContextLayer.RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
                RivenContextLayer.ACTIVE_CANONICAL_CONVERSATION_AND_CURRENT_INTERACTION,
            ),
            RivenContextLayer.entries,
        )
    }

    @Test
    fun clearRemovesOldContentDisablesAndIncrementsRevision() = runBlocking {
        save("old instruction must disappear", true, expectedRevision = 0, occurredAt = 10)

        val cleared = assertCleared(
            service.clear(ClearShaiSystemInstructionsInput(expectedRevision = 1, occurredAt = 20)),
        )

        assertTrue(cleared.changed)
        assertEquals("", cleared.snapshot.content)
        assertFalse(cleared.snapshot.isEnabled)
        assertEquals(2L, cleared.snapshot.revision)
        assertEquals(20L, cleared.snapshot.updatedAt)
        val entity = primaryEntity()
        assertEquals("", entity.content)
        assertEquals(10L, entity.createdAt)
        assertEquals(20L, entity.updatedAt)
        assertFalse(entity.toString().contains("old instruction must disappear"))
        assertEquals(ShaiSystemInstructionsContextResult.NoFragment, contextSource.currentFragment())
    }

    @Test
    fun clearEmptyDefaultIsIdempotentAndDoesNotCreateRow() = runBlocking {
        val cleared = assertCleared(
            service.clear(ClearShaiSystemInstructionsInput(expectedRevision = 0, occurredAt = 10)),
        )

        assertFalse(cleared.changed)
        assertEquals(DEFAULT_SNAPSHOT, cleared.snapshot)
        assertEquals(0, database.shaiSystemInstructionsDao().rowCount())
    }

    @Test
    fun clearWithStaleRevisionLeavesRecordUnchanged() = runBlocking {
        save("current instruction", true, expectedRevision = 0, occurredAt = 10)
        val before = primaryEntity()

        val error = assertWriteFailure(
            service.clear(ClearShaiSystemInstructionsInput(expectedRevision = 0, occurredAt = 20)),
        )

        assertEquals(ShaiSystemInstructionsError.StaleRevision(expected = 0, actual = 1), error)
        assertEquals(before, primaryEntity())
    }

    @Test
    fun configurationEditsCreateNoCognitiveOrDeletionSideEffects() = runBlocking {
        save("first", true, expectedRevision = 0, occurredAt = 1)
        save("first", false, expectedRevision = 1, occurredAt = 2)
        save("second", true, expectedRevision = 2, occurredAt = 3)
        assertCleared(service.clear(ClearShaiSystemInstructionsInput(expectedRevision = 3, occurredAt = 4)))

        listOf(
            "messages",
            "experiences",
            "candidate_memories",
            "memories",
            "open_loops",
            "suppression_tombstones",
            "repair_jobs",
        ).forEach { table ->
            assertEquals("Unexpected side effect in $table", 0L, rowCount(table))
        }
    }

    @Test
    fun revisionOverflowRejectsSaveAndClearWithoutMutation() = runBlocking {
        database.shaiSystemInstructionsDao().insert(
            ShaiSystemInstructionsEntity(
                id = ShaiSystemInstructionsService.PRIMARY_INSTRUCTION_ID,
                content = "maximum revision content",
                isEnabled = true,
                revision = Long.MAX_VALUE,
                createdAt = 1,
                updatedAt = 2,
            ),
        )
        val before = primaryEntity()

        val saveError = assertWriteFailure(
            service.save(
                SaveShaiSystemInstructionsInput(
                    content = "must not save",
                    isEnabled = false,
                    expectedRevision = Long.MAX_VALUE,
                    occurredAt = 3,
                ),
            ),
        )
        assertEquals(ShaiSystemInstructionsError.RevisionOverflow, saveError)
        assertEquals(before, primaryEntity())

        val clearError = assertWriteFailure(
            service.clear(
                ClearShaiSystemInstructionsInput(
                    expectedRevision = Long.MAX_VALUE,
                    occurredAt = 4,
                ),
            ),
        )
        assertEquals(ShaiSystemInstructionsError.RevisionOverflow, clearError)
        assertEquals(before, primaryEntity())
    }

    @Test
    fun controlledFailureAfterInsertRollsBackNewRow() = runBlocking {
        val failingService = ShaiSystemInstructionsService(database) { error("controlled insert failure") }

        val error = assertWriteFailure(
            failingService.save(
                SaveShaiSystemInstructionsInput(
                    content = "must roll back",
                    isEnabled = true,
                    expectedRevision = 0,
                    occurredAt = 1,
                ),
            ),
        )

        assertTrue(error is ShaiSystemInstructionsError.StorageFailure)
        assertEquals(0, database.shaiSystemInstructionsDao().rowCount())
        assertEquals(DEFAULT_SNAPSHOT, readSnapshot())
    }

    @Test
    fun controlledFailureAfterUpdateRollsBackEntireRecord() = runBlocking {
        save("original", true, expectedRevision = 0, occurredAt = 1)
        val before = primaryEntity()
        val failingService = ShaiSystemInstructionsService(database) { error("controlled update failure") }

        val error = assertWriteFailure(
            failingService.save(
                SaveShaiSystemInstructionsInput(
                    content = "must roll back",
                    isEnabled = false,
                    expectedRevision = 1,
                    occurredAt = 2,
                ),
            ),
        )

        assertTrue(error is ShaiSystemInstructionsError.StorageFailure)
        assertEquals(before, primaryEntity())
    }

    @Test
    fun controlledFailureAfterClearUpdateRollsBackEntireRecord() = runBlocking {
        save("must survive failed clear", true, expectedRevision = 0, occurredAt = 1)
        val before = primaryEntity()
        val failingService = ShaiSystemInstructionsService(database) { operation ->
            if (operation == ShaiSystemInstructionsOperation.CLEAR) error("controlled clear failure")
        }

        val error = assertWriteFailure(
            failingService.clear(
                ClearShaiSystemInstructionsInput(
                    expectedRevision = 1,
                    occurredAt = 2,
                ),
            ),
        )

        assertTrue(error is ShaiSystemInstructionsError.StorageFailure)
        assertEquals(before, primaryEntity())
    }

    private suspend fun save(
        content: String,
        isEnabled: Boolean,
        expectedRevision: Long,
        occurredAt: Long,
    ): ShaiSystemInstructionsSnapshot = assertSaved(
        service.save(
            SaveShaiSystemInstructionsInput(
                content = content,
                isEnabled = isEnabled,
                expectedRevision = expectedRevision,
                occurredAt = occurredAt,
            ),
        ),
    )

    private suspend fun readSnapshot(): ShaiSystemInstructionsSnapshot =
        when (val result = service.snapshot()) {
            is ShaiSystemInstructionsReadResult.Success -> result.snapshot
            is ShaiSystemInstructionsReadResult.Failure -> error("Unexpected read failure: ${result.error}")
        }

    private fun primaryEntity(): ShaiSystemInstructionsEntity = checkNotNull(
        database.shaiSystemInstructionsDao().instructions(
            ShaiSystemInstructionsService.PRIMARY_INSTRUCTION_ID,
        ),
    )

    private fun assertSaved(result: ShaiSystemInstructionsWriteResult): ShaiSystemInstructionsSnapshot =
        when (result) {
            is ShaiSystemInstructionsWriteResult.Saved -> result.snapshot
            is ShaiSystemInstructionsWriteResult.Cleared -> error("Expected saved result")
            is ShaiSystemInstructionsWriteResult.Failure -> error("Unexpected write failure: ${result.error}")
        }

    private fun assertCleared(
        result: ShaiSystemInstructionsWriteResult,
    ): ShaiSystemInstructionsWriteResult.Cleared = when (result) {
        is ShaiSystemInstructionsWriteResult.Cleared -> result
        is ShaiSystemInstructionsWriteResult.Saved -> error("Expected cleared result")
        is ShaiSystemInstructionsWriteResult.Failure -> error("Unexpected clear failure: ${result.error}")
    }

    private fun assertWriteFailure(result: ShaiSystemInstructionsWriteResult): ShaiSystemInstructionsError =
        when (result) {
            is ShaiSystemInstructionsWriteResult.Failure -> result.error
            is ShaiSystemInstructionsWriteResult.Saved -> error("Expected write failure")
            is ShaiSystemInstructionsWriteResult.Cleared -> error("Expected write failure")
        }

    private fun assertContextFragment(
        result: ShaiSystemInstructionsContextResult,
    ): ShaiSystemInstructionsContextFragment = when (result) {
        is ShaiSystemInstructionsContextResult.Fragment -> result.value
        ShaiSystemInstructionsContextResult.NoFragment -> error("Expected context fragment")
        is ShaiSystemInstructionsContextResult.Failure -> error("Unexpected context failure: ${result.error}")
    }

    private fun rowCount(table: String): Long = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        val DEFAULT_SNAPSHOT = ShaiSystemInstructionsSnapshot(
            content = "",
            isEnabled = false,
            revision = 0,
            updatedAt = null,
        )
    }
}
