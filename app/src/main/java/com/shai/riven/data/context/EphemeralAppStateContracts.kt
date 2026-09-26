package com.shai.riven.data.context

enum class EphemeralAppStateExposure {
    LOCAL_ONLY,
    RIVEN_CONTEXT,
}

data class EphemeralAppStateEntry(
    val stateId: String,
    val content: String,
    val exposure: EphemeralAppStateExposure,
    val priority: Int,
    val revision: Long,
    val observedAt: Long,
    val validUntil: Long? = null,
)

data class PublishEphemeralAppStateInput(
    val stateId: String,
    val content: String,
    val exposure: EphemeralAppStateExposure,
    val priority: Int,
    val expectedRevision: Long,
    val observedAt: Long,
    val validUntil: Long? = null,
)

data class ClearEphemeralAppStateInput(
    val stateId: String,
    val expectedRevision: Long,
)

sealed interface EphemeralAppStateError {
    data object BlankStateId : EphemeralAppStateError
    data object BlankContent : EphemeralAppStateError

    data class InvalidLifetime(
        val observedAt: Long,
        val validUntil: Long,
    ) : EphemeralAppStateError

    data class StaleRevision(
        val stateId: String,
        val expected: Long,
        val actual: Long,
    ) : EphemeralAppStateError

    data class RevisionOverflow(
        val stateId: String,
    ) : EphemeralAppStateError
}

sealed interface EphemeralAppStateWriteResult {
    data class Published(
        val entry: EphemeralAppStateEntry,
    ) : EphemeralAppStateWriteResult

    data class Cleared(
        val stateId: String,
        val changed: Boolean,
        val previousRevision: Long?,
    ) : EphemeralAppStateWriteResult

    data class Failure(
        val error: EphemeralAppStateError,
    ) : EphemeralAppStateWriteResult
}

data class EphemeralAppStateSnapshot(
    val entries: List<EphemeralAppStateEntry>,
)

data class PurgeExpiredEphemeralAppStateResult(
    val removedStateIds: List<String>,
)
