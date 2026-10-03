package com.shai.riven.ui

import com.shai.riven.data.presence.RivenPresenceSnapshot
import com.shai.riven.data.presence.RivenRoom
import com.shai.riven.data.presence.RivenSemanticSprite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RivenUiAssetsTest {
    @Test
    fun kitchenUsesTheAdjoiningLivingRoomBackgroundWithoutInventingAnAsset() {
        val assets = RivenUiAssets(
            roomBackgrounds = mapOf(
                RivenRoom.LIVING_ROOM to 101,
                RivenRoom.STUDY to 102,
            ),
        )

        assertEquals(101, assets.roomBackgroundResourceId(RivenRoom.LIVING_ROOM))
        assertEquals(101, assets.roomBackgroundResourceId(RivenRoom.KITCHEN))
        assertEquals(102, assets.roomBackgroundResourceId(RivenRoom.STUDY))
        assertNull(assets.roomBackgroundResourceId(RivenRoom.BEDROOM))
    }

    @Test
    fun spriteIsAvailableOnlyWhenRivenOccupiesTheBrowsedRoom() {
        val sprite = RivenSemanticSprite.STANDING_WARM_SMILE
        val assets = RivenUiAssets(fullBodySprites = mapOf(sprite to 201))

        assertEquals(
            201,
            assets.visibleSpriteResourceId(
                snapshot(actual = RivenRoom.STUDY, browsed = RivenRoom.STUDY, sprite = sprite),
            ),
        )
        assertNull(
            assets.visibleSpriteResourceId(
                snapshot(actual = RivenRoom.STUDY, browsed = RivenRoom.TERRACE, sprite = sprite),
            ),
        )
        assertNull(assets.visibleSpriteResourceId(null))
    }

    @Test
    fun everyApprovedSemanticPoseHasBoundedPlacement() {
        val placements = RivenSemanticSprite.entries.associateWith { it.uiPlacement() }

        assertEquals(RivenSemanticSprite.entries.size, placements.size)
        assertTrue(placements.values.all { it.heightFraction in 0.5f..0.8f })
        assertTrue(placements.values.all { it.bottomPaddingDp >= 0 })
        assertTrue(placements.values.map { it.heightFraction to it.anchor }.distinct().size > 1)
    }

    private fun snapshot(
        actual: RivenRoom,
        browsed: RivenRoom,
        sprite: RivenSemanticSprite,
    ) = RivenPresenceSnapshot(
        actualRoom = actual,
        semanticSprite = sprite,
        browsedRoom = browsed,
        presenceRevision = 2,
        browserRevision = 3,
    )
}
