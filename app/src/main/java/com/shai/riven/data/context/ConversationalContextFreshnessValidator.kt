package com.shai.riven.data.context

import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.recall.ConversationalRecallGeneration
import com.shai.riven.data.recall.ConversationalRecallReceiptValidator

/**
 * Rechecks assembly receipts immediately before provider consumption. Context assembly remains
 * read-only, but a forget/delete or timeline mutation after assembly must make the snapshot stale.
 */
class ConversationalContextFreshnessValidator(
    private val timelineService: ConversationTimelineService,
    private val recallValidator: ConversationalRecallReceiptValidator,
) {
    suspend fun validate(snapshot: RivenContextSnapshot): RivenContextFreshnessValidation {
        val stale = linkedSetOf<RivenContextFreshnessReceipt>()
        snapshot.freshnessReceipts.forEach { receipt ->
            val current = when (receipt) {
                is RivenContextFreshnessReceipt.ActiveConversation -> {
                    val read = timelineService.activeTimelineTail(receipt.conversationId, 1)
                    read is TimelineReadResult.Success && read.timelineRevision == receipt.timelineRevision
                }

                is RivenContextFreshnessReceipt.ConversationalRecall -> recallValidator.isCurrent(
                    ConversationalRecallGeneration(
                        databaseSessionId = receipt.databaseSessionId,
                        corpusGeneration = receipt.corpusGeneration,
                        algorithmVersion = receipt.algorithmVersion,
                    ),
                )
            }
            if (!current) stale += receipt
        }
        return if (stale.isEmpty()) {
            RivenContextFreshnessValidation.Current
        } else {
            RivenContextFreshnessValidation.Stale(stale)
        }
    }
}
