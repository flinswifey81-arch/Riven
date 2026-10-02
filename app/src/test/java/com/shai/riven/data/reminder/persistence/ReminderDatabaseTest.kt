package com.shai.riven.data.reminder.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderDatabaseTest {
    private lateinit var database: ReminderDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ReminderDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun independentVersionTwoSchemaContainsOnlyReminderTables() {
        val tables = database.openHelper.writableDatabase.query(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'android_%'
              AND name NOT LIKE 'room_%'
              AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertEquals(
            listOf(
                "local_reminders",
                "reminder_events",
                "reminder_feature_controls",
                "reminder_quiet_hours",
            ),
            tables,
        )
    }
}
