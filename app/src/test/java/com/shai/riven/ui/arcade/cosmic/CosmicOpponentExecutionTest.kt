package com.shai.riven.ui.arcade.cosmic

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmicOpponentExecutionTest {
    @Test
    fun repeatedScopesShareTwoWorkersAndFailClosedWhenNoncooperativeCallbacksExhaustThem() = runBlocking {
        assertTrue(waitUntil { cosmicOpponentExecutorStats().activeWorkers == 0 })
        val initial = rivenTurnSession(seed = 800L)
        val observation = CosmicMischiefEngine.opponentObservation(initial.game)
        val started = CountDownLatch(COSMIC_OPPONENT_GLOBAL_WORKERS)
        val release = CountDownLatch(1)
        val blockingAgent = TestAgent(choose = {
            started.countDown()
            while (true) {
                try {
                    if (release.await(20L, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    // Deliberately noncooperative. The process-wide worker bound is the containment.
                }
            }
            CosmicAction.DrawCard
        })
        val blockingScopes = List(COSMIC_OPPONENT_GLOBAL_WORKERS) {
            CosmicOpponentExecution(actionTimeoutMillis = 5_000L, commentaryTimeoutMillis = 20L)
        }
        val blockingJobs = blockingScopes.map { execution ->
            async(Dispatchers.Default) { execution.chooseAction(blockingAgent, observation) }
        }

        try {
            assertTrue(started.await(2L, TimeUnit.SECONDS))
            blockingScopes.forEach(CosmicOpponentExecution::close)
            withTimeout(2_000L) { blockingJobs.awaitAll() }

            repeat(8) {
                CosmicOpponentExecution(
                    actionTimeoutMillis = 25L,
                    commentaryTimeoutMillis = 25L,
                ).use { execution ->
                    val outcome = CosmicOpponentTurnRunner(execution).run(
                        initial,
                        TestAgent(choose = { CosmicAction.DrawCard }),
                    )
                    assertNotNull(outcome)
                    requireNotNull(outcome)
                    assertTrue(outcome.usedFallback)
                    assertTrue(outcome.actionResult.changed)
                }
            }

            val exhausted = cosmicOpponentExecutorStats()
            assertEquals(COSMIC_OPPONENT_GLOBAL_WORKERS, exhausted.activeWorkers)
            assertTrue(exhausted.poolSize <= COSMIC_OPPONENT_GLOBAL_WORKERS)
            assertTrue(exhausted.largestPoolSize <= COSMIC_OPPONENT_GLOBAL_WORKERS)
            assertEquals(0, exhausted.queuedTasks)
        } finally {
            release.countDown()
            blockingScopes.forEach(CosmicOpponentExecution::close)
            blockingJobs.forEach { it.cancel() }
        }
        assertTrue(waitUntil { cosmicOpponentExecutorStats().activeWorkers == 0 })
    }

    @Test
    fun pauseAndResumeInvalidateAnOlderTurnPermit() {
        val gate = CosmicOpponentTurnGate()
        val oldPermit = requireNotNull(gate.acquirePermit())

        gate.pause()
        assertNull(gate.commitIfPermitted(oldPermit) { "stale" })
        gate.updatePaused(isPaused = false)

        assertNull(gate.commitIfPermitted(oldPermit) { "stale" })
        assertNotNull(gate.acquirePermit())
    }

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

    private fun waitUntil(
        timeoutMillis: Long = 3_000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }

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
