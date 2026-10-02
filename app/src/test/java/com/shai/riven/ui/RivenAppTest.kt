package com.shai.riven.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import com.shai.riven.MainActivity
import com.shai.riven.data.instructions.ShaiSystemInstructionsSnapshot
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.presence.RivenRoom
import com.shai.riven.data.provider.ProviderCapability
import com.shai.riven.data.provider.ProviderProfileSnapshot
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalogError
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalogResult
import com.shai.riven.data.reminder.ReminderController
import com.shai.riven.data.reminder.ReminderDraft
import com.shai.riven.data.reminder.ReminderFeature
import com.shai.riven.data.reminder.ReminderFeatureControl
import com.shai.riven.data.reminder.ReminderOperationResult
import com.shai.riven.data.reminder.ReminderQuietHours
import com.shai.riven.data.reminder.ReminderRescheduleReport
import com.shai.riven.data.reminder.ReminderSettingsSnapshot
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.runtime.RivenChatMessage
import com.shai.riven.data.runtime.RivenMemoryItem
import com.shai.riven.data.runtime.RivenImageAttachment
import com.shai.riven.data.runtime.RivenImagePreviewState
import com.shai.riven.data.runtime.RivenProfileSaveResult
import com.shai.riven.data.runtime.RivenRuntimeController
import com.shai.riven.data.runtime.RivenRuntimeResult
import com.shai.riven.data.runtime.RivenRuntimeSnapshot
import com.shai.riven.ui.theme.RivenTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
class RivenAppNormalTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun normalChatStreamsSendsNavigatesAndRendersInspectablePng() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }

        composeRule.onNodeWithTag("chat_normal").assertIsDisplayed()
        composeRule.onNodeWithText("A grounded reply with enough length to prove the bubble grows naturally.")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Chat destination", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Alarms destination", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Arcade destination", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings destination", useUnmergedTree = true).assertIsDisplayed()
        writeScreenshot("chat-normal.png")

        composeRule.onNodeWithTag("chat_input").performTextReplacement("A fresh message")
        composeRule.onNodeWithTag("chat_send").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sendCalls == 1 }
        composeRule.onNodeWithText("Streamed reply.").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("credential_status").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_arcade").performClick()
        composeRule.onNodeWithTag("arcade_catalog").assertIsDisplayed()
    }

    @Test
    fun brassKeyFloorPlanBrowsesWithoutMovingRivenOrShowingALocationMarker() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }

        composeRule.onNodeWithTag("room_key").performClick()
        composeRule.onNodeWithTag("room_floor_plan").assertIsDisplayed()
        composeRule.onNodeWithTag("room_study").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.browsedRoom == RivenRoom.STUDY }

        composeRule.onNodeWithTag("room_backdrop_study").assertIsDisplayed()
        assertTrue(runtime.actualRoom == RivenRoom.LIVING_ROOM)
        composeRule.onAllNodesWithTag("riven_location_marker").assertCountEquals(0)
    }

    @Test
    fun restoredImageOnlyDraftShowsAccessiblePreviewAndCanBeRemoved() {
        val runtime = FakeRivenRuntime(configuredSnapshot().withImageDraft())
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }

        composeRule.onNodeWithTag("draft_image_preview").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Selected image preview").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_send").assertIsEnabled()
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Fresh caption before debounce")
        composeRule.onNodeWithTag("chat_remove_image").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.removeImageCalls == 1 }
        composeRule.onAllNodesWithTag("draft_image_preview").assertCountEquals(0)
        assertTrue(runtime.currentDraft == "Fresh caption before debounce")
    }

    @Test
    fun failedImagePreflightDoesNotReplaceLiveCaptionWithPersistedDraft() {
        val runtime = FakeRivenRuntime(
            configuredSnapshot().withImageDraft().copy(draft = "older persisted caption"),
            failImagePreflight = true,
        )
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }

        composeRule.onNodeWithTag("chat_input").performTextReplacement("live caption")
        composeRule.onNodeWithTag("chat_remove_image").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.removeImageCalls == 1 }

        composeRule.onNodeWithTag("chat_input").assertTextContains("live caption")
        composeRule.onNodeWithTag("draft_image_preview").assertIsDisplayed()
    }

    @Test
    fun visibleDeferredImageLoadsBeforeItIsReportedUnavailable() {
        val runtime = FakeRivenRuntime(configuredSnapshot().withDeferredImageDraft())
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }

        composeRule.onNodeWithTag("draft_image_preview").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.loadPreviewCalls >= 1 }
        composeRule.onNodeWithContentDescription("Selected image preview").assertIsDisplayed()
    }

    @Test
    fun sameFrameNavigationDoesNotLoseDebouncedDraft() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Keep this immediately")

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runtime.savedDrafts.lastOrNull() == "Keep this immediately"
        }
        composeRule.onNodeWithTag("nav_chat").performClick()

        composeRule.onNodeWithTag("chat_input").assertTextContains("Keep this immediately")
    }

    @Test
    fun alarmNavigationPreservesDraftAndSystemBackReturnsToChat() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        val reminderController = FakeReminderController()
        composeRule.runOnIdle {
            composeRule.activity.setContent {
                RivenTheme {
                    RivenApp(
                        runtimeFactory = { runtime },
                        reminderControllerFactory = { reminderController },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Return to this thought")

        composeRule.onNodeWithTag("nav_alarms").performClick()
        composeRule.onNodeWithTag("reminder_alarm_screen").assertIsDisplayed()
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("chat_normal").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_input").assertTextContains("Return to this thought")
    }

    @Test
    fun freshDraftIsFlushedBeforeContinueAndRemainsVisible() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Unfinished thought")

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.continueCalls == 1 }

        assertTrue(runtime.savedDrafts.contains("Unfinished thought"))
        composeRule.onNodeWithTag("chat_input").assertTextContains("Unfinished thought")
    }

    @Test
    fun navigationDuringStreamCancelsPromptlyAndResyncsCommittedUserTurn() {
        val runtime = FakeRivenRuntime(configuredSnapshot(), holdSend = true)
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Committed before navigation")
        composeRule.onNodeWithTag("chat_send").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sendCalls == 1 }

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sendCancelled }
        composeRule.onNodeWithTag("nav_chat").performClick()

        composeRule.onAllNodesWithText("Committed before navigation").assertCountEquals(1)
    }

    @Test
    fun repeatedIdenticalTextCancelledBeforeCommitRemainsADraftAndAutosaveResumes() {
        val repeated = "Do you remember our plan?"
        val runtime = FakeRivenRuntime(
            configuredSnapshot(),
            holdSend = true,
            holdBeforeCommit = true,
        )
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement(repeated)
        composeRule.onNodeWithTag("chat_send").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sendCalls == 1 }

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sendCancelled }
        composeRule.onNodeWithTag("nav_chat").performClick()

        composeRule.onNodeWithTag("chat_input").assertTextContains(repeated)
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.savedDrafts.lastOrNull() == repeated }
    }

    @Test
    fun delayedNoActiveCancelKeepsEditorBusyThenProtectsTheNextDraftAndSend() {
        val runtime = FakeRivenRuntime(
            configuredSnapshot(),
            holdSend = true,
            holdBeforeCommit = true,
            delayCancel = true,
            cancelReturnsFailure = true,
        )
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Cancelled draft")
        composeRule.onNodeWithTag("chat_send").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sendCalls == 1 }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.cancelEntered.isCompleted && runtime.sendCancelled }
        composeRule.onNodeWithTag("chat_input").assertIsNotEnabled()
        composeRule.onNodeWithTag("chat_send").assertIsNotEnabled()

        runtime.releaseCancel()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.cancelCompleted }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("chat_input").assertIsEnabled().assertTextContains("Cancelled draft")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runtime.savedDrafts.lastOrNull() == "Cancelled draft"
        }

        composeRule.onNodeWithTag("chat_input").performTextReplacement("Replacement draft")
        composeRule.onNodeWithTag("chat_send").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sentContents.lastOrNull() == "Replacement draft" }
        assertTrue(runtime.cancelCalls == 1)
    }

    @Test
    fun cancelDuringSecondaryDraftFlushStopsTheOriginatingJobBeforeProviderDispatch() {
        val runtime = FakeRivenRuntime(
            configuredSnapshot(),
            delayDraftSave = true,
            cancelReturnsFailure = true,
        )
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.saveDraftEntered.isCompleted }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.cancelCompleted }
        composeRule.onNodeWithTag("chat_input").assertIsNotEnabled()

        runtime.releaseDraftSave()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("chat_input").assertIsEnabled()
        assertTrue(runtime.retryCalls == 0)
    }

    @Test
    fun cancelDuringSecondaryDraftFlushPreservesAndPersistsFreshComposerText() {
        val freshDraft = "Keep this secondary-action draft"
        val runtime = FakeRivenRuntime(
            configuredSnapshot(),
            delayDraftSave = true,
            cancelReturnsFailure = true,
        )
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement(freshDraft)
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.saveDraftEntered.isCompleted }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.cancelCompleted }
        composeRule.onNodeWithTag("chat_input").assertIsNotEnabled()
        runtime.releaseDraftSave()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("chat_input").assertIsEnabled().assertTextContains(freshDraft)
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.savedDrafts.lastOrNull() == freshDraft }
        assertTrue(runtime.retryCalls == 0)
    }

    @Test
    fun fastSendWithoutAConfiguredProfileKeepsDraftAcrossNavigationAndRelaunch() {
        val runtime = FakeRivenRuntime(unconfiguredSnapshot(), failUnconfiguredSend = true)
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Configure later, keep this")
        composeRule.onNodeWithTag("chat_send").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sendCalls == 1 }
        composeRule.onNodeWithTag("chat_input").assertTextContains("Configure later, keep this")

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("nav_chat").performClick()
        composeRule.onNodeWithTag("chat_input").assertTextContains("Configure later, keep this")

        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("chat_input").assertTextContains("Configure later, keep this")
    }

    @Test
    fun failedMemoryEditsKeepUserEnteredText() {
        val snapshot = configuredSnapshot().copy(
            memories = listOf(RivenMemoryItem("memory", "Original meaning", MemoryCertainty.CERTAIN, true)),
        )
        val runtime = FakeRivenRuntime(snapshot).apply { failMemoryWrites = true }
        composeRule.runOnIdle {
            composeRule.activity.setContent { RivenTheme { RivenApp { runtime } } }
        }
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").performScrollToIndex(17)
        composeRule.onNodeWithTag("memory_remember_input").performScrollTo()
            .performTextReplacement("Remember this text")
        composeRule.onNodeWithTag("memory_remember").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memory_remember_input").assertTextContains("Remember this text")

        composeRule.onNodeWithTag("settings_screen").performScrollToIndex(19)
        composeRule.onNodeWithText("Correct").performScrollTo().performClick()
        composeRule.onNodeWithTag("memory_correction_input").performScrollTo()
            .performTextReplacement("Corrected text")
        composeRule.onNodeWithText("Apply correction").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memory_correction_input").assertTextContains("Corrected text")
    }

    private fun writeScreenshot(name: String) {
        val output = File(System.getProperty("user.dir"), "build/reports/riven-runtime-preview/$name")
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
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class RivenAppSavedStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun apiKeyEntryIsAbsentFromSavedStateRestoration() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { RivenTheme { RivenApp { runtime } } }
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("profile_api_key").performTextReplacement("fake-secret-not-for-state")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("settings_screen").performScrollToIndex(7)
        composeRule.onNodeWithTag("profile_save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.savedApiKeys.isNotEmpty() }
        assertTrue(runtime.savedApiKeys.last().isEmpty())
    }

    @Test
    fun recreationDuringCommittedUserTurnDoesNotResurrectOrDuplicateSubmittedDraft() {
        val submitted = "Committed across recreation"
        val runtime = FakeRivenRuntime(configuredSnapshot(), holdSend = true)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { RivenTheme { RivenApp { runtime } } }
        composeRule.onNodeWithTag("chat_input").performTextReplacement(submitted)
        composeRule.onNodeWithTag("chat_send").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runtime.sendCalls == 1 }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onAllNodesWithText(submitted).assertCountEquals(1)
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        assertTrue(runtime.currentDraft.isEmpty())
        composeRule.onAllNodesWithText(submitted).assertCountEquals(1)
    }

    @Test
    fun recreationRestoresAnUnsentComposerDraft() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { RivenTheme { RivenApp { runtime } } }
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Unsent across recreation")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("chat_input").assertTextContains("Unsent across recreation")
    }

    @Test
    fun recreationRestoresAlarmDestinationAndKeepsChatDraft() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        val reminderController = FakeReminderController()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            RivenTheme {
                RivenApp(
                    runtimeFactory = { runtime },
                    reminderControllerFactory = { reminderController },
                )
            }
        }
        composeRule.onNodeWithTag("chat_input").performTextReplacement("Draft behind alarms")
        composeRule.onNodeWithTag("nav_alarms").performClick()
        composeRule.onNodeWithTag("reminder_alarm_screen").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("reminder_alarm_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_chat").performClick()
        composeRule.onNodeWithTag("chat_input").assertTextContains("Draft behind alarms")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h600dp-xxhdpi")
class RivenAppCompactTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactChatKeepsTranscriptComposerActionsAndNavigationAvailable() {
        val runtime = FakeRivenRuntime(configuredSnapshot())
        composeRule.setContent { RivenTheme { RivenApp { runtime } } }

        composeRule.onNodeWithTag("chat_compact").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_transcript").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_actions").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_send").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_alarms").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_arcade").assertIsDisplayed()
    }

    @Test
    fun compactImageDraftKeepsPreviewAndImageOnlySendAvailable() {
        val runtime = FakeRivenRuntime(configuredSnapshot().withImageDraft())
        composeRule.setContent { RivenTheme { RivenApp { runtime } } }

        composeRule.onNodeWithTag("draft_image_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_send").assertIsEnabled()
    }
}

private class FakeRivenRuntime(
    initial: RivenRuntimeSnapshot,
    holdSend: Boolean = false,
    private val holdBeforeCommit: Boolean = false,
    private val delayCancel: Boolean = false,
    private val cancelReturnsFailure: Boolean = false,
    private val failUnconfiguredSend: Boolean = false,
    delayDraftSave: Boolean = false,
    private val failImagePreflight: Boolean = false,
) : RivenRuntimeController {
    private var current = initial
    private var holdNextSend = holdSend
    private var delayNextDraftSave = delayDraftSave
    private var activeSendJob: Job? = null
    val currentDraft: String
        get() = current.draft
    var sendCalls = 0
        private set
    val sentContents = mutableListOf<String>()
    val savedDrafts = mutableListOf<String>()
    var continueCalls = 0
        private set
    var sendCancelled = false
        private set
    var cancelCalls = 0
        private set
    val cancelEntered = CompletableDeferred<Unit>()
    private val cancelRelease = CompletableDeferred<Unit>()
    val saveDraftEntered = CompletableDeferred<Unit>()
    private val saveDraftRelease = CompletableDeferred<Unit>()
    var cancelCompleted = false
        private set
    var retryCalls = 0
        private set
    var failMemoryWrites = false
    val savedApiKeys = mutableListOf<String>()
    var removeImageCalls = 0
        private set
    var loadPreviewCalls = 0
        private set
    val browsedRoom: RivenRoom
        get() = current.roomState.browsedRoom
    val actualRoom: RivenRoom
        get() = current.roomState.actualRoom

    override suspend fun initialize() = success()
    override suspend fun snapshot() = success()

    override suspend fun browseRoom(room: RivenRoom): RivenRuntimeResult {
        if (current.roomState.browsedRoom != room) {
            current = current.copy(
                roomState = current.roomState.copy(
                    browsedRoom = room,
                    browserRevision = current.roomState.browserRevision + 1,
                ),
            )
        }
        return success()
    }

    override suspend fun saveDraft(content: String): RivenRuntimeResult {
        if (delayNextDraftSave) {
            delayNextDraftSave = false
            saveDraftEntered.complete(Unit)
            withContext(NonCancellable) { saveDraftRelease.await() }
        }
        savedDrafts += content
        current = current.copy(draft = content)
        return success()
    }

    override suspend fun removeDraftImage(attachmentId: String, content: String): RivenRuntimeResult {
        removeImageCalls += 1
        if (failImagePreflight) {
            return RivenRuntimeResult.Failure("Draft could not be saved.")
        }
        current = current.copy(
            draft = content,
            draftImages = current.draftImages.filterNot { it.attachmentId == attachmentId },
        )
        return success()
    }

    override suspend fun loadImagePreview(attachmentId: String): RivenRuntimeResult {
        loadPreviewCalls += 1
        current = current.copy(
            draftImages = current.draftImages.map { image ->
                if (image.attachmentId == attachmentId) {
                    image.copy(
                        previewBytes = TEST_PNG_BYTES,
                        previewState = RivenImagePreviewState.READY,
                    )
                } else {
                    image
                }
            },
        )
        return success()
    }

    override suspend fun send(content: String, onDelta: suspend (String) -> Unit): RivenRuntimeResult {
        sendCalls += 1
        sentContents += content
        if (failUnconfiguredSend) {
            current = current.copy(draft = content)
            return RivenRuntimeResult.Failure("Configure a profile first.", snapshot = current)
        }
        if (holdNextSend) {
            holdNextSend = false
            activeSendJob = coroutineContext[Job]
            if (!holdBeforeCommit) {
                current = current.copy(
                    timelineRevision = current.timelineRevision + 1,
                    draft = "",
                    messages = current.messages + RivenChatMessage(
                        "user-$sendCalls",
                        MessageRole.USER,
                        MessageDeliveryState.PERSISTED,
                        content,
                        null,
                    ),
                )
            }
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                sendCancelled = true
                throw cancelled
            } finally {
                activeSendJob = null
            }
        }
        onDelta("Streamed ")
        yield()
        onDelta("reply.")
        current = current.copy(
            timelineRevision = current.timelineRevision + 2,
            draft = "",
            messages = current.messages + listOf(
                RivenChatMessage("user-$sendCalls", MessageRole.USER, MessageDeliveryState.PERSISTED, content, null),
                RivenChatMessage(
                    "assistant-$sendCalls",
                    MessageRole.ASSISTANT,
                    MessageDeliveryState.SUCCEEDED,
                    "Streamed reply.",
                    "anthropic/example",
                ),
            ),
        )
        return success()
    }

    override suspend fun retry(onDelta: suspend (String) -> Unit): RivenRuntimeResult {
        retryCalls++
        return success()
    }
    override suspend fun regenerate(onDelta: suspend (String) -> Unit) = success()
    override suspend fun continueConversation(onDelta: suspend (String) -> Unit): RivenRuntimeResult {
        continueCalls += 1
        return success()
    }
    override suspend fun cancel(): RivenRuntimeResult {
        cancelCalls++
        activeSendJob?.cancel(CancellationException("Controlled cancellation"))
        if (delayCancel) {
            cancelEntered.complete(Unit)
            cancelRelease.await()
        }
        cancelCompleted = true
        return if (cancelReturnsFailure) {
            RivenRuntimeResult.Failure("There is no active reply to cancel.", snapshot = current)
        } else {
            success()
        }
    }

    fun releaseCancel() {
        cancelRelease.complete(Unit)
    }

    fun releaseDraftSave() {
        saveDraftRelease.complete(Unit)
    }

    override suspend fun saveProfile(
        profileId: String?,
        displayName: String,
        modelId: String,
        apiKey: String,
    ): RivenProfileSaveResult {
        savedApiKeys += apiKey
        return RivenProfileSaveResult.Success(current.profiles.single())
    }

    override suspend fun selectProfile(profileId: String) = success()
    override suspend fun removeOpenRouterCredential() = success()
    override suspend fun fetchModels() =
        OpenRouterModelCatalogResult.Failure(OpenRouterModelCatalogError.AUTHENTICATION)
    override suspend fun saveInstructions(content: String, enabled: Boolean, expectedRevision: Long) = success()
    override suspend fun remember(meaning: String) = memoryWriteResult()
    override suspend fun correct(memoryId: String, replacement: String) = memoryWriteResult()
    override suspend fun forget(memoryId: String) = success()
    override suspend fun deleteMemory(memoryId: String) = success()
    override fun close() = Unit

    private fun success() = RivenRuntimeResult.Success(current)

    private fun memoryWriteResult(): RivenRuntimeResult = if (failMemoryWrites) {
        RivenRuntimeResult.Failure("Controlled memory failure", snapshot = current)
    } else {
        success()
    }
}

