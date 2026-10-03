package com.shai.riven.ui.arcade

import com.shai.riven.data.arcade.ArcadeGameObservation
import com.shai.riven.data.arcade.ArcadeObservationFact
import com.shai.riven.data.arcade.ArcadeObservationView
import com.shai.riven.data.arcade.ArcadePublicEvent
import com.shai.riven.ui.arcade.comet.CometTrailEngine
import com.shai.riven.ui.arcade.solitaire.MidnightSolitaireEngine
import com.shai.riven.ui.arcade.spire.CelestialSpireEngine
import com.shai.riven.ui.arcade.starstruck.StarstruckEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeObservationMappersTest {
    @Test
    fun solitaireObservationNeverExposesDealSeedOrHiddenCardIdentities() {
        val seed = 7_654_321L
        val state = MidnightSolitaireEngine.newGame(seed)

        val content = state.toArcadeObservation(
            sessionId = "opaque-session",
            sequence = 1,
            paused = false,
            event = "Table opened.",
            observedAt = 10,
        ).toTransientContext()

        assertFalse(content.contains(seed.toString()))
        assertFalse(content.contains("dealSeed"))
        state.stock.forEach { hiddenCard -> assertFalse(content.contains(hiddenCard.spokenName)) }
        assertTrue(content.contains("Stock cards: 24"))
        assertTrue(content.contains("Face-up tableau cards: 7"))
    }

    @Test
    fun allSoloMappersPublishCoarseBoundedPublicFacts() {
        val observations = listOf(
            CelestialSpireEngine.newGame(1).toArcadeObservation("spire", 1, false, "Opened.", 1),
            MidnightSolitaireEngine.newGame(2).toArcadeObservation("solitaire", 1, false, "Opened.", 1),
            StarstruckEngine.newGame(3).toArcadeObservation("stars", 1, false, "Opened.", 1),
            CometTrailEngine.newGame(4).toArcadeObservation("comet", 1, false, "Opened.", 1),
        )

        assertEquals(4, observations.map(ArcadeGameObservation::gameId).distinct().size)
        observations.forEach { observation ->
            assertEquals(ArcadeObservationView.SOLO_PUBLIC, observation.view)
            assertTrue(observation.facts.size <= 12)
            assertEquals(1, observation.recentEvents.size)
            assertTrue(observation.toTransientContext().length <= 4_096)
            assertTrue(observation.toTransientContext().contains("untrusted data"))
        }
    }

    @Test
    fun commentaryGateIsDisabledByDefaultAndThrottlesLocalCuesWithoutCallingAnything() {
        val gate = ArcadeCommentaryGate(minimumIntervalMillis = 100, minimumSequenceGap = 2)

        assertNull(gate.consider(enabled = false, observation = observation(2, 100)))
        assertNull(gate.consider(enabled = true, observation = observation(1, 100)))
        assertTrue(gate.consider(enabled = true, observation = observation(2, 100))!!.contains("nothing is sent"))
        assertNull(gate.consider(enabled = true, observation = observation(3, 150)))
        assertTrue(gate.consider(enabled = true, observation = observation(4, 200))!!.contains("Open chat"))
    }

    @Test
    fun pauseAndResumeAreMeaningfulButFrameOnlyChangesAreNotRequired() {
        val playing = ArcadeObservationSignature(1, 0, 0, paused = false)
        val paused = playing.copy(paused = true)

        assertEquals(
            "The table paused.",
            meaningfulArcadeEvent(ArcadeGame.STACKER, playing, paused),
        )
        assertEquals(
            "Play resumed.",
            meaningfulArcadeEvent(ArcadeGame.STACKER, paused, playing),
        )
    }

    @Test
    fun cometDirectionOnlyTurnIsACompleteMeaningfulObservationChange() {
        val before = ArcadeObservationSignature(0, 0, 0, paused = false, direction = "UP")
        val after = before.copy(direction = "LEFT")

        assertEquals(
            "Turned left.",
            meaningfulArcadeEvent(ArcadeGame.WRAPPING_SNAKE, before, after),
        )
    }

    private fun observation(sequence: Long, observedAt: Long) = ArcadeGameObservation(
        gameId = "stacker",
        gameTitle = "Celestial Spire",
        sessionId = "session",
        sequence = sequence,
        view = ArcadeObservationView.SOLO_PUBLIC,
        phase = "playing",
        facts = listOf(ArcadeObservationFact("Pieces", sequence.toString())),
        recentEvents = listOf(ArcadePublicEvent(sequence, "Placed a piece.")),
        observedAt = observedAt,
    )
}
