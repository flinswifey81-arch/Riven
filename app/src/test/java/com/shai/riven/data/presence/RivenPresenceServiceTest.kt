package com.shai.riven.data.presence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RivenPresenceServiceTest {
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
    fun browsingNeverMovesRivenAndRepeatedNavigationIsIdempotent() = runBlocking {
        val service = RivenPresenceService(database)
        val initial = (service.initialize(1) as RivenPresenceReadResult.Success).snapshot

        val firstBrowse = service.browse(RivenRoom.STUDY, 2) as RivenPresenceWriteResult.Updated
        val repeated = service.browse(RivenRoom.STUDY, 3) as RivenPresenceWriteResult.Unchanged

        assertEquals(RivenRoom.LIVING_ROOM, initial.actualRoom)
        assertEquals(RivenRoom.LIVING_ROOM, firstBrowse.snapshot.actualRoom)
        assertEquals(RivenRoom.STUDY, firstBrowse.snapshot.browsedRoom)
        assertFalse(firstBrowse.snapshot.isRivenVisibleInBrowsedRoom)
        assertEquals(0L, firstBrowse.snapshot.presenceRevision)
        assertEquals(1L, repeated.snapshot.browserRevision)
    }

    @Test
    fun validatesModelRoomAndSpriteThenRestoresCommittedPresence() = runBlocking {
        val service = RivenPresenceService(database)
        service.initialize(1)
        service.browse(RivenRoom.TERRACE, 2)

        assertTrue(service.applyModelControl("attic", "standing_relaxed", 0, 3) is RivenPresenceWriteResult.Rejected)
        assertTrue(service.applyModelControl("study", "dancing", 0, 3) is RivenPresenceWriteResult.Rejected)
        val moved = service.applyModelControl("bedroom", "seated_thoughtful", 0, 4)
            as RivenPresenceWriteResult.Updated

        assertEquals(RivenRoom.BEDROOM, moved.snapshot.actualRoom)
        assertEquals(RivenSemanticSprite.SEATED_THOUGHTFUL, moved.snapshot.semanticSprite)
        assertEquals(RivenRoom.TERRACE, moved.snapshot.browsedRoom)
        assertEquals(1L, moved.snapshot.presenceRevision)
        assertEquals(1L, moved.snapshot.browserRevision)

        val restored = (RivenPresenceService(database).snapshot() as RivenPresenceReadResult.Success).snapshot
        assertEquals(moved.snapshot, restored)
        assertTrue(service.applyModelControl("study", "standing_teasing", 0, 5) is RivenPresenceWriteResult.Rejected)
    }

    @Test
    fun approvedArtCatalogKeepsRoomSpritesAndArcadePortraitSeparate() {
        assertNull(NoApprovedRivenVisualAssets.roomBackgroundAssetId(RivenRoom.LIVING_ROOM))
        assertNull(NoApprovedRivenVisualAssets.fullBodyAssetId(RivenSemanticSprite.STANDING_WARM_SMILE))
        assertNull(NoApprovedRivenVisualAssets.brandIconAssetId())
        assertNull(NoApprovedRivenVisualAssets.arcadePortraitAssetId())
    }
}
