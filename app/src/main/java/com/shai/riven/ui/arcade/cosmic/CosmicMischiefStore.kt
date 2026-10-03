package com.shai.riven.ui.arcade.cosmic

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32

data class CosmicMischiefSettings(
    val largeCardText: Boolean = false,
)

interface CosmicMischiefStore {
    fun loadSession(): CosmicMischiefSession?

    fun saveSession(session: CosmicMischiefSession)

    fun loadSettings(): CosmicMischiefSettings

    fun saveSettings(settings: CosmicMischiefSettings)
}

class SharedPreferencesCosmicMischiefStore(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) : CosmicMischiefStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    override fun loadSession(): CosmicMischiefSession? = preferences.getString(KEY_SESSION, null)
        ?.let(CosmicMischiefSnapshotCodec::decode)

    override fun saveSession(session: CosmicMischiefSession) {
        val existing = preferences.getString(KEY_SESSION, null)
        val preserveCorruptSnapshot = existing != null &&
            CosmicMischiefSnapshotCodec.decode(existing) == null &&
            !preferences.contains(KEY_CORRUPT_SESSION_BACKUP)
        preferences.edit {
            if (preserveCorruptSnapshot) putString(KEY_CORRUPT_SESSION_BACKUP, existing)
            putString(KEY_SESSION, CosmicMischiefSnapshotCodec.encode(session))
        }
    }

    override fun loadSettings(): CosmicMischiefSettings = CosmicMischiefSettings(
        largeCardText = preferences.getBoolean(KEY_LARGE_CARD_TEXT, false),
    )

    override fun saveSettings(settings: CosmicMischiefSettings) {
        preferences.edit { putBoolean(KEY_LARGE_CARD_TEXT, settings.largeCardText) }
    }

    companion object {
        internal const val PREFERENCE_NAME = "cosmic_mischief"
        internal const val KEY_SESSION = "session_v1"
        internal const val KEY_LARGE_CARD_TEXT = "large_card_text"
        internal const val KEY_CORRUPT_SESSION_BACKUP = "session_v1_corrupt_backup"
    }
}

object CosmicMischiefSnapshotCodec {
    private const val VERSION = "1"
    private const val EMPTY = "_"

    fun encode(session: CosmicMischiefSession): String {
        val state = session.game
        require(CosmicMischiefEngine.isValid(state))
        val payload = listOf(
            VERSION,
            state.dealSeed.toString(),
            state.gameNumber.toString(),
            state.randomState.toString(),
            state.revision.toString(),
            state.moves.toString(),
            state.turn.name,
            state.status.name,
            state.activeColor.name,
            encodeCards(state.drawPile),
            encodeCards(state.discardPile),
            encodeCards(state.hand(CosmicPlayer.SHAI)),
            encodeCards(state.hand(CosmicPlayer.RIVEN)),
            encodeCheat(state.pendingCheat),
            encodeTrade(state.pendingTrade),
            state.tradeUsedThisTurn.toString(),
            state.grudges.joinToString(separator = ","),
            state.honoredBargains.toString(),
            state.betrayedBargains.toString(),
            encodeEvents(state.events),
        ).joinToString(separator = "|")
        return "$payload|${checksum(payload)}"
    }

    fun decode(snapshot: String): CosmicMischiefSession? = runCatching {
        val parts = snapshot.split('|')
        require(parts.size == 21)
        require(parts[0] == VERSION)
        val payload = parts.dropLast(1).joinToString(separator = "|")
        require(parts.last() == checksum(payload))
        val state = CosmicMischiefState(
            drawPile = decodeCards(parts[9]),
            discardPile = decodeCards(parts[10]),
            hands = listOf(decodeCards(parts[11]), decodeCards(parts[12])),
            activeColor = CosmicColor.valueOf(parts[8]),
            turn = CosmicPlayer.valueOf(parts[6]),
            status = CosmicStatus.valueOf(parts[7]),
            pendingCheat = decodeCheat(parts[13]),
            pendingTrade = decodeTrade(parts[14]),
            tradeUsedThisTurn = parts[15].toBooleanStrict(),
            grudges = parts[16].split(',').map(String::toInt),
            honoredBargains = parts[17].toInt(),
            betrayedBargains = parts[18].toInt(),
            dealSeed = parts[1].toLong(),
            gameNumber = parts[2].toInt(),
            randomState = parts[3].toLong(),
            revision = parts[4].toLong(),
            moves = parts[5].toInt(),
            events = decodeEvents(parts[19]),
        )
        require(CosmicMischiefEngine.isValid(state))
        CosmicMischiefSession(state)
    }.getOrNull()

