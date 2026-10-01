package com.shai.riven.data.context

import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.recall.ConversationalRecallGeneration
import com.shai.riven.data.recall.ConversationalRecallReceiptValidator

fun interface ActiveConversationReceiptValidator {
    suspend fun isCurrent(receipt: RivenContextFreshnessReceipt.ActiveConversation): Boolean
}

fun interface ShaiSystemInstructionsReceiptValidator {
    suspend fun isCurrent(receipt: RivenContextFreshnessReceipt.ShaiSystemInstructions): Boolean
}

fun interface ProviderProfileReceiptValidator {
    suspend fun isCurrent(receipt: RivenContextFreshnessReceipt.ProviderProfile): Boolean
}

fun interface EphemeralAppStateReceiptValidator {
    fun isCurrent(receipt: RivenContextFreshnessReceipt.EphemeralAppState, now: Long): Boolean
}

/**
 * Rechecks assembly receipts immediately before provider consumption. Context assembly remains
 * read-only, but a forget/delete or timeline mutation after assembly must make the snapshot stale.
 */
class ConversationalContextFreshnessValidator(
    private val activeConversationValidator: ActiveConversationReceiptValidator,
    private val recallValidator: ConversationalRecallReceiptValidator,
    private val instructionsValidator: ShaiSystemInstructionsReceiptValidator? = null,
    private val profileValidator: ProviderProfileReceiptValidator? = null,
    private val ephemeralValidator: EphemeralAppStateReceiptValidator? = null,
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

    suspend fun validate(
        snapshot: RivenContextSnapshot,
        now: Long = Long.MIN_VALUE,
    ): RivenContextFreshnessValidation {
        val stale = linkedSetOf<RivenContextFreshnessReceipt>()
        val activeConversationReceipts = snapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.ActiveConversation>()
        val instructionReceipts = snapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.ShaiSystemInstructions>()
        val profileReceipts = snapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.ProviderProfile>()

        // Complete every suspending read first. Recall receipts are deliberately rechecked only
        // after the final suspension so a concurrent forget/delete cannot become stale while a
        // later Room-backed receipt read is still queued.
        activeConversationReceipts.forEach { receipt ->
            if (!activeConversationValidator.isCurrent(receipt)) stale += receipt
        }
        instructionReceipts.forEach { receipt ->
            if (instructionsValidator?.isCurrent(receipt) != true) stale += receipt
        }
        profileReceipts.forEach { receipt ->
            if (profileValidator?.isCurrent(receipt) != true) stale += receipt
        }
        stale += staleSynchronousReceipts(snapshot, now)
        return stale.toValidation()
    }

    /**
     * Final non-suspending boundary for receipts backed by process-local fences or clocks. Callers
     * can run this after every suspending Room read and immediately before dispatch.
     */
    fun validateSynchronousReceipts(
        snapshot: RivenContextSnapshot,
        now: Long,
    ): RivenContextFreshnessValidation = staleSynchronousReceipts(snapshot, now).toValidation()

    private fun staleSynchronousReceipts(
        snapshot: RivenContextSnapshot,
        now: Long,
    ): Set<RivenContextFreshnessReceipt> {
        val stale = linkedSetOf<RivenContextFreshnessReceipt>()
        val recallReceipts = snapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.ConversationalRecall>()
        val ephemeralReceipts = snapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.EphemeralAppState>()
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
        ephemeralReceipts.forEach { receipt ->
            if (ephemeralValidator?.isCurrent(receipt, now) != true) stale += receipt
        }
        return stale
    }

    private fun Set<RivenContextFreshnessReceipt>.toValidation(): RivenContextFreshnessValidation =
        if (isEmpty()) {
            RivenContextFreshnessValidation.Current
        } else {
            RivenContextFreshnessValidation.Stale(this)
        }
}
