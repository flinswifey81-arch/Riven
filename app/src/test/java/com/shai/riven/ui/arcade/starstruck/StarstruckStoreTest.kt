package com.shai.riven.ui.arcade.starstruck

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StarstruckStoreTest {
    @Test
    fun snapshotRoundTripRestoresExactBoardPowerupsAndContinuation() {
        val initial = StarstruckEngine.newGame(seed = 52L)
        val move = StarstruckEngine.legalMoves(initial.board).first()
        val state = StarstruckEngine.swap(initial, move.first, move.second).state

        val restored = requireNotNull(
            StarstruckSnapshotCodec.decode(StarstruckSnapshotCodec.encode(state)),
        )

        assertEquals(state, restored)
        val continuation = StarstruckEngine.legalMoves(state.board).first()
        assertEquals(
            StarstruckEngine.swap(state, continuation.first, continuation.second),
            StarstruckEngine.swap(restored, continuation.first, continuation.second),
        )
    }

    @Test
    fun corruptMatchedOrDeadSnapshotsAreRejected() {
        val state = StarstruckEngine.newGame(seed = 53L)
        val encoded = StarstruckSnapshotCodec.encode(state)
        val matched = state.copy(
            board = state.board.toMutableList().apply {
                val kind = this[0].kind
                this[1] = StarTile(kind)
                this[2] = StarTile(kind)
            },
        )
        val dead = state.copy(board = stableDeadPattern())

        assertNull(StarstruckSnapshotCodec.decode(encoded.dropLast(1)))
        assertNull(StarstruckSnapshotCodec.decode("not-a-session"))
        assertNull(StarstruckSnapshotCodec.decode(StarstruckSnapshotCodec.encode(matched)))
        assertFalse(StarstruckEngine.hasLegalMove(dead.board))
        assertNull(StarstruckSnapshotCodec.decode(StarstruckSnapshotCodec.encode(dead)))
    }

    @Test
    fun sharedPreferencesRestoreSessionAndIndependentSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "starstruck-test-${System.nanoTime()}"
        val store = SharedPreferencesStarstruckStore(context, preferenceName)
        val state = StarstruckEngine.newGame(seed = 54L)
        val settings = StarstruckSettings(
            soundVolume = 70,
            soundMuted = true,
            gentleEffects = false,
        )

        store.saveSession(state)
        store.saveSettings(settings)

        val restored = SharedPreferencesStarstruckStore(context, preferenceName)
        assertEquals(state, restored.loadSession())
        assertEquals(settings, restored.loadSettings())
    }

    @Test
    fun invalidSettingsNormalizeWithoutAffectingBoard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "starstruck-settings-${System.nanoTime()}"
        val store = SharedPreferencesStarstruckStore(context, preferenceName)
        val state = StarstruckEngine.newGame(seed = 55L)

        store.saveSession(state)
        store.saveSettings(StarstruckSettings(soundVolume = 400, soundMuted = false, gentleEffects = true))

        assertEquals(100, store.loadSettings().soundVolume)
        assertTrue(store.loadSettings().gentleEffects)
        assertEquals(state, store.loadSession())
    }

    private fun stableDeadPattern(): List<StarTile> = buildList {
        for (y in 0 until STARSTRUCK_ROWS) {
            for (x in 0 until STARSTRUCK_COLUMNS) {
                add(StarTile(StarTileKind.entries[(x + y * 2) % StarTileKind.entries.size]))
            }
        }
    }
}
