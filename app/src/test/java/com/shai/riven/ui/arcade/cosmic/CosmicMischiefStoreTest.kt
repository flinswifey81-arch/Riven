package com.shai.riven.ui.arcade.cosmic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CosmicMischiefStoreTest {
    @Test
    fun snapshotRoundTripPreservesResumableTurnEventsAndGrudges() {
        val initial = CosmicMischiefEngine.newGame(seed = 5150L, grudges = listOf(2, 4))
        val session = CosmicMischiefSession(initial)

        val encoded = CosmicMischiefSnapshotCodec.encode(session)
        val restored = CosmicMischiefSnapshotCodec.decode(encoded)

        assertEquals(session, restored)
        assertTrue(CosmicMischiefEngine.isValid(requireNotNull(restored).game))
    }

    @Test
    fun checksumRejectsCorruptionInsteadOfResettingIntoInventedState() {
        val encoded = CosmicMischiefSnapshotCodec.encode(
            CosmicMischiefSession(CosmicMischiefEngine.newGame(seed = 88L)),
        )
        val corrupted = encoded.replaceFirst("|SHAI|", "|RIVEN|")

        assertNotEquals(encoded, corrupted)
        assertNull(CosmicMischiefSnapshotCodec.decode(corrupted))
    }

    @Test
    fun sharedPreferencesBacksUpCorruptSnapshotBeforeExplicitReplacement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "cosmic-store-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        preferences.edit().putString(SharedPreferencesCosmicMischiefStore.KEY_SESSION, "damaged").commit()
        val store = SharedPreferencesCosmicMischiefStore(context, preferenceName)

        assertNull(store.loadSession())
        val replacement = CosmicMischiefSession(CosmicMischiefEngine.newGame(seed = 99L))
        store.saveSession(replacement)

        assertEquals(replacement, store.loadSession())
        assertEquals(
            "damaged",
            preferences.getString(SharedPreferencesCosmicMischiefStore.KEY_CORRUPT_SESSION_BACKUP, null),
        )
    }

    @Test
    fun settingsAndFreshGamePersistWhileGrudgesCarryForward() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SharedPreferencesCosmicMischiefStore(
            context,
            "cosmic-settings-${System.nanoTime()}",
        )
        val old = CosmicMischiefSession(
            CosmicMischiefEngine.newGame(seed = 1L, gameNumber = 3, grudges = listOf(5, 2)),
        )
        val fresh = CosmicMischiefEngine.freshGame(old, seed = 2L)

        store.saveSession(fresh)
        store.saveSettings(CosmicMischiefSettings(largeCardText = true))

        assertEquals(4, store.loadSession()?.game?.gameNumber)
        assertEquals(listOf(5, 2), store.loadSession()?.game?.grudges)
        assertEquals(CosmicMischiefSettings(largeCardText = true), store.loadSettings())
    }

    @Test
    fun exhaustedButValidTablePersistsWithoutInventingOrDroppingCards() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SharedPreferencesCosmicMischiefStore(
            context,
            "cosmic-exhausted-${System.nanoTime()}",
        )
        val shai = requireNotNull(CosmicCard.fromId(11))
        val top = requireNotNull(CosmicCard.fromId(5))
        val session = CosmicMischiefSession(
            CosmicMischiefState(
                drawPile = emptyList(),
                discardPile = listOf(top),
                hands = listOf(
                    listOf(shai),
                    CosmicCard.fullDeck.filterNot { it.id == shai.id || it.id == top.id },
                ),
                activeColor = requireNotNull(top.color),
                turn = CosmicPlayer.SHAI,
                dealSeed = 303L,
                randomState = 404L,
            ),
        )
        require(CosmicMischiefEngine.isValid(session.game))

        store.saveSession(session)
        val restored = requireNotNull(store.loadSession())

        assertEquals(session, restored)
        assertTrue(restored.game.drawPile.isEmpty())
        assertEquals(COSMIC_CARD_COUNT, restored.game.discardPile.size + restored.game.hands.sumOf { it.size })
    }
}
