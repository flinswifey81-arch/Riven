package com.shai.riven.data.reminder.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import com.shai.riven.data.reminder.ReminderFailureCode
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderSoundKind

sealed interface AlarmAudioResult {
    data object Started : AlarmAudioResult

    data class Failure(
        val code: ReminderFailureCode,
        val detail: String,
    ) : AlarmAudioResult
}

internal interface AlarmAudioEngine {
    fun play(reminder: ReminderSnapshot): AlarmAudioResult

    fun playSystemFallback(): AlarmAudioResult

    fun stop()
}

internal class MediaPlayerAlarmAudioEngine(private val context: Context) : AlarmAudioEngine {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(ALARM_ATTRIBUTES)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) stop()
        }
        .build()

    private var player: MediaPlayer? = null
    private var hasAudioFocus = false

    override fun play(reminder: ReminderSnapshot): AlarmAudioResult {
        val uri = when (reminder.soundKind) {
            ReminderSoundKind.SYSTEM_DEFAULT -> defaultAlarmUri()
            ReminderSoundKind.CUSTOM_URI -> reminder.customSoundUri?.let(Uri::parse)
        } ?: return AlarmAudioResult.Failure(
            ReminderFailureCode.AUDIO_SOURCE_UNAVAILABLE,
            "The selected alarm sound is unavailable.",
        )
        return playUri(uri)
    }

    override fun playSystemFallback(): AlarmAudioResult = playUri(defaultAlarmUri())

    override fun stop() {
        val active = synchronized(this) {
            val current = player
            player = null
            current
        }
        active?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.reset()
            it.release()
        }
        if (hasAudioFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasAudioFocus = false
        }
    }

    private fun playUri(uri: Uri?): AlarmAudioResult {
        if (uri == null) {
            return AlarmAudioResult.Failure(
                ReminderFailureCode.AUDIO_SOURCE_UNAVAILABLE,
                "Android does not have a default alarm sound configured.",
            )
        }
        stop()
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return AlarmAudioResult.Failure(
                ReminderFailureCode.AUDIO_FOCUS_DENIED,
                "Android did not grant alarm audio focus. The visible alarm is still active.",
            )
        }
        hasAudioFocus = true
        return try {
            val prepared = MediaPlayer().apply {
                setAudioAttributes(ALARM_ATTRIBUTES)
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            synchronized(this) { player = prepared }
            AlarmAudioResult.Started
        } catch (failure: Exception) {
            stop()
            AlarmAudioResult.Failure(
                ReminderFailureCode.AUDIO_SOURCE_UNAVAILABLE,
                "Alarm audio failed with ${failure::class.java.simpleName}.",
            )
        }
    }

    private fun defaultAlarmUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private companion object {
        val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

internal class AlarmAudioLifecycle(private val engine: AlarmAudioEngine) {
    private var active = false

    fun start(reminder: ReminderSnapshot): AlarmAudioResult {
        stop()
        val result = engine.play(reminder)
        active = result == AlarmAudioResult.Started
        return result
    }

    fun startFallback(): AlarmAudioResult {
        stop()
        val result = engine.playSystemFallback()
        active = result == AlarmAudioResult.Started
        return result
    }

    fun stop() {
        if (active) engine.stop()
        active = false
    }
}

internal object AlarmAudioEngineProvider {
    @Volatile
    var factory: (Context) -> AlarmAudioEngine = ::MediaPlayerAlarmAudioEngine
}
