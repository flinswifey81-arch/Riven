package com.shai.riven.data.arcade

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes ephemeral Arcade context writes and exposes only acknowledged observations. */
class ArcadeObservationCommandCoordinator(
    private val publish: suspend (ArcadeGameObservation) -> ArcadeObservationWriteResult,
    private val clear: suspend () -> ArcadeObservationWriteResult,
) {
    private val stateLock = Any()
    private val writeMutex = Mutex()
    private var desired: Command? = null
    private var acknowledgedRevision = 0L
    private var acknowledgedObservation: ArcadeGameObservation? = null
    private var acknowledgedResult: ArcadeObservationWriteResult =
        ArcadeObservationWriteResult.Cleared(changed = false)

    fun submit(observation: ArcadeGameObservation?): Long = synchronized(stateLock) {
        desired?.takeIf { it.observation == observation }?.let { return@synchronized it.revision }
        val revision = (desired?.revision ?: 0L) + 1L
        check(revision > 0L) { "Arcade observation command revision overflow" }
        desired = Command(revision, observation)
        revision
    }

    suspend fun flushLatest(): ArcadeObservationWriteResult = writeMutex.withLock {
        while (true) {
            val command = synchronized(stateLock) { desired }
                ?: return@withLock synchronized(stateLock) { acknowledgedResult }
            val alreadyAcknowledged = synchronized(stateLock) {
                command.revision <= acknowledgedRevision
            }
            if (alreadyAcknowledged) return@withLock synchronized(stateLock) { acknowledgedResult }

            val prior = synchronized(stateLock) { acknowledgedObservation }
            if (
                prior != null &&
                command.observation != null &&
                prior.sessionId != command.observation.sessionId
            ) {
                when (val cleared = clear()) {
                    is ArcadeObservationWriteResult.Failure -> return@withLock cleared
                    is ArcadeObservationWriteResult.Cleared -> {
                        synchronized(stateLock) {
                            acknowledgedObservation = null
                            acknowledgedResult = cleared
                        }
                    }
                    is ArcadeObservationWriteResult.Published ->
                        error("Arcade clear returned a publish acknowledgement")
                }
            }

            val result = command.observation?.let { publish(it) } ?: clear()
            if (result is ArcadeObservationWriteResult.Failure) return@withLock result
            val isLatest = synchronized(stateLock) {
                acknowledgedRevision = command.revision
                acknowledgedObservation = command.observation
                acknowledgedResult = result
                desired?.revision == command.revision
            }
            if (isLatest) {
                return@withLock result
            }
        }
        error("Unreachable")
    }

    suspend fun prepareForProvider(): ArcadeObservationWriteResult {
        val result = flushLatest()
        if (result is ArcadeObservationWriteResult.Failure) return result
        return synchronized(stateLock) {
            val current = desired
            val acknowledged = acknowledgedObservation
            if (
                current != null &&
                current.revision == acknowledgedRevision &&
                current.observation != null &&
                acknowledged != null &&
                current.observation.sessionId == acknowledged.sessionId &&
                current.observation.sequence == acknowledged.sequence &&
                acknowledgedResult is ArcadeObservationWriteResult.Published
            ) {
                acknowledgedResult
            } else {
                ArcadeObservationWriteResult.Failure(
                    "Current Arcade gameplay context has not been acknowledged; no provider request was made.",
                )
            }
        }
    }

    private data class Command(
        val revision: Long,
        val observation: ArcadeGameObservation?,
    )
}
