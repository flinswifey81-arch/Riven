package com.shai.riven.ui.arcade

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.shai.riven.MainActivity
import com.shai.riven.ui.theme.RivenTheme
import java.io.File
import java.io.FileOutputStream
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
            "Layout preview • Game engines and live Riven replies are not connected in this slice.",
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
    fun stackerPreviewStaysNeutralUntilItsRulesetIsConfirmed() {
        composeRule.setContent {
            RivenTheme {
                ArcadeExperience(
                    state = ArcadeUiState(selectedGameId = ArcadeGame.STACKER.gameId),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("BLOCK BOARD").assertIsDisplayed()
        composeRule.onNodeWithText("Ruleset awaiting confirmation").assertIsDisplayed()
        composeRule.onNodeWithText("CONTROL LAYOUT PREVIEW").assertIsDisplayed()
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
        composeRule.onNodeWithText("Riven's Card Table").performClick()
        composeRule.onNodeWithText("SHARED TABLE").assertIsDisplayed()
        writeScreenshot("shared-card-table.png")
    }

    @Test
    fun recreationPreservesNavigationConversationAndDraftWhileBackUnwindsLayers() {
        composeRule.onNodeWithText("Stacker").performClick()
        composeRule.onNodeWithContentDescription("Open conversation with Riven").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Draft survives recreation")

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Stacker").assertIsDisplayed()
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
        composeRule.onNodeWithText("Riven's Card Table").performClick()
        composeRule.onNodeWithText("SHARED TABLE").assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithText("Riven's Card Table").assertIsDisplayed()
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
        composeRule.onNodeWithText("Riven's Card Table").performClick()
        composeRule.onNodeWithTag("arcade_game_scroll").performScrollToIndex(2)
        composeRule.onAllNodesWithTag("riven_card_back_art").assertCountEquals(8)

        val output = File(
            System.getProperty("user.dir"),
            "build/reports/arcade-preview/shared-card-table-compact.png",
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
        composeRule.onNodeWithContentDescription("Quiet commentary").assertIsDisplayed()
        composeRule.onNodeWithText("CALL OUT").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("riven_card_back_art").assertCountEquals(8)
        composeRule.onNodeWithTag("arcade_game_scroll").performScrollToIndex(3)
        composeRule.onNodeWithText(
            "Preview only • Deck, penalties, challenges, trade protocol, and hidden-information engine remain pending.",
        ).assertIsDisplayed()
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
