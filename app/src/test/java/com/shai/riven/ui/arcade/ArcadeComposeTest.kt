package com.shai.riven.ui.arcade

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.MainActivity
import com.shai.riven.ui.arcade.solitaire.MidnightSolitaireEngine
import com.shai.riven.ui.arcade.solitaire.MidnightSolitaireSession
import com.shai.riven.ui.arcade.solitaire.MidnightSolitaireSettings
import com.shai.riven.ui.arcade.solitaire.MidnightSolitaireState
import com.shai.riven.ui.arcade.solitaire.SharedPreferencesMidnightSolitaireStore
import com.shai.riven.ui.arcade.solitaire.SolitaireCard
import com.shai.riven.ui.arcade.solitaire.SolitaireSuit
import com.shai.riven.ui.arcade.solitaire.SolitaireTableauCard
import com.shai.riven.ui.theme.RivenTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ArcadeComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lobbyShowsAllFiveApprovedEntriesAndHonestScopeLabel() {
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(state = ArcadeUiState(), onAction = {})
            }
        }

        ArcadeGame.entries.forEachIndexed { index, game ->
            composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(index + 2)
            composeRule.onNodeWithText(game.title).assertIsDisplayed()
        }
        composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(ArcadeGame.entries.size + 2)
        composeRule.onNodeWithText(
            "Four solo tables are playable • Cosmic Mischief and live Riven replies remain unconnected.",
        ).assertIsDisplayed()
    }

    @Test
    fun lobbyWithoutMaterializedPortraitUsesNoVisualSubstitute() {
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(state = ArcadeUiState(), onAction = {})
            }
        }

        composeRule.onAllNodesWithTag("approved_arcade_portrait").assertCountEquals(0)
        composeRule.onNodeWithText(
            "Four solo tables are ready. Tap this card to open conversation.",
        ).assertIsDisplayed()
    }

    @Test
    fun sharedTableShowsHiddenOpponentHandUserHandAndApprovedControls() {
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(
                    state = ArcadeUiState(selectedGameId = ArcadeGame.RIVEN_CARD_TABLE.gameId),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("RIVEN • 7 CARDS").assertIsDisplayed()
        composeRule.onNodeWithText("YOUR HAND").assertIsDisplayed()
        composeRule.onNodeWithText("TRADE").assertIsDisplayed()
        composeRule.onNodeWithText("CHEAT").assertIsDisplayed()
        composeRule.onNodeWithText("CALL OUT").assertIsDisplayed()
        composeRule.onNodeWithText("Demo hand • no engine").assertIsDisplayed()
        composeRule.onNodeWithText("DEMO COMMENTARY").assertIsDisplayed()
        composeRule.onAllNodesWithTag("riven_card_back_art").assertCountEquals(8)
        composeRule.onAllNodesWithContentDescription("Face-down card").assertCountEquals(8)
        composeRule.onNodeWithContentDescription("Eclipse. Skip the next turn.").assertIsDisplayed()
        composeRule.onNodeWithTag("celestial_hand").performScrollToIndex(4)
        composeRule.onNodeWithContentDescription(
            "Double Trouble. The next player draws two cards.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(
            "celestial_hand_double-trouble_primary",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(
            "celestial_hand_double-trouble_effect",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("celestial_hand").performScrollToIndex(6)
        composeRule.onNodeWithContentDescription(
            "Rewrite the Stars. Choose the next color.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(
            "celestial_hand_rewrite-the-stars_effect",
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun conversationOverlayStatesThatLiveRepliesAreNotConnected() {
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(
                    state = ArcadeUiState(
                        selectedGameId = ArcadeGame.STACKER.gameId,
                        conversationOpen = true,
                        conversationDraft = "Draft survives",
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Solo table paused").assertIsDisplayed()
        composeRule.onNodeWithText("Draft survives").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Layout only: sending and live provider replies are not connected yet.",
        ).assertIsDisplayed()
    }

    @Test
    fun celestialSpireShowsPlayableEndlessControlsWithoutALossState() {
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(
                    state = ArcadeUiState(selectedGameId = ArcadeGame.STACKER.gameId),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("FALLING BLOCKS").assertIsDisplayed()
        composeRule.onNodeWithTag("celestial_spire_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Move piece left").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hard drop piece").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Endless play • Reaching the top refreshes the board at your selected speed.",
        ).assertIsDisplayed()
    }

    @Test
    fun midnightSolitaireShowsThePlayableNoLossTable() {
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(
                    state = ArcadeUiState(selectedGameId = ArcadeGame.KLONDIKE.gameId),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("DRAW-ONE KLONDIKE").assertIsDisplayed()
        composeRule.onNodeWithTag("midnight_solitaire_board").assertIsDisplayed()
        composeRule.onNodeWithTag("solitaire_stock").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Pause Midnight Solitaire").assertIsDisplayed()
    }

    @Test
    fun cometTrailShowsPlayableWrappingControlsAndReviewDefault() {
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(
                    state = ArcadeUiState(selectedGameId = ArcadeGame.WRAPPING_SNAKE.gameId),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("WRAPPING TRAIL").assertIsDisplayed()
        composeRule.onNodeWithTag("comet_trail_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Turn Comet Trail left").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Comet Trail").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Endless wrapping • Self-contact pauses before contact (review default).",
        ).assertIsDisplayed()
    }

    @Test
    fun starstruckUsesDistinctAccessibleCelestialTiles() {
        val actions = mutableListOf<ArcadeAction>()
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(
                    state = ArcadeUiState(selectedGameId = ArcadeGame.HEART_MATCH.gameId),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Starstruck").assertIsDisplayed()
        composeRule.onNodeWithTag("starstruck_board").assertIsDisplayed()
        composeRule.onNodeWithTag("starstruck_tile_0_0")
            .assertHasClickAction()
            .assertContentDescriptionContains("tile, row 1, column 1", substring = true)
            .performClick()
        composeRule.onNodeWithTag("starstruck_tile_1_0")
            .assertHasClickAction()
            .assertContentDescriptionContains("tile, row 1, column 2", substring = true)
        composeRule.runOnIdle {
            assertTrue(actions.none { it is ArcadeAction.PreviewControl })
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ArcadeActivityScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun openArcade() {
        composeRule.onNodeWithTag("nav_arcade").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("arcade_catalog").fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun rendersSharedCardTableToInspectablePng() {
        writeScreenshot("arcade-lobby.png")
        composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(ArcadeGame.entries.size + 1)
        composeRule.onNodeWithText("Cosmic Mischief").performClick()
        composeRule.onNodeWithText("SHARED TABLE").assertIsDisplayed()
        writeScreenshot("shared-card-table.png")
    }

    @Test
    fun rendersStarstruckGridToInspectablePng() {
        composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(4)
        composeRule.onNodeWithText("Starstruck").performClick()
        composeRule.onNodeWithTag("starstruck_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Starstruck").performClick()
        composeRule.onNodeWithContentDescription("Resume Starstruck").assertIsEnabled()
        writeScreenshot("starstruck.png")
    }

    @Test
    fun rendersPlayableCelestialSpireToInspectablePng() {
        composeRule.onNodeWithText("Celestial Spire").performClick()
        composeRule.onNodeWithTag("celestial_spire_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Celestial Spire").performClick()
        composeRule.onNodeWithContentDescription("Resume Celestial Spire").assertIsEnabled()
        writeScreenshot("celestial-spire.png")
    }

    @Test
    fun rendersPlayableCometTrailToInspectablePng() {
        composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(5)
        composeRule.onNodeWithText("Comet Trail").performClick()
        composeRule.onNodeWithTag("comet_trail_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Comet Trail").performClick()
        composeRule.onNodeWithContentDescription("Resume Comet Trail").assertIsEnabled()
        writeScreenshot("comet-trail.png")
    }

    @Test
    fun recreationPreservesNavigationConversationAndDraftWhileBackUnwindsLayers() {
        composeRule.onNodeWithText("Celestial Spire").performClick()
        composeRule.onNodeWithContentDescription("Open conversation with Riven").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Draft survives recreation")

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Celestial Spire").assertIsDisplayed()
        composeRule.onNodeWithText("Draft survives recreation").assertIsDisplayed()
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        assertTrue(
            composeRule.onAllNodesWithText("Draft survives recreation").fetchSemanticsNodes().isEmpty(),
        )
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag("arcade_catalog").assertIsDisplayed()
    }

    @Test
    fun quietControlHasSwitchSemanticsAndAnAccessibleLabel() {
        composeRule.onNodeWithContentDescription("Quiet commentary")
            .assertIsOff()
            .performClick()
            .assertIsOn()
    }

    @Test
    fun returningFromGamePreservesTheLobbyScrollPosition() {
        composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(ArcadeGame.entries.size + 1)
        composeRule.onNodeWithText("Cosmic Mischief").performClick()
        composeRule.onNodeWithText("SHARED TABLE").assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithText("Cosmic Mischief").assertIsDisplayed()
    }

    private fun writeScreenshot(fileName: String) {
        val output = File(
            System.getProperty("user.dir"),
            "build/reports/arcade-preview/$fileName",
        )
        output.parentFile?.mkdirs()
        composeRule.runOnIdle {
            val view = composeRule.activity.window.decorView
            assertTrue(view.width > 0)
            assertTrue(view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(output).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
        assertTrue(output.isFile)
        assertTrue(output.length() > 0L)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h420dp-xxhdpi")
class ArcadeCompactActivityScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun openArcade() {
        composeRule.onNodeWithTag("nav_arcade").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("arcade_catalog").fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun rendersCompactSharedCardTableToInspectablePng() {
        composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(ArcadeGame.entries.size + 1)
        composeRule.onNodeWithText("Cosmic Mischief").performClick()
        composeRule.onNodeWithTag("arcade_game_scroll").performScrollToIndex(2)
        composeRule.onAllNodesWithTag("riven_card_back_art").assertCountEquals(8)
        composeRule.onNodeWithTag("celestial_hand").performScrollToIndex(6)
        composeRule.onNodeWithTag("arcade_game_scroll").performTouchInput { swipeUp() }
        composeRule.onNodeWithContentDescription(
            "Rewrite the Stars. Choose the next color.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(
            "celestial_hand_rewrite-the-stars_effect",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        writeScreenshot("shared-card-table-compact.png")
    }

    @Test
    fun rendersCompactCelestialSpireControlsToInspectablePng() {
        composeRule.onNodeWithText("Celestial Spire").performClick()
        composeRule.onNodeWithTag("compact_playable_game_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("celestial_spire_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Move piece left").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hard drop piece").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Celestial Spire").performClick()
        composeRule.onNodeWithContentDescription("Resume Celestial Spire").assertIsEnabled()
        writeScreenshot("celestial-spire-compact.png")
    }

    @Test
    fun rendersCompactCometTrailControlsToInspectablePng() {
        composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(5)
        composeRule.onNodeWithText("Comet Trail").performClick()
        composeRule.onNodeWithTag("compact_playable_game_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("comet_trail_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Turn Comet Trail left").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Comet Trail").performClick()
        composeRule.onNodeWithContentDescription("Resume Comet Trail").assertIsEnabled()
        writeScreenshot("comet-trail-compact.png")
    }

    @Test
    fun rendersCompactStarstruckControlsToInspectablePng() {
        composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(4)
        composeRule.onNodeWithText("Starstruck").performClick()
        composeRule.onNodeWithTag("compact_playable_game_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("starstruck_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Starstruck settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Starstruck").performClick()
        composeRule.onNodeWithContentDescription("Resume Starstruck").assertIsEnabled()
        writeScreenshot("starstruck-compact.png")
    }

    private fun writeScreenshot(fileName: String) {
        val output = File(
            System.getProperty("user.dir"),
            "build/reports/arcade-preview/$fileName",
        )
        output.parentFile?.mkdirs()
        composeRule.runOnIdle {
            val view = composeRule.activity.window.decorView
            assertTrue(view.width > 0)
            assertTrue(view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(output).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
        assertTrue(output.isFile)
        assertTrue(output.length() > 0L)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h420dp-xxhdpi")
class ArcadeCompactLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shortLargeTextMidnightSolitaireCanReachTheLastCardInALongTableau() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SharedPreferencesMidnightSolitaireStore(
            context = context,
            preferenceName = "arcade-compact-solitaire-${System.nanoTime()}",
        )
        store.saveSession(longSolitaireSession())
        store.saveSettings(MidnightSolitaireSettings(largeCardText = true))
        composeRule.setContent {
            RivenTheme {
                WithFontScale(1.5f) {
                    ArcadeExperience(
                        state = ArcadeUiState(selectedGameId = ArcadeGame.KLONDIKE.gameId),
                        onAction = {},
                        solitaireStoreOverride = store,
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("arcade_game_scroll").assertCountEquals(0)
        composeRule.onNodeWithTag("compact_playable_game_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("midnight_solitaire_compact_scroll").assertIsDisplayed()
        repeat(4) {
            composeRule.onNodeWithTag("midnight_solitaire_compact_scroll")
                .performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("solitaire_tableau_0_18")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun shortLargeTextCelestialSpireKeepsTheFullBoardAndPlayControlsTogether() {
        composeRule.setContent {
            RivenTheme {
                WithFontScale(1.5f) {
                    ArcadeExperience(
                        state = ArcadeUiState(selectedGameId = ArcadeGame.STACKER.gameId),
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("arcade_game_scroll").assertCountEquals(0)
        composeRule.onNodeWithTag("compact_playable_game_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("celestial_spire_board").assertIsDisplayed()
        listOf(
            "Move piece left",
            "Rotate piece clockwise",
            "Move piece right",
            "Soft drop piece",
            "Hard drop piece",
            "Pause Celestial Spire",
        ).forEach { description ->
            composeRule.onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
        composeRule.onNodeWithContentDescription("Open conversation with Riven").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Celestial Spire settings")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithContentDescription("Raise game sound volume")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
    }

    @Test
    fun shortLargeTextCometTrailKeepsBoardAndControlsTogether() {
        composeRule.setContent {
            RivenTheme {
                WithFontScale(1.5f) {
                    ArcadeExperience(
                        state = ArcadeUiState(selectedGameId = ArcadeGame.WRAPPING_SNAKE.gameId),
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("arcade_game_scroll").assertCountEquals(0)
        composeRule.onNodeWithTag("compact_playable_game_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("comet_trail_board").assertIsDisplayed()
        listOf(
            "Turn Comet Trail up",
            "Turn Comet Trail left",
            "Turn Comet Trail down",
            "Turn Comet Trail right",
            "Pause Comet Trail",
        ).forEach { description ->
            composeRule.onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
        composeRule.onNodeWithContentDescription("Open Comet Trail settings")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Open conversation with Riven").assertIsDisplayed()
    }

    @Test
    fun shortLargeTextStarstruckKeepsBoardAndControlsTogether() {
        composeRule.setContent {
            RivenTheme {
                WithFontScale(1.5f) {
                    ArcadeExperience(
                        state = ArcadeUiState(selectedGameId = ArcadeGame.HEART_MATCH.gameId),
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("arcade_game_scroll").assertCountEquals(0)
        composeRule.onNodeWithTag("compact_playable_game_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("starstruck_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Starstruck settings")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Pause Starstruck")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Open conversation with Riven").assertIsDisplayed()
    }

    @Test
    fun shortLargeTextGameLayoutScrollsFromHeaderThroughControlsAndRules() {
        composeRule.setContent {
            RivenTheme {
                WithFontScale(1.5f) {
                    ArcadeExperience(
                        state = ArcadeUiState(selectedGameId = ArcadeGame.RIVEN_CARD_TABLE.gameId),
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("arcade_game_scroll").assertIsDisplayed()
        composeRule.onNodeWithText("Cosmic Mischief").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Quiet commentary").assertIsDisplayed()
        composeRule.onNodeWithText("CALL OUT").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("riven_card_back_art").assertCountEquals(8)
        composeRule.onNodeWithTag("celestial_hand").performScrollToIndex(6)
        composeRule.onNodeWithTag("arcade_game_scroll").performTouchInput { swipeUp() }
        composeRule.onNodeWithContentDescription(
            "Rewrite the Stars. Choose the next color.",
        ).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithContentDescription(
                "Double Trouble. The next player draws two cards.",
            ).fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithTag("arcade_game_scroll").performScrollToIndex(3)
        composeRule.onNodeWithText(
            "Preview only • Deck, penalties, challenges, trade protocol, and hidden-information engine remain pending.",
        ).assertIsDisplayed()
    }


    @Test
    fun compactLargeTextLobbyKeepsApprovedLongNamesReachable() {
        composeRule.setContent {
            RivenTheme {
                WithFontScale(1.5f) {
                    ArcadeExperience(state = ArcadeUiState(), onAction = {})
                }
            }
        }

        listOf("Celestial Spire", "Midnight Solitaire", "Starstruck", "Comet Trail", "Cosmic Mischief")
            .forEachIndexed { index, title ->
                composeRule.onNodeWithTag("arcade_catalog").performScrollToIndex(index + 2)
                composeRule.onNodeWithText(title).assertIsDisplayed()
            }
    }

    @Test
    fun keyboardSizedLargeTextConversationCanScrollToDraftAndReturnAction() {
        composeRule.setContent {
            RivenTheme {
                WithFontScale(1.5f) {
                    ArcadeExperience(
                        state = ArcadeUiState(
                            selectedGameId = ArcadeGame.STACKER.gameId,
                            conversationOpen = true,
                            conversationDraft = "Short viewport draft",
                        ),
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onNode(hasSetTextAction()).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Keep draft & return to table")
            .performScrollTo()
            .assertIsDisplayed()
    }
}

@Composable
private fun WithFontScale(
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = currentDensity.density,
            fontScale = fontScale,
        ),
        content = content,
    )
}

private fun longSolitaireSession(): MidnightSolitaireSession {
    val faceUp = (13 downTo 1).mapIndexed { index, rank ->
        SolitaireCard(
            suit = if (index % 2 == 0) SolitaireSuit.SPADES else SolitaireSuit.HEARTS,
            rank = rank,
        )
    }
    val deck = SolitaireSuit.entries.flatMap { suit ->
        (1..13).map { rank -> SolitaireCard(suit, rank) }
    }
    val remaining = deck.filterNot(faceUp.toSet()::contains)
    val longPile = remaining.take(6).map { card ->
        SolitaireTableauCard(card, faceUp = false)
    } + faceUp.map { card ->
        SolitaireTableauCard(card, faceUp = true)
    }
    val state = MidnightSolitaireState(
        stock = remaining.drop(6),
        waste = emptyList(),
        foundations = List(4) { emptyList() },
        tableau = listOf(longPile) + List(6) { emptyList() },
        dealSeed = 420L,
    )
    check(MidnightSolitaireEngine.isValid(state))
    return MidnightSolitaireSession(state)
}
