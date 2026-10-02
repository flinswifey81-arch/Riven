package com.shai.riven.ui.arcade.starstruck

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

data class StarstruckSettings(
    val soundVolume: Int = 45,
    val soundMuted: Boolean = false,
    val gentleEffects: Boolean = true,
) {
    fun normalized(): StarstruckSettings = copy(soundVolume = soundVolume.coerceIn(0, 100))
}

interface StarstruckStore {
    fun loadSession(): StarstruckState?

    fun saveSession(state: StarstruckState)

    fun loadSettings(): StarstruckSettings

    fun saveSettings(settings: StarstruckSettings)
}

class SharedPreferencesStarstruckStore(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) : StarstruckStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    override fun loadSession(): StarstruckState? = preferences.getString(KEY_SESSION, null)
        ?.let(StarstruckSnapshotCodec::decode)

    override fun saveSession(state: StarstruckState) {
        preferences.edit { putString(KEY_SESSION, StarstruckSnapshotCodec.encode(state)) }
    }

    override fun loadSettings(): StarstruckSettings = StarstruckSettings(
        soundVolume = preferences.getInt(KEY_VOLUME, 45).coerceIn(0, 100),
        soundMuted = preferences.getBoolean(KEY_MUTED, false),
        gentleEffects = preferences.getBoolean(KEY_EFFECTS, true),
    )

    override fun saveSettings(settings: StarstruckSettings) {
        val normalized = settings.normalized()
        preferences.edit {
            putInt(KEY_VOLUME, normalized.soundVolume)
            putBoolean(KEY_MUTED, normalized.soundMuted)
            putBoolean(KEY_EFFECTS, normalized.gentleEffects)
        }
    }

    companion object {
        internal const val PREFERENCE_NAME = "starstruck"
        private const val KEY_SESSION = "session_v1"
        private const val KEY_VOLUME = "sound_volume"
        private const val KEY_MUTED = "sound_muted"
        private const val KEY_EFFECTS = "gentle_effects"
    }
}

object StarstruckSnapshotCodec {
    private const val VERSION = "1"
    private const val ENCODED_TILE_LENGTH = 2

    fun encode(state: StarstruckState): String {
        val board = state.board.joinToString(separator = "") { tile ->
            "${tile.kind.code}${tile.power.code}"
        }
        val payload = listOf(
            VERSION,
            board,
            state.randomState,
            state.movesMade,
            state.matchesMade,
            state.cascadeWaves,
            state.tilesCleared,
            state.reshuffles,
        ).joinToString(separator = "|")
        return "$payload|${checksum(payload)}"
    }

    fun decode(snapshot: String): StarstruckState? = runCatching {
        val parts = snapshot.split('|')
        require(parts.size == 9)
        require(parts[0] == VERSION)
        val payload = parts.dropLast(1).joinToString(separator = "|")
        require(parts.last() == checksum(payload))
        require(parts[1].length == STARSTRUCK_COLUMNS * STARSTRUCK_ROWS * ENCODED_TILE_LENGTH)
        val board = parts[1].chunked(ENCODED_TILE_LENGTH).map { encoded ->
            StarTile(
                kind = requireNotNull(StarTileKind.fromCode(encoded[0])),
                power = requireNotNull(StarPower.fromCode(encoded[1])),
            )
        }
        val state = StarstruckState(
            board = board,
            randomState = parts[2].toLong(),
            movesMade = parts[3].toInt(),
            matchesMade = parts[4].toInt(),
            cascadeWaves = parts[5].toInt(),
            tilesCleared = parts[6].toInt(),
            reshuffles = parts[7].toInt(),
        )
        require(!StarstruckEngine.hasImmediateMatches(state.board))
        require(StarstruckEngine.hasLegalMove(state.board))
        state
    }.getOrNull()

    private fun checksum(payload: String): String = CRC32().run {
        update(payload.toByteArray(StandardCharsets.UTF_8))
        value.toString(radix = 16)
    }
}
