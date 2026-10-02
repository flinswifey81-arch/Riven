package com.shai.riven.ui.arcade.solitaire

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class MidnightSolitaireComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tableUsesReadableTargetsAndExistingCardBack() {
        val store = FakeMidnightSolitaireStore(
            session = MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 10L)),
        )
        composeRule.setContent {
            RivenTheme {
                MidnightSolitaireGame(
                    externallyPaused = false,
                    compactLayout = true,
                    storeOverride = store,
                )
            }
        }

        composeRule.onNodeWithTag("midnight_solitaire_board").assertIsDisplayed()
        composeRule.onNodeWithTag("solitaire_stock")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(64.dp)
        composeRule.onNodeWithContentDescription("Stock, 24 cards. Draw one.")
            .performClick()
        composeRule.waitForIdle()

        assertEquals(23, store.savedSession?.game?.stock?.size)
        assertEquals(1, store.savedSession?.game?.waste?.size)
    }

    @Test
    fun selectedSequenceIdentityTargetSuitAndLiveNoticeAreExplicit() {
        val original = selectionFixtureSession()
        val store = FakeMidnightSolitaireStore(session = original)
        composeRule.setContent {
            RivenTheme {
                MidnightSolitaireGame(
                    externallyPaused = false,
                    storeOverride = store,
                )
            }
        }

        composeRule.onNodeWithTag("solitaire_tableau_0_0")
            .assertIsNotSelected()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("solitaire_tableau_0_0").assertIsSelected()
        assertEquals(
            "Selected. Face up, 2 card sequence",
            composeRule.onNodeWithTag("solitaire_tableau_0_0")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        assertEquals(
            LiveRegionMode.Polite,
            composeRule.onNodeWithTag("solitaire_notice")
                .fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )

        composeRule.onNodeWithContentDescription("Move selected card to its matching foundation")
            .performClick()
        composeRule.waitForIdle()
        assertEquals(original.game, store.savedSession?.game)
        composeRule.onNodeWithTag("solitaire_tableau_0_0").assertIsSelected()

        composeRule.onNodeWithTag("solitaire_tableau_0_1").performClick().assertIsSelected()
        composeRule.onNodeWithContentDescription("hearts foundation empty.").performClick()
        composeRule.waitForIdle()
        assertEquals(original.game, store.savedSession?.game)
        composeRule.onNodeWithTag("solitaire_tableau_0_1").assertIsSelected()

        composeRule.onNodeWithContentDescription("clubs foundation empty.").performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf(SolitaireCard(SolitaireSuit.CLUBS, 1)),
            store.savedSession?.game?.foundations?.get(SolitaireSuit.CLUBS.ordinal),
        )
        assertEquals(1, store.savedSession?.game?.tableau?.first()?.size)
    }

    @Test
    fun chatPauseDisablesMovesAndPreservesThePosition() {
        val original = MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 11L))
        val store = FakeMidnightSolitaireStore(session = original)
        composeRule.setContent {
            RivenTheme {
                MidnightSolitaireGame(
                    externallyPaused = true,
                    storeOverride = store,
                )
            }
        }

        composeRule.onNodeWithText("PAUSED FOR CHAT").assertIsDisplayed()
        composeRule.onNodeWithTag("solitaire_stock").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Resume Midnight Solitaire").assertIsNotEnabled()
        assertEquals(original, store.savedSession)
    }

    @Test
    fun freshDealRequiresConfirmationAndExplainsSolvabilityTruthfully() {
        val original = MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 12L))
        val store = FakeMidnightSolitaireStore(session = original)
        composeRule.setContent {
            RivenTheme {
                MidnightSolitaireGame(
                    externallyPaused = false,
                    storeOverride = store,
                )
            }
        }

        composeRule.onNodeWithText("NEW DEAL").performClick()
        composeRule.onNodeWithText("Start a fresh deal?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Your current position and undo history will be replaced. Fresh standard Klondike deals are not guaranteed solvable, and there is no penalty for choosing another.",
        ).assertIsDisplayed()
        assertEquals(original.game, store.savedSession?.game)

        composeRule.onNodeWithText("Fresh deal").performClick()
        composeRule.waitForIdle()

        assertEquals(2, store.savedSession?.game?.dealNumber)
        assertTrue(store.savedSession?.undoStack?.isEmpty() == true)
    }

    @Test
    fun largeTextModePersistsAndKeepsPrimaryTargetsFullSized() {
        val store = FakeMidnightSolitaireStore(
            session = MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 13L)),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.5f),
            ) {
                RivenTheme {
                    MidnightSolitaireGame(
                        externallyPaused = false,
                        compactLayout = true,
                        storeOverride = store,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Toggle large Solitaire card text").performClick()
        composeRule.waitForIdle()

        assertEquals(MidnightSolitaireSettings(largeCardText = true), store.savedSettings)
        composeRule.onNodeWithTag("solitaire_stock")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(64.dp)
    }
}

private class FakeMidnightSolitaireStore(
    private val session: MidnightSolitaireSession? = null,
    private val settings: MidnightSolitaireSettings = MidnightSolitaireSettings(),
) : MidnightSolitaireStore {
    var savedSession: MidnightSolitaireSession? = session
    var savedSettings: MidnightSolitaireSettings? = settings

    override fun loadSession(): MidnightSolitaireSession? = session

    override fun saveSession(session: MidnightSolitaireSession) {
        savedSession = session
    }

    override fun loadSettings(): MidnightSolitaireSettings = settings

    override fun saveSettings(settings: MidnightSolitaireSettings) {
        savedSettings = settings
    }
}

private fun selectionFixtureSession(): MidnightSolitaireSession {
    val buriedHeartTwo = SolitaireCard(SolitaireSuit.HEARTS, 2)
    val topClubAce = SolitaireCard(SolitaireSuit.CLUBS, 1)
    val tableauCards = listOf(
        SolitaireTableauCard(buriedHeartTwo, faceUp = true),
        SolitaireTableauCard(topClubAce, faceUp = true),
    )
    val placed = tableauCards.map(SolitaireTableauCard::card).toSet()
    val stock = SolitaireSuit.entries.flatMap { suit ->
        (1..13).map { rank -> SolitaireCard(suit, rank) }
    }.filterNot(placed::contains)
    val state = MidnightSolitaireState(
        stock = stock,
        waste = emptyList(),
        foundations = List(4) { emptyList() },
        tableau = listOf(tableauCards) + List(6) { emptyList() },
        dealSeed = 88L,
    )
    check(MidnightSolitaireEngine.isValid(state))
    return MidnightSolitaireSession(state)
}
