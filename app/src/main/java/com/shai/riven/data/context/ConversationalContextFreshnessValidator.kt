package com.shai.riven.data.context

import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.recall.ConversationalRecallGeneration
import com.shai.riven.data.recall.ConversationalRecallReceiptValidator

fun interface ActiveConversationReceiptValidator {
    suspend fun isCurrent(receipt: RivenContextFreshnessReceipt.ActiveConversation): Boolean
}

/**
 * Rechecks assembly receipts immediately before provider consumption. Context assembly remains
 * read-only, but a forget/delete or timeline mutation after assembly must make the snapshot stale.
 */
class ConversationalContextFreshnessValidator(
    private val activeConversationValidator: ActiveConversationReceiptValidator,
    private val recallValidator: ConversationalRecallReceiptValidator,
) {
    constructor(
        timelineService: ConversationTimelineService,
        recallValidator: ConversationalRecallReceiptValidator,
    ) : this(
        activeConversationValidator = ActiveConversationReceiptValidator { receipt ->
            val read = timelineService.activeTimelineTail(receipt.conversationId, 1)
            read is TimelineReadResult.Success && read.timelineRevision == receipt.timelineRevision
        },
        recallValidator = recallValidator,
    )

    suspend fun validate(snapshot: RivenContextSnapshot): RivenContextFreshnessValidation {
        val stale = linkedSetOf<RivenContextFreshnessReceipt>()
        val activeConversationReceipts = snapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.ActiveConversation>()
        val recallReceipts = snapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.ConversationalRecall>()

        // Complete every suspending read first. Recall receipts are deliberately rechecked only
        // after the final suspension so a concurrent forget/delete cannot become stale while a
        // later timeline read is still queued.
        activeConversationReceipts.forEach { receipt ->
            if (!activeConversationValidator.isCurrent(receipt)) stale += receipt
        }
        recallReceipts.forEach { receipt ->
            if (
                !recallValidator.isCurrent(
                    ConversationalRecallGeneration(
                        databaseSessionId = receipt.databaseSessionId,
                        corpusGeneration = receipt.corpusGeneration,
                        algorithmVersion = receipt.algorithmVersion,
                    ),
                )
            ) {
                stale += receipt
            }
        }
        return if (stale.isEmpty()) {
            RivenContextFreshnessValidation.Current
        } else {
            RivenContextFreshnessValidation.Stale(stale)
        }
    }
}
