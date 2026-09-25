package com.shai.riven.data.instructions

class ShaiSystemInstructionsContextSource(
    private val service: ShaiSystemInstructionsService,
) {
    suspend fun currentFragment(): ShaiSystemInstructionsContextResult = when (val result = service.snapshot()) {
        is ShaiSystemInstructionsReadResult.Failure ->
            ShaiSystemInstructionsContextResult.Failure(result.error)

        is ShaiSystemInstructionsReadResult.Success -> {
            val snapshot = result.snapshot
            if (!snapshot.isEnabled || snapshot.content.isBlank()) {
                ShaiSystemInstructionsContextResult.NoFragment
            } else {
                ShaiSystemInstructionsContextResult.Fragment(
                    ShaiSystemInstructionsContextFragment(
                        layer = RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS,
                        content = snapshot.content,
                        revision = snapshot.revision,
                    ),
                )
            }
        }
    }
}
