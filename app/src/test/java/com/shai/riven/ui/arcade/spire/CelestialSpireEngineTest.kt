package com.shai.riven.ui.arcade.spire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialSpireEngineTest {
    @Test
    fun sameSeedProducesSameSevenPieceBagWithoutDuplicates() {
        val first = CelestialSpireEngine.newGame(seed = 42L)
        val second = CelestialSpireEngine.newGame(seed = 42L)

        assertEquals(first, second)
        val firstBag = listOf(first.active.type, first.next) + first.bag
        assertEquals(SpirePieceType.entries.size, firstBag.size)
        assertEquals(SpirePieceType.entries.toSet(), firstBag.toSet())
    }

    @Test
    fun movementAndRotationStayInsideTheBoard() {
        var state = CelestialSpireEngine.newGame(seed = 7L)

        repeat(20) {
            state = CelestialSpireEngine.step(state, SpireAction.MOVE_LEFT).state
        }
        assertTrue(CelestialSpireEngine.cells(state.active).all { it.x >= 0 })

        val rotated = CelestialSpireEngine.step(state, SpireAction.ROTATE_CLOCKWISE).state
        assertTrue(CelestialSpireEngine.canPlace(rotated.board, rotated.active))
        assertNotEquals(state.active.rotation, rotated.active.rotation)

        repeat(20) {
            state = CelestialSpireEngine.step(state, SpireAction.MOVE_RIGHT).state
        }
        assertTrue(CelestialSpireEngine.cells(state.active).all { it.x < CELESTIAL_SPIRE_COLUMNS })
    }

    @Test
    fun lockingACompletedRowClearsItAndEmitsSeparateSounds() {
        val board = emptyBoard().toMutableList().apply {
            repeat(8) { column ->
                this[(CELESTIAL_SPIRE_ROWS - 1) * CELESTIAL_SPIRE_COLUMNS + column] = SpirePieceType.J
            }
        }
        val state = fixtureState(
            board = board,
            active = SpirePiece(SpirePieceType.O, rotation = 0, x = 7, y = 18),
            next = SpirePieceType.T,
        )

        val result = CelestialSpireEngine.step(state, SpireAction.TICK)

        assertEquals(1, result.state.linesCleared)
        assertEquals(1, result.state.piecesPlaced)
        assertEquals(
            listOf(SpireSoundCue.PIECE_LANDED, SpireSoundCue.LINE_CLEARED),
            result.sounds,
        )
        assertEquals(2, result.state.board.count { it == SpirePieceType.O })
    }

    @Test
    fun reachingTheTopRefreshesInsteadOfCreatingALossState() {
        val board = emptyBoard().toMutableList().apply {
            for (column in 3..6) {
                this[CELESTIAL_SPIRE_COLUMNS + column] = SpirePieceType.J
            }
        }
        val state = fixtureState(
            board = board,
            active = SpirePiece(SpirePieceType.O, rotation = 0, x = 0, y = 18),
            next = SpirePieceType.I,
        ).copy(
            linesCleared = 12,
            piecesPlaced = 30,
        )

        val result = CelestialSpireEngine.step(state, SpireAction.TICK)

        assertTrue(result.state.board.all { it == null })
        assertEquals(SpirePieceType.I, result.state.active.type)
        assertEquals(1, result.state.boardRefreshes)
        assertEquals(0, result.state.linesCleared)
        assertEquals(0, result.state.piecesPlaced)
        assertEquals(listOf(SpireSoundCue.PIECE_LANDED), result.sounds)
    }

    @Test
    fun ceilingKickLockOutRefreshesBeforeAnyMinoIsDiscarded() {
        val board = emptyBoard().toMutableList().apply {
            for (row in 3 until CELESTIAL_SPIRE_ROWS) {
                for (column in 0..4) {
                    this[row * CELESTIAL_SPIRE_COLUMNS + column] = SpirePieceType.J
                }
            }
        }
        val state = fixtureState(
            board = board,
            active = SpirePiece(SpirePieceType.I, rotation = 0, x = 0, y = 0),
            next = SpirePieceType.O,
        )

        val rotated = CelestialSpireEngine.step(state, SpireAction.ROTATE_CLOCKWISE).state
        assertEquals(SpirePiece(SpirePieceType.I, rotation = 1, x = 0, y = -1), rotated.active)

        val result = CelestialSpireEngine.step(rotated, SpireAction.TICK)

        assertTrue(result.state.board.all { it == null })
        assertEquals(SpirePieceType.O, result.state.active.type)
        assertEquals(1, result.state.boardRefreshes)
        assertEquals(listOf(SpireSoundCue.PIECE_LANDED), result.sounds)
    }

    @Test
    fun hardDropLocksImmediatelyAndAdvancesTheQueue() {
        val state = CelestialSpireEngine.newGame(seed = 99L)

        val result = CelestialSpireEngine.step(state, SpireAction.HARD_DROP)

        assertEquals(state.next, result.state.active.type)
        assertEquals(1, result.state.piecesPlaced)
        assertEquals(listOf(SpireSoundCue.PIECE_LANDED), result.sounds)
        assertEquals(4, result.state.board.count { it == state.active.type })
    }

    @Test
    fun fallSpeedIsOnlyChangedByExplicitSettingSelection() {
        val settings = CelestialSpireSettings(fallSpeed = SpireFallSpeed.CALM)
        var state = CelestialSpireEngine.newGame(seed = 123L)

        repeat(40) {
            state = CelestialSpireEngine.step(state, SpireAction.HARD_DROP).state
        }

        assertEquals(SpireFallSpeed.CALM, settings.fallSpeed)
        assertEquals(900L, settings.fallSpeed.tickMillis)
        assertEquals(SpireFallSpeed.STEADY, settings.copy(fallSpeed = settings.fallSpeed.next()).fallSpeed)
    }

    private fun fixtureState(
        board: List<SpirePieceType?>,
        active: SpirePiece,
        next: SpirePieceType,
    ): CelestialSpireState = CelestialSpireState(
        board = board,
        active = active,
        next = next,
        bag = listOf(SpirePieceType.S, SpirePieceType.Z, SpirePieceType.L),
        randomState = 17L,
    )

    private fun emptyBoard(): List<SpirePieceType?> =
        List(CELESTIAL_SPIRE_COLUMNS * CELESTIAL_SPIRE_ROWS) { null }
}
