package com.shai.riven.ui.arcade.solitaire

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
class MidnightSolitaireStoreTest {
    @Test
    fun snapshotRoundTripPreservesPositionAndBoundedUndoHistory() {
        var session = MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 314159L))
        repeat(8) {
            session = MidnightSolitaireEngine.apply(session, SolitaireMove.DrawOrRecycle).session
        }

        val encoded = MidnightSolitaireSnapshotCodec.encode(session)

        assertEquals(session, MidnightSolitaireSnapshotCodec.decode(encoded))
        assertEquals(8, requireNotNull(MidnightSolitaireSnapshotCodec.decode(encoded)).undoStack.size)
    }

    @Test
    fun corruptionTruncationDuplicateCardsAndImpossibleLayoutAreRejected() {
        val session = MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 8L))
        val encoded = MidnightSolitaireSnapshotCodec.encode(session)

        assertNull(MidnightSolitaireSnapshotCodec.decode(encoded.dropLast(1)))
        assertNull(MidnightSolitaireSnapshotCodec.decode(encoded.replaceRange(5, 6, "?")))
        assertNull(MidnightSolitaireSnapshotCodec.decode("not-a-session"))

        val duplicate = session.game.copy(stock = session.game.stock + session.game.stock.first())
        assertTrue(!MidnightSolitaireEngine.isValid(duplicate))
    }

    @Test
    fun preferencesPersistSessionUndoAndLargeTextSetting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "midnight-solitaire-test-${System.nanoTime()}"
        val store = SharedPreferencesMidnightSolitaireStore(context, preferenceName)
        val session = MidnightSolitaireEngine.apply(
            MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 71L)),
            SolitaireMove.DrawOrRecycle,
        ).session
        val settings = MidnightSolitaireSettings(largeCardText = true)

        store.saveSession(session)
        store.saveSettings(settings)

        val restored = SharedPreferencesMidnightSolitaireStore(context, preferenceName)
        assertEquals(session, restored.loadSession())
        assertEquals(settings, restored.loadSettings())
    }

    @Test
    fun corruptStoredSessionFallsBackWithoutResettingIndependentSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "midnight-solitaire-corrupt-${System.nanoTime()}"
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putString(SharedPreferencesMidnightSolitaireStore.KEY_SESSION, "broken")
            .putBoolean(SharedPreferencesMidnightSolitaireStore.KEY_LARGE_CARD_TEXT, true)
            .apply()

        val store = SharedPreferencesMidnightSolitaireStore(context, preferenceName)

        assertNull(store.loadSession())
        assertEquals(MidnightSolitaireSettings(largeCardText = true), store.loadSettings())

        store.saveSession(MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 99L)))
        assertEquals(
            "broken",
            context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                .getString(SharedPreferencesMidnightSolitaireStore.KEY_CORRUPT_SESSION_BACKUP, null),
        )
    }
}
