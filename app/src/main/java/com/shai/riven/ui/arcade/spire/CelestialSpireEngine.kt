package com.shai.riven.ui.arcade.spire

const val CELESTIAL_SPIRE_COLUMNS = 10
const val CELESTIAL_SPIRE_ROWS = 20

enum class SpirePieceType(val code: Char) {
    I('I'),
    O('O'),
    T('T'),
    S('S'),
    Z('Z'),
    J('J'),
    L('L'),
    ;

    companion object {
        fun fromCode(code: Char): SpirePieceType? = entries.firstOrNull { it.code == code }
    }
}

data class SpirePoint(
    val x: Int,
    val y: Int,
)

data class SpirePiece(
    val type: SpirePieceType,
    val rotation: Int,
    val x: Int,
    val y: Int,
)

data class CelestialSpireState(
    val board: List<SpirePieceType?>,
    val active: SpirePiece,
    val next: SpirePieceType,
    val bag: List<SpirePieceType>,
    val randomState: Long,
    val linesCleared: Int = 0,
    val piecesPlaced: Int = 0,
    val boardRefreshes: Int = 0,
) {
    init {
        require(board.size == CELESTIAL_SPIRE_COLUMNS * CELESTIAL_SPIRE_ROWS)
        require(active.rotation in 0..3)
        require(linesCleared >= 0)
        require(piecesPlaced >= 0)
        require(boardRefreshes >= 0)
    }

    fun cell(x: Int, y: Int): SpirePieceType? = board[y * CELESTIAL_SPIRE_COLUMNS + x]
}

enum class SpireAction {
    MOVE_LEFT,
    MOVE_RIGHT,
    ROTATE_CLOCKWISE,
    SOFT_DROP,
    HARD_DROP,
    TICK,
}

enum class SpireSoundCue {
    PIECE_LANDED,
    LINE_CLEARED,
}

data class SpireStepResult(
    val state: CelestialSpireState,
    val sounds: List<SpireSoundCue> = emptyList(),
)

object CelestialSpireEngine {
    private const val DEFAULT_RANDOM_STATE = 0x5EED5EEDL

    fun newGame(seed: Long = DEFAULT_RANDOM_STATE): CelestialSpireState {
        val first = drawPiece(normalizeSeed(seed), emptyList())
        val second = drawPiece(first.randomState, first.bag)
        return CelestialSpireState(
            board = emptyBoard(),
            active = spawn(first.type),
            next = second.type,
            bag = second.bag,
            randomState = second.randomState,
        )
    }

    fun step(
        state: CelestialSpireState,
        action: SpireAction,
    ): SpireStepResult = when (action) {
        SpireAction.MOVE_LEFT -> moveHorizontally(state, delta = -1)
        SpireAction.MOVE_RIGHT -> moveHorizontally(state, delta = 1)
        SpireAction.ROTATE_CLOCKWISE -> rotateClockwise(state)
        SpireAction.SOFT_DROP,
        SpireAction.TICK,
        -> moveDownOrLock(state)

        SpireAction.HARD_DROP -> hardDrop(state)
    }

    fun cells(piece: SpirePiece): List<SpirePoint> = shape(piece.type, piece.rotation).map { point ->
        SpirePoint(x = piece.x + point.x, y = piece.y + point.y)
    }

    fun ghostPiece(state: CelestialSpireState): SpirePiece {
        var ghost = state.active
        while (canPlace(state.board, ghost.copy(y = ghost.y + 1))) {
            ghost = ghost.copy(y = ghost.y + 1)
        }
        return ghost
    }

    fun canPlace(
        board: List<SpirePieceType?>,
        piece: SpirePiece,
    ): Boolean = cells(piece).all { point ->
        point.x in 0 until CELESTIAL_SPIRE_COLUMNS &&
            point.y < CELESTIAL_SPIRE_ROWS &&
            (point.y < 0 || board[point.y * CELESTIAL_SPIRE_COLUMNS + point.x] == null)
    }

    private fun moveHorizontally(
        state: CelestialSpireState,
        delta: Int,
    ): SpireStepResult {
        val moved = state.active.copy(x = state.active.x + delta)
        return if (canPlace(state.board, moved)) {
            SpireStepResult(state.copy(active = moved))
        } else {
            SpireStepResult(state)
        }
    }

    private fun rotateClockwise(state: CelestialSpireState): SpireStepResult {
        val rotation = (state.active.rotation + 1) % 4
        val kickOffsets = listOf(
            SpirePoint(0, 0),
            SpirePoint(-1, 0),
            SpirePoint(1, 0),
            SpirePoint(-2, 0),
            SpirePoint(2, 0),
            SpirePoint(0, -1),
        )
        val rotated = kickOffsets.firstNotNullOfOrNull { kick ->
            state.active.copy(
                rotation = rotation,
                x = state.active.x + kick.x,
                y = state.active.y + kick.y,
            ).takeIf { candidate -> canPlace(state.board, candidate) }
        }
        return SpireStepResult(rotated?.let { state.copy(active = it) } ?: state)
    }

    private fun moveDownOrLock(state: CelestialSpireState): SpireStepResult {
        val moved = state.active.copy(y = state.active.y + 1)
        return if (canPlace(state.board, moved)) {
            SpireStepResult(state.copy(active = moved))
        } else {
            lock(state)
        }
    }

    private fun hardDrop(state: CelestialSpireState): SpireStepResult {
        var dropped = state.active
        while (canPlace(state.board, dropped.copy(y = dropped.y + 1))) {
            dropped = dropped.copy(y = dropped.y + 1)
        }
        return lock(state.copy(active = dropped))
    }

