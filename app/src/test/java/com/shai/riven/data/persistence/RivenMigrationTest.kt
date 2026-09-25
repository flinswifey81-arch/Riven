package com.shai.riven.data.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RivenMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath(TEST_DATABASE),
        driver = AndroidSQLiteDriver(),
        databaseClass = RivenDatabase::class,
    )

    @Test
    fun migrationOneToTwoBackfillsLinearTimelinesAndPreservesCanonicalRows() {
        migrationHelper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO conversations VALUES " +
                    "('conversation-linear', 10, 99, 'ACTIVE', 'Linear history'), " +
                    "('conversation-empty', 20, 88, 'ACTIVE', 'Empty history')",
            )
            execSQL(
                """
                INSERT INTO messages (
                    message_id, conversation_id, sequence_number, role, delivery_state,
                    content, created_at, updated_at
                ) VALUES
                    ('message-1', 'conversation-linear', 1, 'USER', 'PERSISTED', 'First', 11, 11),
                    ('message-2', 'conversation-linear', 2, 'ASSISTANT', 'PERSISTED', 'Second', 12, 12),
                    ('message-3', 'conversation-linear', 3, 'USER', 'PERSISTED', 'Third', 13, 13)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO experiences (
                    experience_id, event_order, experience_type, actor, source_content,
                    occurred_at, recorded_at, sensitivity, availability
                ) VALUES (
                    'experience-1', 1, 'CONVERSATION_MESSAGE', 'SHAI', 'Preserved experience',
                    11, 11, 'STANDARD', 'AVAILABLE'
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO experience_message_sources " +
                    "VALUES ('experience-1', 'message-2', 0, 'PRIMARY', NULL, NULL, 11)",
            )
            execSQL(
                """
                INSERT INTO memories (
                    memory_id, kind, scope, meaning, epistemic_basis, certainty,
                    truth_state, retention_state, lifecycle_state, temporal_state,
                    learned_at, sensitivity, created_at, updated_at
                ) VALUES (
                    'memory-1', 'SEMANTIC', 'SHAI', 'Preserved meaning',
                    'DIRECT_USER_STATEMENT', 'CERTAIN', 'SUPPORTED', 'ACTIVE',
                    'VALIDATED', 'CURRENT', 11, 'STANDARD', 11, 11
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO memory_evidence VALUES " +
                    "('memory-1', 'experience-1', 'SUPPORTS', 'DIRECT_USER_STATEMENT', " +
                    "'CERTAIN', 'lineage-1', 11)",
            )
            execSQL(
                "INSERT INTO candidate_memories VALUES " +
                    "('candidate-1', 'SEMANTIC', 'SHAI', 'Candidate', 'INFERENCE', " +
                    "'PROBABLE', 'TENTATIVE', 'STANDARD', 11, 11)",
            )
            execSQL(
                "INSERT INTO candidate_memory_evidence VALUES " +
                    "('candidate-1', 'experience-1', 0, 'SEED', 'lineage-1', 11)",
            )
            execSQL(
                """
                INSERT INTO open_loops (
                    open_loop_id, creation_experience_id, related_memory_id, title,
                    state, opened_at, sensitivity, created_at, updated_at
                ) VALUES (
                    'open-loop-1', 'experience-1', 'memory-1', 'Preserved loop',
                    'ACTIVE', 11, 'STANDARD', 11, 11
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO derived_artifacts VALUES " +
                    "('artifact-1', 'SUMMARY', 'CURRENT', 'v1', 1, NULL, 11, NULL)",
            )
            execSQL(
                "INSERT INTO derived_artifact_message_dependencies VALUES " +
                    "('artifact-1', 'message-2', 11)",
            )
            execSQL(
                "INSERT INTO suppression_tombstones VALUES " +
                    "('tombstone-1', 'FORGET', 'opaque-hash', 1, 11, NULL, 1)",
            )
            execSQL(
                "INSERT INTO repair_jobs VALUES " +
                    "('repair-1', 'INVALIDATE_DERIVED', 'PENDING', 'MEMORY', " +
                    "'memory-1', 0, 11, 11, NULL)",
            )
            execSQL(
                """
                INSERT INTO memory_audit_history (
                    memory_audit_id, memory_id, action, occurred_at
                ) VALUES ('audit-1', 'memory-1', 'CREATED', 11)
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 2,
            migrations = listOf(MIGRATION_1_2),
        )

        assertEquals("message-3", migrated.singleString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-linear'",
        ))
        assertEquals(0L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-linear'",
        ))
        assertEquals(99L, migrated.singleLong(
            "SELECT updated_at FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-linear'",
        ))
        assertNull(migrated.singleNullableString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-empty'",
        ))
        assertEquals(0L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-empty'",
        ))
        assertEquals(listOf("message-1", "message-2", "message-3"), migrated.activePath("message-3"))
        assertNull(migrated.parentId("message-1"))
        assertEquals("message-1", migrated.parentId("message-2"))
        assertEquals("message-2", migrated.parentId("message-3"))

        val preservedTables = listOf(
            "conversations",
            "messages",
            "experiences",
            "experience_message_sources",
            "memories",
            "memory_evidence",
            "candidate_memories",
            "candidate_memory_evidence",
            "open_loops",
            "derived_artifacts",
            "derived_artifact_message_dependencies",
            "suppression_tombstones",
            "repair_jobs",
            "memory_audit_history",
        )
        preservedTables.forEach { table ->
            val expectedCount = when (table) {
                "conversations" -> 2L
                "messages" -> 3L
                else -> 1L
            }
            assertEquals("Unexpected preserved row count for $table", expectedCount, migrated.rowCount(table))
        }
        migrated.close()
    }

    @Test
    fun migrationTwoToThreeCreatesEmptyInstructionsTableAndPreservesVersionTwoData() {
        migrationHelper.createDatabase(2).apply {
            execSQL(
                "INSERT INTO conversations VALUES " +
                    "('conversation-v2', 10, 20, 'ACTIVE', 'Version two conversation')",
            )
            execSQL(
                """
                INSERT INTO messages (
                    message_id, conversation_id, sequence_number, role, delivery_state,
                    content, created_at, updated_at
                ) VALUES (
                    'message-v2', 'conversation-v2', 1, 'USER', 'PERSISTED',
                    'Preserved version two message', 11, 11
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO conversation_timeline_heads VALUES " +
                    "('conversation-v2', 'message-v2', 7, 20)",
            )
            execSQL(
                """
                INSERT INTO experiences (
                    experience_id, event_order, experience_type, actor, source_content,
                    occurred_at, recorded_at, sensitivity, availability
                ) VALUES (
                    'experience-v2', 1, 'CONVERSATION_MESSAGE', 'SHAI',
                    'Preserved version two experience', 11, 11, 'STANDARD', 'AVAILABLE'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO memories (
                    memory_id, kind, scope, meaning, epistemic_basis, certainty,
                    truth_state, retention_state, lifecycle_state, temporal_state,
                    learned_at, sensitivity, created_at, updated_at
                ) VALUES (
                    'memory-v2', 'SEMANTIC', 'SHAI', 'Preserved version two meaning',
                    'DIRECT_USER_STATEMENT', 'CERTAIN', 'SUPPORTED', 'ACTIVE',
                    'VALIDATED', 'CURRENT', 11, 'STANDARD', 11, 11
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 3,
            migrations = listOf(MIGRATION_2_3),
        )

        assertEquals(0L, migrated.rowCount("shai_system_instructions"))
        assertEquals(1L, migrated.rowCount("conversations"))
        assertEquals(1L, migrated.rowCount("messages"))
        assertEquals(1L, migrated.rowCount("conversation_timeline_heads"))
        assertEquals(1L, migrated.rowCount("experiences"))
        assertEquals(1L, migrated.rowCount("memories"))
        assertEquals("message-v2", migrated.singleString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-v2'",
        ))
        assertEquals(7L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-v2'",
        ))
        assertEquals("Preserved version two message", migrated.singleString(
            "SELECT content FROM messages WHERE message_id = 'message-v2'",
        ))
        assertEquals("Preserved version two meaning", migrated.singleString(
            "SELECT meaning FROM memories WHERE memory_id = 'memory-v2'",
        ))
        migrated.close()
    }

    @Test
    fun migrationOneToThreeChainsExistingTimelineMigrationAndCreatesInstructionsTable() {
        migrationHelper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO conversations VALUES " +
                    "('conversation-chain', 10, 30, 'ACTIVE', 'Migration chain')",
            )
            execSQL(
                """
                INSERT INTO messages (
                    message_id, conversation_id, sequence_number, role, delivery_state,
                    content, created_at, updated_at
                ) VALUES
                    ('chain-1', 'conversation-chain', 1, 'USER', 'PERSISTED', 'First', 11, 11),
                    ('chain-2', 'conversation-chain', 2, 'ASSISTANT', 'PERSISTED', 'Second', 12, 12),
                    ('chain-3', 'conversation-chain', 3, 'USER', 'PERSISTED', 'Third', 13, 13)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO memories (
                    memory_id, kind, scope, meaning, epistemic_basis, certainty,
                    truth_state, retention_state, lifecycle_state, temporal_state,
                    learned_at, sensitivity, created_at, updated_at
                ) VALUES (
                    'memory-chain', 'SEMANTIC', 'SHAI', 'Preserved through full chain',
                    'DIRECT_USER_STATEMENT', 'CERTAIN', 'SUPPORTED', 'ACTIVE',
                    'VALIDATED', 'CURRENT', 11, 'STANDARD', 11, 11
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 3,
            migrations = listOf(MIGRATION_1_2, MIGRATION_2_3),
        )

        assertEquals("chain-3", migrated.singleString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-chain'",
        ))
        assertEquals(listOf("chain-1", "chain-2", "chain-3"), migrated.activePath("chain-3"))
        assertEquals(0L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-chain'",
        ))
        assertEquals(1L, migrated.rowCount("memories"))
        assertEquals("Preserved through full chain", migrated.singleString(
            "SELECT meaning FROM memories WHERE memory_id = 'memory-chain'",
        ))
        assertEquals(0L, migrated.rowCount("shai_system_instructions"))
        migrated.close()
    }

    private fun SQLiteConnection.execSQL(sql: String) {
        prepare(sql).use { statement -> statement.step() }
    }

    private fun SQLiteConnection.activePath(headMessageId: String): List<String> {
        val reversePath = mutableListOf<String>()
        var current: String? = headMessageId
        while (current != null) {
            reversePath += current
            current = parentId(current)
        }
        return reversePath.asReversed()
    }

    private fun SQLiteConnection.parentId(childMessageId: String): String? =
        prepare(
            "SELECT parent_message_id FROM message_parent_edges WHERE child_message_id = ?",
        ).use { statement ->
            statement.bindText(1, childMessageId)
            if (statement.step()) statement.getText(0) else null
        }

    private fun SQLiteConnection.singleString(sql: String): String =
        checkNotNull(singleNullableString(sql))

    private fun SQLiteConnection.singleNullableString(sql: String): String? =
        prepare(sql).use { statement ->
            check(statement.step())
            if (statement.isNull(0)) null else statement.getText(0)
        }

    private fun SQLiteConnection.singleLong(sql: String): Long =
        prepare(sql).use { statement ->
            check(statement.step())
            statement.getLong(0)
        }

    private fun SQLiteConnection.rowCount(table: String): Long = singleLong("SELECT COUNT(*) FROM `$table`")

    private companion object {
        const val TEST_DATABASE = "riven-migration-1-2-test"
    }
}
