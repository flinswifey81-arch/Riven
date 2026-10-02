package com.shai.riven.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_parent_edges` (
                `child_message_id` TEXT NOT NULL,
                `parent_message_id` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`child_message_id`),
                FOREIGN KEY(`child_message_id`) REFERENCES `messages`(`message_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(`parent_message_id`) REFERENCES `messages`(`message_id`) ON UPDATE CASCADE ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_parent_edges_parent_message_id` " +
                "ON `message_parent_edges` (`parent_message_id`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_timeline_heads` (
                `conversation_id` TEXT NOT NULL,
                `active_head_message_id` TEXT,
                `timeline_revision` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`conversation_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`conversation_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(`active_head_message_id`) REFERENCES `messages`(`message_id`) ON UPDATE CASCADE ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_timeline_heads_active_head_message_id` " +
                "ON `conversation_timeline_heads` (`active_head_message_id`)",
        )

        db.execSQL(
            """
            INSERT INTO `message_parent_edges` (`child_message_id`, `parent_message_id`, `created_at`)
            SELECT child.`message_id`,
                   (
                       SELECT parent.`message_id`
                       FROM `messages` AS parent
                       WHERE parent.`conversation_id` = child.`conversation_id`
                         AND parent.`sequence_number` < child.`sequence_number`
                       ORDER BY parent.`sequence_number` DESC
                       LIMIT 1
                   ),
                   child.`created_at`
            FROM `messages` AS child
            WHERE EXISTS (
                SELECT 1
                FROM `messages` AS parent
                WHERE parent.`conversation_id` = child.`conversation_id`
                  AND parent.`sequence_number` < child.`sequence_number`
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `conversation_timeline_heads` (
                `conversation_id`,
                `active_head_message_id`,
                `timeline_revision`,
                `updated_at`
            )
            SELECT conversation.`conversation_id`,
                   (
                       SELECT message.`message_id`
                       FROM `messages` AS message
                       WHERE message.`conversation_id` = conversation.`conversation_id`
                       ORDER BY message.`sequence_number` DESC
                       LIMIT 1
                   ),
                   0,
                   conversation.`updated_at`
            FROM `conversations` AS conversation
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `shai_system_instructions` (
                `instruction_id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `is_enabled` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`instruction_id`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `attachments` (
                `attachment_id` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `storage_key` TEXT NOT NULL,
                `byte_size` INTEGER,
                `content_sha256` TEXT,
                `source` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`attachment_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_attachments_storage_key` " +
                "ON `attachments` (`storage_key`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_attachments` (
                `message_id` TEXT NOT NULL,
                `attachment_id` TEXT NOT NULL,
                `attachment_order` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`message_id`, `attachment_id`),
                FOREIGN KEY(`message_id`) REFERENCES `messages`(`message_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(`attachment_id`) REFERENCES `attachments`(`attachment_id`) ON UPDATE CASCADE ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_attachments_attachment_id` " +
                "ON `message_attachments` (`attachment_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_attachments_message_id_attachment_order` " +
                "ON `message_attachments` (`message_id`, `attachment_order`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `generated_media_provenance` (
                `attachment_id` TEXT NOT NULL,
                `generation_kind` TEXT NOT NULL,
                `generator_provider` TEXT,
                `generator_model` TEXT,
                `provider_request_id` TEXT,
                `appearance_authority` TEXT,
                `appearance_authority_fingerprint` TEXT,
                `request_fingerprint` TEXT,
                `generated_at` INTEGER NOT NULL,
                PRIMARY KEY(`attachment_id`),
                FOREIGN KEY(`attachment_id`) REFERENCES `attachments`(`attachment_id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `derived_artifact_attachment_dependencies` (
                `derived_artifact_id` TEXT NOT NULL,
                `attachment_id` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`derived_artifact_id`, `attachment_id`),
                FOREIGN KEY(`derived_artifact_id`) REFERENCES `derived_artifacts`(`derived_artifact_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(`attachment_id`) REFERENCES `attachments`(`attachment_id`) ON UPDATE CASCADE ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_derived_artifact_attachment_dependencies_attachment_id` " +
                "ON `derived_artifact_attachment_dependencies` (`attachment_id`)",
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `provider_profiles` (
                `profile_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `adapter_id` TEXT NOT NULL,
                `endpoint_base_url` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `credential_slot_id` TEXT,
                `is_enabled` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`profile_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_provider_profiles_adapter_id` " +
                "ON `provider_profiles` (`adapter_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_provider_profiles_credential_slot_id` " +
                "ON `provider_profiles` (`credential_slot_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_provider_profiles_is_enabled` " +
                "ON `provider_profiles` (`is_enabled`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `provider_profile_capabilities` (
                `profile_id` TEXT NOT NULL,
                `capability` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`profile_id`, `capability`),
                FOREIGN KEY(`profile_id`) REFERENCES `provider_profiles`(`profile_id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_provider_profile_capabilities_capability` " +
                "ON `provider_profile_capabilities` (`capability`)",
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_drafts` (
                `conversation_id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`conversation_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`conversation_id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `draft_attachments` (
                `conversation_id` TEXT NOT NULL,
                `attachment_id` TEXT NOT NULL,
                `attachment_order` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`conversation_id`, `attachment_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `conversation_drafts`(`conversation_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(`attachment_id`) REFERENCES `attachments`(`attachment_id`) ON UPDATE CASCADE ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_draft_attachments_attachment_id` " +
                "ON `draft_attachments` (`attachment_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_draft_attachments_conversation_id_attachment_order` " +
                "ON `draft_attachments` (`conversation_id`, `attachment_order`)",
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `experience_attention_assessments` (
                `experience_id` TEXT NOT NULL,
                `outcome` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`experience_id`),
                FOREIGN KEY(`experience_id`) REFERENCES `experiences`(`experience_id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `experience_attention_signals` (
                `experience_id` TEXT NOT NULL,
                `signal` TEXT NOT NULL,
                `polarity` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`experience_id`, `signal`, `polarity`),
                FOREIGN KEY(`experience_id`) REFERENCES `experience_attention_assessments`(`experience_id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experience_attention_signals_signal` " +
                "ON `experience_attention_signals` (`signal`)",
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_runs` (
                `run_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `user_message_id` TEXT NOT NULL,
                `assistant_message_id` TEXT NOT NULL,
                `trigger` TEXT NOT NULL,
                `retry_of_run_id` TEXT,
                `regenerate_of_message_id` TEXT,
                `state` TEXT NOT NULL,
                `active_conversation_id` TEXT,
                `idempotency_key` TEXT NOT NULL,
                `input_fingerprint` TEXT NOT NULL,
                `owner_session_token` TEXT NOT NULL,
                `profile_id` TEXT NOT NULL,
                `profile_revision` INTEGER,
                `adapter_id` TEXT,
                `endpoint_base_url` TEXT,
                `model_id` TEXT,
                `selected_head_message_id` TEXT,
                `context_head_message_id` TEXT NOT NULL,
                `reserved_timeline_revision` INTEGER NOT NULL,
                `provider_request_id` TEXT,
                `error_code` TEXT,
                `created_at` INTEGER NOT NULL,
                `started_at` INTEGER,
                `updated_at` INTEGER NOT NULL,
                `finished_at` INTEGER,
                PRIMARY KEY(`run_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`conversation_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(`user_message_id`) REFERENCES `messages`(`message_id`) ON UPDATE CASCADE ON DELETE RESTRICT,
                FOREIGN KEY(`assistant_message_id`) REFERENCES `messages`(`message_id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_runs_conversation_id` " +
                "ON `conversation_runs` (`conversation_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_runs_user_message_id` " +
                "ON `conversation_runs` (`user_message_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_runs_assistant_message_id` " +
                "ON `conversation_runs` (`assistant_message_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_runs_idempotency_key` " +
                "ON `conversation_runs` (`idempotency_key`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_runs_active_conversation_id` " +
                "ON `conversation_runs` (`active_conversation_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_runs_owner_session_token` " +
                "ON `conversation_runs` (`owner_session_token`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_runs_retry_of_run_id` " +
                "ON `conversation_runs` (`retry_of_run_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_runs_regenerate_of_message_id` " +
                "ON `conversation_runs` (`regenerate_of_message_id`)",
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `automatic_memory_jobs` (
                `automatic_memory_job_id` TEXT NOT NULL,
                `originating_run_id` TEXT NOT NULL,
                `source_message_id` TEXT NOT NULL,
                `source_experience_id` TEXT NOT NULL,
                `source_timeline_revision` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `next_stage` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_error_code` TEXT,
                PRIMARY KEY(`automatic_memory_job_id`),
                FOREIGN KEY(`originating_run_id`) REFERENCES `conversation_runs`(`run_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(`source_message_id`) REFERENCES `messages`(`message_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(`source_experience_id`) REFERENCES `experiences`(`experience_id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_automatic_memory_jobs_originating_run_id` " +
                "ON `automatic_memory_jobs` (`originating_run_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_automatic_memory_jobs_source_message_id` " +
                "ON `automatic_memory_jobs` (`source_message_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_automatic_memory_jobs_source_experience_id` " +
                "ON `automatic_memory_jobs` (`source_experience_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_automatic_memory_jobs_state_updated_at` " +
                "ON `automatic_memory_jobs` (`state`, `updated_at`)",
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `suppression_source_coverages` (
                `coverage_id` TEXT NOT NULL,
                `tombstone_id` TEXT NOT NULL,
                `source_identity_hash` TEXT NOT NULL,
                `start_offset` INTEGER NOT NULL,
                `end_offset_exclusive` INTEGER NOT NULL,
                PRIMARY KEY(`coverage_id`),
                FOREIGN KEY(`tombstone_id`) REFERENCES `suppression_tombstones`(`tombstone_id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_suppression_source_coverages_tombstone_id` " +
                "ON `suppression_source_coverages` (`tombstone_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_suppression_source_coverages_source_identity_hash_start_offset_end_offset_exclusive` " +
                "ON `suppression_source_coverages` (`source_identity_hash`, `start_offset`, `end_offset_exclusive`)",
        )
    }
}
