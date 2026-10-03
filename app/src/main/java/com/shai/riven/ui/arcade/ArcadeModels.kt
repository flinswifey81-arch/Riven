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
        title = "Celestial Spire",
        eyebrow = "FALLING BLOCKS • ENDLESS",
        description = "Clear lines at your chosen steady pace; reaching the top begins a fresh board.",
        mode = ArcadeGameMode.SOLO,
        commentary = "Let's read the board before choosing a move.",
    ),
    KLONDIKE(
        gameId = "klondike",
        title = "Midnight Solitaire",
        eyebrow = "SOLITAIRE • DRAW ONE",
        description = "A familiar draw-one table with room to think ahead.",
        mode = ArcadeGameMode.SOLO,
        commentary = "Take your time. Tap me whenever you want company and the table will pause.",
    ),
    HEART_MATCH(
        gameId = "heart-match",
        title = "Starstruck",
        eyebrow = "MATCH THREE",
        description = "Match distinct moons, hearts, and stars into bright cascades.",
        mode = ArcadeGameMode.SOLO,
        commentary = "There is a little cascade waiting in the lower corner.",
    ),
    WRAPPING_SNAKE(
        gameId = "wrapping-snake",
        title = "Comet Trail",
        eyebrow = "SNAKE • WRAPPING",
        description = "Guide a comet-like trail through wrapping edges at a comfortable rhythm.",
        mode = ArcadeGameMode.SOLO,
        commentary = "Easy pace. The far edge is a doorway, not a wall.",
    ),
    RIVEN_CARD_TABLE(
        gameId = "riven-card-table",
        title = "Cosmic Mischief",
        eyebrow = "SHARED CARD GAME",
        description = "Match color or number, bargain, bluff, and empty your hand first.",
        mode = ArcadeGameMode.SHARED_WITH_RIVEN,
        commentary = "Offline fallback ready. Live Riven is not connected to this table yet.",
    ),
    ;

    val hasPlayableEngine: Boolean
        get() = true

    /** Solo tables refresh or continue; only Cosmic Mischief may eventually end in a loss. */
    val allowsLoss: Boolean
        get() = this == RIVEN_CARD_TABLE

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
    val commentaryCuesEnabled: Boolean = false,
    val commentaryCue: String? = null,
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
    data object ToggleCommentaryCues : ArcadeAction
    data class ShowCommentaryCue(val message: String) : ArcadeAction
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
            commentaryCue = null,
            demoNotice = null,
        )
    } ?: state

    ArcadeAction.ExitGame -> state.copy(
        selectedGameId = null,
        conversationOpen = false,
        commentaryCue = null,
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
    ArcadeAction.ToggleCommentaryCues -> state.copy(
        commentaryCuesEnabled = !state.commentaryCuesEnabled,
        commentaryCue = null,
    )
    is ArcadeAction.ShowCommentaryCue -> state.copy(
        commentaryCue = action.message.take(MAX_ARCADE_COMMENTARY_CUE_CHARS),
    )
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

private const val MAX_ARCADE_COMMENTARY_CUE_CHARS = 320
