package com.shai.riven.ui.arcade.comet

const val COMET_TRAIL_COLUMNS = 12
const val COMET_TRAIL_ROWS = 12

data class TrailPoint(
    val x: Int,
    val y: Int,
)

enum class TrailDirection(
    val dx: Int,
    val dy: Int,
) {
    UP(0, -1),
    RIGHT(1, 0),
    DOWN(0, 1),
    LEFT(-1, 0),
    ;

    fun opposite(): TrailDirection = when (this) {
        UP -> DOWN
        RIGHT -> LEFT
        DOWN -> UP
        LEFT -> RIGHT
    }
}

data class CometTrailState(
    val body: List<TrailPoint>,
    val direction: TrailDirection,
    val food: TrailPoint,
    val randomState: Long,
    val treatsEaten: Int = 0,
    val stepsTaken: Int = 0,
    val boardRefreshes: Int = 0,
    val collisionDirection: TrailDirection? = null,
) {
    init {
        require(body.isNotEmpty())
        require(body.size <= COMET_TRAIL_COLUMNS * COMET_TRAIL_ROWS)
        require(body.distinct().size == body.size)
        require(body.all(::isOnTrailBoard))
        require(isOnTrailBoard(food))
        require(treatsEaten >= 0)
        require(stepsTaken >= 0)
        require(boardRefreshes >= 0)
    }

    val head: TrailPoint
        get() = body.first()
}

object CometTrailEngine {
    private const val DEFAULT_RANDOM_STATE = 0xC0FE771L

    fun newGame(seed: Long = DEFAULT_RANDOM_STATE): CometTrailState {
        val body = initialBody()
        val placement = placeFood(normalizeSeed(seed), body)
        return CometTrailState(
            body = body,
            direction = TrailDirection.RIGHT,
            food = placement.point,
            randomState = placement.randomState,
        )
    }

    fun turn(
        state: CometTrailState,
        direction: TrailDirection,
    ): CometTrailState {
        if (state.collisionDirection != null) {
            return if (wouldCollide(state, direction)) {
                state.copy(collisionDirection = direction)
            } else {
                state.copy(direction = direction, collisionDirection = null)
            }
        }
        if (direction == state.direction.opposite()) return state
        return state.copy(direction = direction)
    }

    fun tick(state: CometTrailState): CometTrailState {
        if (state.collisionDirection != null) return state
        val nextHead = wrappedStep(state.head, state.direction)
        val growing = nextHead == state.food
        val collisionBody = if (growing) state.body else state.body.dropLast(1)
        if (nextHead in collisionBody) {
            return state.copy(collisionDirection = state.direction)
        }

        val movedBody = if (growing) {
            listOf(nextHead) + state.body
        } else {
            listOf(nextHead) + state.body.dropLast(1)
        }
        if (!growing) {
            return state.copy(
                body = movedBody,
                stepsTaken = state.stepsTaken + 1,
            )
        }

        val eaten = state.treatsEaten + 1
        val steps = state.stepsTaken + 1
        if (movedBody.size == COMET_TRAIL_COLUMNS * COMET_TRAIL_ROWS) {
            val refreshedBody = initialBody()
            val placement = placeFood(state.randomState, refreshedBody)
            return CometTrailState(
                body = refreshedBody,
                direction = TrailDirection.RIGHT,
                food = placement.point,
                randomState = placement.randomState,
                treatsEaten = eaten,
                stepsTaken = steps,
                boardRefreshes = state.boardRefreshes + 1,
            )
        }

        val placement = placeFood(state.randomState, movedBody)
        return state.copy(
            body = movedBody,
            food = placement.point,
            randomState = placement.randomState,
            treatsEaten = eaten,
            stepsTaken = steps,
        )
    }

    fun wrappedStep(
        point: TrailPoint,
        direction: TrailDirection,
    ): TrailPoint = TrailPoint(
        x = floorMod(point.x + direction.dx, COMET_TRAIL_COLUMNS),
        y = floorMod(point.y + direction.dy, COMET_TRAIL_ROWS),
    )

    fun wouldCollide(
        state: CometTrailState,
        direction: TrailDirection,
    ): Boolean {
        val nextHead = wrappedStep(state.head, direction)
        val growing = nextHead == state.food
        val collisionBody = if (growing) state.body else state.body.dropLast(1)
        return nextHead in collisionBody
    }

    fun isConnectedBody(body: List<TrailPoint>): Boolean = body.zipWithNext().all { (first, second) ->
        TrailDirection.entries.any { direction -> wrappedStep(first, direction) == second }
    }

    private fun initialBody(): List<TrailPoint> = listOf(
        TrailPoint(6, 6),
        TrailPoint(5, 6),
        TrailPoint(4, 6),
    )

    private data class FoodPlacement(
        val point: TrailPoint,
        val randomState: Long,
    )

    private fun placeFood(
        randomState: Long,
        body: List<TrailPoint>,
    ): FoodPlacement {
        val occupied = body.toSet()
        val available = buildList {
            for (y in 0 until COMET_TRAIL_ROWS) {
                for (x in 0 until COMET_TRAIL_COLUMNS) {
                    TrailPoint(x, y).takeIf { it !in occupied }?.let(::add)
                }
            }
        }
        require(available.isNotEmpty())
        val nextRandomState = nextRandom(randomState)
        val index = ((nextRandomState ushr 1) % available.size).toInt()
        return FoodPlacement(available[index], nextRandomState)
    }

    private fun floorMod(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus

    private fun normalizeSeed(seed: Long): Long = if (seed == 0L) DEFAULT_RANDOM_STATE else seed

    private fun nextRandom(state: Long): Long {
        var value = normalizeSeed(state)
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        return value
    }
}

fun isOnTrailBoard(point: TrailPoint): Boolean =
    point.x in 0 until COMET_TRAIL_COLUMNS && point.y in 0 until COMET_TRAIL_ROWS
