package com.shai.riven.data.presence

enum class RivenRoom(val stableId: String, val displayName: String) {
    LIVING_ROOM("living_room", "Living room"),
    STUDY("study", "Study"),
    TERRACE("terrace", "Terrace"),
    KITCHEN("kitchen", "Kitchen"),
    BEDROOM("bedroom", "Bedroom");

    companion object {
        fun fromStableId(value: String): RivenRoom? = entries.singleOrNull { it.stableId == value }
    }
}

enum class RivenSemanticSprite(val stableId: String) {
    STANDING_RELAXED("standing_relaxed"),
    STANDING_WARM_SMILE("standing_warm_smile"),
    STANDING_TEASING("standing_teasing"),
    SEATED_RELAXED("seated_relaxed"),
    SEATED_AMUSED("seated_amused"),
    SEATED_THOUGHTFUL("seated_thoughtful");

    companion object {
        fun fromStableId(value: String): RivenSemanticSprite? =
            entries.singleOrNull { it.stableId == value }
    }
}

data class RivenPresenceSnapshot(
    val actualRoom: RivenRoom,
    val semanticSprite: RivenSemanticSprite,
    val browsedRoom: RivenRoom,
    val presenceRevision: Long,
    val browserRevision: Long,
) {
    val isRivenVisibleInBrowsedRoom: Boolean
        get() = actualRoom == browsedRoom
}

sealed interface RivenPresenceReadResult {
    data class Success(val snapshot: RivenPresenceSnapshot) : RivenPresenceReadResult
    data class Failure(val reason: String) : RivenPresenceReadResult
}

sealed interface RivenPresenceWriteResult {
    data class Updated(val snapshot: RivenPresenceSnapshot) : RivenPresenceWriteResult
    data class Unchanged(val snapshot: RivenPresenceSnapshot) : RivenPresenceWriteResult
    data class Rejected(val reason: String) : RivenPresenceWriteResult
}

/** Approved art is deliberately supplied later; room state never fabricates replacement assets. */
interface RivenVisualAssetCatalog {
    fun fullBodyAssetId(sprite: RivenSemanticSprite): String?
    fun arcadePortraitAssetId(): String?
}

object NoApprovedRivenVisualAssets : RivenVisualAssetCatalog {
    override fun fullBodyAssetId(sprite: RivenSemanticSprite): String? = null
    override fun arcadePortraitAssetId(): String? = null
}
