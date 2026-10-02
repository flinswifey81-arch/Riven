package com.shai.riven.data.context

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.presence.RivenPresenceService
import com.shai.riven.data.presence.RivenRoom
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RivenPresenceContextSourceTest {
    private lateinit var database: RivenDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            RivenDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun exposesPersistedActualPresenceButNotTheBrowsedRoom() = runBlocking {
        val service = RivenPresenceService(database)
        service.initialize(1)
        service.browse(RivenRoom.STUDY, 2)

        val result = RivenPresenceContextSource(service).read(RivenContextReadRequest(now = 3))
            as RivenContextSourceResult.Success
        val content = result.payloads.single().content

        assertTrue(content.contains("actual_room=living_room"))
        assertFalse(content.contains("browsed_room=study"))
        assertTrue(content.contains(RivenPresenceContextSource.CONTROL_PREFIX))
        assertTrue(result.freshnessReceipts.single() is RivenContextFreshnessReceipt.RivenPresence)
    }
}
