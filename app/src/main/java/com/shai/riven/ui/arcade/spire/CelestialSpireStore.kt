package com.shai.riven.ui.arcade.spire

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

enum class SpireFallSpeed(
    val label: String,
    val tickMillis: Long,
) {
    CALM("Calm", 900L),
    STEADY("Steady", 650L),
    BRISK("Brisk", 400L),
    ;

    fun next(): SpireFallSpeed = entries[(ordinal + 1) % entries.size]
}

data class CelestialSpireSettings(
    val fallSpeed: SpireFallSpeed = SpireFallSpeed.STEADY,
    val soundVolume: Int = 55,
    val soundMuted: Boolean = false,
) {
    fun normalized(): CelestialSpireSettings = copy(soundVolume = soundVolume.coerceIn(0, 100))
}

interface CelestialSpireStore {
    fun loadSession(): CelestialSpireState?

    fun saveSession(state: CelestialSpireState)

    fun loadSettings(): CelestialSpireSettings

    fun saveSettings(settings: CelestialSpireSettings)
}

class SharedPreferencesCelestialSpireStore(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) : CelestialSpireStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    override fun loadSession(): CelestialSpireState? = preferences.getString(KEY_SESSION, null)
        ?.let(CelestialSpireSnapshotCodec::decode)

    override fun saveSession(state: CelestialSpireState) {
        preferences.edit {
            putString(KEY_SESSION, CelestialSpireSnapshotCodec.encode(state))
        }
    }

    override fun loadSettings(): CelestialSpireSettings {
        val speed = preferences.getString(KEY_SPEED, null)
            ?.let { stored -> SpireFallSpeed.entries.firstOrNull { it.name == stored } }
            ?: SpireFallSpeed.STEADY
        return CelestialSpireSettings(
            fallSpeed = speed,
            soundVolume = preferences.getInt(KEY_VOLUME, 55).coerceIn(0, 100),
            soundMuted = preferences.getBoolean(KEY_MUTED, false),
        )
    }

    override fun saveSettings(settings: CelestialSpireSettings) {
        val normalized = settings.normalized()
        preferences.edit {
            putString(KEY_SPEED, normalized.fallSpeed.name)
            putInt(KEY_VOLUME, normalized.soundVolume)
            putBoolean(KEY_MUTED, normalized.soundMuted)
        }
    }

    companion object {
        internal const val PREFERENCE_NAME = "celestial_spire"
        private const val KEY_SESSION = "session_v1"
        private const val KEY_SPEED = "fall_speed"
        private const val KEY_VOLUME = "sound_volume"
        private const val KEY_MUTED = "sound_muted"
    }
}

object CelestialSpireSnapshotCodec {
    private const val VERSION = "1"

    fun encode(state: CelestialSpireState): String {
        val board = state.board.joinToString(separator = "") { piece -> piece?.code?.toString() ?: "." }
        val payload = listOf(
            VERSION,
            board,
            state.active.type.code,
            state.active.rotation,
            state.active.x,
            state.active.y,
            state.next.code,
            state.bag.joinToString(separator = "") { it.code.toString() },
            state.randomState,
            state.linesCleared,
            state.piecesPlaced,
            state.boardRefreshes,
        ).joinToString(separator = "|")
        return "$payload|${checksum(payload)}"
    }

    fun decode(snapshot: String): CelestialSpireState? = runCatching {
        val parts = snapshot.split('|')
        require(parts.size == 13)
        require(parts[0] == VERSION)
        val payload = parts.dropLast(1).joinToString(separator = "|")
        require(parts.last() == checksum(payload))
        require(parts[1].length == CELESTIAL_SPIRE_COLUMNS * CELESTIAL_SPIRE_ROWS)
        val board = parts[1].map { code ->
            if (code == '.') null else requireNotNull(SpirePieceType.fromCode(code))
        }
        val active = SpirePiece(
            type = requireNotNull(parts[2].singleOrNull()?.let(SpirePieceType::fromCode)),
            rotation = parts[3].toInt(),
            x = parts[4].toInt(),
            y = parts[5].toInt(),
        )
        require(active.x in -3 until CELESTIAL_SPIRE_COLUMNS)
        require(active.y in -3 until CELESTIAL_SPIRE_ROWS)
        val state = CelestialSpireState(
            board = board,
            active = active,
            next = requireNotNull(parts[6].singleOrNull()?.let(SpirePieceType::fromCode)),
            bag = parts[7].map { code -> requireNotNull(SpirePieceType.fromCode(code)) },
            randomState = parts[8].toLong(),
            linesCleared = parts[9].toInt(),
            piecesPlaced = parts[10].toInt(),
            boardRefreshes = parts[11].toInt(),
        )
        require(state.bag.distinct().size == state.bag.size)
        require(CelestialSpireEngine.canPlace(state.board, state.active))
        state
    }.getOrNull()

    private fun checksum(payload: String): String = CRC32().run {
        update(payload.toByteArray(StandardCharsets.UTF_8))
        value.toString(radix = 16)
    }
}
