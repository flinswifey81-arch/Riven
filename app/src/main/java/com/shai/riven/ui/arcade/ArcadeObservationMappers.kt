package com.shai.riven.ui.arcade

import com.shai.riven.data.arcade.ArcadeGameObservation
import com.shai.riven.data.arcade.ArcadeObservationFact
import com.shai.riven.data.arcade.ArcadeObservationView
import com.shai.riven.data.arcade.ArcadePublicEvent
import com.shai.riven.ui.arcade.comet.CometTrailState
import com.shai.riven.ui.arcade.solitaire.MidnightSolitaireState
import com.shai.riven.ui.arcade.spire.CelestialSpireState
import com.shai.riven.ui.arcade.starstruck.StarPower
import com.shai.riven.ui.arcade.starstruck.StarstruckState

internal fun CelestialSpireState.toArcadeObservation(
    sessionId: String,
    sequence: Long,
    paused: Boolean,
    event: String?,
    observedAt: Long,
): ArcadeGameObservation = soloObservation(
    game = ArcadeGame.STACKER,
    sessionId = sessionId,
    sequence = sequence,
    phase = if (paused) "paused" else "playing",
    facts = listOf(
        fact("Lines cleared", linesCleared),
        fact("Pieces placed", piecesPlaced),
        fact("Board refreshes", boardRefreshes),
        fact("Occupied cells", board.count { it != null }),
        fact("Next piece", next.name),
    ),
    event = event,
    observedAt = observedAt,
)

internal fun StarstruckState.toArcadeObservation(
    sessionId: String,
    sequence: Long,
    paused: Boolean,
    event: String?,
    observedAt: Long,
): ArcadeGameObservation = soloObservation(
    game = ArcadeGame.HEART_MATCH,
    sessionId = sessionId,
    sequence = sequence,
    phase = if (paused) "paused" else "playing",
    facts = listOf(
        fact("Moves", movesMade),
        fact("Matches", matchesMade),
        fact("Cascade waves", cascadeWaves),
        fact("Tiles cleared", tilesCleared),
        fact("Reshuffles", reshuffles),
        fact("Visible power tiles", board.count { it.power != StarPower.NONE }),
    ),
    event = event,
    observedAt = observedAt,
)

internal fun CometTrailState.toArcadeObservation(
    sessionId: String,
    sequence: Long,
    paused: Boolean,
    event: String?,
    observedAt: Long,
): ArcadeGameObservation = soloObservation(
    game = ArcadeGame.WRAPPING_SNAKE,
    sessionId = sessionId,
    sequence = sequence,
    phase = when {
        collisionDirection != null -> "waiting for a safe turn"
        paused -> "paused"
        else -> "gliding"
    },
    facts = listOf(
        fact("Treats collected", treatsEaten),
        fact("Trail length", body.size),
        fact("Board refreshes", boardRefreshes),
        fact("Direction", direction.name.lowercase()),
    ),
    event = event,
    observedAt = observedAt,
)

internal fun MidnightSolitaireState.toArcadeObservation(
    sessionId: String,
    sequence: Long,
    paused: Boolean,
    event: String?,
    observedAt: Long,
): ArcadeGameObservation = soloObservation(
    game = ArcadeGame.KLONDIKE,
    sessionId = sessionId,
    sequence = sequence,
    phase = when {
        status.name == "WON" -> "complete"
        paused -> "paused"
        else -> "playing"
    },
    facts = listOf(
        fact("Deal number", dealNumber),
        fact("Moves", moves),
        fact("Foundation cards", foundations.sumOf { pile -> pile.size }),
        fact("Stock cards", stock.size),
        fact("Waste cards", waste.size),
        fact("Face-up tableau cards", tableau.sumOf { pile -> pile.count { it.faceUp } }),
        fact("Recycles", recycles),
    ),
    event = event,
    observedAt = observedAt,
)

private fun soloObservation(
    game: ArcadeGame,
    sessionId: String,
    sequence: Long,
    phase: String,
    facts: List<ArcadeObservationFact>,
    event: String?,
    observedAt: Long,
) = ArcadeGameObservation(
    gameId = game.gameId,
    gameTitle = game.title,
    sessionId = sessionId,
    sequence = sequence,
    view = ArcadeObservationView.SOLO_PUBLIC,
    phase = phase,
    facts = facts,
    recentEvents = event?.let { listOf(ArcadePublicEvent(sequence, it)) }.orEmpty(),
    observedAt = observedAt,
)

private fun fact(label: String, value: Any) = ArcadeObservationFact(label, value.toString())

internal data class ArcadeObservationSignature(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val paused: Boolean,
    val sessionMarker: Int = 0,
)

internal fun meaningfulArcadeEvent(
    game: ArcadeGame,
    previous: ArcadeObservationSignature?,
    current: ArcadeObservationSignature,
): String = when {
    previous == null -> "${game.title} opened."
    previous.sessionMarker != current.sessionMarker -> "A fresh ${game.title} session began."
    !previous.paused && current.paused -> "The table paused."
    previous.paused && !current.paused -> "Play resumed."
    game == ArcadeGame.STACKER && current.tertiary > previous.tertiary -> "The board refreshed after reaching the top."
    game == ArcadeGame.STACKER && current.secondary > previous.secondary ->
        "Cleared ${current.secondary - previous.secondary} line(s)."
    game == ArcadeGame.STACKER -> "Placed a piece."
    game == ArcadeGame.HEART_MATCH && current.tertiary > previous.tertiary -> "The board reshuffled."
    game == ArcadeGame.HEART_MATCH -> "Completed a match move."
    game == ArcadeGame.WRAPPING_SNAKE && current.tertiary > previous.tertiary -> "The trail board refreshed."
    game == ArcadeGame.WRAPPING_SNAKE && current.secondary > previous.secondary -> "Collected a comet treat."
    game == ArcadeGame.WRAPPING_SNAKE -> "The trail needs a safe turn."
    game == ArcadeGame.KLONDIKE -> "Completed a legal solitaire move."
    else -> "The public game state changed."
}

class ArcadeCommentaryGate(
    private val minimumIntervalMillis: Long = 90_000L,
    private val minimumSequenceGap: Long = 2L,
) {
    private var lastSessionId: String? = null
    private var lastSuggestedSequence = 0L
    private var lastSuggestedAt: Long? = null

    init {
        require(minimumIntervalMillis >= 0L)
        require(minimumSequenceGap >= 1L)
    }

    /** Returns a local cue only. This gate never invokes a provider or mutates conversation state. */
    fun consider(
        enabled: Boolean,
        observation: ArcadeGameObservation,
        now: Long = observation.observedAt,
    ): String? {
        if (!enabled || observation.recentEvents.isEmpty()) return null
        if (observation.sessionId != lastSessionId) {
            lastSessionId = observation.sessionId
            lastSuggestedSequence = 0L
            lastSuggestedAt = null
        }
        if (observation.sequence - lastSuggestedSequence < minimumSequenceGap) return null
        val previousAt = lastSuggestedAt
        if (previousAt != null && now - previousAt < minimumIntervalMillis) return null
        lastSuggestedSequence = observation.sequence
        lastSuggestedAt = now
        val event = observation.recentEvents.last().summary.removeSuffix(".")
        return "$event. Open chat if you want Riven's take; nothing is sent automatically."
    }
}
