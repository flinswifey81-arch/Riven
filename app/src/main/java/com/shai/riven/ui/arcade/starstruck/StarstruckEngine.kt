package com.shai.riven.ui.arcade.starstruck

const val STARSTRUCK_COLUMNS = 7
const val STARSTRUCK_ROWS = 7

data class StarPoint(
    val x: Int,
    val y: Int,
)

enum class StarTileKind(val code: Char) {
    MOON('M'),
    HEART('H'),
    STAR('S'),
    COMET('C'),
    PLANET('P'),
    CRYSTAL('R'),
    ;

    companion object {
        fun fromCode(code: Char): StarTileKind? = entries.firstOrNull { it.code == code }
    }
}

enum class StarPower(val code: Char) {
    NONE('N'),
    ROW_CLEAR('W'),
    COLOR_CLEAR('A'),
    ;

    companion object {
        fun fromCode(code: Char): StarPower? = entries.firstOrNull { it.code == code }
    }
}

data class StarTile(
    val kind: StarTileKind,
    val power: StarPower = StarPower.NONE,
)

data class StarstruckState(
    val board: List<StarTile>,
    val randomState: Long,
    val movesMade: Int = 0,
    val matchesMade: Int = 0,
    val cascadeWaves: Int = 0,
    val tilesCleared: Int = 0,
    val reshuffles: Int = 0,
) {
    init {
        require(board.size == STARSTRUCK_COLUMNS * STARSTRUCK_ROWS)
        require(movesMade >= 0)
        require(matchesMade >= 0)
        require(cascadeWaves >= 0)
        require(tilesCleared >= 0)
        require(reshuffles >= 0)
    }

    operator fun get(point: StarPoint): StarTile = board[starIndex(point)]
}

enum class StarstruckSoundCue {
    SWAP,
    CASCADE,
    POWERUP,
    RESHUFFLE,
}

data class StarstruckMoveResult(
    val state: StarstruckState,
    val accepted: Boolean,
    val resolutionWaves: Int = 0,
    val clearedTiles: Int = 0,
    val powerupsCreated: Int = 0,
    val powerupsActivated: Int = 0,
    val powerupsActivatedAtCreationAnchors: Int = 0,
    val cascadeAnchorPowerupsActivated: Int = 0,
    val reshuffled: Boolean = false,
    val sounds: List<StarstruckSoundCue> = emptyList(),
)

object StarstruckEngine {
    private const val DEFAULT_RANDOM_STATE = 0x57A2572L
    private const val MAX_INITIAL_ATTEMPTS = 512
    private const val MAX_CASCADE_WAVES = 128

    fun newGame(seed: Long = DEFAULT_RANDOM_STATE): StarstruckState {
        val generated = generatePlayableBoard(normalizeSeed(seed))
        return StarstruckState(
            board = generated.board,
            randomState = generated.randomState,
        )
    }

    fun swap(
        state: StarstruckState,
        first: StarPoint,
        second: StarPoint,
    ): StarstruckMoveResult = swap(state, first, second, MAX_CASCADE_WAVES)

