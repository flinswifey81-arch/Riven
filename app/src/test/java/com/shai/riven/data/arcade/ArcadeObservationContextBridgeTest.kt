package com.shai.riven.data.arcade

import com.shai.riven.data.context.EphemeralAppStateContextSource
import com.shai.riven.data.context.EphemeralAppStateStore
import com.shai.riven.data.context.RivenContextReadRequest
import com.shai.riven.data.context.RivenContextSourceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeObservationContextBridgeTest {
    @Test
    fun publishReplaceAndClearUseOneBoundedTransientContextRecord() = runBlocking {
        val store = EphemeralAppStateStore()
        val bridge = ArcadeObservationContextBridge(store)
        val source = EphemeralAppStateContextSource(store)

        val first = bridge.publish(observation("session-one", 1, "First deal opened.", observedAt = 100))
        val second = bridge.publish(observation("session-two", 2, "Fresh deal ready.", observedAt = 200))

        assertTrue(first is ArcadeObservationWriteResult.Published)
        assertTrue(second is ArcadeObservationWriteResult.Published)
        assertEquals(1, store.snapshot().entries.size)
        val content = source.payloadAt(now = 200).single().content
        assertTrue(content.contains("session-two"))
        assertTrue(content.contains("Fresh deal ready"))
        assertFalse(content.contains("session-one"))
        assertFalse(content.contains("First deal opened"))
        assertTrue(content.contains("not durable memory"))
        assertTrue(content.contains("never infer hidden state"))

        val cleared = bridge.clear()

        assertEquals(ArcadeObservationWriteResult.Cleared(changed = true), cleared)
        assertTrue(source.payloadAt(now = 201).isEmpty())
        assertEquals(ArcadeObservationWriteResult.Cleared(changed = false), bridge.clear())
    }

    @Test
    fun contextExpiresWithoutProviderOrStorageActivity() = runBlocking {
        val store = EphemeralAppStateStore()
        val bridge = ArcadeObservationContextBridge(store)
        val source = EphemeralAppStateContextSource(store)
        bridge.publish(observation("session", 1, "Opened.", observedAt = 1_000))

        assertEquals(1, source.payloadAt(1_000 + ArcadeObservationContextBridge.OBSERVATION_TTL_MILLIS - 1).size)
        assertTrue(source.payloadAt(1_000 + ArcadeObservationContextBridge.OBSERVATION_TTL_MILLIS).isEmpty())
    }

    @Test
    fun contractsRejectFrameFloodSizedPayloads() {
        val failure = runCatching {
            observation(
                sessionId = "session",
                sequence = 1,
                event = "event",
                observedAt = 1,
                facts = List(MAX_ARCADE_OBSERVATION_FACTS + 1) { index ->
                    ArcadeObservationFact("fact-$index", "value")
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun observation(
        sessionId: String,
        sequence: Long,
        event: String,
        observedAt: Long,
        facts: List<ArcadeObservationFact> = listOf(ArcadeObservationFact("Moves", sequence.toString())),
    ) = ArcadeGameObservation(
        gameId = "klondike",
        gameTitle = "Midnight Solitaire",
        sessionId = sessionId,
        sequence = sequence,
        view = ArcadeObservationView.SOLO_PUBLIC,
        phase = "playing",
        facts = facts,
        recentEvents = listOf(ArcadePublicEvent(sequence, event)),
        observedAt = observedAt,
    )

    private suspend fun EphemeralAppStateContextSource.payloadAt(now: Long) =
        (read(RivenContextReadRequest(now = now)) as RivenContextSourceResult.Success).payloads
}
