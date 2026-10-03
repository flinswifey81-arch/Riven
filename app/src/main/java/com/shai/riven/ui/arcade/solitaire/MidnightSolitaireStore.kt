package com.shai.riven.ui.arcade.solitaire

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32

data class MidnightSolitaireSettings(
    val largeCardText: Boolean = false,
)

interface MidnightSolitaireStore {
    fun loadSession(): MidnightSolitaireSession?

    fun saveSession(session: MidnightSolitaireSession)

    fun loadSettings(): MidnightSolitaireSettings

    fun saveSettings(settings: MidnightSolitaireSettings)
}

class SharedPreferencesMidnightSolitaireStore(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) : MidnightSolitaireStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    override fun loadSession(): MidnightSolitaireSession? = preferences.getString(KEY_SESSION, null)
        ?.let(MidnightSolitaireSnapshotCodec::decode)

    override fun saveSession(session: MidnightSolitaireSession) {
        val existing = preferences.getString(KEY_SESSION, null)
        val preserveCorruptSnapshot = existing != null &&
            MidnightSolitaireSnapshotCodec.decode(existing) == null &&
            !preferences.contains(KEY_CORRUPT_SESSION_BACKUP)
        preferences.edit {
            if (preserveCorruptSnapshot) putString(KEY_CORRUPT_SESSION_BACKUP, existing)
            putString(KEY_SESSION, MidnightSolitaireSnapshotCodec.encode(session))
        }
    }

    override fun loadSettings(): MidnightSolitaireSettings = MidnightSolitaireSettings(
        largeCardText = preferences.getBoolean(KEY_LARGE_CARD_TEXT, false),
    )

    override fun saveSettings(settings: MidnightSolitaireSettings) {
        preferences.edit { putBoolean(KEY_LARGE_CARD_TEXT, settings.largeCardText) }
    }

    companion object {
        internal const val PREFERENCE_NAME = "midnight_solitaire"
        internal const val KEY_SESSION = "session_v1"
        internal const val KEY_LARGE_CARD_TEXT = "large_card_text"
        internal const val KEY_CORRUPT_SESSION_BACKUP = "session_v1_corrupt_backup"
    }
}

object MidnightSolitaireSnapshotCodec {
    private const val VERSION = "1"
    private const val EMPTY = "_"

    fun encode(session: MidnightSolitaireSession): String {
        require(MidnightSolitaireEngine.isValid(session.game))
        require(session.undoStack.size <= SOLITAIRE_MAX_UNDO)
        require(session.undoStack.all(MidnightSolitaireEngine::isValid))
        val current = encodeBase64(encodeState(session.game))
        val history = session.undoStack.joinToString(separator = ",") { state ->
            encodeBase64(encodeState(state))
        }
        val payload = "$VERSION|$current|$history"
        return "$payload|${checksum(payload)}"
    }

    fun decode(snapshot: String): MidnightSolitaireSession? = runCatching {
        val parts = snapshot.split('|')
        require(parts.size == 4)
        require(parts[0] == VERSION)
        val payload = parts.dropLast(1).joinToString(separator = "|")
        require(parts.last() == checksum(payload))
        val game = decodeState(decodeBase64(parts[1]))
        val history = if (parts[2].isBlank()) {
            emptyList()
        } else {
            parts[2].split(',').map { encoded -> decodeState(decodeBase64(encoded)) }
        }
        require(history.size <= SOLITAIRE_MAX_UNDO)
        require(MidnightSolitaireEngine.isValid(game))
        require(history.all(MidnightSolitaireEngine::isValid))
        MidnightSolitaireSession(game = game, undoStack = history)
    }.getOrNull()

    private fun encodeState(state: MidnightSolitaireState): String = listOf(
        state.dealSeed.toString(),
        state.dealNumber.toString(),
        state.moves.toString(),
        state.recycles.toString(),
        state.status.name,
        encodeCards(state.stock),
        encodeCards(state.waste),
        state.foundations.joinToString(separator = "/", transform = ::encodeCards),
        state.tableau.joinToString(separator = "/") { pile ->
            if (pile.isEmpty()) {
                EMPTY
            } else {
                pile.joinToString(separator = ",") { card ->
                    "${card.card.id}${if (card.faceUp) 'U' else 'D'}"
                }
            }
        },
    ).joinToString(separator = "~")

    private fun decodeState(encoded: String): MidnightSolitaireState {
        val parts = encoded.split('~')
        require(parts.size == 9)
        val foundations = parts[7].split('/').map(::decodeCards)
        val tableau = parts[8].split('/').map { encodedPile ->
            if (encodedPile == EMPTY) {
                emptyList()
            } else {
                encodedPile.split(',').map { encodedCard ->
                    require(encodedCard.length >= 2)
                    val face = encodedCard.last()
                    require(face == 'U' || face == 'D')
                    SolitaireTableauCard(
                        card = requireNotNull(SolitaireCard.fromId(encodedCard.dropLast(1).toInt())),
                        faceUp = face == 'U',
                    )
                }
            }
        }
        return MidnightSolitaireState(
            stock = decodeCards(parts[5]),
            waste = decodeCards(parts[6]),
            foundations = foundations,
            tableau = tableau,
            dealSeed = parts[0].toLong(),
            dealNumber = parts[1].toInt(),
            moves = parts[2].toInt(),
            recycles = parts[3].toInt(),
            status = SolitaireStatus.valueOf(parts[4]),
        )
    }

    private fun encodeCards(cards: List<SolitaireCard>): String = if (cards.isEmpty()) {
        EMPTY
    } else {
        cards.joinToString(separator = ",") { it.id.toString() }
    }

    private fun decodeCards(encoded: String): List<SolitaireCard> = if (encoded == EMPTY) {
        emptyList()
    } else {
        encoded.split(',').map { id -> requireNotNull(SolitaireCard.fromId(id.toInt())) }
    }

    private fun encodeBase64(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeBase64(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )

    private fun checksum(payload: String): String = CRC32().run {
        update(payload.toByteArray(StandardCharsets.UTF_8))
        value.toString(radix = 16)
    }
}
