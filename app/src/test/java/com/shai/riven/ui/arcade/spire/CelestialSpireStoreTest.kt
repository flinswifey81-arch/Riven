package com.shai.riven.ui.arcade.spire

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
class CelestialSpireStoreTest {
    @Test
    fun snapshotRoundTripPreservesEveryDeterministicField() {
        var state = CelestialSpireEngine.newGame(seed = 314159L)
        repeat(5) {
            state = CelestialSpireEngine.step(state, SpireAction.HARD_DROP).state
        }

        val encoded = CelestialSpireSnapshotCodec.encode(state)

        assertEquals(state, CelestialSpireSnapshotCodec.decode(encoded))
    }

    @Test
    fun corruptOrTruncatedSnapshotIsRejectedSafely() {
        val state = CelestialSpireEngine.newGame(seed = 8L)
        val encoded = CelestialSpireSnapshotCodec.encode(state)

        assertNull(CelestialSpireSnapshotCodec.decode(encoded.dropLast(1)))
        assertNull(CelestialSpireSnapshotCodec.decode(encoded.replaceRange(5, 6, "?")))
        assertNull(CelestialSpireSnapshotCodec.decode("not-a-session"))
        assertNull(
            CelestialSpireSnapshotCodec.decode(
                CelestialSpireSnapshotCodec.encode(
                    state.copy(active = state.active.copy(y = Int.MIN_VALUE)),
                ),
            ),
        )
    }

    @Test
    fun sharedPreferencesPersistsSessionSpeedAndIndependentSoundSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "celestial-spire-test-${System.nanoTime()}"
        val store = SharedPreferencesCelestialSpireStore(context, preferenceName)
        val state = CelestialSpireEngine.step(
            CelestialSpireEngine.newGame(seed = 71L),
            SpireAction.HARD_DROP,
        ).state
        val settings = CelestialSpireSettings(
            fallSpeed = SpireFallSpeed.BRISK,
            soundVolume = 35,
            soundMuted = true,
        )

        store.saveSession(state)
        store.saveSettings(settings)

        assertEquals(state, SharedPreferencesCelestialSpireStore(context, preferenceName).loadSession())
        assertEquals(settings, SharedPreferencesCelestialSpireStore(context, preferenceName).loadSettings())
    }

    @Test
    fun invalidStoredValuesFallBackWithoutAffectingTheSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "celestial-spire-invalid-${System.nanoTime()}"
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putString("session_v1", "broken")
            .putString("fall_speed", "AUTOMATIC")
            .putInt("sound_volume", 900)
            .apply()

        val store = SharedPreferencesCelestialSpireStore(context, preferenceName)

        assertNull(store.loadSession())
        assertEquals(SpireFallSpeed.STEADY, store.loadSettings().fallSpeed)
        assertEquals(100, store.loadSettings().soundVolume)
        assertTrue(!store.loadSettings().soundMuted)
    }
}
