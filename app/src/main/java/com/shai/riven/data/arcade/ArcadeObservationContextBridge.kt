package com.shai.riven.data.arcade

import com.shai.riven.data.context.ClearEphemeralAppStateInput
import com.shai.riven.data.context.EphemeralAppStateExposure
import com.shai.riven.data.context.EphemeralAppStateStore
import com.shai.riven.data.context.EphemeralAppStateWriteResult
import com.shai.riven.data.context.PublishEphemeralAppStateInput

class ArcadeObservationContextBridge(
    private val store: EphemeralAppStateStore,
) {
    private val lock = Any()
    private var contextRevision = 0L

    fun publish(observation: ArcadeGameObservation): ArcadeObservationWriteResult = synchronized(lock) {
        val validUntil = observation.observedAt
            .takeIf { it <= Long.MAX_VALUE - OBSERVATION_TTL_MILLIS }
            ?.plus(OBSERVATION_TTL_MILLIS)
        when (
            val result = store.publish(
                PublishEphemeralAppStateInput(
                    stateId = STATE_ID,
                    content = observation.toTransientContext(),
                    exposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
                    priority = CONTEXT_PRIORITY,
                    expectedRevision = contextRevision,
                    observedAt = observation.observedAt,
                    validUntil = validUntil,
                ),
            )
        ) {
            is EphemeralAppStateWriteResult.Published -> {
                contextRevision = result.entry.revision
                ArcadeObservationWriteResult.Published(
                    gameId = observation.gameId,
                    sessionId = observation.sessionId,
                    contextRevision = result.entry.revision,
                )
            }
            is EphemeralAppStateWriteResult.Failure -> ArcadeObservationWriteResult.Failure(
                "Arcade observation was not published: ${result.error::class.simpleName}",
            )
            is EphemeralAppStateWriteResult.Cleared -> error("Publish returned a clear result")
        }
    }

    fun clear(): ArcadeObservationWriteResult = synchronized(lock) {
        when (
            val result = store.clear(
                ClearEphemeralAppStateInput(
                    stateId = STATE_ID,
                    expectedRevision = contextRevision,
                ),
            )
        ) {
            is EphemeralAppStateWriteResult.Cleared -> {
                contextRevision = 0L
                ArcadeObservationWriteResult.Cleared(result.changed)
            }
            is EphemeralAppStateWriteResult.Failure -> ArcadeObservationWriteResult.Failure(
                "Arcade observation was not cleared: ${result.error::class.simpleName}",
            )
            is EphemeralAppStateWriteResult.Published -> error("Clear returned a publish result")
        }
    }

    companion object {
        const val STATE_ID = "ARCADE_GAMEPLAY_OBSERVATION"
        const val OBSERVATION_TTL_MILLIS = 15L * 60L * 1_000L
        private const val CONTEXT_PRIORITY = 75
    }
}