    private fun encodeCards(cards: List<CosmicCard>): String = if (cards.isEmpty()) {
        EMPTY
    } else {
        cards.joinToString(separator = ",") { it.id.toString() }
    }

    private fun decodeCards(encoded: String): List<CosmicCard> = if (encoded == EMPTY) {
        emptyList()
    } else {
        encoded.split(',').map { id -> requireNotNull(CosmicCard.fromId(id.toInt())) }
    }

    private fun encodeCheat(cheat: CosmicCheatRecord?): String = cheat?.let {
        listOf(
            it.accused.name,
            it.primaryCardId,
            it.extraCardId,
            it.primaryChosenColor?.name ?: EMPTY,
            it.extraChosenColor?.name ?: EMPTY,
        ).joinToString(separator = ",")
    } ?: EMPTY

    private fun decodeCheat(encoded: String): CosmicCheatRecord? {
        if (encoded == EMPTY) return null
        val parts = encoded.split(',')
        require(parts.size == 5)
        return CosmicCheatRecord(
            accused = CosmicPlayer.valueOf(parts[0]),
            primaryCardId = parts[1].toInt(),
            extraCardId = parts[2].toInt(),
            primaryChosenColor = parts[3].takeUnless { it == EMPTY }?.let(CosmicColor::valueOf),
            extraChosenColor = parts[4].takeUnless { it == EMPTY }?.let(CosmicColor::valueOf),
        )
    }

    private fun encodeTrade(trade: CosmicTradeOffer?): String = trade?.let {
        "${it.proposer.name},${it.offeredCardId}"
    } ?: EMPTY

    private fun decodeTrade(encoded: String): CosmicTradeOffer? {
        if (encoded == EMPTY) return null
        val parts = encoded.split(',')
        require(parts.size == 2)
        return CosmicTradeOffer(
            proposer = CosmicPlayer.valueOf(parts[0]),
            offeredCardId = parts[1].toInt(),
        )
    }

    private fun encodeEvents(events: List<CosmicEvent>): String = if (events.isEmpty()) {
        EMPTY
    } else {
        events.joinToString(separator = ",") { event ->
            listOf(
                event.type.name,
                event.actor?.name ?: EMPTY,
                event.target?.name ?: EMPTY,
                event.cardId?.toString() ?: EMPTY,
                event.secondaryCardId?.toString() ?: EMPTY,
                encodeBase64(event.message),
            ).joinToString(separator = "~")
        }
    }

    private fun decodeEvents(encoded: String): List<CosmicEvent> = if (encoded == EMPTY) {
        emptyList()
    } else {
        encoded.split(',').map { encodedEvent ->
            val parts = encodedEvent.split('~')
            require(parts.size == 6)
            CosmicEvent(
                type = CosmicEventType.valueOf(parts[0]),
                actor = parts[1].takeUnless { it == EMPTY }?.let(CosmicPlayer::valueOf),
                target = parts[2].takeUnless { it == EMPTY }?.let(CosmicPlayer::valueOf),
                cardId = parts[3].takeUnless { it == EMPTY }?.toInt(),
                secondaryCardId = parts[4].takeUnless { it == EMPTY }?.toInt(),
                message = decodeBase64(parts[5]),
            )
        }.takeLast(COSMIC_EVENT_LIMIT)
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
