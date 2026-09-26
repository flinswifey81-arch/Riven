package com.shai.riven.data.context

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralAppStateStoreTest {
    @Test
    fun firstPublishCreatesRevisionOne() {
        val store = EphemeralAppStateStore()

        val published = assertPublished(
            store.publish(input("ARCADE_STATE", "game: STACKER", expectedRevision = 0, observedAt = 10)),
        )

        assertEquals(1L, published.revision)
        assertEquals(published, store.snapshot().entries.single())
    }

    @Test
    fun updateWithCurrentRevisionAtomicallyReplacesContentAndMetadata() {
        val store = EphemeralAppStateStore()
        assertPublished(store.publish(input("ACTIVE_TIMER", "remaining: 60", 0, 1)))

        val updated = assertPublished(
            store.publish(
                input(
                    stateId = "ACTIVE_TIMER",
                    content = "remaining: 30",
                    expectedRevision = 1,
                    observedAt = 2,
                    exposure = EphemeralAppStateExposure.LOCAL_ONLY,
                    priority = 9,
                    validUntil = 32,
                ),
            ),
        )

        assertEquals(2L, updated.revision)
        assertEquals("remaining: 30", updated.content)
        assertEquals(EphemeralAppStateExposure.LOCAL_ONLY, updated.exposure)
        assertEquals(9, updated.priority)
        assertEquals(2L, updated.observedAt)
        assertEquals(32L, updated.validUntil)
        assertEquals(listOf(updated), store.snapshot().entries)
    }

    @Test
    fun staleUpdateReturnsTypedFailureAndLeavesEntryUnchanged() {
        val store = EphemeralAppStateStore()
        val original = assertPublished(store.publish(input("STATE", "original", 0, 1)))

        val failure = assertFailure(store.publish(input("STATE", "stale", 0, 2)))

        assertEquals(
            EphemeralAppStateError.StaleRevision("STATE", expected = 0, actual = 1),
            failure,
        )
        assertEquals(listOf(original), store.snapshot().entries)
    }

    @Test
    fun revisionOverflowReturnsTypedFailureWithoutMutation() {
        val original = EphemeralAppStateEntry(
            stateId = "STATE",
            content = "maximum",
            exposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
            priority = 0,
            revision = Long.MAX_VALUE,
            observedAt = 1,
        )
        val store = EphemeralAppStateStore(listOf(original))

        val failure = assertFailure(
            store.publish(input("STATE", "must not replace", Long.MAX_VALUE, observedAt = 2)),
        )

        assertEquals(EphemeralAppStateError.RevisionOverflow("STATE"), failure)
        assertEquals(listOf(original), store.snapshot().entries)
    }

    @Test
    fun clearRemovesExistingEntryWithoutTombstone() {
        val store = EphemeralAppStateStore()
        assertPublished(store.publish(input("STATE", "value", 0, 1)))

        val cleared = assertCleared(store.clear(ClearEphemeralAppStateInput("STATE", expectedRevision = 1)))

        assertTrue(cleared.changed)
        assertEquals(1L, cleared.previousRevision)
        assertTrue(store.snapshot().entries.isEmpty())
    }

    @Test
    fun absentClearAtRevisionZeroIsIdempotent() {
        val store = EphemeralAppStateStore()

        val cleared = assertCleared(store.clear(ClearEphemeralAppStateInput("MISSING", expectedRevision = 0)))

        assertFalse(cleared.changed)
        assertNull(cleared.previousRevision)
        assertTrue(store.snapshot().entries.isEmpty())
    }

    @Test
    fun invalidLifetimeIsTypedAndDoesNotCreateState() {
        val store = EphemeralAppStateStore()

        val equal = assertFailure(
            store.publish(input("EQUAL", "value", 0, observedAt = 10, validUntil = 10)),
        )
        val reversed = assertFailure(
            store.publish(input("REVERSED", "value", 0, observedAt = 10, validUntil = 9)),
        )

        assertTrue(equal is EphemeralAppStateError.InvalidLifetime)
        assertTrue(reversed is EphemeralAppStateError.InvalidLifetime)
        assertTrue(store.snapshot().entries.isEmpty())
    }

    @Test
    fun localOnlyEntryIsExcludedFromContext() {
        val store = EphemeralAppStateStore()
        assertPublished(
            store.publish(
                input(
                    "NAVIGATION_CONTEXT",
                    "screen: SETTINGS",
                    0,
                    1,
                    exposure = EphemeralAppStateExposure.LOCAL_ONLY,
                ),
            ),
        )

        val payloads = readPayloads(EphemeralAppStateContextSource(store), now = 1)

        assertTrue(payloads.isEmpty())
    }

    @Test
    fun freshRivenContextEntryEmitsExactPayloadMetadata() {
        val store = EphemeralAppStateStore()
        val content = "game: STACKER\nstatus: ACTIVE\nscore: 4820"
        assertPublished(
            store.publish(
                input(
                    stateId = "ARCADE_STATE",
                    content = content,
                    expectedRevision = 0,
                    observedAt = 10,
                    validUntil = 20,
                ),
            ),
        )

        val payload = readPayloads(EphemeralAppStateContextSource(store), now = 19).single()

        assertEquals("ARCADE_STATE", payload.fragmentId)
        assertEquals(content, payload.content)
        assertEquals(1L, payload.revision)
        assertEquals(10L, payload.observedAt)
        assertEquals(20L, payload.validUntil)
    }

    @Test
    fun expirationExcludesContextWithoutDeletingOrMutatingStoredEntry() {
        val store = EphemeralAppStateStore()
        assertPublished(store.publish(input("STATE", "fresh", 0, observedAt = 10, validUntil = 20)))
        val before = store.snapshot()
        val source = EphemeralAppStateContextSource(store)

        assertEquals(listOf("STATE"), readPayloads(source, now = 19).map { it.fragmentId })
        assertTrue(readPayloads(source, now = 20).isEmpty())
        assertTrue(readPayloads(source, now = 21).isEmpty())

        assertEquals(before, store.snapshot())
    }

    @Test
    fun purgeExpiredRemovesOnlyExpiredEntries() {
        val store = EphemeralAppStateStore()
        assertPublished(store.publish(input("expired-a", "a", 0, observedAt = 1, validUntil = 5)))
        assertPublished(store.publish(input("expired-b", "b", 0, observedAt = 2, validUntil = 6)))
        assertPublished(store.publish(input("fresh", "fresh", 0, observedAt = 3, validUntil = 7)))
        assertPublished(store.publish(input("unbounded", "unbounded", 0, observedAt = 4)))

        val purged = store.purgeExpired(now = 6)

        assertEquals(listOf("expired-a", "expired-b"), purged.removedStateIds)
        assertEquals(listOf("fresh", "unbounded"), store.snapshot().entries.map { it.stateId })
    }

    @Test
    fun contextSourceOrdersPriorityDescendingThenStateIdAscending() {
        val store = EphemeralAppStateStore()
        assertPublished(store.publish(input("low", "low", 0, 1, priority = 1)))
        assertPublished(store.publish(input("z-high", "z", 0, 1, priority = 10)))
        assertPublished(store.publish(input("a-high", "a", 0, 1, priority = 10)))
        assertPublished(store.publish(input("middle", "middle", 0, 1, priority = 5)))

        val payloads = readPayloads(EphemeralAppStateContextSource(store), now = 1)

        assertEquals(listOf("a-high", "z-high", "middle", "low"), payloads.map { it.fragmentId })
    }

    @Test
    fun repeatedSourceAndRegistryReadsDoNotMutateState() = kotlinx.coroutines.runBlocking {
        val store = EphemeralAppStateStore()
        assertPublished(store.publish(input("STATE", "exact", 0, observedAt = 10, validUntil = 20)))
        val before = store.snapshot()
        val source = EphemeralAppStateContextSource(store)
        val registry = RivenContextSourceRegistry(listOf(source))

        repeat(3) {
            readPayloads(source, now = 11)
            val collected = registry.collect(now = 11)
            assertTrue(collected is RivenContextCollectionResult.Success)
        }

        assertEquals(before, store.snapshot())
    }

    @Test
    fun concurrentDistinctPublishesRetainCompleteEntries() {
        val store = EphemeralAppStateStore()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 64).map { index ->
                executor.submit<EphemeralAppStateWriteResult> {
                    store.publish(input("state-$index", "content-$index", 0, index.toLong()))
                }
            }

            futures.forEach { future -> assertTrue(future.get() is EphemeralAppStateWriteResult.Published) }
            val snapshot = store.snapshot()
            assertEquals(64, snapshot.entries.size)
            snapshot.entries.forEach { entry ->
                assertEquals("content-${entry.stateId.removePrefix("state-")}", entry.content)
                assertEquals(1L, entry.revision)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readPayloads(
        source: EphemeralAppStateContextSource,
        now: Long,
    ): List<RivenContextPayload> = kotlinx.coroutines.runBlocking {
        when (val result = source.read(RivenContextReadRequest(now))) {
            is RivenContextSourceResult.Success -> result.payloads
            is RivenContextSourceResult.Failure -> error("Unexpected source failure: ${result.error}")
        }
    }

    private fun assertPublished(result: EphemeralAppStateWriteResult): EphemeralAppStateEntry {
        assertTrue("Expected publish success, got $result", result is EphemeralAppStateWriteResult.Published)
        return (result as EphemeralAppStateWriteResult.Published).entry
    }

    private fun assertCleared(result: EphemeralAppStateWriteResult): EphemeralAppStateWriteResult.Cleared {
        assertTrue("Expected clear success, got $result", result is EphemeralAppStateWriteResult.Cleared)
        return result as EphemeralAppStateWriteResult.Cleared
    }

    private fun assertFailure(result: EphemeralAppStateWriteResult): EphemeralAppStateError {
        assertTrue("Expected failure, got $result", result is EphemeralAppStateWriteResult.Failure)
        return (result as EphemeralAppStateWriteResult.Failure).error
    }

    private fun input(
        stateId: String,
        content: String,
        expectedRevision: Long,
        observedAt: Long,
        exposure: EphemeralAppStateExposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
        priority: Int = 0,
        validUntil: Long? = null,
    ) = PublishEphemeralAppStateInput(
        stateId = stateId,
        content = content,
        exposure = exposure,
        priority = priority,
        expectedRevision = expectedRevision,
        observedAt = observedAt,
        validUntil = validUntil,
    )
}
