package com.shai.riven.data.instructions

enum class ShaiSystemInstructionsOperation {
    READ,
    SAVE,
    CLEAR,
}

data class ShaiSystemInstructionsSnapshot(
    val content: String,
    val isEnabled: Boolean,
    val revision: Long,
    val updatedAt: Long?,
)

data class SaveShaiSystemInstructionsInput(
    val content: String,
    val isEnabled: Boolean,
    val expectedRevision: Long,
    val occurredAt: Long,
)

data class ClearShaiSystemInstructionsInput(
    val expectedRevision: Long,
    val occurredAt: Long,
)

sealed interface ShaiSystemInstructionsError {
    data class StaleRevision(
        val expected: Long,
        val actual: Long,
    ) : ShaiSystemInstructionsError

    data object RevisionOverflow : ShaiSystemInstructionsError

    data class StorageFailure(
        val operation: ShaiSystemInstructionsOperation,
        val causeType: String,
    ) : ShaiSystemInstructionsError
}

sealed interface ShaiSystemInstructionsReadResult {
    data class Success(val snapshot: ShaiSystemInstructionsSnapshot) : ShaiSystemInstructionsReadResult
    data class Failure(val error: ShaiSystemInstructionsError) : ShaiSystemInstructionsReadResult
}

sealed interface ShaiSystemInstructionsWriteResult {
    data class Saved(val snapshot: ShaiSystemInstructionsSnapshot) : ShaiSystemInstructionsWriteResult

    data class Cleared(
        val snapshot: ShaiSystemInstructionsSnapshot,
        val changed: Boolean,
    ) : ShaiSystemInstructionsWriteResult

    data class Failure(val error: ShaiSystemInstructionsError) : ShaiSystemInstructionsWriteResult
}

/** Future provider-neutral context assembly order, from strongest to most situational. */
enum class RivenContextLayer {
    APP_INVARIANTS_SAFETY_AND_TOOL_TRUTH,
    LOCKED_RIVEN_PERSONALITY_AND_IDENTITY_CANON,
    SHAI_SYSTEM_INSTRUCTIONS,
    RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
    ACTIVE_CANONICAL_CONVERSATION_AND_CURRENT_INTERACTION,
}

data class ShaiSystemInstructionsContextFragment(
    val layer: RivenContextLayer,
    val content: String,
    val revision: Long,
)

sealed interface ShaiSystemInstructionsContextResult {
    data object NoFragment : ShaiSystemInstructionsContextResult
    data class Fragment(val value: ShaiSystemInstructionsContextFragment) : ShaiSystemInstructionsContextResult
    data class Failure(val error: ShaiSystemInstructionsError) : ShaiSystemInstructionsContextResult
}
