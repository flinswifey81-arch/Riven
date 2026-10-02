package com.shai.riven.ui.arcade.comet

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

enum class TrailSpeed(
    val label: String,
    val tickMillis: Long,
) {
    GENTLE("Gentle", 650L),
    EASY("Easy", 450L),
    BREEZY("Breezy", 300L),
    ;

    fun next(): TrailSpeed = entries[(ordinal + 1) % entries.size]
}

data class CometTrailSettings(
    val speed: TrailSpeed = TrailSpeed.GENTLE,
)

interface CometTrailStore {
    fun loadSession(): CometTrailState?

    fun saveSession(state: CometTrailState)

    fun loadSettings(): CometTrailSettings

    fun saveSettings(settings: CometTrailSettings)
}

class SharedPreferencesCometTrailStore(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) : CometTrailStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    override fun loadSession(): CometTrailState? = preferences.getString(KEY_SESSION, null)
        ?.let(CometTrailSnapshotCodec::decode)

    override fun saveSession(state: CometTrailState) {
        preferences.edit {
            putString(KEY_SESSION, CometTrailSnapshotCodec.encode(state))
        }
    }

    override fun loadSettings(): CometTrailSettings {
        val speed = preferences.getString(KEY_SPEED, null)
            ?.let { stored -> TrailSpeed.entries.firstOrNull { it.name == stored } }
            ?: TrailSpeed.GENTLE
        return CometTrailSettings(speed = speed)
    }

    override fun saveSettings(settings: CometTrailSettings) {
        preferences.edit { putString(KEY_SPEED, settings.speed.name) }
    }

    companion object {
        internal const val PREFERENCE_NAME = "comet_trail"
        private const val KEY_SESSION = "session_v1"
        private const val KEY_SPEED = "speed"
    }
}

object CometTrailSnapshotCodec {
    private const val VERSION = "1"

    fun encode(state: CometTrailState): String {
        val body = state.body.joinToString(separator = ";") { point -> "${point.x},${point.y}" }
        val payload = listOf(
            VERSION,
            body,
            state.direction.name,
            state.food.x,
            state.food.y,
            state.randomState,
            state.treatsEaten,
            state.stepsTaken,
            state.boardRefreshes,
            state.collisionDirection?.name.orEmpty(),
        ).joinToString(separator = "|")
        return "$payload|${checksum(payload)}"
    }

    fun decode(snapshot: String): CometTrailState? = runCatching {
        val parts = snapshot.split('|')
        require(parts.size == 11)
        require(parts[0] == VERSION)
        val payload = parts.dropLast(1).joinToString(separator = "|")
        require(parts.last() == checksum(payload))
        val body = parts[1].split(';').map { encodedPoint ->
            val coordinates = encodedPoint.split(',')
            require(coordinates.size == 2)
            TrailPoint(coordinates[0].toInt(), coordinates[1].toInt())
        }
        val state = CometTrailState(
            body = body,
            direction = TrailDirection.valueOf(parts[2]),
            food = TrailPoint(parts[3].toInt(), parts[4].toInt()),
            randomState = parts[5].toLong(),
            treatsEaten = parts[6].toInt(),
            stepsTaken = parts[7].toInt(),
            boardRefreshes = parts[8].toInt(),
            collisionDirection = parts[9].takeIf(String::isNotEmpty)?.let(TrailDirection::valueOf),
        )
        require(CometTrailEngine.isConnectedBody(state.body))
        require(state.food !in state.body)
        state.collisionDirection?.let { attempted ->
            require(CometTrailEngine.wouldCollide(state.copy(collisionDirection = null), attempted))
        }
        state
    }.getOrNull()

    private fun checksum(payload: String): String = CRC32().run {
        update(payload.toByteArray(StandardCharsets.UTF_8))
        value.toString(radix = 16)
    }
}