private class FakeReminderController : ReminderController {
    private val reminders = MutableStateFlow<List<ReminderSnapshot>>(emptyList())
    private val settings = MutableStateFlow(
        ReminderSettingsSnapshot(
            quietHours = ReminderQuietHours(),
            featureControls = ReminderFeature.entries.associateWith { feature ->
                ReminderFeatureControl(
                    feature = feature,
                    enabled = true,
                    allowDuringQuietHours = false,
                )
            },
        ),
    )

    override fun observeReminders(): Flow<List<ReminderSnapshot>> = reminders

    override fun observeSettings(): Flow<ReminderSettingsSnapshot> = settings

    override suspend fun create(draft: ReminderDraft): ReminderOperationResult = unsupported()

    override suspend fun edit(
        reminderId: String,
        draft: ReminderDraft,
    ): ReminderOperationResult = unsupported()

    override suspend fun complete(reminderId: String): ReminderOperationResult = unsupported()

    override suspend fun cancel(reminderId: String): ReminderOperationResult = unsupported()

    override suspend fun updateQuietHours(settings: ReminderQuietHours) = Unit

    override suspend fun updateFeatureControl(control: ReminderFeatureControl) = Unit

    override suspend fun rescheduleAll(reason: String) = ReminderRescheduleReport(
        scheduled = 0,
        permissionBlocked = 0,
        failed = 0,
    )

