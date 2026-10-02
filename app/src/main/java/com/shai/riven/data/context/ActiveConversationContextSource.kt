package com.shai.riven.data.context

import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole

/** Required, bounded transcript source. Only the canonical active parent-walk is observable. */
class ActiveConversationContextSource(
    private val timelineService: ConversationTimelineService,
) : RivenContextSource {
    override val descriptor = RivenContextSourceDescriptor(
        sourceId = SOURCE_ID,
        layer = RivenContextLayer.ACTIVE_CANONICAL_CONVERSATION_AND_CURRENT_INTERACTION,
        provenanceClass = RivenContextProvenanceClass.ACTIVE_CONVERSATION,
        criticality = RivenContextSourceCriticality.REQUIRED,
        orderWithinLayer = 0,
        maxFragments = MAX_FRAGMENTS,
        maxCharsPerFragment = MAX_CHARS_PER_FRAGMENT,
        maxAggregateChars = MAX_AGGREGATE_CHARS,
        budgetBehavior = RivenContextBudgetBehavior.DROP_IF_NEEDED,
        contentAuthority = RivenContextContentAuthority.UNTRUSTED_DATA,
    )

    override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult {
        val conversation = request.conversation ?: return failure("MissingConversationRequest")
        if (conversation.conversationId.isBlank() || conversation.expectedTimelineRevision < 0L) {
            return failure("InvalidConversationRequest")
        }
        val read = conversation.contextHeadMessageId?.let { contextHeadMessageId ->
            timelineService.activeTimelineTailEndingAt(
                conversationId = conversation.conversationId,
                contextHeadMessageId = contextHeadMessageId,
                maximumMessages = MAX_TRANSCRIPT_MESSAGES,
            )
        } ?: timelineService.activeTimelineTail(
            conversation.conversationId,
            MAX_TRANSCRIPT_MESSAGES,
        )
        if (read !is TimelineReadResult.Success) {
            val error = (read as TimelineReadResult.Failure).error
            return failure(error::class.java.simpleName)
        }
        if (read.timelineRevision != conversation.expectedTimelineRevision) {
            return failure("StaleTimelineRevision")
        }

        val eligibleMessages = read.messages.filter { message -> message.isContextEligible() }
        val interaction = conversation.currentInteraction
        if (interaction.content.isBlank()) return failure("BlankCurrentInteraction")
        val matching = interaction.messageId?.let { id -> eligibleMessages.singleOrNull { it.id == id } }
        if (matching != null && matching.content != interaction.content) {
            return failure("CurrentInteractionMismatch")
        }
        val payloads = eligibleMessages.map { message ->
            message.toContextPayload(
                timelineRevision = read.timelineRevision,
                required = message.id == matching?.id,
            )
        }.toMutableList()
        if (matching == null) {
            payloads += RivenContextPayload(
                fragmentId = interaction.messageId ?: CURRENT_INTERACTION_FRAGMENT_ID,
                content = "role=CURRENT_INTERACTION\n${interaction.content}",
                revision = read.timelineRevision,
                observedAt = request.now,
                conversationRole = MessageRole.USER,
                budgetBehavior = RivenContextBudgetBehavior.REQUIRED,
            )
        }
        return RivenContextSourceResult.Success(
            payloads = payloads,
            freshnessReceipts = setOf(
                RivenContextFreshnessReceipt.ActiveConversation(
                    conversationId = conversation.conversationId,
                    timelineRevision = read.timelineRevision,
                ),
            ),
        )
    }

    private fun MessageEntity.isContextEligible(): Boolean = when (deliveryState) {
        MessageDeliveryState.PERSISTED,
        MessageDeliveryState.SUCCEEDED,
        -> true
        MessageDeliveryState.PENDING,
        MessageDeliveryState.FAILED,
        MessageDeliveryState.CANCELLED,
        -> false
    }

    private fun MessageEntity.toContextPayload(
        timelineRevision: Long,
        required: Boolean,
    ) = RivenContextPayload(
        fragmentId = id,
        content = "role=${role.name}\n$content",
        revision = timelineRevision,
        observedAt = updatedAt,
        conversationRole = role,
        budgetBehavior = if (required) {
            RivenContextBudgetBehavior.REQUIRED
        } else {
            RivenContextBudgetBehavior.DROP_IF_NEEDED
        },
    )

    private fun failure(errorType: String) = RivenContextSourceResult.Failure(
        RivenContextSourceError.ReadFailure(errorType),
    )

    companion object {
        const val SOURCE_ID = "ACTIVE_CONVERSATION"
        const val CURRENT_INTERACTION_FRAGMENT_ID = "CURRENT_INTERACTION"
        const val MAX_TRANSCRIPT_MESSAGES = 32
        const val MAX_FRAGMENTS = MAX_TRANSCRIPT_MESSAGES + 1
        const val MAX_CHARS_PER_FRAGMENT = 32_768
        const val MAX_AGGREGATE_CHARS = 131_072
    }
}
