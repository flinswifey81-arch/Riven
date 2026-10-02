package com.shai.riven.data.reminder.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocalReminderEntity::class,
        ReminderFeatureControlEntity::class,
        ReminderQuietHoursEntity::class,
        ReminderEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    companion object {
        const val DATABASE_NAME = "riven-reminders.db"

        fun build(context: Context): ReminderDatabase = Room.databaseBuilder(
            context.applicationContext,
            ReminderDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2).build()

        fun buildNamedForRestoreValidation(
            context: Context,
            databaseNameOrAbsolutePath: String,
        ): ReminderDatabase = Room.databaseBuilder(
            context.applicationContext,
            ReminderDatabase::class.java,
            databaseNameOrAbsolutePath,
        ).addMigrations(MIGRATION_1_2).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_reminders ADD COLUMN ring_until_at INTEGER")
            }
        }
    }
}
