package com.shai.riven.data.context

class EphemeralAppStateStore internal constructor(
    initialEntries: Collection<EphemeralAppStateEntry>,
) {
    private val lock = Any()
    private val entries = linkedMapOf<String, EphemeralAppStateEntry>()

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
                EphemeralAppStateWriteResult.Cleared(
                    stateId = input.stateId,
                    changed = true,
                    previousRevision = current.revision,
                )
            }
        }
    }

    fun snapshot(): EphemeralAppStateSnapshot = synchronized(lock) {
        EphemeralAppStateSnapshot(entries.values.sortedBy(EphemeralAppStateEntry::stateId))
    }

    fun purgeExpired(now: Long): PurgeExpiredEphemeralAppStateResult = synchronized(lock) {
        val expiredIds = entries.values
            .asSequence()
            .filter { entry -> entry.validUntil?.let { validUntil -> validUntil <= now } == true }
            .map(EphemeralAppStateEntry::stateId)
            .sorted()
            .toList()
        expiredIds.forEach(entries::remove)
        PurgeExpiredEphemeralAppStateResult(expiredIds)
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
