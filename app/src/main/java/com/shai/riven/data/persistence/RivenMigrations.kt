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