    internal fun swap(
        state: StarstruckState,
        first: StarPoint,
        second: StarPoint,
        cascadeWaveLimit: Int,
    ): StarstruckMoveResult {
        require(cascadeWaveLimit > 0)
        if (!isOnBoard(first) || !isOnBoard(second) || !areAdjacent(first, second)) {
            return StarstruckMoveResult(state = state, accepted = false)
        }

        val firstTile = state[first]
        val secondTile = state[second]
        val swapped = state.board.toMutableList().apply {
            this[starIndex(first)] = secondTile
            this[starIndex(second)] = firstTile
        }.toList()
        val directColorClear = colorSwapClear(state.board, first, second)
        val initialRuns = findRuns(swapped)
        if (directColorClear == null && initialRuns.isEmpty()) {
            return StarstruckMoveResult(state = state, accepted = false)
        }

        val resolved = resolve(
            board = swapped,
            randomState = state.randomState,
            initialForcedClear = directColorClear?.points,
            initialSuppressedPowers = directColorClear?.suppressedPowers.orEmpty(),
            preferredAnchors = listOf(second, first),
            cascadeWaveLimit = cascadeWaveLimit,
        )
        var nextState = state.copy(
            board = resolved.board,
            randomState = resolved.randomState,
            movesMade = state.movesMade + 1,
            matchesMade = state.matchesMade + 1,
            cascadeWaves = state.cascadeWaves + (resolved.waves - 1).coerceAtLeast(0),
            tilesCleared = state.tilesCleared + resolved.clearedTiles,
            reshuffles = state.reshuffles + if (resolved.safetyRecovered) 1 else 0,
        )
        val playable = ensurePlayable(nextState)
        val reshuffled = resolved.safetyRecovered || playable !== nextState
        nextState = playable

        val sounds = buildList {
            add(StarstruckSoundCue.SWAP)
            if (resolved.waves > 1) add(StarstruckSoundCue.CASCADE)
            if (resolved.powerupsCreated > 0 || resolved.powerupsActivated > 0 || directColorClear != null) {
                add(StarstruckSoundCue.POWERUP)
            }
            if (reshuffled) add(StarstruckSoundCue.RESHUFFLE)
        }
        return StarstruckMoveResult(
            state = nextState,
            accepted = true,
            resolutionWaves = resolved.waves,
            clearedTiles = resolved.clearedTiles,
            powerupsCreated = resolved.powerupsCreated,
            powerupsActivated = resolved.powerupsActivated,
            powerupsActivatedAtCreationAnchors = resolved.powerupsActivatedAtCreationAnchors,
            cascadeAnchorPowerupsActivated = resolved.cascadeAnchorPowerupsActivated,
            reshuffled = reshuffled,
            sounds = sounds,
        )
    }

    fun ensurePlayable(state: StarstruckState): StarstruckState {
        if (!hasImmediateMatches(state.board) && hasLegalMove(state.board)) return state
        val reshuffled = reshuffle(state.board, state.randomState)
        return state.copy(
            board = reshuffled.board,
            randomState = reshuffled.randomState,
            reshuffles = state.reshuffles + 1,
        )
    }

    fun hasImmediateMatches(board: List<StarTile>): Boolean = findRuns(board).isNotEmpty()

    fun hasLegalMove(board: List<StarTile>): Boolean {
        require(board.size == STARSTRUCK_COLUMNS * STARSTRUCK_ROWS)
        for (y in 0 until STARSTRUCK_ROWS) {
            for (x in 0 until STARSTRUCK_COLUMNS) {
                val first = StarPoint(x, y)
                listOf(StarPoint(x + 1, y), StarPoint(x, y + 1))
                    .filter(::isOnBoard)
                    .forEach { second ->
                        if (board[starIndex(first)].power == StarPower.COLOR_CLEAR ||
                            board[starIndex(second)].power == StarPower.COLOR_CLEAR
                        ) {
                            return true
                        }
                        val swapped = board.toMutableList().apply {
                            val firstIndex = starIndex(first)
                            val secondIndex = starIndex(second)
                            val held = this[firstIndex]
                            this[firstIndex] = this[secondIndex]
                            this[secondIndex] = held
                        }
                        if (findRuns(swapped).isNotEmpty()) return true
                    }
            }
        }
        return false
    }

    fun legalMoves(board: List<StarTile>): List<Pair<StarPoint, StarPoint>> = buildList {
        for (y in 0 until STARSTRUCK_ROWS) {
            for (x in 0 until STARSTRUCK_COLUMNS) {
                val first = StarPoint(x, y)
                listOf(StarPoint(x + 1, y), StarPoint(x, y + 1))
                    .filter(::isOnBoard)
                    .filter { second ->
                        val firstTile = board[starIndex(first)]
                        val secondTile = board[starIndex(second)]
                        if (firstTile.power == StarPower.COLOR_CLEAR || secondTile.power == StarPower.COLOR_CLEAR) {
                            true
                        } else {
                            val swapped = board.toMutableList().apply {
                                this[starIndex(first)] = secondTile
                                this[starIndex(second)] = firstTile
                            }
                            findRuns(swapped).isNotEmpty()
                        }
                    }
                    .forEach { second -> add(first to second) }
            }
        }
    }

