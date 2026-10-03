package com.shai.riven.ui.arcade.cosmic

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

const val COSMIC_OPPONENT_ACTION_TIMEOUT_MILLIS = 500L
const val COSMIC_OPPONENT_COMMENTARY_TIMEOUT_MILLIS = 350L
const val COSMIC_OPPONENT_COMMENTARY_LIMIT = 240

/**
 * Executes untrusted opponent callbacks away from the UI thread. The worker and queue counts are
 * deliberately bounded; a callback that ignores interruption cannot create an unbounded thread or
 * retry loop.
 */
class CosmicOpponentExecution(
    private val actionTimeoutMillis: Long = COSMIC_OPPONENT_ACTION_TIMEOUT_MILLIS,
    private val commentaryTimeoutMillis: Long = COSMIC_OPPONENT_COMMENTARY_TIMEOUT_MILLIS,
    workerCount: Int = 2,
) : AutoCloseable {
    private val threadNumber = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        workerCount,
        workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(workerCount),
        { runnable ->
            Thread(runnable, "cosmic-opponent-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    suspend fun chooseAction(
        agent: CosmicOpponentAgent,
        observation: CosmicOpponentObservation,
    ): CosmicAction? = invokeBounded(actionTimeoutMillis) { agent.chooseAction(observation) }

    suspend fun commentary(
        agent: CosmicOpponentAgent,
        observation: CosmicPublicObservation,
        event: CosmicEvent,
    ): String? = invokeBounded(commentaryTimeoutMillis) { agent.commentary(observation, event) }

    private suspend fun <T> invokeBounded(timeoutMillis: Long, block: () -> T): T? {
        val future = try {
            executor.submit(Callable(block))
        } catch (_: RejectedExecutionException) {
            return null
        }
        return try {
            runInterruptible(Dispatchers.IO) {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            }
        } catch (cancelled: CancellationException) {
            future.cancel(true)
            throw cancelled
        } catch (_: Exception) {
            future.cancel(true)
            null
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

data class CosmicOpponentTurnOutcome(
    val actionResult: CosmicActionResult,
    val notice: String,
    val usedFallback: Boolean,
)

/** Runs at most one injected action and one trusted deterministic fallback action. */
class CosmicOpponentTurnRunner(
    private val execution: CosmicOpponentExecution,
) {
    private val fallback: CosmicOpponentAgent = DeterministicCosmicOpponentAgent()

    suspend fun run(
        session: CosmicMischiefSession,
        agent: CosmicOpponentAgent,
    ): CosmicOpponentTurnOutcome? {
        val state = session.game
        if (
            state.status != CosmicStatus.PLAYING ||
            CosmicMischiefEngine.decisionPlayer(state) != CosmicPlayer.RIVEN
        ) {
            return null
        }

        val observation = CosmicMischiefEngine.opponentObservation(state)
        val injectedAction = execution.chooseAction(agent, observation)
        var actionResult = injectedAction?.let { action ->
            CosmicMischiefEngine.apply(
                session,
                CosmicCommand(CosmicPlayer.RIVEN, state.revision, action),
            )
        }
        val usedFallback = actionResult?.changed != true
        val commentaryAgent = if (usedFallback) fallback else agent

        if (usedFallback) {
            val fallbackAction = fallback.chooseAction(CosmicMischiefEngine.opponentObservation(state))
            actionResult = CosmicMischiefEngine.apply(
                session,
                CosmicCommand(CosmicPlayer.RIVEN, state.revision, fallbackAction),
            )
        }
        val accepted = actionResult?.takeIf(CosmicActionResult::changed) ?: return null
        val latestEvent = accepted.events.lastOrNull()
        val spoken = latestEvent?.let { event ->
            execution.commentary(
                commentaryAgent,
                CosmicMischiefEngine.publicObservation(accepted.session.game),
                event,
            )
        }
        val notice = spoken?.take(COSMIC_OPPONENT_COMMENTARY_LIMIT) ?: accepted.message
        return CosmicOpponentTurnOutcome(
            actionResult = accepted,
            notice = if (usedFallback) "Offline fallback: $notice" else notice,
            usedFallback = usedFallback,
        )
    }
}
