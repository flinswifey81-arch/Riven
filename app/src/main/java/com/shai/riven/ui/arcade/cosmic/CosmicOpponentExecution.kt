package com.shai.riven.ui.arcade.cosmic

import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible

const val COSMIC_OPPONENT_ACTION_TIMEOUT_MILLIS = 500L
const val COSMIC_OPPONENT_COMMENTARY_TIMEOUT_MILLIS = 350L
const val COSMIC_OPPONENT_COMMENTARY_LIMIT = 240
internal const val COSMIC_OPPONENT_GLOBAL_WORKERS = 2
private const val COSMIC_OPPONENT_GLOBAL_QUEUE_CAPACITY = 2

/**
 * Process-wide containment for untrusted opponent callbacks.
 *
 * The JVM cannot forcibly terminate arbitrary code that ignores interruption, so two such callbacks
 * can occupy both daemon workers for the rest of the process. That failure remains contained: no
 * later screen mount can create replacement callback threads, the queue stays bounded, and rejected
 * or timed-out work fails closed so the trusted deterministic fallback can advance the game.
 */
private object CosmicOpponentProcessExecutor {
    private val threadNumber = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        COSMIC_OPPONENT_GLOBAL_WORKERS,
        COSMIC_OPPONENT_GLOBAL_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(COSMIC_OPPONENT_GLOBAL_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "cosmic-opponent-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun execute(task: FutureTask<*>): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun remove(task: FutureTask<*>) {
        executor.remove(task)
        executor.purge()
    }

    fun stats(): CosmicOpponentExecutorStats = CosmicOpponentExecutorStats(
        poolSize = executor.poolSize,
        activeWorkers = executor.activeCount,
        largestPoolSize = executor.largestPoolSize,
        queuedTasks = executor.queue.size,
    )
}

internal data class CosmicOpponentExecutorStats(
    val poolSize: Int,
    val activeWorkers: Int,
    val largestPoolSize: Int,
    val queuedTasks: Int,
)

internal fun cosmicOpponentExecutorStats(): CosmicOpponentExecutorStats =
    CosmicOpponentProcessExecutor.stats()

/**
 * Per-screen request scope backed by the process-wide bounded executor. Closing a scope cancels and
 * removes its queued requests, but never creates or destroys the shared worker pool.
 */
class CosmicOpponentExecution(
    private val actionTimeoutMillis: Long = COSMIC_OPPONENT_ACTION_TIMEOUT_MILLIS,
    private val commentaryTimeoutMillis: Long = COSMIC_OPPONENT_COMMENTARY_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val tasks = Collections.synchronizedSet(mutableSetOf<FutureTask<*>>())
    @Volatile
    private var closed = false

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
        val task = FutureTask(Callable(block))
        synchronized(tasks) {
            if (closed) return null
            tasks.add(task)
            if (!CosmicOpponentProcessExecutor.execute(task)) {
                tasks.remove(task)
                return null
            }
        }
        return try {
            runInterruptible(Dispatchers.IO) {
                task.get(timeoutMillis, TimeUnit.MILLISECONDS)
            }
        } catch (cancelled: CancellationException) {
            task.cancel(true)
            if (!currentCoroutineContext().isActive) throw cancelled
            null
        } catch (_: Exception) {
            task.cancel(true)
            null
        } finally {
            task.cancel(true)
            CosmicOpponentProcessExecutor.remove(task)
            tasks.remove(task)
        }
    }

    override fun close() {
        val pending = synchronized(tasks) {
            if (closed) return
            closed = true
            tasks.toList().also { tasks.clear() }
        }
        pending.forEach { task ->
            task.cancel(true)
            CosmicOpponentProcessExecutor.remove(task)
        }
    }
}

/** A token gate that makes an opponent commit conditional on the latest pause/session epoch. */
class CosmicOpponentTurnGate {
    private val epoch = AtomicLong()
    @Volatile
    private var paused = false

    @Synchronized
    fun pause() {
        if (!paused) {
            paused = true
            epoch.incrementAndGet()
        }
    }

    @Synchronized
    fun updatePaused(isPaused: Boolean) {
        if (paused != isPaused) {
            paused = isPaused
            epoch.incrementAndGet()
        }
    }

    @Synchronized
    fun acquirePermit(): Long? = if (paused) null else epoch.get()

    @Synchronized
    internal fun <T : Any> commitIfPermitted(permit: Long, commit: () -> T?): T? {
        if (paused || epoch.get() != permit) return null
        return commit()
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
