package com.shai.riven.ui.arcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeModelsTest {
    @Test
    fun catalogContainsExactlyTheFiveApprovedHonestPreviews() {
        assertEquals(
            listOf(
                "stacker",
                "klondike",
                "heart-match",
                "wrapping-snake",
                "riven-card-table",
            ),
            ArcadeGame.entries.map(ArcadeGame::gameId),
        )
        assertEquals(4, ArcadeGame.entries.count { it.mode == ArcadeGameMode.SOLO })
        assertEquals(1, ArcadeGame.entries.count { it.mode == ArcadeGameMode.SHARED_WITH_RIVEN })
        assertTrue(ArcadeGame.entries.none(ArcadeGame::hasPlayableEngine))
    }

    @Test
    fun unknownGameCannotCreateAFalseNavigationState() {
        val state = ArcadeUiState(conversationDraft = "kept")

        val result = reduceArcadeState(state, ArcadeAction.OpenGame("not-a-game"))

        assertSame(state, result)
    }

    @Test
    fun stackerCatalogCopyDoesNotSelectAnUnconfirmedRuleset() {
        val stackerCopy = listOf(
            ArcadeGame.STACKER.eyebrow,
            ArcadeGame.STACKER.description,
            ArcadeGame.STACKER.commentary,
        ).joinToString(" ").lowercase()

        assertTrue(stackerCopy.contains("confirmed stacker ruleset"))
        listOf("tower", "line clear", "falling", "drop block", "timing", "physics").forEach { claim ->
            assertFalse(stackerCopy.contains(claim))
        }
    }

    @Test
    fun openingConversationPausesSoloAndWaitsSharedTable() {
        val solo = reduceArcadeState(
            ArcadeUiState(selectedGameId = ArcadeGame.HEART_MATCH.gameId),
            ArcadeAction.OpenConversation,
        )
        val shared = reduceArcadeState(
            ArcadeUiState(selectedGameId = ArcadeGame.RIVEN_CARD_TABLE.gameId),
            ArcadeAction.OpenConversation,
        )

        assertEquals(ArcadeInteractionHold.SOLO_PAUSED_FOR_CHAT, solo.interactionHold)
        assertEquals(ArcadeInteractionHold.SHARED_GAME_WAITING_FOR_CHAT, shared.interactionHold)
    }

    @Test
    fun quietModeDoesNotBlockUserInitiatedConversationOrLoseDraft() {
        val withDraft = reduceArcadeState(
            ArcadeUiState(conversationDraft = "Want to talk strategy?"),
            ArcadeAction.ToggleQuietMode,
        )
        val conversation = reduceArcadeState(withDraft, ArcadeAction.OpenConversation)

        assertTrue(conversation.quietMode)
        assertTrue(conversation.conversationOpen)
        assertEquals("Want to talk strategy?", conversation.conversationDraft)
    }

    @Test
    fun backDismissesConversationBeforeLeavingGameAndPreservesDraft() {
        val initial = ArcadeUiState(
            selectedGameId = ArcadeGame.STACKER.gameId,
            conversationOpen = true,
            conversationDraft = "Hold that thought",
        )

        val dismissed = reduceArcadeState(initial, ArcadeAction.Back)
        val lobby = reduceArcadeState(dismissed, ArcadeAction.Back)

        assertFalse(dismissed.conversationOpen)
        assertEquals(ArcadeGame.STACKER, dismissed.selectedGame)
        assertEquals("Hold that thought", dismissed.conversationDraft)
        assertNull(lobby.selectedGame)
        assertEquals("Hold that thought", lobby.conversationDraft)
    }

    @Test
    fun conversationDraftIsBoundedBeforeItEntersSaveableUiState() {
        val oversized = "x".repeat(MAX_ARCADE_CONVERSATION_DRAFT_CHARS + 500)

        val result = reduceArcadeState(
            ArcadeUiState(),
            ArcadeAction.UpdateConversationDraft(oversized),
        )

        assertEquals(MAX_ARCADE_CONVERSATION_DRAFT_CHARS, result.conversationDraft.length)
    }
}
