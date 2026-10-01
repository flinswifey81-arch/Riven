package com.shai.riven.ui.arcade

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
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
