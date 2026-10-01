package com.shai.riven.data.context

import java.util.UUID

class EphemeralAppStateStore internal constructor(
    initialEntries: Collection<EphemeralAppStateEntry>,
    private val storeSessionId: String = UUID.randomUUID().toString(),
) {
    private val lock = Any()
    private val entries = linkedMapOf<String, EphemeralAppStateEntry>()
    private var generation = 0L

    constructor() : this(emptyList())

    init {
        initialEntries.forEach { entry ->
            require(entry.stateId.isNotBlank()) { "Initial state ID is blank" }
            require(entry.content.isNotBlank()) { "Initial state content is blank" }
            require(entry.revision > 0) { "Initial state revision must be positive" }
            require(entry.validUntil == null || entry.validUntil > entry.observedAt) {
                "Initial state lifetime is invalid"
            }
            require(entries.put(entry.stateId, entry) == null) {
                "Duplicate initial state ID: ${entry.stateId}"
            }
        }
    }

    fun publish(input: PublishEphemeralAppStateInput): EphemeralAppStateWriteResult {
        validate(input)?.let { error -> return EphemeralAppStateWriteResult.Failure(error) }
        return synchronized(lock) {
            val current = entries[input.stateId]
            val actualRevision = current?.revision ?: INITIAL_REVISION
            if (input.expectedRevision != actualRevision) {
                return@synchronized EphemeralAppStateWriteResult.Failure(
                    EphemeralAppStateError.StaleRevision(
                        stateId = input.stateId,
                        expected = input.expectedRevision,
                        actual = actualRevision,
                    ),
                )
            }
            if (actualRevision == Long.MAX_VALUE) {
                return@synchronized EphemeralAppStateWriteResult.Failure(
                    EphemeralAppStateError.RevisionOverflow(input.stateId),
                )
            }

            val published = EphemeralAppStateEntry(
                stateId = input.stateId,
                content = input.content,
                exposure = input.exposure,
                priority = input.priority,
                revision = actualRevision + 1,
                observedAt = input.observedAt,
                validUntil = input.validUntil,
            )
            entries[input.stateId] = published
            advanceGeneration()
            EphemeralAppStateWriteResult.Published(published)
        }
    }

    fun clear(input: ClearEphemeralAppStateInput): EphemeralAppStateWriteResult {
        if (input.stateId.isBlank()) {
            return EphemeralAppStateWriteResult.Failure(EphemeralAppStateError.BlankStateId)
        }
        return synchronized(lock) {
            val current = entries[input.stateId]
            val actualRevision = current?.revision ?: INITIAL_REVISION
            if (input.expectedRevision != actualRevision) {
                return@synchronized EphemeralAppStateWriteResult.Failure(
                    EphemeralAppStateError.StaleRevision(
                        stateId = input.stateId,
                        expected = input.expectedRevision,
                        actual = actualRevision,
                    ),
                )
            }
            if (current == null) {
                EphemeralAppStateWriteResult.Cleared(
                    stateId = input.stateId,
                    changed = false,
                    previousRevision = null,
                )
            } else {
                entries.remove(input.stateId)
                advanceGeneration()
                EphemeralAppStateWriteResult.Cleared(
                    stateId = input.stateId,
                    changed = true,
                    previousRevision = current.revision,
                )
            }
        }
    }

    fun snapshot(): EphemeralAppStateSnapshot = synchronized(lock) {
        EphemeralAppStateSnapshot(
            storeSessionId = storeSessionId,
            generation = generation,
            entries = entries.values.sortedBy(EphemeralAppStateEntry::stateId),
        )
    }

    fun purgeExpired(now: Long): PurgeExpiredEphemeralAppStateResult = synchronized(lock) {
        val expiredIds = entries.values
            .asSequence()
            .filter { entry -> entry.validUntil?.let { validUntil -> validUntil <= now } == true }
            .map(EphemeralAppStateEntry::stateId)
            .sorted()
            .toList()
        expiredIds.forEach(entries::remove)
        if (expiredIds.isNotEmpty()) advanceGeneration()
        PurgeExpiredEphemeralAppStateResult(expiredIds)
    }

    fun isCurrent(
        receipt: RivenContextFreshnessReceipt.EphemeralAppState,
        now: Long,
    ): Boolean = synchronized(lock) {
        matchesLocked(receipt, now)
    }

    internal fun <T : Any> withFreshnessGuard(
        receipt: RivenContextFreshnessReceipt.EphemeralAppState?,
        now: Long,
        block: () -> T,
    ): T? = synchronized(lock) {
        if (receipt != null && !matchesLocked(receipt, now)) null else block()
    }

    private fun matchesLocked(
        receipt: RivenContextFreshnessReceipt.EphemeralAppState,
        now: Long,
    ): Boolean = receipt.storeSessionId == storeSessionId &&
        receipt.generation == generation &&
        receipt.earliestValidUntil?.let { validUntil -> validUntil > now } != false

    private fun advanceGeneration() {
        check(generation != Long.MAX_VALUE) { "Ephemeral app-state generation exhausted" }
        generation += 1L
    }

    private fun validate(input: PublishEphemeralAppStateInput): EphemeralAppStateError? {
        if (input.stateId.isBlank()) return EphemeralAppStateError.BlankStateId
        if (input.content.isBlank()) return EphemeralAppStateError.BlankContent
        val validUntil = input.validUntil
        if (validUntil != null && validUntil <= input.observedAt) {
            return EphemeralAppStateError.InvalidLifetime(
                observedAt = input.observedAt,
                validUntil = validUntil,
            )
        }
        return null
    }

    private companion object {
        const val INITIAL_REVISION = 0L
    }
}
