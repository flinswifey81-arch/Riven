package com.shai.riven.data.conversation.engine

import com.shai.riven.data.context.RivenContextBudgetBehavior
import com.shai.riven.data.context.RivenContextContentAuthority
import com.shai.riven.data.context.RivenContextLayer
import com.shai.riven.data.context.RivenContextProvenanceClass
import com.shai.riven.data.context.RivenContextSourceCriticality
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.provider.ProviderCapability

enum class ProviderSystemContextMode {
    NATIVE_INSTRUCTIONS,
    STRUCTURED_CONTEXT,
    UNSUPPORTED,
}

data class ProviderAdapterDescriptor(
    val adapterId: String,
    val capabilities: Set<ProviderCapability>,
    val systemContextMode: ProviderSystemContextMode,
)

data class ProviderContextFragment(
    val sourceId: String,
    val fragmentId: String,
    val content: String,
    val layer: RivenContextLayer,
    val provenanceClass: RivenContextProvenanceClass,
    val criticality: RivenContextSourceCriticality,
    val orderWithinLayer: Int,
    val orderWithinSource: Int,
    val budgetBehavior: RivenContextBudgetBehavior,
    val revision: Long?,
    val observedAt: Long?,
    val validUntil: Long?,
    val contentAuthority: RivenContextContentAuthority,
    val conversationRole: MessageRole?,
)

data class ProviderConversationRequest(
    val runId: String,
    val idempotencyKey: String,
    val endpointBaseUrl: String,
    val modelId: String,
    val credential: ProviderSecret?,
    val context: List<ProviderContextFragment>,
    /** Image bytes are keyed by their canonical conversation fragment/message id. */
    val imagesByFragmentId: Map<String, List<ProviderImageContent>> = emptyMap(),
)

data class ProviderImageContent(
    val attachmentId: String,
    val mimeType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val contentSha256: String,
)

fun interface ProviderImageContentResolver {
    fun resolve(attachmentId: String): ProviderImageContent?
}

enum class ProviderFailureCode {
    AUTHENTICATION,
    RATE_LIMITED,
    UNAVAILABLE,
    INVALID_REQUEST,
    CONTENT_REJECTED,
    TIMEOUT,
    STATE_CONTROL_INVALID,
    OTHER,
}

data class ProviderStateControlRequest(
    val roomId: String,
    val spriteId: String,
)

sealed interface ProviderStateControlResult {
    data class Applied(
        val actualRoomId: String,
        val semanticSpriteId: String,
        val presenceRevision: Long,
    ) : ProviderStateControlResult

    data class Rejected(val reason: String) : ProviderStateControlResult
}

fun interface ProviderStateControlHandler {
    suspend fun apply(
        request: ProviderStateControlRequest,
        expectedPresenceRevision: Long,
        occurredAt: Long,
    ): ProviderStateControlResult
}

sealed interface ProviderStreamEvent {
    data class Delta(val content: String) : ProviderStreamEvent

    data class StateControlRequested(
        val request: ProviderStateControlRequest,
    ) : ProviderStreamEvent

    data class Completed(
        val providerRequestId: String? = null,
    ) : ProviderStreamEvent

    data class Failure(
        val code: ProviderFailureCode,
        val providerRequestId: String? = null,
    ) : ProviderStreamEvent
}

interface ConversationProviderAdapter {
    val descriptor: ProviderAdapterDescriptor

    /**
     * Events are delivered synchronously through [emit], so the engine has no unbounded event
     * queue. Implementations must return after exactly one terminal event and cooperate with
     * coroutine cancellation.
     */
    suspend fun stream(
        request: ProviderConversationRequest,
        emit: suspend (ProviderStreamEvent) -> Unit,
    )
}

sealed interface ProviderAdapterRegistryError {
    data object BlankAdapterId : ProviderAdapterRegistryError
    data class DuplicateAdapterId(val adapterId: String) : ProviderAdapterRegistryError
    data class MissingAdapter(val adapterId: String) : ProviderAdapterRegistryError
}

class ProviderAdapterRegistryConfigurationException(
    val error: ProviderAdapterRegistryError,
) : IllegalArgumentException(error.toString())

sealed interface ProviderAdapterLookupResult {
    data class Found(val adapter: ConversationProviderAdapter) : ProviderAdapterLookupResult
    data class Failure(val error: ProviderAdapterRegistryError) : ProviderAdapterLookupResult
}
