package com.shai.riven.ui.arcade.comet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CometTrailStoreTest {
    @Test
    fun snapshotRoundTripRestoresMovementAndCollisionPauseExactly() {
        val state = CometTrailState(
            body = listOf(
                TrailPoint(2, 2),
                TrailPoint(2, 3),
                TrailPoint(1, 3),
                TrailPoint(1, 2),
                TrailPoint(1, 1),
            ),
            direction = TrailDirection.LEFT,
            food = TrailPoint(9, 9),
            randomState = 28L,
            treatsEaten = 7,
            stepsTaken = 80,
            boardRefreshes = 1,
            collisionDirection = TrailDirection.LEFT,
        )

        val restored = requireNotNull(
            CometTrailSnapshotCodec.decode(CometTrailSnapshotCodec.encode(state)),
        )

        assertEquals(state, restored)
        assertEquals(
            CometTrailEngine.tick(CometTrailEngine.turn(state, TrailDirection.UP)),
            CometTrailEngine.tick(CometTrailEngine.turn(restored, TrailDirection.UP)),
        )
    }

    @Test
    fun corruptDisconnectedOrUnsafeSnapshotsAreRejected() {
        val state = CometTrailEngine.newGame(seed = 8L)
        val encoded = CometTrailSnapshotCodec.encode(state)

        assertNull(CometTrailSnapshotCodec.decode(encoded.dropLast(1)))
        assertNull(CometTrailSnapshotCodec.decode("not-a-session"))
        assertNull(
            CometTrailSnapshotCodec.decode(
                CometTrailSnapshotCodec.encode(
                    state.copy(body = listOf(TrailPoint(0, 0), TrailPoint(6, 6))),
                ),
            ),
        )
        assertNull(
            CometTrailSnapshotCodec.decode(
                CometTrailSnapshotCodec.encode(
                    state.copy(food = state.head),
                ),
            ),
        )
    }

    @Test
    fun sharedPreferencesRestoresSessionAndSpeed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "comet-trail-test-${System.nanoTime()}"
        val store = SharedPreferencesCometTrailStore(context, preferenceName)
        val state = CometTrailEngine.tick(CometTrailEngine.newGame(seed = 71L))
        val settings = CometTrailSettings(TrailSpeed.BREEZY)

        store.saveSession(state)
        store.saveSettings(settings)

        val restored = SharedPreferencesCometTrailStore(context, preferenceName)
        assertEquals(state, restored.loadSession())
        assertEquals(settings, restored.loadSettings())
    }

    @Test
    fun invalidStoredValuesFallBackSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "comet-trail-invalid-${System.nanoTime()}"
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putString("session_v1", "broken")
            .putString("speed", "TURBO")
            .apply()

        val store = SharedPreferencesCometTrailStore(context, preferenceName)

        assertNull(store.loadSession())
        assertEquals(TrailSpeed.GENTLE, store.loadSettings().speed)
        assertTrue(store.loadSettings().speed.tickMillis >= 600L)
    }
}
