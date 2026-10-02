package com.shai.riven.ui.arcade.comet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CometTrailEngineTest {
    @Test
    fun sameSeedProducesTheSameSafeFoodPlacement() {
        val first = CometTrailEngine.newGame(seed = 41L)
        val second = CometTrailEngine.newGame(seed = 41L)

        assertEquals(first, second)
        assertTrue(first.food !in first.body)
        assertTrue(CometTrailEngine.isConnectedBody(first.body))
    }

    @Test
    fun movementWrapsAtEveryEdge() {
        val cases = listOf(
            Triple(TrailPoint(0, 4), TrailDirection.LEFT, TrailPoint(COMET_TRAIL_COLUMNS - 1, 4)),
            Triple(TrailPoint(COMET_TRAIL_COLUMNS - 1, 4), TrailDirection.RIGHT, TrailPoint(0, 4)),
            Triple(TrailPoint(4, 0), TrailDirection.UP, TrailPoint(4, COMET_TRAIL_ROWS - 1)),
            Triple(TrailPoint(4, COMET_TRAIL_ROWS - 1), TrailDirection.DOWN, TrailPoint(4, 0)),
        )

        cases.forEach { (head, direction, expected) ->
            val state = fixture(body = listOf(head), direction = direction, food = TrailPoint(8, 8))
            assertEquals(expected, CometTrailEngine.tick(state).head)
        }
    }

    @Test
    fun eatingGrowsTheTrailAndPlacesAnotherSafeTreat() {
        val state = fixture(
            body = listOf(TrailPoint(5, 5), TrailPoint(4, 5), TrailPoint(3, 5)),
            direction = TrailDirection.RIGHT,
            food = TrailPoint(6, 5),
        )

        val result = CometTrailEngine.tick(state)

        assertEquals(4, result.body.size)
        assertEquals(TrailPoint(6, 5), result.head)
        assertEquals(1, result.treatsEaten)
        assertEquals(1, result.stepsTaken)
        assertTrue(result.food !in result.body)
    }

    @Test
    fun predictedSelfContactPausesBeforeMovementAndSafeTurnContinues() {
        val body = listOf(
            TrailPoint(2, 2),
            TrailPoint(2, 3),
            TrailPoint(1, 3),
            TrailPoint(1, 2),
            TrailPoint(1, 1),
        )
        val state = fixture(body = body, direction = TrailDirection.LEFT, food = TrailPoint(9, 9))

        val paused = CometTrailEngine.tick(state)

        assertEquals(body, paused.body)
        assertEquals(0, paused.stepsTaken)
        assertEquals(TrailDirection.LEFT, paused.collisionDirection)

        val redirected = CometTrailEngine.turn(paused, TrailDirection.UP)
        assertNull(redirected.collisionDirection)
        assertEquals(body, redirected.body)
        val continued = CometTrailEngine.tick(redirected)
        assertEquals(TrailPoint(2, 1), continued.head)
        assertEquals(1, continued.stepsTaken)
    }

    @Test
    fun unsafeTurnDuringCollisionPauseKeepsTheSafeSnapshotIntact() {
        val body = listOf(
            TrailPoint(2, 2),
            TrailPoint(2, 3),
            TrailPoint(1, 3),
            TrailPoint(1, 2),
            TrailPoint(1, 1),
        )
        val paused = CometTrailEngine.tick(
            fixture(body = body, direction = TrailDirection.LEFT, food = TrailPoint(9, 9)),
        )

        val stillPaused = CometTrailEngine.turn(paused, TrailDirection.DOWN)

        assertEquals(body, stillPaused.body)
        assertEquals(TrailDirection.DOWN, stillPaused.collisionDirection)
        assertEquals(0, stillPaused.stepsTaken)
    }

    @Test
    fun reverseTurnIsIgnoredDuringOrdinaryPlay() {
        val state = CometTrailEngine.newGame(seed = 5L)

        assertEquals(state, CometTrailEngine.turn(state, TrailDirection.LEFT))
    }

    @Test
    fun fillingTheBoardStartsAFreshOrbitWithoutLoss() {
        val path = buildList {
            for (y in 0 until COMET_TRAIL_ROWS) {
                val columns = if (y % 2 == 0) {
                    0 until COMET_TRAIL_COLUMNS
                } else {
                    (COMET_TRAIL_COLUMNS - 1 downTo 0)
                }
                columns.forEach { x -> add(TrailPoint(x, y)) }
            }
        }
        val state = fixture(
            body = path.drop(1),
            direction = TrailDirection.LEFT,
            food = path.first(),
        ).copy(treatsEaten = 20, stepsTaken = 500)

        val result = CometTrailEngine.tick(state)

        assertEquals(3, result.body.size)
        assertEquals(21, result.treatsEaten)
        assertEquals(501, result.stepsTaken)
        assertEquals(1, result.boardRefreshes)
        assertTrue(result.food !in result.body)
    }

    @Test
    fun speedChangesOnlyThroughExplicitSettingSelection() {
        val settings = CometTrailSettings(speed = TrailSpeed.GENTLE)
        var state = CometTrailEngine.newGame(seed = 99L)
        repeat(30) { state = CometTrailEngine.tick(state) }

        assertEquals(TrailSpeed.GENTLE, settings.speed)
        assertEquals(650L, settings.speed.tickMillis)
        assertEquals(TrailSpeed.EASY, settings.copy(speed = settings.speed.next()).speed)
        assertTrue(state.stepsTaken > 0)
    }

    private fun fixture(
        body: List<TrailPoint>,
        direction: TrailDirection,
        food: TrailPoint,
    ): CometTrailState = CometTrailState(
        body = body,
        direction = direction,
        food = food,
        randomState = 17L,
    )
}
