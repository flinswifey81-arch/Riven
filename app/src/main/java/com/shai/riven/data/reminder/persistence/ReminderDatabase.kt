package com.shai.riven.data.reminder.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalReminderEntity::class,
        ReminderFeatureControlEntity::class,
        ReminderQuietHoursEntity::class,
        ReminderEventEntity::class,
    ],
    version = 1,
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
        ).build()
    }
}
