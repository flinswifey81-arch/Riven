package com.shai.riven.data.validation

import com.shai.riven.data.persistence.RivenDatabase
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface ValidationRecallCorpusChange {
    data class MemoryIdsChanged(val memoryIds: Set<String>) : ValidationRecallCorpusChange
    data object Unknown : ValidationRecallCorpusChange

    companion object {
        fun memoryIds(memoryIds: Collection<String>): ValidationRecallCorpusChange =
            MemoryIdsChanged(memoryIds.filterTo(sortedSetOf()) { it.isNotBlank() })
    }
}

internal data class ValidationRecallCommittedMutation(
    val generation: ValidationRecallGeneration,
    val change: ValidationRecallCorpusChange,
)

internal data class ValidationRecallFenceObservation(
    val generation: ValidationRecallGeneration,
    val mutationInFlight: Boolean,
)

internal class ValidationRecallMutationToken internal constructor(
    internal val id: Long,
)

/**
 * Process-local, database-instance-scoped commit fence for validation recall.
 *
 * Canonical mutations are serialized and become in-flight before their Room transaction starts.
 * While a mutation is in-flight, an old receipt cannot authorize admission and an index build
 * cannot publish. The committed generation advances only after Room reports a successful commit;
 * rollback removes the in-flight barrier and preserves the prior generation. A new database
 * instance (relaunch, restore, reset, or replacement) receives a new session id and cannot reuse an
 * old process index.
 *
 * Callers must start this fence outside any existing Room transaction. The fenced block settles in
 * a non-cancellable context so the in-flight barrier remains active until Room has actually ended
 * the transaction. Caller cancellation is delivered only after the committed or rolled-back
 * outcome has been published to the fence.
 */
internal class ValidationRecallCorpusFence {
    private val stateLock = Any()
    private val mutationMutex = Mutex()
    private val sessionId = UUID.randomUUID().toString()
    private var corpusGeneration = 0L
    private var nextMutationId = 0L
    private var activeMutationId: Long? = null
    private val listeners = CopyOnWriteArrayList<WeakReference<(ValidationRecallCommittedMutation) -> Unit>>()

    fun snapshot(): ValidationRecallGeneration = synchronized(stateLock) { generationLocked() }

    fun snapshotForIndexBuild(): ValidationRecallGeneration? = synchronized(stateLock) {
        if (activeMutationId == null) generationLocked() else null
    }

    fun isMutationInFlight(): Boolean = synchronized(stateLock) { activeMutationId != null }

    fun observationSnapshot(): ValidationRecallFenceObservation = synchronized(stateLock) {
        ValidationRecallFenceObservation(generationLocked(), activeMutationId != null)
    }

    fun ownsActiveMutation(token: ValidationRecallMutationToken): Boolean = synchronized(stateLock) {
        activeMutationId == token.id
    }

    fun matches(snapshot: ValidationRecallGeneration?): Boolean = synchronized(stateLock) {
        activeMutationId == null && snapshot == generationLocked()
    }

    fun matchesDuringMutation(
        snapshot: ValidationRecallGeneration?,
        token: ValidationRecallMutationToken,
    ): Boolean = synchronized(stateLock) {
        activeMutationId == token.id && snapshot == generationLocked()
    }

    suspend fun <T> withCanonicalMutation(
        change: (T) -> ValidationRecallCorpusChange,
        block: suspend (ValidationRecallMutationToken) -> T,
    ): T = mutationMutex.withLock {
        val token = beginMutation()
        withContext(NonCancellable) {
            val result = try {
                block(token)
            } catch (cancelled: CancellationException) {
                completeCommittedMutation(token, ValidationRecallCorpusChange.Unknown)
                throw cancelled
            } catch (failure: Throwable) {
                completeRolledBackMutation(token)
                throw failure
            }
            val committedChange = try {
                change(result)
            } catch (failure: Throwable) {
                completeCommittedMutation(token, ValidationRecallCorpusChange.Unknown)
                throw failure
            }
            completeCommittedMutation(token, committedChange)
            result
        }
    }

    fun addInvalidationListener(listener: (ValidationRecallCommittedMutation) -> Unit) {
        pruneListeners()
        listeners += WeakReference(listener)
    }

    fun removeInvalidationListener(listener: (ValidationRecallCommittedMutation) -> Unit) {
        listeners.removeAll { reference ->
            val registered = reference.get()
            registered == null || registered === listener
        }
    }

    internal fun liveInvalidationListenerCount(): Int {
        pruneListeners()
        return listeners.count { it.get() != null }
    }

    private fun beginMutation(): ValidationRecallMutationToken = synchronized(stateLock) {
        check(activeMutationId == null) { "Canonical recall mutation already active" }
        nextMutationId = if (nextMutationId == Long.MAX_VALUE) 1L else nextMutationId + 1L
        ValidationRecallMutationToken(nextMutationId).also { activeMutationId = it.id }
    }

    private fun completeCommittedMutation(
        token: ValidationRecallMutationToken,
        change: ValidationRecallCorpusChange,
    ) {
        val committed = synchronized(stateLock) {
            requireActive(token)
            if (corpusGeneration == Long.MAX_VALUE) {
                throw IllegalStateException("Validation recall corpus generation exhausted")
            }
            activeMutationId = null
            corpusGeneration += 1L
            ValidationRecallCommittedMutation(generationLocked(), change)
        }
        listeners.removeAll { reference ->
            val listener = reference.get() ?: return@removeAll true
            runCatching { listener(committed) }
            false
        }
    }

    private fun completeRolledBackMutation(token: ValidationRecallMutationToken) {
        synchronized(stateLock) {
            requireActive(token)
            activeMutationId = null
        }
    }

    private fun requireActive(token: ValidationRecallMutationToken) {
        check(activeMutationId == token.id) { "Canonical recall mutation token is not active" }
    }

    private fun generationLocked() = ValidationRecallGeneration(
        databaseSessionId = sessionId,
        corpusGeneration = corpusGeneration,
        algorithmVersion = TARGETED_VALIDATION_RECALL_ALGORITHM_VERSION,
    )

    private fun pruneListeners() {
        listeners.removeAll { it.get() == null }
    }
}

private object ValidationRecallCorpusFences {
    private val fences = WeakHashMap<RivenDatabase, ValidationRecallCorpusFence>()

    @Synchronized
    fun forDatabase(database: RivenDatabase): ValidationRecallCorpusFence =
        fences.getOrPut(database) { ValidationRecallCorpusFence() }
}

internal fun RivenDatabase.validationRecallCorpusFence(): ValidationRecallCorpusFence =
    ValidationRecallCorpusFences.forDatabase(this)

internal fun RivenDatabase.requireTopLevelValidationRecallMutation() {
    check(!inTransaction()) {
        "Canonical recall writers cannot start inside an existing Room transaction; " +
            "use the explicit in-current-transaction API under one outer recall fence"
    }
}
