package com.shai.riven.ui.arcade.spire

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shai.riven.ui.theme.RivenTheme
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
class CelestialSpireComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun controlsClearALineAndDeliverBothSoundCues() {
        composeRule.mainClock.autoAdvance = false
        val store = FakeSpireStore(session = lineClearFixture())
        val sound = FakeSpireSoundPlayer()
        composeRule.setContent {
            RivenTheme {
                CelestialSpireGame(
                    externallyPaused = false,
                    storeOverride = store,
                    soundPlayerFactory = { sound },
                    initialSeed = 1L,
                )
            }
        }

        composeRule.onNodeWithTag("celestial_spire_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Soft drop piece").assertIsEnabled().performClick()
        settleCompose()

        assertEquals(
            listOf(SpireSoundCue.PIECE_LANDED, SpireSoundCue.LINE_CLEARED),
            sound.played,
        )
        assertEquals(1, store.savedSession?.linesCleared)
        composeRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun speedAndSoundSettingsOnlyChangeThroughTheirControls() {
        composeRule.mainClock.autoAdvance = false
        val store = FakeSpireStore(
            session = CelestialSpireEngine.newGame(seed = 22L),
            settings = CelestialSpireSettings(
                fallSpeed = SpireFallSpeed.CALM,
                soundVolume = 50,
                soundMuted = false,
            ),
        )
        val sound = FakeSpireSoundPlayer()
        composeRule.setContent {
            RivenTheme {
                CelestialSpireGame(
                    externallyPaused = false,
                    storeOverride = store,
                    soundPlayerFactory = { sound },
                )
            }
        }

        composeRule.onNodeWithTag("celestial_spire_speed").performClick()
        settleCompose()
        composeRule.onNodeWithText("Steady\n650 ms").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Lower game sound volume")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        settleCompose()
        assertEquals(40, store.savedSettings?.soundVolume)
        composeRule.onNodeWithContentDescription("Raise game sound volume")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        settleCompose()
        composeRule.onNodeWithContentDescription("Celestial Spire sound").performClick()
        settleCompose()

        assertEquals(SpireFallSpeed.STEADY, store.savedSettings?.fallSpeed)
        assertEquals(50, store.savedSettings?.soundVolume)
        assertTrue(store.savedSettings?.soundMuted == true)
        assertEquals(50 to true, sound.updates.last())
    }

