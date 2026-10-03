package com.shai.riven.ui

import androidx.annotation.DrawableRes
import com.shai.riven.R
import com.shai.riven.data.presence.RivenPresenceSnapshot
import com.shai.riven.data.presence.RivenRoom
import com.shai.riven.data.presence.RivenSemanticSprite

/**
 * Resource bindings for approved visual assets.
 *
 * The empty default is intentional: an approved asset is rendered only after its bytes have
 * been materialized into the consumer and assigned a real Android resource id.
 */
data class RivenUiAssets(
    val roomBackgrounds: Map<RivenRoom, Int> = emptyMap(),
    val fullBodySprites: Map<RivenSemanticSprite, Int> = emptyMap(),
    @param:DrawableRes val brandIconResourceId: Int? = null,
    @param:DrawableRes val arcadePortraitResourceId: Int? = null,
) {
    @DrawableRes
    fun roomBackgroundResourceId(room: RivenRoom): Int? = roomBackgrounds[
        if (room == RivenRoom.KITCHEN) RivenRoom.LIVING_ROOM else room
    ]

    @DrawableRes
    fun visibleSpriteResourceId(state: RivenPresenceSnapshot?): Int? = state
        ?.takeIf(RivenPresenceSnapshot::isRivenVisibleInBrowsedRoom)
        ?.semanticSprite
        ?.let(fullBodySprites::get)

    companion object {
        val Empty = RivenUiAssets()

        /**
         * The versioned, approved production art set. Kitchen intentionally reuses the adjoining
         * living-room plate because no separate kitchen plate was supplied or approved.
         */
        val Approved = RivenUiAssets(
            roomBackgrounds = mapOf(
                RivenRoom.BEDROOM to R.drawable.riven_room_bedroom,
                RivenRoom.LIVING_ROOM to R.drawable.riven_room_living,
                RivenRoom.STUDY to R.drawable.riven_room_study,
                RivenRoom.TERRACE to R.drawable.riven_room_terrace,
            ),
            fullBodySprites = mapOf(
                RivenSemanticSprite.STANDING_RELAXED to R.drawable.riven_sprite_standing_relaxed,
                RivenSemanticSprite.STANDING_WARM_SMILE to R.drawable.riven_sprite_standing_warm_smile,
                RivenSemanticSprite.STANDING_TEASING to R.drawable.riven_sprite_standing_teasing,
                RivenSemanticSprite.SEATED_RELAXED to R.drawable.riven_sprite_seated_relaxed,
                RivenSemanticSprite.SEATED_AMUSED to R.drawable.riven_sprite_seated_amused,
                RivenSemanticSprite.SEATED_THOUGHTFUL to R.drawable.riven_sprite_seated_thoughtful,
            ),
            brandIconResourceId = R.drawable.riven_brand_icon,
            arcadePortraitResourceId = R.drawable.riven_arcade_portrait,
        )
    }
}

internal enum class RivenSpriteAnchor {
    START,
    CENTER,
    END,
}

internal data class RivenSpritePlacement(
    val heightFraction: Float,
    val anchor: RivenSpriteAnchor,
    val bottomPaddingDp: Int,
)

internal fun RivenSemanticSprite.uiPlacement(): RivenSpritePlacement = when (this) {
    RivenSemanticSprite.STANDING_RELAXED -> RivenSpritePlacement(0.72f, RivenSpriteAnchor.CENTER, 8)
    RivenSemanticSprite.STANDING_WARM_SMILE -> RivenSpritePlacement(0.70f, RivenSpriteAnchor.START, 8)
    RivenSemanticSprite.STANDING_TEASING -> RivenSpritePlacement(0.74f, RivenSpriteAnchor.END, 6)
    RivenSemanticSprite.SEATED_RELAXED -> RivenSpritePlacement(0.55f, RivenSpriteAnchor.CENTER, 10)
    RivenSemanticSprite.SEATED_AMUSED -> RivenSpritePlacement(0.57f, RivenSpriteAnchor.START, 8)
    RivenSemanticSprite.SEATED_THOUGHTFUL -> RivenSpritePlacement(0.56f, RivenSpriteAnchor.END, 10)
}
