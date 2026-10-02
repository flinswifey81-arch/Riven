package com.shai.riven.data.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
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

    @Test
    fun migrationThreeToFourCreatesAttachmentFoundationWithoutFabricatingRows() {
        migrationHelper.createDatabase(3).apply {
            execSQL(
                "INSERT INTO conversations VALUES " +
                    "('conversation-v3', 10, 20, 'ACTIVE', 'Version three conversation')",
            )
            execSQL(
                """
                INSERT INTO messages (
                    message_id, conversation_id, sequence_number, role, delivery_state,
                    content, created_at, updated_at
                ) VALUES (
                    'message-v3', 'conversation-v3', 1, 'USER', 'PERSISTED',
                    'Preserved version three message', 11, 11
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO conversation_timeline_heads VALUES " +
                    "('conversation-v3', 'message-v3', 4, 20)",
            )
            execSQL(
                """
                INSERT INTO memories (
                    memory_id, kind, scope, meaning, epistemic_basis, certainty,
                    truth_state, retention_state, lifecycle_state, temporal_state,
                    learned_at, sensitivity, created_at, updated_at
                ) VALUES (
                    'memory-v3', 'SEMANTIC', 'SHAI', 'Preserved version three meaning',
                    'DIRECT_USER_STATEMENT', 'CERTAIN', 'SUPPORTED', 'ACTIVE',
                    'VALIDATED', 'CURRENT', 11, 'STANDARD', 11, 11
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO shai_system_instructions VALUES " +
                    "('PRIMARY', 'Preserve exact instructions', 1, 7, 12, 13)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 4,
            migrations = listOf(MIGRATION_3_4),
        )

        assertEquals(1L, migrated.rowCount("conversations"))
        assertEquals(1L, migrated.rowCount("messages"))
        assertEquals(1L, migrated.rowCount("conversation_timeline_heads"))
        assertEquals(1L, migrated.rowCount("memories"))
        assertEquals(1L, migrated.rowCount("shai_system_instructions"))
        assertEquals("message-v3", migrated.singleString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-v3'",
        ))
        assertEquals(4L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-v3'",
        ))
        assertEquals("Preserved version three meaning", migrated.singleString(
            "SELECT meaning FROM memories WHERE memory_id = 'memory-v3'",
        ))
        assertEquals("Preserve exact instructions", migrated.singleString(
            "SELECT content FROM shai_system_instructions WHERE instruction_id = 'PRIMARY'",
        ))
        listOf(
            "attachments",
            "message_attachments",
            "generated_media_provenance",
            "derived_artifact_attachment_dependencies",
        ).forEach { table ->
            assertEquals("Migration must not fabricate rows in $table", 0L, migrated.rowCount(table))
        }
        migrated.close()
    }

    @Test
    fun migrationFourToFivePreservesVersionFourDataAndCreatesEmptyProviderTables() {
        migrationHelper.createDatabase(4).apply {
            execSQL(
                "INSERT INTO conversations VALUES " +
                    "('conversation-v4', 10, 20, 'ACTIVE', 'Version four conversation')",
            )
            execSQL(
                """
                INSERT INTO messages (
                    message_id, conversation_id, sequence_number, role, delivery_state,
                    content, created_at, updated_at
                ) VALUES (
                    'message-v4', 'conversation-v4', 1, 'USER', 'PERSISTED',
                    'Preserved version four message', 11, 11
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO conversation_timeline_heads VALUES " +
                    "('conversation-v4', 'message-v4', 5, 20)",
            )
            execSQL(
                """
                INSERT INTO memories (
                    memory_id, kind, scope, meaning, epistemic_basis, certainty,
                    truth_state, retention_state, lifecycle_state, temporal_state,
                    learned_at, sensitivity, created_at, updated_at
                ) VALUES (
                    'memory-v4', 'SEMANTIC', 'SHAI', 'Preserved version four memory',
                    'DIRECT_USER_STATEMENT', 'CERTAIN', 'SUPPORTED', 'ACTIVE',
                    'VALIDATED', 'CURRENT', 11, 'STANDARD', 11, 11
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO shai_system_instructions VALUES " +
                    "('PRIMARY', 'Preserved version four instructions', 1, 8, 12, 13)",
            )
            execSQL(
                """
                INSERT INTO attachments (
                    attachment_id, kind, mime_type, state, storage_key, byte_size,
                    content_sha256, source, created_at, updated_at
                ) VALUES (
                    'attachment-v4', 'IMAGE', 'image/png', 'AVAILABLE', 'blob-v4', 3,
                    'abc123', 'RIVEN_GENERATED', 14, 14
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO message_attachments VALUES " +
                    "('message-v4', 'attachment-v4', 0, 14)",
            )
            execSQL(
                """
                INSERT INTO generated_media_provenance (
                    attachment_id, generation_kind, generator_provider, generator_model,
                    provider_request_id, appearance_authority,
                    appearance_authority_fingerprint, request_fingerprint, generated_at
                ) VALUES (
                    'attachment-v4', 'GENERATED_IMAGE', 'provider-v4', 'model-v4',
                    'request-v4', 'appearance-v4', 'appearance-hash-v4',
                    'request-hash-v4', 14
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 5,
            migrations = listOf(MIGRATION_4_5),
        )

        assertEquals("message-v4", migrated.singleString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-v4'",
        ))
        assertEquals(5L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-v4'",
        ))
        assertEquals("Preserved version four memory", migrated.singleString(
            "SELECT meaning FROM memories WHERE memory_id = 'memory-v4'",
        ))
        assertEquals("Preserved version four instructions", migrated.singleString(
            "SELECT content FROM shai_system_instructions WHERE instruction_id = 'PRIMARY'",
        ))
        assertEquals("blob-v4", migrated.singleString(
            "SELECT storage_key FROM attachments WHERE attachment_id = 'attachment-v4'",
        ))
        assertEquals("attachment-v4", migrated.singleString(
            "SELECT attachment_id FROM message_attachments WHERE message_id = 'message-v4'",
        ))
        assertEquals("provider-v4", migrated.singleString(
            "SELECT generator_provider FROM generated_media_provenance " +
                "WHERE attachment_id = 'attachment-v4'",
        ))
        assertEquals(0L, migrated.rowCount("provider_profiles"))
        assertEquals(0L, migrated.rowCount("provider_profile_capabilities"))
        migrated.close()
    }

    @Test
    fun migrationOneToFiveChainsAllFoundationMigrations() {
        migrationHelper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO conversations VALUES " +
                    "('conversation-full-chain', 10, 30, 'ACTIVE', 'Full chain')",
            )
            execSQL(
                """
                INSERT INTO messages (
                    message_id, conversation_id, sequence_number, role, delivery_state,
                    content, created_at, updated_at
                ) VALUES
                    ('full-1', 'conversation-full-chain', 1, 'USER', 'PERSISTED', 'First', 11, 11),
                    ('full-2', 'conversation-full-chain', 2, 'ASSISTANT', 'PERSISTED', 'Second', 12, 12)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO memories (
                    memory_id, kind, scope, meaning, epistemic_basis, certainty,
                    truth_state, retention_state, lifecycle_state, temporal_state,
                    learned_at, sensitivity, created_at, updated_at
                ) VALUES (
                    'memory-full-chain', 'SEMANTIC', 'SHAI', 'Preserved from version one',
                    'DIRECT_USER_STATEMENT', 'CERTAIN', 'SUPPORTED', 'ACTIVE',
                    'VALIDATED', 'CURRENT', 11, 'STANDARD', 11, 11
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 5,
            migrations = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5),
        )

        assertEquals(listOf("full-1", "full-2"), migrated.activePath("full-2"))
        assertEquals("full-2", migrated.singleString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-full-chain'",
        ))
        assertEquals(0L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-full-chain'",
        ))
        assertEquals("Preserved from version one", migrated.singleString(
            "SELECT meaning FROM memories WHERE memory_id = 'memory-full-chain'",
        ))
        assertEquals(0L, migrated.rowCount("shai_system_instructions"))
        assertEquals(0L, migrated.rowCount("attachments"))
        assertEquals(0L, migrated.rowCount("message_attachments"))
        assertEquals(0L, migrated.rowCount("generated_media_provenance"))
        assertEquals(0L, migrated.rowCount("derived_artifact_attachment_dependencies"))
        assertEquals(0L, migrated.rowCount("provider_profiles"))
        assertEquals(0L, migrated.rowCount("provider_profile_capabilities"))
        migrated.close()
    }

    @Test
    fun migrationFiveToSixPreservesCanonicalDataAndCreatesEmptyDraftTablesWithRequiredSchema() {
        migrationHelper.createDatabase(5).apply {
            execSQL(
                "INSERT INTO conversations VALUES " +
                    "('conversation-v5', 10, 20, 'ACTIVE', 'Version five conversation')",
            )
            execSQL(
                "INSERT INTO conversation_timeline_heads VALUES " +
                    "('conversation-v5', NULL, 7, 20)",
            )
            execSQL(
                "INSERT INTO provider_profiles VALUES " +
                    "('profile-v5', 'Provider', 'adapter', 'https://example.invalid', " +
                    "'model', NULL, 1, 1, 10, 20)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 6,
            migrations = listOf(MIGRATION_5_6),
        )

        assertEquals(1L, migrated.rowCount("conversations"))
        assertEquals(7L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-v5'",
        ))
        assertEquals(1L, migrated.rowCount("provider_profiles"))
        assertEquals(0L, migrated.rowCount("conversation_drafts"))
        assertEquals(0L, migrated.rowCount("draft_attachments"))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_index_list('draft_attachments') " +
                "WHERE name = 'index_draft_attachments_attachment_id'",
        ))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_index_list('draft_attachments') " +
                "WHERE name = 'index_draft_attachments_conversation_id_attachment_order' " +
                "AND `unique` = 1",
        ))
        assertEquals(2L, migrated.singleLong("SELECT COUNT(*) FROM pragma_foreign_key_list('draft_attachments')"))
        migrated.close()
    }

    @Test
    fun migrationOneToSixRunsCanonicalFullChainWithoutInventingDrafts() {
        migrationHelper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO conversations VALUES " +
                    "('conversation-v1-v6', 10, 20, 'ACTIVE', 'Full chain v6')",
            )
            execSQL(
                """
                INSERT INTO messages (
                    message_id, conversation_id, sequence_number, role, delivery_state,
                    content, created_at, updated_at
                ) VALUES (
                    'message-v1-v6', 'conversation-v1-v6', 1, 'USER', 'PERSISTED',
                    'Preserved', 11, 11
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 6,
            migrations = listOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
            ),
        )

        assertEquals("message-v1-v6", migrated.singleString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id = 'conversation-v1-v6'",
        ))
        assertEquals("Preserved", migrated.singleString(
            "SELECT content FROM messages WHERE message_id = 'message-v1-v6'",
        ))
        assertEquals(0L, migrated.rowCount("conversation_drafts"))
        assertEquals(0L, migrated.rowCount("draft_attachments"))
        migrated.close()
    }

    @Test
    fun migrationSixToSevenPreservesCanonicalRowsAndCreatesEmptyAttentionTables() {
        migrationHelper.createDatabase(6).apply {
            execSQL(
                "INSERT INTO experiences (experience_id, event_order, experience_type, actor, " +
                    "occurred_at, recorded_at, sensitivity, availability) VALUES " +
                    "('experience-v6', 1, 'OTHER', 'OTHER', 1, 1, 'STANDARD', 'AVAILABLE')",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 7,
            migrations = listOf(MIGRATION_6_7),
        )

        assertEquals(1L, migrated.rowCount("experiences"))
        assertEquals(0L, migrated.rowCount("experience_attention_assessments"))
        assertEquals(0L, migrated.rowCount("experience_attention_signals"))
        migrated.close()
    }

    @Test
    fun migrationOneToSevenRunsCanonicalFullChainWithoutInventingAttention() {
        migrationHelper.createDatabase(1).apply {
            execSQL("INSERT INTO conversations VALUES ('conversation-v1-v7', 1, 1, 'ACTIVE', 'Preserved')")
            execSQL(
                "INSERT INTO messages (message_id, conversation_id, sequence_number, role, delivery_state, " +
                    "content, created_at, updated_at) VALUES " +
                    "('message-v1-v7', 'conversation-v1-v7', 1, 'USER', 'PERSISTED', 'Exact', 1, 1)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 7,
            migrations = listOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            ),
        )

        assertEquals("Exact", migrated.singleString("SELECT content FROM messages WHERE message_id='message-v1-v7'"))
        assertEquals(0L, migrated.rowCount("experience_attention_assessments"))
        assertEquals(0L, migrated.rowCount("experience_attention_signals"))
        migrated.close()
    }

    @Test
    fun migrationSixToSevenCreatesRequiredAttentionForeignKeysAndSignalIndex() {
        migrationHelper.createDatabase(6).close()
        val migrated = migrationHelper.runMigrationsAndValidate(7, listOf(MIGRATION_6_7))

        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_foreign_key_list('experience_attention_assessments') " +
                "WHERE `table`='experiences' AND on_delete='CASCADE' AND on_update='CASCADE'",
        ))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_foreign_key_list('experience_attention_signals') " +
                "WHERE `table`='experience_attention_assessments' AND on_delete='CASCADE' AND on_update='CASCADE'",
        ))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_index_list('experience_attention_signals') " +
                "WHERE name='index_experience_attention_signals_signal'",
        ))
        migrated.close()
    }

    @Test
    fun migrationSevenToEightPreservesCanonicalRowsAndCreatesEmptyConversationRuns() {
        migrationHelper.createDatabase(7).apply {
            execSQL("INSERT INTO conversations VALUES ('conversation-v7', 1, 2, 'ACTIVE', 'Preserved')")
            execSQL(
                "INSERT INTO messages (message_id, conversation_id, sequence_number, role, " +
                    "delivery_state, content, created_at, updated_at) VALUES " +
                    "('message-v7', 'conversation-v7', 1, 'USER', 'PERSISTED', 'Exact', 1, 1)",
            )
            execSQL("INSERT INTO conversation_timeline_heads VALUES ('conversation-v7', 'message-v7', 4, 2)")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8))

        assertEquals("Exact", migrated.singleString("SELECT content FROM messages WHERE message_id='message-v7'"))
        assertEquals(4L, migrated.singleLong(
            "SELECT timeline_revision FROM conversation_timeline_heads WHERE conversation_id='conversation-v7'",
        ))
        assertEquals(0L, migrated.rowCount("conversation_runs"))
        assertEquals(3L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_foreign_key_list('conversation_runs')",
        ))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_index_list('conversation_runs') " +
                "WHERE name='index_conversation_runs_active_conversation_id' AND `unique`=1",
        ))
        migrated.close()
    }

    @Test
    fun migrationOneToEightRunsFullNonDestructiveChain() {
        migrationHelper.createDatabase(1).apply {
            execSQL("INSERT INTO conversations VALUES ('conversation-v1-v8', 1, 1, 'ACTIVE', 'Preserved')")
            execSQL(
                "INSERT INTO messages (message_id, conversation_id, sequence_number, role, " +
                    "delivery_state, content, created_at, updated_at) VALUES " +
                    "('message-v1-v8', 'conversation-v1-v8', 1, 'USER', 'PERSISTED', 'Exact', 1, 1)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 8,
            migrations = listOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            ),
        )

        assertEquals("Exact", migrated.singleString("SELECT content FROM messages WHERE message_id='message-v1-v8'"))
        assertEquals("message-v1-v8", migrated.singleString(
            "SELECT active_head_message_id FROM conversation_timeline_heads " +
                "WHERE conversation_id='conversation-v1-v8'",
        ))
        assertEquals(0L, migrated.rowCount("conversation_runs"))
        migrated.close()
    }

    @Test
    fun migrationOneToEightPreservesTwoHundredFiftyConversationsAndFiveThousandLinearMessages() {
        migrationHelper.createDatabase(1).apply {
            execSQL("BEGIN IMMEDIATE TRANSACTION")
            repeat(250) { conversationIndex ->
                val conversationId = "stress-conversation-${conversationIndex.toString().padStart(3, '0')}"
                execSQL(
                    "INSERT INTO conversations VALUES " +
                        "('$conversationId', $conversationIndex, $conversationIndex, 'ACTIVE', 'Stress $conversationIndex')",
                )
                repeat(20) { messageIndex ->
                    val messageId = "$conversationId-message-${messageIndex.toString().padStart(2, '0')}"
                    execSQL(
                        "INSERT INTO messages (message_id, conversation_id, sequence_number, role, " +
                            "delivery_state, content, created_at, updated_at) VALUES " +
                            "('$messageId', '$conversationId', ${messageIndex + 1}, " +
                            "'${if (messageIndex % 2 == 0) "USER" else "ASSISTANT"}', " +
                            "'${if (messageIndex % 2 == 0) "PERSISTED" else "SUCCEEDED"}', " +
                            "'stress-content-$conversationIndex-$messageIndex', $messageIndex, $messageIndex)",
                    )
                }
            }
            execSQL("COMMIT")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 8,
            migrations = listOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            ),
        )

        assertEquals(250L, migrated.rowCount("conversations"))
        assertEquals(5_000L, migrated.rowCount("messages"))
        assertEquals(250L, migrated.rowCount("conversation_timeline_heads"))
        assertEquals(4_750L, migrated.rowCount("message_parent_edges"))
        assertEquals(
            "stress-conversation-249-message-19",
            migrated.singleString(
                "SELECT active_head_message_id FROM conversation_timeline_heads " +
                    "WHERE conversation_id='stress-conversation-249'",
            ),
        )
        assertEquals(20, migrated.activePath("stress-conversation-249-message-19").size)
        assertEquals(0L, migrated.rowCount("conversation_runs"))
        migrated.close()
    }

    @Test
    fun migrationEightToNineCreatesDurableAutomaticMemoryQueueWithoutInventingWork() {
        migrationHelper.createDatabase(8).apply {
            execSQL("INSERT INTO conversations VALUES ('conversation-v8', 1, 2, 'ACTIVE', 'Preserved')")
            execSQL(
                "INSERT INTO messages (message_id, conversation_id, sequence_number, role, " +
                    "delivery_state, content, created_at, updated_at) VALUES " +
                    "('message-v8', 'conversation-v8', 1, 'USER', 'PERSISTED', 'Exact', 1, 1)",
            )
            execSQL("INSERT INTO conversation_timeline_heads VALUES ('conversation-v8', 'message-v8', 4, 2)")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(9, listOf(MIGRATION_8_9))

        assertEquals("Exact", migrated.singleString("SELECT content FROM messages WHERE message_id='message-v8'"))
        assertEquals(0L, migrated.rowCount("automatic_memory_jobs"))
        assertEquals(3L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_foreign_key_list('automatic_memory_jobs')",
        ))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_index_list('automatic_memory_jobs') " +
                "WHERE name='index_automatic_memory_jobs_source_message_id' AND `unique`=1",
        ))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_index_list('automatic_memory_jobs') " +
                "WHERE name='index_automatic_memory_jobs_source_experience_id' AND `unique`=1",
        ))
        migrated.close()
    }

    @Test
    fun migrationOneToElevenRunsFullNonDestructiveChainWithoutInventingLifecycleRows() {
        migrationHelper.createDatabase(1).apply {
            execSQL("INSERT INTO conversations VALUES ('conversation-v1-v9', 1, 1, 'ACTIVE', 'Preserved')")
            execSQL(
                "INSERT INTO messages (message_id, conversation_id, sequence_number, role, delivery_state, " +
                    "content, created_at, updated_at) VALUES " +
                    "('message-v1-v9', 'conversation-v1-v9', 1, 'USER', 'PERSISTED', 'Exact', 1, 1)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 11,
            migrations = listOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
            ),
        )

        assertEquals("Exact", migrated.singleString("SELECT content FROM messages WHERE message_id='message-v1-v9'"))
        assertEquals(0L, migrated.rowCount("automatic_memory_jobs"))
        assertEquals(0L, migrated.rowCount("suppression_source_coverages"))
        assertEquals(0L, migrated.rowCount("memory_accessibility"))
        assertEquals(0L, migrated.rowCount("memory_aging_sweep_checkpoints"))
        assertEquals(0L, migrated.rowCount("open_loop_pass_checkpoints"))
        assertEquals(0L, migrated.rowCount("consolidation_checkpoints"))
        assertEquals(0L, migrated.rowCount("derived_artifact_payloads"))
        migrated.close()
    }

    @Test
    fun migrationTenToElevenAddsEmptyLifecycleAndRepairMaterializationTables() {
        migrationHelper.createDatabase(10).close()

        val migrated = migrationHelper.runMigrationsAndValidate(11, listOf(MIGRATION_10_11))

        assertEquals(0L, migrated.rowCount("memory_accessibility"))
        assertEquals(0L, migrated.rowCount("memory_aging_sweep_checkpoints"))
        assertEquals(0L, migrated.rowCount("open_loop_pass_checkpoints"))
        assertEquals(0L, migrated.rowCount("consolidation_checkpoints"))
        assertEquals(0L, migrated.rowCount("derived_artifact_payloads"))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_foreign_key_list('memory_accessibility')",
        ))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_foreign_key_list('derived_artifact_payloads')",
        ))
        migrated.close()
    }

    @Test
    fun migrationNineToTenPreservesTombstonesAndAddsEmptyDurableCoverage() {
        migrationHelper.createDatabase(9).apply {
            execSQL(
                "INSERT INTO suppression_tombstones VALUES " +
                    "('tombstone-v9', 'FORGET', '${"a".repeat(64)}', 1, 10, NULL, 1)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(10, listOf(MIGRATION_9_10))

        assertEquals(1L, migrated.rowCount("suppression_tombstones"))
        assertEquals(0L, migrated.rowCount("suppression_source_coverages"))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_foreign_key_list('suppression_source_coverages')",
        ))
        assertEquals(1L, migrated.singleLong(
            "SELECT COUNT(*) FROM pragma_index_list('suppression_source_coverages') " +
                "WHERE name='index_suppression_source_coverages_tombstone_id' AND `unique`=1",
        ))
        migrated.close()
    }

    @Test
    fun historicalSchemaExportsOneThroughSevenRemainByteIdentical() {
        val expected = mapOf(
            1 to "8021b472cb9147553b9d9fda9e89716f82e927d7",
            2 to "c0242ecce351766e8c084026bf7750904409d8ac",
            3 to "5e642593a2b92ea167f4fb171a2446216dfbf104",
            4 to "761805095ce31a2ac81adcff4f3620aeb81c22c0",
            5 to "85eaaa7d2f6e9d2a6be7d6b63b8b9e0d1428929f",
            6 to "fc9a21739fb265eee6472b0e242714539194cfed",
            7 to "65a8942ad575919cf61fb8c8e8f3f4fcacf40fa2",
        )
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

        expected.forEach { (version, expectedGitBlobSha) ->
            val bytes = assets.open(
                "com.shai.riven.data.persistence.RivenDatabase/$version.json",
            ).use { input ->
                input.readBytes()
                    .toString(Charsets.UTF_8)
                    .replace("\r\n", "\n")
                    .toByteArray(Charsets.UTF_8)
            }
            val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
            val actual = MessageDigest.getInstance("SHA-1")
                .digest(header + bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            assertEquals("Historical schema v$version changed", expectedGitBlobSha, actual)
        }
    }

    @Test
    fun databaseVersionSixHasExactlyThirtyFourApplicationTables() {
        val created = migrationHelper.createDatabase(6)

        val count = created.singleLong(
            """
            SELECT COUNT(*) FROM sqlite_master
            WHERE type = 'table'
              AND name NOT IN ('android_metadata', 'room_master_table')
              AND name NOT LIKE 'sqlite_%'
            """.trimIndent(),
        )

        assertEquals(34L, count)
        created.close()
    }

    @Test
    fun databaseVersionSevenHasExactlyThirtySixApplicationTables() {
        val created = migrationHelper.createDatabase(7)
        val count = created.singleLong(
            """
            SELECT COUNT(*) FROM sqlite_master
            WHERE type = 'table'
              AND name NOT IN ('android_metadata', 'room_master_table')
              AND name NOT LIKE 'sqlite_%'
            """.trimIndent(),
        )
        assertEquals(36L, count)
        created.close()
    }

    @Test
    fun databaseVersionEightHasExactlyThirtySevenApplicationTables() {
        val created = migrationHelper.createDatabase(8)
        val count = created.singleLong(
            """
            SELECT COUNT(*) FROM sqlite_master
            WHERE type = 'table'
              AND name NOT IN ('android_metadata', 'room_master_table')
              AND name NOT LIKE 'sqlite_%'
            """.trimIndent(),
        )
        assertEquals(37L, count)
        created.close()
    }

    @Test
    fun databaseVersionNineHasExactlyThirtyEightApplicationTables() {
        val created = migrationHelper.createDatabase(9)
        val count = created.singleLong(
            """
            SELECT COUNT(*) FROM sqlite_master
            WHERE type = 'table'
              AND name NOT IN ('android_metadata', 'room_master_table')
              AND name NOT LIKE 'sqlite_%'
            """.trimIndent(),
        )
        assertEquals(38L, count)
        created.close()
    }

    @Test
    fun databaseVersionElevenHasExactlyFortyFourApplicationTables() {
        val created = migrationHelper.createDatabase(11)
        val count = created.singleLong(
            """
            SELECT COUNT(*) FROM sqlite_master
            WHERE type = 'table'
              AND name NOT IN ('android_metadata', 'room_master_table')
              AND name NOT LIKE 'sqlite_%'
            """.trimIndent(),
        )
        assertEquals(44L, count)
        created.close()
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
