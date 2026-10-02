package com.shai.riven.ui.arcade

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
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
import com.shai.riven.MainActivity
import com.shai.riven.ui.theme.RivenTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            "Celestial Spire and Comet Trail are playable • Three table previews and live Riven replies remain unconnected.",
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
        composeRule.onNodeWithText("Moons • hearts • stars").assertIsDisplayed()
        listOf("Aqua moon tile", "Ruby heart tile", "Gold star tile").forEach { label ->
            composeRule.onAllNodesWithContentDescription(label)[0]
                .assertHasClickAction()
                .performClick()
        }
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    ArcadeAction.PreviewControl("Celestial tile swap"),
                    ArcadeAction.PreviewControl("Celestial tile swap"),
                    ArcadeAction.PreviewControl("Celestial tile swap"),
                ),
                actions,
            )
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ArcadeActivityScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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
        composeRule.onNodeWithText("CELESTIAL GRID").assertIsDisplayed()
        writeScreenshot("starstruck-grid.png")
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
        composeRule.onNodeWithText("Arcade").assertIsDisplayed()
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
