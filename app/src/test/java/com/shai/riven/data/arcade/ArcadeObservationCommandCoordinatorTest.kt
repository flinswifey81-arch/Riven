package com.shai.riven.data.arcade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeObservationCommandCoordinatorTest {
    @Test
    fun newSessionClearsAcknowledgedContextBeforePublishingReplacement() = runBlocking {
        val calls = mutableListOf<String>()
        var contextRevision = 0L
        val coordinator = ArcadeObservationCommandCoordinator(
            publish = { observation ->
                calls += "publish:${observation.sessionId}:${observation.sequence}"
                ArcadeObservationWriteResult.Published(
                    observation.gameId,
                    observation.sessionId,
                    ++contextRevision,
                )
            },
            clear = {
                calls += "clear"
                contextRevision = 0L
                ArcadeObservationWriteResult.Cleared(changed = true)
            },
        )

        coordinator.submit(observation("deal-one", 1))
        coordinator.flushLatest()
        coordinator.submit(observation("deal-two", 1))
        val prepared = coordinator.prepareForProvider()

        assertTrue(prepared is ArcadeObservationWriteResult.Published)
        assertEquals(
            listOf("publish:deal-one:1", "clear", "publish:deal-two:1"),
            calls,
        )
    }

    @Test
    fun concurrentFlushesSerializeAndProviderPreparationAcknowledgesLatestRevision() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val coordinator = ArcadeObservationCommandCoordinator(
            publish = { observation ->
                calls += "publish:${observation.sequence}"
                if (observation.sequence == 1L) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                ArcadeObservationWriteResult.Published("game", observation.sessionId, observation.sequence)
            },
            clear = { ArcadeObservationWriteResult.Cleared(changed = true) },
        )

        coordinator.submit(observation("session", 1))
        val first = async { coordinator.flushLatest() }
        firstEntered.await()
        coordinator.submit(observation("session", 2))
        val second = async { coordinator.flushLatest() }
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        val prepared = coordinator.prepareForProvider()
        assertTrue(prepared is ArcadeObservationWriteResult.Published)
        assertEquals(2L, (prepared as ArcadeObservationWriteResult.Published).contextRevision)
        assertEquals(listOf("publish:1", "publish:2"), calls)
    }

    @Test
    fun failedLatestPublishBlocksProviderPreparation() = runBlocking {
        var publishCalls = 0
        val coordinator = ArcadeObservationCommandCoordinator(
            publish = {
                publishCalls += 1
                ArcadeObservationWriteResult.Failure("injected failure")
            },
            clear = { ArcadeObservationWriteResult.Cleared(changed = false) },
        )

        coordinator.submit(observation("session", 1))
        val result = coordinator.prepareForProvider()

        assertEquals(1, publishCalls)
        assertTrue(result is ArcadeObservationWriteResult.Failure)
    }

    @Test
    fun repeatedClearSubmissionReusesTheAcknowledgedCommand() = runBlocking {
        var clearCalls = 0
        val coordinator = ArcadeObservationCommandCoordinator(
            publish = { observation ->
                ArcadeObservationWriteResult.Published("game", observation.sessionId, observation.sequence)
            },
            clear = {
                clearCalls += 1
                ArcadeObservationWriteResult.Cleared(changed = clearCalls == 1)
            },
        )
        coordinator.submit(observation("session", 1))
        coordinator.flushLatest()

        val firstRevision = coordinator.submit(null)
        assertTrue(coordinator.flushLatest() is ArcadeObservationWriteResult.Cleared)
        val repeatedRevision = coordinator.submit(null)
        assertTrue(coordinator.flushLatest() is ArcadeObservationWriteResult.Cleared)

        assertEquals(firstRevision, repeatedRevision)
        assertEquals(1, clearCalls)
    }

    private fun observation(sessionId: String, sequence: Long) = ArcadeGameObservation(
        gameId = "game",
        gameTitle = "Game",
        sessionId = sessionId,
        sequence = sequence,
        view = ArcadeObservationView.SOLO_PUBLIC,
        phase = "playing",
        facts = listOf(ArcadeObservationFact("Moves", sequence.toString())),
        observedAt = sequence,
    )
}
