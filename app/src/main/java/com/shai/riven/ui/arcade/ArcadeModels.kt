package com.shai.riven.ui.arcade

const val MAX_ARCADE_CONVERSATION_DRAFT_CHARS = 4_000

enum class ArcadeGameMode {
    SOLO,
    SHARED_WITH_RIVEN,
}

enum class ArcadeGame(
    val gameId: String,
    val title: String,
    val eyebrow: String,
    val description: String,
    val mode: ArcadeGameMode,
    val commentary: String,
) {
    STACKER(
        gameId = "stacker",
        title = "Stacker",
        eyebrow = "STEADY HANDS",
        description = "Place bright moving blocks and keep the tower balanced.",
        mode = ArcadeGameMode.SOLO,
        commentary = "No rush. A clean landing matters more than a tall tower.",
    ),
    KLONDIKE(
        gameId = "klondike",
        title = "Klondike Solitaire",
        eyebrow = "DRAW ONE",
        description = "A familiar draw-one table with room to think ahead.",
        mode = ArcadeGameMode.SOLO,
        commentary = "The open red seven gives you options. We can take this slowly.",
    ),
    HEART_MATCH(
        gameId = "heart-match",
        title = "Heart Match",
        eyebrow = "MATCH THREE",
        description = "Swap adjacent colored hearts and set off cozy cascades.",
        mode = ArcadeGameMode.SOLO,
        commentary = "There is a little cascade waiting in the lower corner.",
    ),
    WRAPPING_SNAKE(
        gameId = "wrapping-snake",
        title = "Wrapping Snake",
        eyebrow = "MINDFUL ENDLESS",
        description = "Glide through the edges, grow, and find a comfortable rhythm.",
        mode = ArcadeGameMode.SOLO,
        commentary = "Easy pace. The far edge is a doorway, not a wall.",
    ),
    RIVEN_CARD_TABLE(
        gameId = "riven-card-table",
        title = "Riven's Card Table",
        eyebrow = "PLAY TOGETHER",
        description = "Match color or number, bargain, bluff, and empty your hand first.",
        mode = ArcadeGameMode.SHARED_WITH_RIVEN,
        commentary = "I might accept a trade. Whether I honor it is another question.",
    ),
    ;

    /** Game engines are intentionally outside this foundation/layout slice. */
    val hasPlayableEngine: Boolean = false

    companion object {
        fun fromId(gameId: String?): ArcadeGame? = entries.firstOrNull { it.gameId == gameId }
    }
}

enum class ArcadeInteractionHold {
    NONE,
    SOLO_PAUSED_FOR_CHAT,
    SHARED_GAME_WAITING_FOR_CHAT,
}

data class ArcadeUiState(
    val selectedGameId: String? = null,
    val quietMode: Boolean = false,
    val conversationOpen: Boolean = false,
    val conversationDraft: String = "",
    val demoNotice: String? = null,
) {
    val selectedGame: ArcadeGame?
        get() = ArcadeGame.fromId(selectedGameId)

    val interactionHold: ArcadeInteractionHold
        get() = when {
            !conversationOpen -> ArcadeInteractionHold.NONE
            selectedGame?.mode == ArcadeGameMode.SOLO -> ArcadeInteractionHold.SOLO_PAUSED_FOR_CHAT
            selectedGame?.mode == ArcadeGameMode.SHARED_WITH_RIVEN ->
                ArcadeInteractionHold.SHARED_GAME_WAITING_FOR_CHAT

            else -> ArcadeInteractionHold.NONE
        }
}

sealed interface ArcadeAction {
    data class OpenGame(val gameId: String) : ArcadeAction
    data object ExitGame : ArcadeAction
    data object OpenConversation : ArcadeAction
    data object DismissConversation : ArcadeAction
    data class UpdateConversationDraft(val value: String) : ArcadeAction
    data object ToggleQuietMode : ArcadeAction
    data class PreviewControl(val label: String) : ArcadeAction
    data object DismissDemoNotice : ArcadeAction
    data object Back : ArcadeAction
}

fun reduceArcadeState(
    state: ArcadeUiState,
    action: ArcadeAction,
): ArcadeUiState = when (action) {
    is ArcadeAction.OpenGame -> ArcadeGame.fromId(action.gameId)?.let { game ->
        state.copy(
            selectedGameId = game.gameId,
            conversationOpen = false,
            demoNotice = null,
        )
    } ?: state

    ArcadeAction.ExitGame -> state.copy(
        selectedGameId = null,
        conversationOpen = false,
        demoNotice = null,
    )

    ArcadeAction.OpenConversation -> state.copy(
        conversationOpen = true,
        demoNotice = null,
    )

    ArcadeAction.DismissConversation -> state.copy(conversationOpen = false)
    is ArcadeAction.UpdateConversationDraft -> state.copy(
        conversationDraft = action.value.take(MAX_ARCADE_CONVERSATION_DRAFT_CHARS),
    )

    ArcadeAction.ToggleQuietMode -> state.copy(quietMode = !state.quietMode)
    is ArcadeAction.PreviewControl -> state.copy(
        demoNotice = "${action.label} is shown for layout review; its game rule is not implemented yet.",
    )

    ArcadeAction.DismissDemoNotice -> state.copy(demoNotice = null)
    ArcadeAction.Back -> when {
        state.conversationOpen -> state.copy(conversationOpen = false)
        state.selectedGame != null -> state.copy(selectedGameId = null, demoNotice = null)
        else -> state
    }
}
