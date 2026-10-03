package com.shai.riven.data.context

import com.shai.riven.data.presence.RivenPresenceReadResult
import com.shai.riven.data.presence.RivenPresenceService
import com.shai.riven.data.presence.RivenRoom
import com.shai.riven.data.presence.RivenSemanticSprite

class RivenPresenceContextSource(
    private val service: RivenPresenceService,
) : RivenContextSource {
    override val descriptor = RivenContextSourceDescriptor(
        sourceId = SOURCE_ID,
        layer = RivenContextLayer.APP_INVARIANTS_SAFETY_AND_TOOL_TRUTH,
        provenanceClass = RivenContextProvenanceClass.DYNAMIC_APP_STATE,
        criticality = RivenContextSourceCriticality.REQUIRED,
        orderWithinLayer = 10,
        maxFragments = 1,
        maxCharsPerFragment = MAX_CHARS,
        maxAggregateChars = MAX_CHARS,
        budgetBehavior = RivenContextBudgetBehavior.REQUIRED,
        contentAuthority = RivenContextContentAuthority.INSTRUCTIONS,
    )

    override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult =
        when (val result = service.snapshot()) {
            is RivenPresenceReadResult.Failure -> RivenContextSourceResult.Failure(
                RivenContextSourceError.ReadFailure("PRESENCE_UNAVAILABLE", result.reason),
            )
            is RivenPresenceReadResult.Success -> {
                val state = result.snapshot
                RivenContextSourceResult.Success(
                    payloads = listOf(
                        RivenContextPayload(
                            fragmentId = PRIMARY_FRAGMENT_ID,
                            content = buildString {
                                appendLine("RIVEN ROOM PRESENCE AND SEMANTIC SPRITE CONTROL")
                                appendLine("CURRENT_USER_VIEWED_ROOM=${state.browsedRoom.stableId}")
                                appendLine("RIVEN_ACTUAL_ROOM=${state.actualRoom.stableId}")
                                appendLine("RIVEN_SEMANTIC_SPRITE=${state.semanticSprite.stableId}")
                                appendLine("The user can see the current viewed room and expects Riven to know which room is on screen.")
                                appendLine("Viewing a room is UI state only: it does not move Riven or imply that Riven is visible there.")
                                if (state.isRivenVisibleInBrowsedRoom) {
                                    appendLine("Riven is physically present in the room the user is viewing.")
                                } else {
                                    appendLine("Riven is not physically present in the room the user is viewing; keep both locations distinct.")
                                }
                                appendLine("Allowed room ids: ${RivenRoom.entries.joinToString { it.stableId }}.")
                                appendLine("Allowed semantic sprite ids: ${RivenSemanticSprite.entries.joinToString { it.stableId }}.")
                                appendLine("To change room or sprite before narrating it as accomplished, begin the reply with exactly one line:")
                                appendLine("$CONTROL_PREFIX{\"room\":\"<allowed room id>\",\"sprite\":\"<allowed semantic sprite id>\"}")
                                append("Do not emit that line unless changing the persisted state. Never imply a move or pose change completed unless the control is accepted.")
                            },
                            revision = state.presenceRevision,
                        ),
                    ),
                    freshnessReceipts = setOf(
                        RivenContextFreshnessReceipt.RivenPresence(
                            actualRoomId = state.actualRoom.stableId,
                            semanticSpriteId = state.semanticSprite.stableId,
                            presenceRevision = state.presenceRevision,
                            browsedRoomId = state.browsedRoom.stableId,
                            browserRevision = state.browserRevision,
                        ),
                    ),
                )
            }
        }

    companion object {
        const val SOURCE_ID = "RIVEN_PRESENCE"
        const val PRIMARY_FRAGMENT_ID = "ACTUAL_ROOM_AND_SPRITE"
        const val CONTROL_PREFIX = "RIVEN_STATE_CONTROL "
        const val MAX_CHARS = 2_048
    }
}
