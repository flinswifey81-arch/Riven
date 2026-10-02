package com.shai.riven.ui.arcade.starstruck

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shai.riven.ui.theme.RivenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class StarstruckComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun adjacentTileClicksResolveAndPersistLegalMove() {
        composeRule.mainClock.autoAdvance = false
        val initial = StarstruckEngine.newGame(seed = 61L)
        val move = StarstruckEngine.legalMoves(initial.board).first()
        val store = FakeStarstruckStore(session = initial)
        val sound = FakeStarstruckSoundPlayer()
        setGame(store = store, sound = sound)

        click(move.first)
        click(move.second)
        composeRule.runOnIdle { }

        assertEquals(1, store.savedSession?.movesMade)
        assertTrue(sound.played.contains(StarstruckSoundCue.SWAP))
        assertFalse(StarstruckEngine.hasImmediateMatches(requireNotNull(store.savedSession).board))
    }

    @Test
    fun invalidSwapRestoresBoardAndExplainsGently() {
        val initial = StarstruckEngine.newGame(seed = 62L)
        val move = firstInvalidAdjacentSwap(initial.board)
        assertFalse(StarstruckEngine.swap(initial, move.first, move.second).accepted)
        val store = FakeStarstruckStore(session = initial)
        setGame(store = store)

        click(move.first)
        composeRule.runOnIdle { }
        click(move.second)
        composeRule.runOnIdle { }

        assertEquals(initial, store.savedSession)
        composeRule.onNodeWithTag("starstruck_status")
            .assertTextContains("No match there", substring = true)
    }

    @Test
    fun chatPauseDisablesBoardAndPreservesSession() {
        val initial = StarstruckEngine.newGame(seed = 63L)
        val store = FakeStarstruckStore(session = initial)
        setGame(store = store, externallyPaused = true)

        composeRule.onNodeWithText("PAUSED FOR CHAT").assertIsDisplayed()
        composeRule.onNodeWithTag("starstruck_tile_0_0").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Pause Starstruck").assertIsNotEnabled()
        assertEquals(initial, store.savedSession)
    }

    @Test
    fun settingsPauseBoardAndPersistIndependentSoundAndEffects() {
        val initial = StarstruckEngine.newGame(seed = 64L)
        val store = FakeStarstruckStore(session = initial)
        val sound = FakeStarstruckSoundPlayer()
        setGame(store = store, sound = sound)

        composeRule.onNodeWithContentDescription("Open Starstruck settings").performClick()
        composeRule.onNodeWithContentDescription("Starstruck sound").performClick()
        composeRule.onNodeWithContentDescription("Starstruck gentle effects").performClick()
        composeRule.onNodeWithText("LOUDER").performClick()
        composeRule.onNodeWithText("DONE").performClick()
        composeRule.runOnIdle { }

        assertTrue(store.savedSettings?.soundMuted == true)
        assertFalse(requireNotNull(store.savedSettings).gentleEffects)
        assertEquals(55, store.savedSettings?.soundVolume)
        assertEquals(55, sound.volume)
        assertTrue(sound.muted)
        assertEquals(initial, store.savedSession)
    }

    @Test
    fun lifecycleStopPersistsAndDisablesPlayUntilResume() {
        val initial = StarstruckEngine.newGame(seed = 65L)
        val store = FakeStarstruckStore(session = initial)
        val lifecycleOwner = MutableStarstruckLifecycleOwner()
        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                RivenTheme {
                    StarstruckGame(
                        externallyPaused = false,
                        storeOverride = store,
                        soundPlayerFactory = { FakeStarstruckSoundPlayer() },
                    )
                }
            }
        }

        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.CREATED) }
        composeRule.onNodeWithText("PAUSED WHILE AWAY").assertIsDisplayed()
        composeRule.onNodeWithTag("starstruck_tile_0_0").assertIsNotEnabled()
        assertEquals(initial, store.savedSession)

        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        composeRule.onNodeWithTag("starstruck_tile_0_0").assertIsEnabled()
    }

    @Test
    fun sameFrameMoveThenDisposeCannotOverwriteNewSession() {
        composeRule.mainClock.autoAdvance = false
        val initial = StarstruckEngine.newGame(seed = 66L)
        val move = StarstruckEngine.legalMoves(initial.board).first()
        val store = FakeStarstruckStore(session = initial)
        val showGame = mutableStateOf(true)
        composeRule.setContent {
            RivenTheme {
                if (showGame.value) {
                    StarstruckGame(
                        externallyPaused = false,
                        storeOverride = store,
                        soundPlayerFactory = { FakeStarstruckSoundPlayer() },
                    )
                }
            }
        }

        click(move.first)
        click(move.second)
        composeRule.runOnIdle { showGame.value = false }

        assertEquals(1, store.savedSession?.movesMade)
    }

    @Test
    fun sameFrameSettingThenDisposeCannotOverwriteSoundChoice() {
        val store = FakeStarstruckStore(session = StarstruckEngine.newGame(seed = 67L))
        val showGame = mutableStateOf(true)
        composeRule.setContent {
            RivenTheme {
                if (showGame.value) {
                    StarstruckGame(
                        externallyPaused = false,
                        storeOverride = store,
                        soundPlayerFactory = { FakeStarstruckSoundPlayer() },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Open Starstruck settings").performClick()
        composeRule.onNodeWithContentDescription("Starstruck sound").performClick()
        composeRule.runOnIdle { showGame.value = false }

        assertTrue(store.savedSettings?.soundMuted == true)
    }

    @Test
    fun compactLayoutKeepsBoardAndBothControlsVisibleTogether() {
        val store = FakeStarstruckStore(session = StarstruckEngine.newGame(seed = 68L))
        setGame(store = store, compactLayout = true)

        composeRule.onNodeWithTag("starstruck_compact").assertIsDisplayed()
        composeRule.onNodeWithTag("starstruck_board").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Starstruck settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Starstruck").assertIsDisplayed()
    }

    private fun setGame(
        store: FakeStarstruckStore,
        sound: FakeStarstruckSoundPlayer = FakeStarstruckSoundPlayer(),
        externallyPaused: Boolean = false,
        compactLayout: Boolean = false,
    ) {
        composeRule.setContent {
            RivenTheme {
                StarstruckGame(
                    externallyPaused = externallyPaused,
                    compactLayout = compactLayout,
                    storeOverride = store,
                    soundPlayerFactory = { sound },
                )
            }
        }
    }

    private fun click(point: StarPoint) {
        composeRule.onNodeWithTag("starstruck_tile_${point.x}_${point.y}").performClick()
    }

    private fun firstInvalidAdjacentSwap(board: List<StarTile>): Pair<StarPoint, StarPoint> {
        val legal = StarstruckEngine.legalMoves(board).toSet()
        for (y in 0 until STARSTRUCK_ROWS) {
            for (x in 0 until STARSTRUCK_COLUMNS - 1) {
                val move = StarPoint(x, y) to StarPoint(x + 1, y)
                if (move !in legal) return move
            }
        }
        error("Expected invalid move")
    }
}

private class MutableStarstruckLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

private class FakeStarstruckStore(
    session: StarstruckState? = null,
    settings: StarstruckSettings = StarstruckSettings(),
) : StarstruckStore {
    private var loadedSession = session
    private var loadedSettings = settings
    var savedSession: StarstruckState? = session
        private set
    var savedSettings: StarstruckSettings? = settings
        private set

    override fun loadSession(): StarstruckState? = loadedSession

    override fun saveSession(state: StarstruckState) {
        savedSession = state
        loadedSession = state
    }

    override fun loadSettings(): StarstruckSettings = loadedSettings

    override fun saveSettings(settings: StarstruckSettings) {
        savedSettings = settings
        loadedSettings = settings
    }
}

private class FakeStarstruckSoundPlayer : StarstruckSoundPlayer {
    var volume = -1
        private set
    var muted = false
        private set
    val played = mutableListOf<StarstruckSoundCue>()

    override fun update(volume: Int, muted: Boolean) {
        this.volume = volume
        this.muted = muted
    }

    override fun play(cue: StarstruckSoundCue) {
        played += cue
    }

    override fun release() = Unit
}