    @Test
    fun largeTextKeepsBothVolumeDirectionsVisibleTappableAndFullSized() {
        composeRule.mainClock.autoAdvance = false
        val store = FakeSpireStore(
            session = CelestialSpireEngine.newGame(seed = 23L),
            settings = CelestialSpireSettings(soundVolume = 50),
        )
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 1.5f,
                ),
            ) {
                RivenTheme {
                    CelestialSpireGame(
                        externallyPaused = true,
                        storeOverride = store,
                        soundPlayerFactory = { FakeSpireSoundPlayer() },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Lower game sound volume")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        settleCompose()
        assertEquals(40, store.savedSettings?.soundVolume)
        composeRule.onNodeWithContentDescription("Raise game sound volume")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        settleCompose()
        assertEquals(50, store.savedSettings?.soundVolume)
    }

    @Test
    fun chatPauseDisablesPlayControlsWithoutChangingTheBoard() {
        val initial = CelestialSpireEngine.newGame(seed = 4L)
        val store = FakeSpireStore(session = initial)
        composeRule.setContent {
            RivenTheme {
                CelestialSpireGame(
                    externallyPaused = true,
                    storeOverride = store,
                    soundPlayerFactory = { FakeSpireSoundPlayer() },
                )
            }
        }

        composeRule.onNodeWithText("PAUSED FOR CHAT").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Move piece left").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Resume Celestial Spire").assertIsNotEnabled()
        assertEquals(initial, store.savedSession)
    }

    @Test
    fun manualPauseCanResumeAndLeavingSavesTheCurrentSession() {
        composeRule.mainClock.autoAdvance = false
        val store = FakeSpireStore(session = CelestialSpireEngine.newGame(seed = 73L))
        val sound = FakeSpireSoundPlayer()
        val showGame = mutableStateOf(true)
        composeRule.setContent {
            RivenTheme {
                if (showGame.value) {
                    CelestialSpireGame(
                        externallyPaused = false,
                        storeOverride = store,
                        soundPlayerFactory = { sound },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Pause Celestial Spire").performClick()
        settleCompose()
        composeRule.onNodeWithContentDescription("Resume Celestial Spire").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Move piece right").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Resume Celestial Spire").performClick()
        settleCompose()
        composeRule.onNodeWithContentDescription("Hard drop piece").performClick()
        settleCompose()
        composeRule.runOnIdle { showGame.value = false }
        settleCompose()

        assertEquals(1, store.savedSession?.piecesPlaced)
        assertTrue(sound.released)
    }

    @Test
    fun sameFrameMoveThenDisposeCannotOverwriteTheNewSession() {
        composeRule.mainClock.autoAdvance = false
        val store = FakeSpireStore(session = CelestialSpireEngine.newGame(seed = 74L))
        val showGame = mutableStateOf(true)
        composeRule.setContent {
            RivenTheme {
                if (showGame.value) {
                    CelestialSpireGame(
                        externallyPaused = false,
                        storeOverride = store,
                        soundPlayerFactory = { FakeSpireSoundPlayer() },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Hard drop piece").performClick()
        composeRule.runOnIdle { showGame.value = false }
        settleCompose()

        assertEquals(1, store.savedSession?.piecesPlaced)
    }

    @Test
    fun sameFrameSettingChangeThenDisposeCannotOverwriteTheNewSettings() {
        composeRule.mainClock.autoAdvance = false
        val store = FakeSpireStore(
            session = CelestialSpireEngine.newGame(seed = 75L),
            settings = CelestialSpireSettings(fallSpeed = SpireFallSpeed.CALM),
        )
        val showGame = mutableStateOf(true)
        composeRule.setContent {
            RivenTheme {
                if (showGame.value) {
                    CelestialSpireGame(
                        externallyPaused = false,
                        storeOverride = store,
                        soundPlayerFactory = { FakeSpireSoundPlayer() },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("celestial_spire_speed").performClick()
        composeRule.runOnIdle { showGame.value = false }
        settleCompose()

        assertEquals(SpireFallSpeed.STEADY, store.savedSettings?.fallSpeed)
    }

    @Test
    fun lifecycleStopPausesUntilTheHostResumes() {
        composeRule.mainClock.autoAdvance = false
        val initial = CelestialSpireEngine.newGame(seed = 91L)
        val store = FakeSpireStore(session = initial)
        val lifecycleOwner = MutableLifecycleOwner()
        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                RivenTheme {
                    CelestialSpireGame(
                        externallyPaused = false,
                        storeOverride = store,
                        soundPlayerFactory = { FakeSpireSoundPlayer() },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Pause Celestial Spire").assertIsEnabled()
        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.CREATED) }
        settleCompose()
        composeRule.onNodeWithContentDescription("Resume Celestial Spire").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Move piece left").assertIsNotEnabled()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.runOnIdle { }
        assertEquals(initial, store.savedSession)

        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        settleCompose()
        composeRule.onNodeWithContentDescription("Pause Celestial Spire").assertIsEnabled()
    }

    private fun settleCompose() {
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeBy(100L, ignoreFrameDuration = false)
        composeRule.runOnIdle { }
    }

    private fun lineClearFixture(): CelestialSpireState {
        val board = MutableList<SpirePieceType?>(CELESTIAL_SPIRE_COLUMNS * CELESTIAL_SPIRE_ROWS) { null }
        repeat(8) { column ->
            board[(CELESTIAL_SPIRE_ROWS - 1) * CELESTIAL_SPIRE_COLUMNS + column] = SpirePieceType.J
        }
        return CelestialSpireState(
            board = board,
            active = SpirePiece(SpirePieceType.O, rotation = 0, x = 7, y = 18),
            next = SpirePieceType.T,
            bag = listOf(SpirePieceType.S, SpirePieceType.Z),
            randomState = 11L,
        )
    }
}

private class MutableLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

private class FakeSpireStore(
    session: CelestialSpireState? = null,
    settings: CelestialSpireSettings = CelestialSpireSettings(),
) : CelestialSpireStore {
    private var loadedSession = session
    private var loadedSettings = settings
    var savedSession: CelestialSpireState? = session
        private set
    var savedSettings: CelestialSpireSettings? = settings
        private set

    override fun loadSession(): CelestialSpireState? = loadedSession

    override fun saveSession(state: CelestialSpireState) {
        savedSession = state
        loadedSession = state
    }

    override fun loadSettings(): CelestialSpireSettings = loadedSettings

    override fun saveSettings(settings: CelestialSpireSettings) {
        savedSettings = settings
        loadedSettings = settings
    }
}

private class FakeSpireSoundPlayer : CelestialSpireSoundPlayer {
    val played = mutableListOf<SpireSoundCue>()
    val updates = mutableListOf<Pair<Int, Boolean>>()
    var released: Boolean = false
        private set

    override fun update(volume: Int, muted: Boolean) {
        updates += volume to muted
    }

    override fun play(cue: SpireSoundCue) {
        played += cue
    }

    override fun release() {
        released = true
    }
}
