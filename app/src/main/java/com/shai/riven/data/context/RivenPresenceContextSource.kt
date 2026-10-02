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
                                appendLine("Riven's persisted actual_room=${state.actualRoom.stableId}.")
                                appendLine("Riven's persisted semantic_sprite=${state.semanticSprite.stableId}.")
                                appendLine("The room the user is browsing is UI state and does not move Riven.")
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
