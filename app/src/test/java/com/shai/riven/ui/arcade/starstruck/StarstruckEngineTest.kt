package com.shai.riven.ui.arcade.starstruck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarstruckEngineTest {
    @Test
    fun sameSeedCreatesStableBoardWithAtLeastOneLegalMove() {
        val first = StarstruckEngine.newGame(seed = 41L)
        val second = StarstruckEngine.newGame(seed = 41L)

        assertEquals(first, second)
        assertFalse(StarstruckEngine.hasImmediateMatches(first.board))
        assertTrue(StarstruckEngine.hasLegalMove(first.board))
        assertTrue(first.board.all { it.power == StarPower.NONE })
        assertTrue(StarTileKind.entries.all { kind -> first.board.any { it.kind == kind } })
    }

    @Test
    fun generatedBoardsAcrossSeedsStartWithoutMatchesAndWithALegalMove() {
        for (seed in 1L..64L) {
            val state = StarstruckEngine.newGame(seed)
            assertFalse("seed $seed has an immediate match", StarstruckEngine.hasImmediateMatches(state.board))
            assertTrue("seed $seed has no legal move", StarstruckEngine.hasLegalMove(state.board))
        }
    }

    @Test
    fun invalidOrNonAdjacentSwapRollsBackExactly() {
        val state = StarstruckEngine.newGame(seed = 7L)
        val invalidAdjacent = firstInvalidAdjacentSwap(state.board)

        val invalid = StarstruckEngine.swap(state, invalidAdjacent.first, invalidAdjacent.second)
        val distant = StarstruckEngine.swap(state, StarPoint(0, 0), StarPoint(2, 0))

        assertFalse(invalid.accepted)
        assertEquals(state, invalid.state)
        assertFalse(distant.accepted)
        assertEquals(state, distant.state)
    }

    @Test
    fun adjacentLegalSwapResolvesToAnotherStablePlayableBoard() {
        val state = StarstruckEngine.newGame(seed = 13L)
        val move = StarstruckEngine.legalMoves(state.board).first()

        val result = StarstruckEngine.swap(state, move.first, move.second)

        assertTrue(result.accepted)
        assertEquals(1, result.state.movesMade)
        assertEquals(1, result.state.matchesMade)
        assertTrue(result.clearedTiles >= 3)
        assertFalse(StarstruckEngine.hasImmediateMatches(result.state.board))
        assertTrue(StarstruckEngine.hasLegalMove(result.state.board))
    }

    @Test
    fun straightFourCreatesRowBurstAtMovedTileAsReviewDefault() {
        val state = fixtureState(fourMatchBoard(), randomState = 17L)
        assertFalse(StarstruckEngine.hasImmediateMatches(state.board))

        val result = StarstruckEngine.swap(state, StarPoint(2, 2), StarPoint(2, 3))

        assertTrue(result.accepted)
        assertTrue(result.powerupsCreated >= 1)
        assertTrue(result.state.board.any { it.power == StarPower.ROW_CLEAR })
        assertTrue(StarstruckSoundCue.POWERUP in result.sounds)
    }

    @Test
    fun earnedRowBurstAtNewPowerAnchorFiresBeforeCreationAndChains() {
        val board = fourMatchBoard().toMutableList().apply {
            this[starIndex(StarPoint(2, 2))] = this[starIndex(StarPoint(2, 2))].copy(
                power = StarPower.ROW_CLEAR,
            )
            this[starIndex(StarPoint(5, 3))] = this[starIndex(StarPoint(5, 3))].copy(
                power = StarPower.ROW_CLEAR,
            )
        }
        val state = fixtureState(board, randomState = 71L)
        assertFalse(StarstruckEngine.hasImmediateMatches(state.board))

        val result = StarstruckEngine.swap(state, StarPoint(2, 2), StarPoint(2, 3))

        assertTrue(result.accepted)
        assertTrue(result.powerupsActivated >= 2)
        assertTrue(result.powerupsActivatedAtCreationAnchors >= 1)
        assertTrue(result.powerupsCreated >= 1)
        assertTrue(result.clearedTiles >= STARSTRUCK_COLUMNS - 1)
        assertTrue(result.state.board.any { it.power == StarPower.ROW_CLEAR })
        assertTrue(StarstruckSoundCue.POWERUP in result.sounds)
        assertFalse(StarstruckEngine.hasImmediateMatches(result.state.board))
    }

    @Test
    fun straightFiveCreatesColorNovaAtMovedTileAsReviewDefault() {
        val state = fixtureState(fiveMatchBoard(), randomState = 23L)
        assertFalse(StarstruckEngine.hasImmediateMatches(state.board))

        val result = StarstruckEngine.swap(state, StarPoint(2, 2), StarPoint(2, 3))

        assertTrue(result.accepted)
        assertTrue(result.state.board.any { it.power == StarPower.COLOR_CLEAR })
        assertTrue(result.powerupsCreated >= 1)
    }

    @Test
    fun earnedRowBurstFiresBeforeTheSameAnchorBecomesColorNova() {
        val board = fiveMatchBoard().toMutableList().apply {
            this[starIndex(StarPoint(2, 2))] = this[starIndex(StarPoint(2, 2))].copy(
                power = StarPower.ROW_CLEAR,
            )
            this[starIndex(StarPoint(6, 3))] = this[starIndex(StarPoint(6, 3))].copy(
                power = StarPower.ROW_CLEAR,
            )
        }
        val state = fixtureState(board, randomState = 79L)

        val result = StarstruckEngine.swap(state, StarPoint(2, 2), StarPoint(2, 3))

        assertTrue(result.accepted)
        assertTrue(result.powerupsActivated >= 2)
        assertTrue(result.powerupsActivatedAtCreationAnchors >= 1)
        assertTrue(result.powerupsCreated >= 1)
        assertTrue(result.state.board.any { it.power == StarPower.COLOR_CLEAR })
        assertFalse(StarstruckEngine.hasImmediateMatches(result.state.board))
    }

    @Test
    fun matchedRowBurstClearsItsWholeRowAndChainsOtherPowerups() {
        val board = exactThreeBoard().toMutableList().apply {
            this[starIndex(StarPoint(1, 3))] = this[starIndex(StarPoint(1, 3))].copy(
                power = StarPower.ROW_CLEAR,
            )
            this[starIndex(StarPoint(5, 3))] = this[starIndex(StarPoint(5, 3))].copy(
                power = StarPower.ROW_CLEAR,
            )
        }
        val state = fixtureState(board, randomState = 31L)

        val result = StarstruckEngine.swap(state, StarPoint(2, 2), StarPoint(2, 3))

        assertTrue(result.accepted)
        assertTrue(result.clearedTiles >= STARSTRUCK_COLUMNS)
        assertTrue(StarstruckSoundCue.POWERUP in result.sounds)
        assertFalse(StarstruckEngine.hasImmediateMatches(result.state.board))
    }

    @Test
    fun swappingColorNovaClearsTheOtherTilesCelestialType() {
        val board = stablePattern().toMutableList().apply {
            this[starIndex(StarPoint(0, 0))] = this[starIndex(StarPoint(0, 0))].copy(
                power = StarPower.COLOR_CLEAR,
            )
        }
        val state = fixtureState(board, randomState = 37L)
        val targetKind = state[StarPoint(1, 0)].kind
        val targetCount = state.board.count { it.kind == targetKind }

        val result = StarstruckEngine.swap(state, StarPoint(0, 0), StarPoint(1, 0))

        assertTrue(result.accepted)
        assertTrue(result.clearedTiles >= targetCount + 1)
        assertTrue(StarstruckSoundCue.POWERUP in result.sounds)
        assertFalse(StarstruckEngine.hasImmediateMatches(result.state.board))
    }

    @Test
    fun deterministicMoveCanProduceAnEndlessCascadeWithoutPressureState() {
        val state = StarstruckEngine.newGame(seed = 2L)
        val result = StarstruckEngine.swap(
            state,
            StarPoint(2, 1),
            StarPoint(3, 1),
        )

        assertTrue(result.accepted)
        assertTrue(result.resolutionWaves > 1)
        assertEquals(result.resolutionWaves - 1, result.state.cascadeWaves)
        assertFalse(StarstruckEngine.hasImmediateMatches(result.state.board))
        assertTrue(StarstruckEngine.hasLegalMove(result.state.board))
    }

    @Test
    fun cascadeSafetyBoundaryRecoversToPlayableBoardInsteadOfThrowing() {
        val state = StarstruckEngine.newGame(seed = 2L)

        val result = StarstruckEngine.swap(
            state = state,
            first = StarPoint(2, 1),
            second = StarPoint(3, 1),
            cascadeWaveLimit = 1,
        )

        assertTrue(result.accepted)
        assertEquals(1, result.resolutionWaves)
        assertTrue(result.reshuffled)
        assertEquals(1, result.state.reshuffles)
        assertEquals(1, result.state.movesMade)
        assertFalse(StarstruckEngine.hasImmediateMatches(result.state.board))
        assertTrue(StarstruckEngine.hasLegalMove(result.state.board))
    }

    @Test
    fun earnedPowerAtCascadeCreationAnchorFiresBeforeReplacement() {
        val poweredPoint = StarPoint(2, 1)
        val state = StarstruckEngine.newGame(seed = 2L).let { initial ->
            initial.copy(
                board = initial.board.toMutableList().apply {
                    this[starIndex(poweredPoint)] = this[starIndex(poweredPoint)].copy(
                        power = StarPower.ROW_CLEAR,
                    )
                },
            )
        }

        val result = StarstruckEngine.swap(
            state,
            StarPoint(3, 3),
            StarPoint(3, 4),
        )

        assertTrue(result.accepted)
        assertTrue(result.resolutionWaves > 1)
        assertTrue(result.cascadeAnchorPowerupsActivated >= 1)
        assertTrue(result.powerupsCreated >= 1)
        assertTrue(result.powerupsActivated >= 1)
        assertFalse(StarstruckEngine.hasImmediateMatches(result.state.board))
        assertTrue(StarstruckEngine.hasLegalMove(result.state.board))
    }

    @Test
    fun deadBoardAutomaticallyReshufflesWithoutLosingProgress() {
        val deadBoard = stablePattern().toMutableList().apply {
            this[starIndex(StarPoint(0, 0))] = this[starIndex(StarPoint(0, 0))].copy(
                power = StarPower.ROW_CLEAR,
            )
        }
        val dead = fixtureState(deadBoard, randomState = 43L).copy(
            movesMade = 8,
            matchesMade = 8,
            cascadeWaves = 3,
            tilesCleared = 29,
        )
        assertFalse(StarstruckEngine.hasImmediateMatches(dead.board))
        assertFalse(StarstruckEngine.hasLegalMove(dead.board))

        val recovered = StarstruckEngine.ensurePlayable(dead)

        assertNotEquals(dead.board, recovered.board)
        assertEquals(8, recovered.movesMade)
        assertEquals(8, recovered.matchesMade)
        assertEquals(3, recovered.cascadeWaves)
        assertEquals(29, recovered.tilesCleared)
        assertEquals(1, recovered.reshuffles)
        assertEquals(1, recovered.board.count { it.power == StarPower.ROW_CLEAR })
        assertFalse(StarstruckEngine.hasImmediateMatches(recovered.board))
        assertTrue(StarstruckEngine.hasLegalMove(recovered.board))
    }

    private fun firstInvalidAdjacentSwap(board: List<StarTile>): Pair<StarPoint, StarPoint> {
        val legal = StarstruckEngine.legalMoves(board).toSet()
        for (y in 0 until STARSTRUCK_ROWS) {
            for (x in 0 until STARSTRUCK_COLUMNS - 1) {
                val move = StarPoint(x, y) to StarPoint(x + 1, y)
                if (move !in legal) return move
            }
        }
        error("Expected at least one invalid adjacent swap")
    }

    private fun fourMatchBoard(): List<StarTile> = stablePattern().toMutableList().apply {
        setKind(StarPoint(0, 3), StarTileKind.MOON)
        setKind(StarPoint(1, 3), StarTileKind.MOON)
        setKind(StarPoint(2, 3), StarTileKind.HEART)
        setKind(StarPoint(3, 3), StarTileKind.MOON)
        setKind(StarPoint(2, 2), StarTileKind.MOON)
    }

    private fun fiveMatchBoard(): List<StarTile> = fourMatchBoard().toMutableList().apply {
        setKind(StarPoint(4, 3), StarTileKind.MOON)
    }

    private fun exactThreeBoard(): List<StarTile> = stablePattern().toMutableList().apply {
        setKind(StarPoint(0, 3), StarTileKind.MOON)
        setKind(StarPoint(1, 3), StarTileKind.MOON)
        setKind(StarPoint(2, 3), StarTileKind.HEART)
        setKind(StarPoint(3, 3), StarTileKind.STAR)
        setKind(StarPoint(2, 2), StarTileKind.MOON)
    }

    private fun stablePattern(): List<StarTile> = buildList {
        for (y in 0 until STARSTRUCK_ROWS) {
            for (x in 0 until STARSTRUCK_COLUMNS) {
                add(StarTile(StarTileKind.entries[(x + y * 2) % StarTileKind.entries.size]))
            }
        }
    }

    private fun MutableList<StarTile>.setKind(point: StarPoint, kind: StarTileKind) {
        this[starIndex(point)] = StarTile(kind)
    }

    private fun fixtureState(board: List<StarTile>, randomState: Long): StarstruckState = StarstruckState(
        board = board,
        randomState = randomState,
    )
}
