package com.shai.riven.ui.arcade.comet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shai.riven.ui.theme.RivenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class CometTrailComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun directionControlAndClockAdvanceThePersistedTrail() {
        composeRule.mainClock.autoAdvance = false
        val initial = CometTrailEngine.newGame(seed = 21L)
        val store = FakeCometTrailStore(session = initial)
        composeRule.setContent {
            RivenTheme {
                CometTrailGame(externallyPaused = false, storeOverride = store)
            }
        }

        composeRule.onNodeWithTag("comet_trail_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Turn Comet Trail down").performClick()
        settleCompose()
        composeRule.mainClock.advanceTimeBy(700L)
        composeRule.runOnIdle { }

        assertEquals(TrailDirection.DOWN, store.savedSession?.direction)
        assertEquals(TrailPoint(6, 7), store.savedSession?.head)
        assertEquals(1, store.savedSession?.stepsTaken)
    }

    @Test
    fun collisionPauseKeepsDirectionControlsAvailableForSafeRecovery() {
        composeRule.mainClock.autoAdvance = false
        val body = listOf(
            TrailPoint(2, 2),
            TrailPoint(2, 3),
            TrailPoint(1, 3),
            TrailPoint(1, 2),
            TrailPoint(1, 1),
        )
        val collision = CometTrailEngine.tick(
            CometTrailState(
                body = body,
                direction = TrailDirection.LEFT,
                food = TrailPoint(9, 9),
                randomState = 17L,
            ),
        )
        val store = FakeCometTrailStore(session = collision)
        composeRule.setContent {
            RivenTheme {
                CometTrailGame(externallyPaused = false, storeOverride = store)
            }
        }

        composeRule.onNodeWithText("CHOOSE A SAFER TURN").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Comet Trail").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Turn Comet Trail up").assertIsEnabled().performClick()
        settleCompose()
        assertNull(store.savedSession?.collisionDirection)
        assertEquals(body, store.savedSession?.body)

        composeRule.mainClock.advanceTimeBy(700L)
        composeRule.runOnIdle { }
        assertEquals(TrailPoint(2, 1), store.savedSession?.head)
    }

    @Test
    fun chatPauseStopsGravityAndDisablesPlayControls() {
        composeRule.mainClock.autoAdvance = false
        val initial = CometTrailEngine.newGame(seed = 22L)
        val store = FakeCometTrailStore(session = initial)
        composeRule.setContent {
            RivenTheme {
                CometTrailGame(externallyPaused = true, storeOverride = store)
            }
        }

        composeRule.onNodeWithText("PAUSED FOR CHAT").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Turn Comet Trail left").assertIsNotEnabled()
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.runOnIdle { }
        assertEquals(initial, store.savedSession)
    }

    @Test
    fun settingsPauseGravityAndPreserveExistingManualPause() {
        composeRule.mainClock.autoAdvance = false
        val initial = CometTrailEngine.newGame(seed = 23L)
        val store = FakeCometTrailStore(session = initial)
        composeRule.setContent {
            RivenTheme {
                CometTrailGame(
                    externallyPaused = false,
                    compactLayout = true,
                    storeOverride = store,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Pause Comet Trail").performClick()
        settleCompose()
        composeRule.onNodeWithContentDescription("Open Comet Trail settings").performClick()
        settleCompose()
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.runOnIdle { }
        assertEquals(initial, store.savedSession)

        composeRule.onNodeWithText("DONE").performScrollTo().performClick()
        settleCompose()
        composeRule.onNodeWithContentDescription("Resume Comet Trail").assertIsEnabled()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.runOnIdle { }
        assertEquals(initial, store.savedSession)
    }

    @Test
    fun settingsModalPausesRunningGameAndDismissalResumesIt() {
        composeRule.mainClock.autoAdvance = false
        val initial = CometTrailEngine.newGame(seed = 27L)
        val store = FakeCometTrailStore(session = initial)
        composeRule.setContent {
            RivenTheme {
                CometTrailGame(
                    externallyPaused = false,
                    compactLayout = true,
                    storeOverride = store,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open Comet Trail settings").performClick()
        settleCompose()
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.runOnIdle { }
        assertEquals(initial, store.savedSession)

        composeRule.onNodeWithText("DONE").performScrollTo().performClick()
        settleCompose()
        composeRule.mainClock.advanceTimeBy(700L)
        composeRule.runOnIdle { }
        assertEquals(1, store.savedSession?.stepsTaken)
    }

    @Test
    fun lifecycleStopPausesUntilTheHostResumes() {
        composeRule.mainClock.autoAdvance = false
        val initial = CometTrailEngine.newGame(seed = 24L)
        val store = FakeCometTrailStore(session = initial)
        val lifecycleOwner = MutableCometLifecycleOwner()
        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                RivenTheme {
                    CometTrailGame(externallyPaused = false, storeOverride = store)
                }
            }
        }

        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.CREATED) }
        settleCompose()
        composeRule.onNodeWithContentDescription("Turn Comet Trail right").assertIsNotEnabled()
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.runOnIdle { }
        assertEquals(initial, store.savedSession)

        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        settleCompose()
        composeRule.onNodeWithContentDescription("Turn Comet Trail right").assertIsEnabled()
    }

    @Test
    fun sameFrameDirectionThenDisposeCannotOverwriteTheNewSession() {
        composeRule.mainClock.autoAdvance = false
        val store = FakeCometTrailStore(session = CometTrailEngine.newGame(seed = 25L))
        val showGame = mutableStateOf(true)
        composeRule.setContent {
            RivenTheme {
                if (showGame.value) {
                    CometTrailGame(externallyPaused = false, storeOverride = store)
                }
            }
        }

        composeRule.onNodeWithContentDescription("Turn Comet Trail down").performClick()
        composeRule.runOnIdle { showGame.value = false }
        settleCompose()

        assertEquals(TrailDirection.DOWN, store.savedSession?.direction)
    }

    @Test
    fun sameFrameSpeedChangeThenDisposeCannotOverwriteTheNewSetting() {
        composeRule.mainClock.autoAdvance = false
        val store = FakeCometTrailStore(
            session = CometTrailEngine.newGame(seed = 26L),
            settings = CometTrailSettings(TrailSpeed.GENTLE),
        )
        val showGame = mutableStateOf(true)
        composeRule.setContent {
            RivenTheme {
                if (showGame.value) {
                    CometTrailGame(
                        externallyPaused = false,
                        compactLayout = true,
                        storeOverride = store,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Open Comet Trail settings").performClick()
        settleCompose()
        composeRule.onNodeWithTag("comet_trail_speed").performClick()
        composeRule.runOnIdle { showGame.value = false }
        settleCompose()

        assertEquals(TrailSpeed.EASY, store.savedSettings?.speed)
    }

    private fun settleCompose() {
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeBy(100L, ignoreFrameDuration = false)
        composeRule.runOnIdle { }
    }
}

private class MutableCometLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

private class FakeCometTrailStore(
    session: CometTrailState? = null,
    settings: CometTrailSettings = CometTrailSettings(),
) : CometTrailStore {
    private var loadedSession = session
    private var loadedSettings = settings
    var savedSession: CometTrailState? = session
        private set
    var savedSettings: CometTrailSettings? = settings
        private set

    override fun loadSession(): CometTrailState? = loadedSession

    override fun saveSession(state: CometTrailState) {
        savedSession = state
        loadedSession = state
    }

    override fun loadSettings(): CometTrailSettings = loadedSettings

    override fun saveSettings(settings: CometTrailSettings) {
        savedSettings = settings
        loadedSettings = settings
    }
}