    fun areAdjacent(first: StarPoint, second: StarPoint): Boolean =
        kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.y - second.y) == 1

    private fun resolve(
        board: List<StarTile>,
        randomState: Long,
        initialForcedClear: Set<StarPoint>?,
        initialSuppressedPowers: Set<StarPoint>,
        preferredAnchors: List<StarPoint>,
        cascadeWaveLimit: Int,
    ): Resolution {
        var currentBoard = board
        var currentRandom = randomState
        var forcedClear = initialForcedClear
        var suppressedPowers = initialSuppressedPowers
        var preferred = preferredAnchors
        var waves = 0
        var clearedTiles = 0
        var powerupsCreated = 0
        var powerupsActivated = 0
        var powerupsActivatedAtCreationAnchors = 0
        var cascadeAnchorPowerupsActivated = 0

        while (waves < cascadeWaveLimit) {
            val runs = findRuns(currentBoard)
            if (forcedClear == null && runs.isEmpty()) break
            val creations = if (forcedClear == null) powerCreations(runs, preferred) else emptyMap()
            val matched = forcedClear ?: runs.flatMapTo(linkedSetOf()) { it.points }
            val poweredCreationAnchors = creations.keys.count { point ->
                currentBoard[starIndex(point)].power != StarPower.NONE
            }
            val expanded = expandPowerClear(
                board = currentBoard,
                // Earned powers fire before a matched cell becomes the anchor for a new power.
                initial = matched,
                suppressedPowers = suppressedPowers,
            )
            val clearSet = expanded.points - creations.keys
            val falling = currentBoard.map { tile -> tile.copy() }.toMutableList<StarTile?>()
            clearSet.forEach { point -> falling[starIndex(point)] = null }
            creations.forEach { (point, power) ->
                falling[starIndex(point)] = currentBoard[starIndex(point)].copy(power = power)
            }
            val collapsed = collapse(falling, currentRandom)
            currentBoard = collapsed.board
            currentRandom = collapsed.randomState
            clearedTiles += clearSet.size
            powerupsCreated += creations.size
            powerupsActivated += expanded.powerupsActivated
            powerupsActivatedAtCreationAnchors += poweredCreationAnchors
            if (waves > 0) cascadeAnchorPowerupsActivated += poweredCreationAnchors
            waves += 1
            forcedClear = null
            suppressedPowers = emptySet()
            preferred = emptyList()
        }
        val safetyRecovered = findRuns(currentBoard).isNotEmpty()
        if (safetyRecovered) {
            val recovered = reshuffle(currentBoard, currentRandom)
            currentBoard = recovered.board
            currentRandom = recovered.randomState
        }
        return Resolution(
            currentBoard,
            currentRandom,
            waves,
            clearedTiles,
            powerupsCreated,
            powerupsActivated,
            powerupsActivatedAtCreationAnchors,
            cascadeAnchorPowerupsActivated,
            safetyRecovered,
        )
    }

    private fun findRuns(board: List<StarTile>): List<MatchRun> = buildList {
        for (y in 0 until STARSTRUCK_ROWS) {
            var start = 0
            while (start < STARSTRUCK_COLUMNS) {
                val kind = board[starIndex(StarPoint(start, y))].kind
                var end = start + 1
                while (end < STARSTRUCK_COLUMNS && board[starIndex(StarPoint(end, y))].kind == kind) end++
                if (end - start >= 3) {
                    add(MatchRun((start until end).map { x -> StarPoint(x, y) }))
                }
                start = end
            }
        }
        for (x in 0 until STARSTRUCK_COLUMNS) {
            var start = 0
            while (start < STARSTRUCK_ROWS) {
                val kind = board[starIndex(StarPoint(x, start))].kind
                var end = start + 1
                while (end < STARSTRUCK_ROWS && board[starIndex(StarPoint(x, end))].kind == kind) end++
                if (end - start >= 3) {
                    add(MatchRun((start until end).map { y -> StarPoint(x, y) }))
                }
                start = end
            }
        }
    }

    private fun powerCreations(
        runs: List<MatchRun>,
        preferredAnchors: List<StarPoint>,
    ): Map<StarPoint, StarPower> {
        val creations = linkedMapOf<StarPoint, StarPower>()
        runs.filter { it.points.size >= 4 }.forEach { run ->
            val power = if (run.points.size >= 5) StarPower.COLOR_CLEAR else StarPower.ROW_CLEAR
            val anchor = preferredAnchors.firstOrNull { it in run.points } ?: run.points[run.points.size / 2]
            val previous = creations[anchor]
            if (previous == null || power.ordinal > previous.ordinal) creations[anchor] = power
        }
        return creations
    }

    private fun expandPowerClear(
        board: List<StarTile>,
        initial: Set<StarPoint>,
        suppressedPowers: Set<StarPoint>,
    ): ExpandedClear {
        val result = initial.toMutableSet()
        val pending = ArrayDeque(initial)
        val activated = mutableSetOf<StarPoint>()
        var powerupsActivated = 0
        while (pending.isNotEmpty()) {
            val point = pending.removeFirst()
            if (!activated.add(point) || point in suppressedPowers) continue
            val tile = board[starIndex(point)]
            if (tile.power != StarPower.NONE) powerupsActivated++
            val additions = when (tile.power) {
                StarPower.NONE -> emptyList()
                StarPower.ROW_CLEAR -> (0 until STARSTRUCK_COLUMNS).map { x -> StarPoint(x, point.y) }
                StarPower.COLOR_CLEAR -> allPoints().filter { board[starIndex(it)].kind == tile.kind }
            }
            additions.forEach { added ->
                if (result.add(added)) pending.addLast(added)
            }
        }
        return ExpandedClear(result, powerupsActivated)
    }

    private fun collapse(
        board: MutableList<StarTile?>,
        randomState: Long,
    ): GeneratedBoard {
        var currentRandom = randomState
        for (x in 0 until STARSTRUCK_COLUMNS) {
            var writeRow = STARSTRUCK_ROWS - 1
            for (readRow in STARSTRUCK_ROWS - 1 downTo 0) {
                val tile = board[starIndex(StarPoint(x, readRow))] ?: continue
                board[starIndex(StarPoint(x, writeRow))] = tile
                if (writeRow != readRow) board[starIndex(StarPoint(x, readRow))] = null
                writeRow--
            }
            while (writeRow >= 0) {
                val generated = nextKind(currentRandom)
                currentRandom = generated.randomState
                board[starIndex(StarPoint(x, writeRow))] = StarTile(generated.kind)
                writeRow--
            }
        }
        return GeneratedBoard(board.map(::requireNotNull), currentRandom)
    }

    private fun colorSwapClear(
        board: List<StarTile>,
        first: StarPoint,
        second: StarPoint,
    ): DirectColorClear? {
        val firstTile = board[starIndex(first)]
        val secondTile = board[starIndex(second)]
        val firstIsColor = firstTile.power == StarPower.COLOR_CLEAR
        val secondIsColor = secondTile.power == StarPower.COLOR_CLEAR
        if (!firstIsColor && !secondIsColor) return null
        if (firstIsColor && secondIsColor) {
            return DirectColorClear(allPoints().toSet(), setOf(first, second))
        }
        val colorPoint = if (firstIsColor) second else first
        val target = if (firstIsColor) secondTile.kind else firstTile.kind
        val points = allPoints().filterTo(mutableSetOf()) { point -> board[starIndex(point)].kind == target }
        points += colorPoint
        points += if (firstIsColor) first else second
        return DirectColorClear(points, setOf(colorPoint))
    }

    private fun generatePlayableBoard(randomState: Long): GeneratedBoard {
        var currentRandom = randomState
        repeat(MAX_INITIAL_ATTEMPTS) {
            val board = MutableList<StarTile>(STARSTRUCK_COLUMNS * STARSTRUCK_ROWS) {
                StarTile(StarTileKind.MOON)
            }
            for (y in 0 until STARSTRUCK_ROWS) {
                for (x in 0 until STARSTRUCK_COLUMNS) {
                    val blocked = buildSet {
                        if (x >= 2) {
                            val first = board[starIndex(StarPoint(x - 1, y))].kind
                            val second = board[starIndex(StarPoint(x - 2, y))].kind
                            if (first == second) add(first)
                        }
                        if (y >= 2) {
                            val first = board[starIndex(StarPoint(x, y - 1))].kind
                            val second = board[starIndex(StarPoint(x, y - 2))].kind
                            if (first == second) add(first)
                        }
                    }
                    val choices = StarTileKind.entries.filterNot { it in blocked }
                    val generated = nextRandom(currentRandom)
                    currentRandom = generated
                    board[starIndex(StarPoint(x, y))] = StarTile(
                        choices[((generated ushr 1) % choices.size).toInt()],
                    )
                }
            }
            if (hasLegalMove(board)) return GeneratedBoard(board, currentRandom)
        }
        error("Unable to generate a playable Starstruck board")
    }

    private fun reshuffle(
        board: List<StarTile>,
        randomState: Long,
    ): GeneratedBoard {
        var currentRandom = randomState
        repeat(MAX_INITIAL_ATTEMPTS) {
            val shuffled = board.toMutableList()
            for (index in shuffled.lastIndex downTo 1) {
                val generated = nextRandom(currentRandom)
                currentRandom = generated
                val other = ((generated ushr 1) % (index + 1)).toInt()
                val held = shuffled[index]
                shuffled[index] = shuffled[other]
                shuffled[other] = held
            }
            if (!hasImmediateMatches(shuffled) && hasLegalMove(shuffled)) {
                return GeneratedBoard(shuffled, currentRandom)
            }
        }
        val generated = generatePlayableBoard(currentRandom)
        val earnedPowers = board.map(StarTile::power).filter { it != StarPower.NONE }
        val withPowers = generated.board.toMutableList()
        earnedPowers.forEachIndexed { index, power ->
            withPowers[index] = withPowers[index].copy(power = power)
        }
        return generated.copy(board = withPowers)
    }

    private fun nextKind(randomState: Long): GeneratedKind {
        val next = nextRandom(randomState)
        val kind = StarTileKind.entries[((next ushr 1) % StarTileKind.entries.size).toInt()]
        return GeneratedKind(kind, next)
    }

    private fun allPoints(): List<StarPoint> = buildList {
        for (y in 0 until STARSTRUCK_ROWS) {
            for (x in 0 until STARSTRUCK_COLUMNS) add(StarPoint(x, y))
        }
    }

    private fun isOnBoard(point: StarPoint): Boolean =
        point.x in 0 until STARSTRUCK_COLUMNS && point.y in 0 until STARSTRUCK_ROWS

    private fun normalizeSeed(seed: Long): Long = if (seed == 0L) DEFAULT_RANDOM_STATE else seed

    private fun nextRandom(state: Long): Long {
        var value = normalizeSeed(state)
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        return value
    }

    private data class MatchRun(val points: List<StarPoint>)

    private data class GeneratedBoard(val board: List<StarTile>, val randomState: Long)

    private data class GeneratedKind(val kind: StarTileKind, val randomState: Long)

    private data class DirectColorClear(
        val points: Set<StarPoint>,
        val suppressedPowers: Set<StarPoint>,
    )

    private data class Resolution(
        val board: List<StarTile>,
        val randomState: Long,
        val waves: Int,
        val clearedTiles: Int,
        val powerupsCreated: Int,
        val powerupsActivated: Int,
        val powerupsActivatedAtCreationAnchors: Int,
        val cascadeAnchorPowerupsActivated: Int,
        val safetyRecovered: Boolean,
    )

    private data class ExpandedClear(
        val points: Set<StarPoint>,
        val powerupsActivated: Int,
    )
}

internal fun starIndex(point: StarPoint): Int = point.y * STARSTRUCK_COLUMNS + point.x
