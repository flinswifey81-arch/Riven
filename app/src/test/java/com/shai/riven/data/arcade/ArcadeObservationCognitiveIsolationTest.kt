package com.shai.riven.data.arcade

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.context.EphemeralAppStateStore
import com.shai.riven.data.persistence.RivenDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArcadeObservationCognitiveIsolationTest {
    private lateinit var database: RivenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun publishingAndClearingGameplayDoesNotCreateMemoryOrExtractionWork() {
        val before = MEMORY_TABLES.associateWith(::rowCount)
        val bridge = ArcadeObservationContextBridge(EphemeralAppStateStore())
        val observation = ArcadeGameObservation(
            gameId = "heart-match",
            gameTitle = "Starstruck",
            sessionId = "session",
            sequence = 7,
            view = ArcadeObservationView.SOLO_PUBLIC,
            phase = "playing",
            facts = listOf(ArcadeObservationFact("Matches", "3")),
            recentEvents = listOf(ArcadePublicEvent(7, "Completed a match move.")),
            observedAt = 100,
        )

        bridge.publish(observation)
        bridge.clear()

        assertEquals(before, MEMORY_TABLES.associateWith(::rowCount))
    }

    private fun rowCount(table: String): Long = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        val MEMORY_TABLES = listOf(
            "automatic_memory_jobs",
            "experiences",
            "candidate_memories",
            "candidate_memory_evidence",
            "memories",
            "memory_evidence",
            "memory_relationships",
            "memory_entity_links",
        )
    }
}
