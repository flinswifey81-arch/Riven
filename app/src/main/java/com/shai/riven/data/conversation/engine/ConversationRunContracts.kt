package com.shai.riven.data.conversation.engine

import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.persistence.model.ConversationRunTrigger

data class ConversationEngineLimits(
    val maxOutputChars: Int = 262_144,
    val maxDeltaChars: Int = 16_384,
    val maxStreamEvents: Int = 4_096,
    val maxProviderRequestIdChars: Int = 1_024,
    val maxProviderDurationMillis: Long = 120_000,
    val maxConcurrentRuns: Int = 4,
    val concurrencyWaitMillis: Long = 5_000,
) {
    init {
        require(maxOutputChars > 0)
        require(maxDeltaChars > 0)
        require(maxStreamEvents > 0)
        require(maxProviderRequestIdChars > 0)
        require(maxProviderDurationMillis > 0L)
        require(maxConcurrentRuns > 0)
        require(concurrencyWaitMillis > 0L)
    }
}

data class StartConversationRunInput(
    val runId: String,
    val idempotencyKey: String,
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val profileId: String,
    val trigger: ConversationRunTrigger = ConversationRunTrigger.INITIAL,
    val retryOfRunId: String? = null,
    val regenerateOfMessageId: String? = null,
    val expectedTimelineRevision: Long,
    val occurredAt: Long,
    val imageInputAuthorization: ImageInputAuthorization? = null,
)

enum class ImageInputAuthorization {
    MODEL_DECLARED_SUPPORTED,
    USER_CONFIRMED_UNKNOWN,
}

data class ConversationRunSnapshot(
    val runId: String,
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val trigger: ConversationRunTrigger,
    val retryOfRunId: String?,
    val regenerateOfMessageId: String?,
    val state: ConversationRunState,
    val profileId: String,
    val profileRevision: Long?,
    val adapterId: String?,
    val modelId: String?,
    val selectedHeadMessageId: String?,
    val contextHeadMessageId: String,
    val reservedTimelineRevision: Long,
    val providerRequestId: String?,
    val errorCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
)

enum class ConversationEngineErrorCode {
    INVALID_INPUT,
    MISSING_CONVERSATION,
    MISSING_MESSAGE,
    INVALID_USER_MESSAGE,
    STALE_TIMELINE,
    INVALID_RETRY,
    INVALID_REGENERATION,
    ACTIVE_RUN_EXISTS,
    IDEMPOTENCY_MISMATCH,
    STORAGE_FAILURE,
    PROFILE_MISSING,
    PROFILE_DISABLED,
    PROFILE_CAPABILITY_MISSING,
    CREDENTIAL_MISSING,
    CREDENTIAL_UNREADABLE,
    ADAPTER_MISSING,
    ADAPTER_CAPABILITY_MISSING,
    SYSTEM_CONTEXT_UNSUPPORTED,
    ATTACHMENTS_UNSUPPORTED,
    IMAGE_ATTACHMENT_INVALID,
    CONTEXT_ASSEMBLY_FAILED,
    CONTEXT_LIMIT_EXCEEDED,
    CONTEXT_STALE,
    CONCURRENCY_LIMIT,
    PROVIDER_TIMEOUT,
    PROVIDER_FAILURE,
    PROVIDER_PROTOCOL,
    STATE_CONTROL_INVALID,
    STATE_CONTROL_REJECTED,
    OUTPUT_LIMIT,
    CANCELLED,
    INTERRUPTED,
}

sealed interface ConversationEngineResult {
    data class Succeeded(
        val run: ConversationRunSnapshot,
        val timelineRevision: Long,
    ) : ConversationEngineResult

    data class Existing(
        val run: ConversationRunSnapshot,
    ) : ConversationEngineResult

    data class Failed(
        val run: ConversationRunSnapshot?,
        val code: ConversationEngineErrorCode,
    ) : ConversationEngineResult
}

sealed interface ConversationRunControlResult {
    data class Updated(val run: ConversationRunSnapshot) : ConversationRunControlResult
    data class Unchanged(val run: ConversationRunSnapshot) : ConversationRunControlResult
    data object Missing : ConversationRunControlResult
    data class Failure(val code: ConversationEngineErrorCode) : ConversationRunControlResult
}

data class ConversationRunRecoveryResult(
    val interruptedRunIds: List<String>,
)
