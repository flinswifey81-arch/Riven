package com.shai.riven.data.validation

import com.shai.riven.data.persistence.RivenDatabase
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local, database-instance-scoped generation fence for validation recall.
 *
 * Canonical writers advance the fence inside their Room transaction. Advancing on a transaction
 * that later rolls back is intentionally allowed: it produces a conservative rebuild, never a
 * stale-success result. A new database instance (relaunch, restore, reset, or replacement) receives
 * a new session id and therefore cannot reuse an old in-memory index.
 */
internal class ValidationRecallCorpusFence {
    private val generation = AtomicLong(0L)
    private val sessionId = UUID.randomUUID().toString()
    private val invalidationListeners = CopyOnWriteArraySet<() -> Unit>()

    fun snapshot(): ValidationRecallGeneration = ValidationRecallGeneration(
        databaseSessionId = sessionId,
        corpusGeneration = generation.get(),
        algorithmVersion = TARGETED_VALIDATION_RECALL_ALGORITHM_VERSION,
    )

    fun markCanonicalMutation() {
        generation.incrementAndGet()
        invalidationListeners.forEach { it() }
    }

    fun addInvalidationListener(listener: () -> Unit) {
        invalidationListeners += listener
    }

    fun removeInvalidationListener(listener: () -> Unit) {
        invalidationListeners -= listener
    }

    fun matches(snapshot: ValidationRecallGeneration?): Boolean = snapshot == this.snapshot()
}

private object ValidationRecallCorpusFences {
    private val fences = WeakHashMap<RivenDatabase, ValidationRecallCorpusFence>()

    @Synchronized
    fun forDatabase(database: RivenDatabase): ValidationRecallCorpusFence =
        fences.getOrPut(database) { ValidationRecallCorpusFence() }
}

internal fun RivenDatabase.validationRecallCorpusFence(): ValidationRecallCorpusFence =
    ValidationRecallCorpusFences.forDatabase(this)