    private fun unsupported(): Nothing = error("Reminder mutation was not expected in a navigation test.")
}

private fun configuredSnapshot(): RivenRuntimeSnapshot {
    val profile = ProviderProfileSnapshot(
        profileId = "profile",
        displayName = "OpenRouter Primary",
        adapterId = "openrouter.chat-completions",
        endpointBaseUrl = "https://openrouter.ai/api/v1",
        modelId = "anthropic/example",
        credentialSlotId = "openrouter-account-key",
        isEnabled = true,
        capabilities = setOf(ProviderCapability.TEXT_CHAT, ProviderCapability.STREAMING),
        revision = 1,
        createdAt = 1,
        updatedAt = 1,
    )
    return RivenRuntimeSnapshot(
        conversationId = "riven-primary-conversation",
        timelineRevision = 2,
        messages = listOf(
            RivenChatMessage(
                id = "user",
                role = MessageRole.USER,
                deliveryState = MessageDeliveryState.PERSISTED,
                content = "Do you remember our plan?",
                providerModel = null,
            ),
            RivenChatMessage(
                id = "assistant",
                role = MessageRole.ASSISTANT,
                deliveryState = MessageDeliveryState.SUCCEEDED,
                content = "A grounded reply with enough length to prove the bubble grows naturally.",
                providerModel = profile.modelId,
            ),
        ),
        draft = "",
        profiles = listOf(profile),
        selectedProfileId = profile.profileId,
        selectedProfileHasCredential = true,
        instructions = ShaiSystemInstructionsSnapshot("", false, 0, null),
        memories = emptyList(),
    )
}

private fun unconfiguredSnapshot() = configuredSnapshot().copy(
    timelineRevision = 0,
    messages = emptyList(),
    profiles = emptyList(),
    selectedProfileId = null,
    selectedProfileHasCredential = false,
)

private fun RivenRuntimeSnapshot.withImageDraft() = copy(
    draftImages = listOf(
        RivenImageAttachment(
            attachmentId = "draft-image",
            mimeType = "image/png",
            byteSize = TEST_PNG_BYTES.size.toLong(),
            previewBytes = TEST_PNG_BYTES,
        ),
    ),
)

private fun RivenRuntimeSnapshot.withDeferredImageDraft() = copy(
    draftImages = listOf(
        RivenImageAttachment(
            attachmentId = "deferred-draft-image",
            mimeType = "image/png",
            byteSize = TEST_PNG_BYTES.size.toLong(),
            previewBytes = ByteArray(0),
            previewState = RivenImagePreviewState.DEFERRED,
        ),
    ),
)

private val TEST_PNG_BYTES: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAALSURBVBhXY2BABwAAEgABp3qZbgAAAABJRU5ErkJggg==",
)
