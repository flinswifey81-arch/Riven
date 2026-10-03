package com.shai.riven.ui.arcade.cosmic

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmicOpponentExecutionTest {
    @Test
    fun mutationThrowBlockingAndIllegalActionsEachUseOneValidatedFallback() = runBlocking {
        val initial = rivenTurnSession(seed = 801L)
        val shaiCardId = initial.game.hand(CosmicPlayer.SHAI).first().id
        val agents = listOf(
            TestAgent(choose = { observation ->
                (observation.ownHand as MutableList<CosmicCard>).clear()
                CosmicAction.DrawCard
            }),
            TestAgent(choose = { throw IllegalStateException("agent failure") }),
            TestAgent(choose = {
                try {
                    Thread.sleep(5_000L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
                CosmicAction.DrawCard
            }),
            TestAgent(choose = { CosmicAction.PlayCard(shaiCardId) }),
        )

        agents.forEach { agent ->
            CosmicOpponentExecution(
                actionTimeoutMillis = 35L,
                commentaryTimeoutMillis = 35L,
            ).use { execution ->
                val outcome = CosmicOpponentTurnRunner(execution).run(initial, agent)
                assertNotNull(outcome)
                requireNotNull(outcome)
                assertTrue(outcome.usedFallback)
                assertTrue(outcome.actionResult.changed)
                assertTrue(outcome.notice.startsWith("Offline fallback:"))
                assertEquals(initial.game.revision + 1L, outcome.actionResult.session.game.revision)
                assertEquals(1, agent.actionCalls.get())
                assertTrue(CosmicMischiefEngine.isValid(outcome.actionResult.session.game))
            }
            assertEquals(CosmicPlayer.RIVEN, CosmicMischiefEngine.decisionPlayer(initial.game))
            assertEquals(52, allCards(initial.game).size)
        }
    }

    @Test
    fun throwingOrBlockingCommentaryCannotUndoAcceptedActionOrBlockTurn() = runBlocking {
        val initial = rivenTurnSession(seed = 802L)
        val agents = listOf(
            TestAgent(
                choose = { CosmicAction.DrawCard },
                speak = { _, _ -> throw IllegalArgumentException("commentary failure") },
            ),
            TestAgent(
                choose = { CosmicAction.DrawCard },
                speak = { _, _ ->
                    try {
                        Thread.sleep(5_000L)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw interrupted
                    }
                    "too late"
                },
            ),
        )

        agents.forEach { agent ->
            CosmicOpponentExecution(
                actionTimeoutMillis = 35L,
                commentaryTimeoutMillis = 35L,
            ).use { execution ->
                val outcome = CosmicOpponentTurnRunner(execution).run(initial, agent)
                assertNotNull(outcome)
                requireNotNull(outcome)
                assertFalse(outcome.usedFallback)
                assertTrue(outcome.actionResult.changed)
                assertEquals(outcome.actionResult.message, outcome.notice)
                assertEquals(1, agent.actionCalls.get())
                assertEquals(1, agent.commentaryCalls.get())
                assertTrue(CosmicMischiefEngine.isValid(outcome.actionResult.session.game))
            }
        }
    }

    private fun rivenTurnSession(seed: Long): CosmicMischiefSession {
        val state = CosmicMischiefEngine.newGame(seed)
        val result = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(CosmicPlayer.SHAI, state.revision, CosmicAction.DrawCard),
        )
        require(result.changed)
        require(CosmicMischiefEngine.decisionPlayer(result.session.game) == CosmicPlayer.RIVEN)
        return result.session
    }

    private fun allCards(state: CosmicMischiefState): List<CosmicCard> =
        state.drawPile + state.discardPile + state.hands.flatten()

    private class TestAgent(
        private val choose: (CosmicOpponentObservation) -> CosmicAction,
        private val speak: (CosmicPublicObservation, CosmicEvent) -> String? = { _, event -> event.message },
    ) : CosmicOpponentAgent {
        override val displayName: String = "Adversarial test agent"
        val actionCalls = AtomicInteger()
        val commentaryCalls = AtomicInteger()

        override fun chooseAction(observation: CosmicOpponentObservation): CosmicAction {
            actionCalls.incrementAndGet()
            return choose(observation)
        }

        override fun commentary(
            observation: CosmicPublicObservation,
            event: CosmicEvent,
        ): String? {
            commentaryCalls.incrementAndGet()
            return speak(observation, event)
        }
    }
}
