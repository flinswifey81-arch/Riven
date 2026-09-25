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
