package com.shai.riven.data.context

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EphemeralAppStateCognitiveIsolationTest {
    private lateinit var database: RivenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun publishUpdateReadExpirePurgeAndClearHaveNoCanonicalOrCognitiveSideEffects() = runBlocking {
        val before = COGNITIVE_AND_CANONICAL_TABLES.associateWith(::rowCount)
        val store = EphemeralAppStateStore()
        val source = EphemeralAppStateContextSource(store)
        val registry = RivenContextSourceRegistry(listOf(source))

        assertTrue(
            store.publish(
                PublishEphemeralAppStateInput(
                    stateId = "ARCADE_STATE",
                    content = "game: STACKER\nstatus: ACTIVE",
                    exposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
                    priority = 10,
                    expectedRevision = 0,
                    observedAt = 1,
                    validUntil = 10,
                ),
            ) is EphemeralAppStateWriteResult.Published,
        )
        assertTrue(
            store.publish(
                PublishEphemeralAppStateInput(
                    stateId = "LOCAL_NAVIGATION",
                    content = "screen: CHAT",
                    exposure = EphemeralAppStateExposure.LOCAL_ONLY,
                    priority = 0,
                    expectedRevision = 0,
                    observedAt = 2,
                ),
            ) is EphemeralAppStateWriteResult.Published,
        )
        assertTrue(
            store.publish(
                PublishEphemeralAppStateInput(
                    stateId = "ARCADE_STATE",
                    content = "game: STACKER\nstatus: GAME_OVER",
                    exposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
                    priority = 10,
                    expectedRevision = 1,
                    observedAt = 3,
                    validUntil = 10,
                ),
            ) is EphemeralAppStateWriteResult.Published,
        )

        assertTrue(registry.collect(now = 9) is RivenContextCollectionResult.Success)
        assertTrue(registry.collect(now = 10) is RivenContextCollectionResult.Success)
        assertEquals(listOf("ARCADE_STATE"), store.purgeExpired(now = 10).removedStateIds)
        assertTrue(
            store.clear(ClearEphemeralAppStateInput("LOCAL_NAVIGATION", expectedRevision = 1))
                is EphemeralAppStateWriteResult.Cleared,
        )

        val after = COGNITIVE_AND_CANONICAL_TABLES.associateWith(::rowCount)
        assertEquals(before, after)
    }

    private fun rowCount(table: String): Long = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        val COGNITIVE_AND_CANONICAL_TABLES = listOf(
            "conversations",
            "messages",
            "experiences",
            "candidate_memories",
            "memories",
            "open_loops",
            "suppression_tombstones",
            "derived_artifacts",
            "repair_jobs",
            "attachments",
            "shai_system_instructions",
        )
    }
}