    private fun lock(state: CelestialSpireState): SpireStepResult {
        val merged = state.board.toMutableList()
        cells(state.active).forEach { point ->
            if (point.y in 0 until CELESTIAL_SPIRE_ROWS) {
                merged[point.y * CELESTIAL_SPIRE_COLUMNS + point.x] = state.active.type
            }
        }
        val (clearedBoard, clearedLines) = clearCompleteLines(merged)
        val draw = drawPiece(state.randomState, state.bag)
        val nextActive = spawn(state.next)
        val sounds = buildList {
            add(SpireSoundCue.PIECE_LANDED)
            if (clearedLines > 0) add(SpireSoundCue.LINE_CLEARED)
        }
        if (!canPlace(clearedBoard, nextActive)) {
            return SpireStepResult(
                state = CelestialSpireState(
                    board = emptyBoard(),
                    active = nextActive,
                    next = draw.type,
                    bag = draw.bag,
                    randomState = draw.randomState,
                    boardRefreshes = state.boardRefreshes + 1,
                ),
                sounds = sounds,
            )
        }
        return SpireStepResult(
            state = state.copy(
                board = clearedBoard,
                active = nextActive,
                next = draw.type,
                bag = draw.bag,
                randomState = draw.randomState,
                linesCleared = state.linesCleared + clearedLines,
                piecesPlaced = state.piecesPlaced + 1,
            ),
            sounds = sounds,
        )
    }

    private fun clearCompleteLines(
        board: List<SpirePieceType?>,
    ): Pair<List<SpirePieceType?>, Int> {
        val rows = board.chunked(CELESTIAL_SPIRE_COLUMNS)
        val remaining = rows.filterNot { row -> row.all { it != null } }
        val cleared = CELESTIAL_SPIRE_ROWS - remaining.size
        val emptyRows = List(cleared) { List<SpirePieceType?>(CELESTIAL_SPIRE_COLUMNS) { null } }
        return (emptyRows + remaining).flatten() to cleared
    }

    private fun spawn(type: SpirePieceType): SpirePiece = SpirePiece(
        type = type,
        rotation = 0,
        x = 3,
        y = 0,
    )

    private fun emptyBoard(): List<SpirePieceType?> =
        List(CELESTIAL_SPIRE_COLUMNS * CELESTIAL_SPIRE_ROWS) { null }

    private data class PieceDraw(
        val type: SpirePieceType,
        val randomState: Long,
        val bag: List<SpirePieceType>,
    )

    private fun drawPiece(
        randomState: Long,
        bag: List<SpirePieceType>,
    ): PieceDraw {
        var nextRandomState = randomState
        val available = if (bag.isEmpty()) {
            val shuffled = SpirePieceType.entries.toMutableList()
            for (index in shuffled.lastIndex downTo 1) {
                nextRandomState = nextRandom(nextRandomState)
                val swapIndex = ((nextRandomState ushr 1) % (index + 1)).toInt()
                val value = shuffled[index]
                shuffled[index] = shuffled[swapIndex]
                shuffled[swapIndex] = value
            }
            shuffled
        } else {
            bag
        }
        return PieceDraw(
            type = available.first(),
            randomState = nextRandomState,
            bag = available.drop(1),
        )
    }

    private fun normalizeSeed(seed: Long): Long = if (seed == 0L) DEFAULT_RANDOM_STATE else seed

    private fun nextRandom(state: Long): Long {
        var value = normalizeSeed(state)
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        return value
    }

    private fun shape(type: SpirePieceType, rotation: Int): List<SpirePoint> = SHAPES.getValue(type)[rotation % 4]

    private val SHAPES: Map<SpirePieceType, List<List<SpirePoint>>> = mapOf(
        SpirePieceType.I to listOf(
            points(0, 1, 1, 1, 2, 1, 3, 1),
            points(2, 0, 2, 1, 2, 2, 2, 3),
            points(0, 2, 1, 2, 2, 2, 3, 2),
            points(1, 0, 1, 1, 1, 2, 1, 3),
        ),
        SpirePieceType.O to List(4) { points(1, 0, 2, 0, 1, 1, 2, 1) },
        SpirePieceType.T to listOf(
            points(1, 0, 0, 1, 1, 1, 2, 1),
            points(1, 0, 1, 1, 2, 1, 1, 2),
            points(0, 1, 1, 1, 2, 1, 1, 2),
            points(1, 0, 0, 1, 1, 1, 1, 2),
        ),
        SpirePieceType.S to listOf(
            points(1, 0, 2, 0, 0, 1, 1, 1),
            points(1, 0, 1, 1, 2, 1, 2, 2),
            points(1, 1, 2, 1, 0, 2, 1, 2),
            points(0, 0, 0, 1, 1, 1, 1, 2),
        ),
        SpirePieceType.Z to listOf(
            points(0, 0, 1, 0, 1, 1, 2, 1),
            points(2, 0, 1, 1, 2, 1, 1, 2),
            points(0, 1, 1, 1, 1, 2, 2, 2),
            points(1, 0, 0, 1, 1, 1, 0, 2),
        ),
        SpirePieceType.J to listOf(
            points(0, 0, 0, 1, 1, 1, 2, 1),
            points(1, 0, 2, 0, 1, 1, 1, 2),
            points(0, 1, 1, 1, 2, 1, 2, 2),
            points(1, 0, 1, 1, 0, 2, 1, 2),
        ),
        SpirePieceType.L to listOf(
            points(2, 0, 0, 1, 1, 1, 2, 1),
            points(1, 0, 1, 1, 1, 2, 2, 2),
            points(0, 1, 1, 1, 2, 1, 0, 2),
            points(0, 0, 1, 0, 1, 1, 1, 2),
        ),
    )

    private fun points(vararg coordinates: Int): List<SpirePoint> = coordinates
        .asList()
        .chunked(2)
        .map { (x, y) -> SpirePoint(x, y) }
}
