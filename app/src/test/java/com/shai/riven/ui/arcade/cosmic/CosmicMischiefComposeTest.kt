package com.shai.riven.ui.arcade.cosmic

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.shai.riven.ui.theme.RivenTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class CosmicMischiefComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun offlineTableHidesOpponentCardsAndExposesAccessibleUserControls() {
        val session = CosmicMischiefSession(CosmicMischiefEngine.newGame(seed = 120L))
        val store = FakeCosmicStore(session)
        val firstCard = session.game.hand(CosmicPlayer.SHAI).first()
        composeRule.setContent {
            RivenTheme {
                CosmicMischiefGame(
                    externallyWaiting = false,
                    storeOverride = store,
                    opponentOverride = PassiveOpponent,
                )
            }
        }

        composeRule.onNodeWithTag("cosmic_mischief_board").assertIsDisplayed()
        composeRule.onNodeWithText("Offline rival • NOT LIVE RIVEN • 7 CARDS").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Face-down opponent card").assertCountEquals(7)
        composeRule.onNodeWithContentDescription("Face-down draw pile").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play selected Cosmic Mischief card").assertIsNotEnabled()
        composeRule.onNodeWithTag("cosmic_user_card_${firstCard.id}")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithContentDescription("Play selected Cosmic Mischief card")
            .assertHasClickAction()
        composeRule.onNodeWithText("PROVISIONAL DEFAULTS", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "DECK: 52 = 4 colors x (0-9 + 1 Eclipse skip + 1 Double Trouble draw 2) + 4 Rewrite the Stars wilds",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "primary stays discarded but its effect is cancelled",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "TRADE: face-up offered card for one return chosen privately by the responder",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "BETRAY: responder keeps the offered gift and proposer draws 1 available consolation card",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun observableRivenTellOffersUntimedCalloutAndDisplaysCaughtResult() {
        val base = rigState(
            shaiIds = listOf(3, 31),
            rivenIds = listOf(1, 17, 22),
            topId = 5,
            turn = CosmicPlayer.RIVEN,
        )
        val cheated = CosmicMischiefEngine.apply(
            CosmicMischiefSession(base),
            CosmicCommand(
                CosmicPlayer.RIVEN,
                base.revision,
                CosmicAction.SneakExtraDiscard(primaryCardId = 1, extraCardId = 17),
            ),
        ).session
        val store = FakeCosmicStore(cheated)
        composeRule.setContent {
            RivenTheme {
                CosmicMischiefGame(
                    externallyWaiting = false,
                    storeOverride = store,
                    opponentOverride = PassiveOpponent,
                )
            }
        }

        composeRule.onNodeWithTag("cosmic_cheat_tell")
            .assertTextContains("Call it out or let it slide", substring = true)
        composeRule.onNodeWithContentDescription("Call out the observable extra-card tell")
            .performClick()
        composeRule.waitForIdle()

        assertNull(requireNotNull(store.savedSession).game.pendingCheat)
        assertTrue(requireNotNull(store.savedSession).game.hand(CosmicPlayer.RIVEN).any { it.id == 17 })
        composeRule.onNodeWithTag("cosmic_notice")
            .assertTextContains("Caught:", substring = true)
    }

    @Test
    fun freshGameRequiresConfirmationAndCarriesPlayfulGrudges() {
        val initial = CosmicMischiefSession(
            CosmicMischiefEngine.newGame(seed = 4L, gameNumber = 2, grudges = listOf(3, 1)),
        )
        val store = FakeCosmicStore(initial)
        composeRule.setContent {
            RivenTheme {
                CosmicMischiefGame(
                    externallyWaiting = false,
                    storeOverride = store,
                    opponentOverride = PassiveOpponent,
                )
            }
        }

        composeRule.onNodeWithText("FRESH GAME").performScrollTo().performClick()
        composeRule.onNodeWithText("Start a fresh Cosmic Mischief game?").assertIsDisplayed()
        assertEquals(initial, store.savedSession)
        composeRule.onNodeWithText("START FRESH").performClick()
        composeRule.waitForIdle()

        assertEquals(3, store.savedSession?.game?.gameNumber)
        assertEquals(listOf(3, 1), store.savedSession?.game?.grudges)
    }

    @Test
    fun chatWaitBlocksSharedActionsWithoutMutatingTheGame() {
        val initial = CosmicMischiefSession(CosmicMischiefEngine.newGame(seed = 70L))
        val store = FakeCosmicStore(initial)
        composeRule.setContent {
            RivenTheme {
                CosmicMischiefGame(
                    externallyWaiting = true,
                    storeOverride = store,
                    opponentOverride = PassiveOpponent,
                )
            }
        }

        composeRule.onNodeWithText("WAITING FOR CHAT").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Draw one card and yield the turn").assertIsNotEnabled()
        assertEquals(initial, store.savedSession)
    }

    @Test
    fun invalidInjectedOpponentActionUsesOneDeterministicValidatedFallback() {
        val initial = CosmicMischiefSession(
            rigState(
                shaiIds = listOf(1, 22),
                rivenIds = listOf(3, 31),
                topId = 5,
                turn = CosmicPlayer.RIVEN,
            ),
        )
        val store = FakeCosmicStore(initial)
        val invalid = object : CosmicOpponentAgent {
            override val displayName = "Injected test opponent"

            override fun chooseAction(observation: CosmicOpponentObservation): CosmicAction =
                CosmicAction.PlayCard(cardId = 1)

            override fun commentary(
                observation: CosmicPublicObservation,
                event: CosmicEvent,
            ): String = "This should never apply."
        }
        composeRule.setContent {
            RivenTheme {
                CosmicMischiefGame(
                    externallyWaiting = false,
                    storeOverride = store,
                    opponentOverride = invalid,
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            requireNotNull(store.savedSession).game.revision > initial.game.revision
        }

        val recovered = requireNotNull(store.savedSession).game
        assertEquals(CosmicPlayer.SHAI, CosmicMischiefEngine.decisionPlayer(recovered))
        assertTrue(recovered != initial.game)
        composeRule.onNodeWithTag("cosmic_notice")
            .assertTextContains("Offline fallback:", substring = true)
    }

    @Test
    fun confirmedFreshGameCancelsBlockingOpponentAndDropsItsStaleAction() {
        val initial = CosmicMischiefSession(
            rigState(
                shaiIds = listOf(1, 22),
                rivenIds = listOf(3, 31),
                topId = 5,
                turn = CosmicPlayer.RIVEN,
            ),
        )
        val store = FakeCosmicStore(initial)
        val blocking = IgnoringBlockingOpponent()
        composeRule.setContent {
            RivenTheme {
                CosmicMischiefGame(
                    externallyWaiting = false,
                    storeOverride = store,
                    opponentOverride = blocking,
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { blocking.actionCalls.get() == 1 }

        composeRule.onNodeWithText("FRESH GAME").performScrollTo().performClick()
        composeRule.onNodeWithText("START FRESH").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            requireNotNull(store.savedSession).game.gameNumber == initial.game.gameNumber + 1
        }
        val fresh = requireNotNull(store.savedSession)
        Thread.sleep(COSMIC_OPPONENT_ACTION_TIMEOUT_MILLIS + 250L)
        composeRule.waitForIdle()

        assertEquals(fresh, store.savedSession)
        assertEquals(0L, fresh.game.revision)
        assertEquals(CosmicPlayer.SHAI, fresh.game.turn)
        assertEquals(1, blocking.actionCalls.get())
    }

    private fun rigState(
        shaiIds: List<Int>,
        rivenIds: List<Int>,
        topId: Int,
        turn: CosmicPlayer,
    ): CosmicMischiefState {
        val placed = (shaiIds + rivenIds + topId).toSet()
        val top = requireNotNull(CosmicCard.fromId(topId))
        return CosmicMischiefState(
            drawPile = CosmicCard.fullDeck.filterNot { it.id in placed },
            discardPile = listOf(top),
            hands = listOf(
                shaiIds.map { requireNotNull(CosmicCard.fromId(it)) },
                rivenIds.map { requireNotNull(CosmicCard.fromId(it)) },
            ),
            activeColor = requireNotNull(top.color),
            turn = turn,
            dealSeed = 12L,
            randomState = 13L,
        ).also { require(CosmicMischiefEngine.isValid(it)) }
    }
}

private object PassiveOpponent : CosmicOpponentAgent {
    override val displayName: String = "Offline rival"

    override fun chooseAction(observation: CosmicOpponentObservation): CosmicAction = CosmicAction.DrawCard

    override fun commentary(
        observation: CosmicPublicObservation,
        event: CosmicEvent,
    ): String = event.message
}

private class IgnoringBlockingOpponent : CosmicOpponentAgent {
    override val displayName: String = "Blocking test opponent"
    val actionCalls = AtomicInteger()

    override fun chooseAction(observation: CosmicOpponentObservation): CosmicAction {
        actionCalls.incrementAndGet()
        val deadline = System.nanoTime() + 700_000_000L
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L)
            } catch (_: InterruptedException) {
                // Deliberately ignore interruption to prove stale work cannot commit after a fresh game.
            }
        }
        return CosmicAction.DrawCard
    }

    override fun commentary(
        observation: CosmicPublicObservation,
        event: CosmicEvent,
    ): String = "Stale commentary must not appear."
}

private class FakeCosmicStore(
    session: CosmicMischiefSession,
    settings: CosmicMischiefSettings = CosmicMischiefSettings(),
) : CosmicMischiefStore {
    var savedSession: CosmicMischiefSession? = session
        private set
    private var savedSettings: CosmicMischiefSettings = settings

    override fun loadSession(): CosmicMischiefSession? = savedSession

    override fun saveSession(session: CosmicMischiefSession) {
        savedSession = session
    }

    override fun loadSettings(): CosmicMischiefSettings = savedSettings

    override fun saveSettings(settings: CosmicMischiefSettings) {
        savedSettings = settings
    }
}
