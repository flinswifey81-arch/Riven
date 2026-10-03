package com.shai.riven.data.reminder.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import com.shai.riven.R
import com.shai.riven.data.reminder.ReminderFailureCode
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderSoundKind
import kotlinx.coroutines.CancellationException

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

/** SYSTEM_DEFAULT remains the persisted key while resolving to Riven's bundled alarm voice. */
internal fun selectAlarmAudioSource(
    soundKind: ReminderSoundKind,
    customSoundUri: String?,
    bundledRivenAlarmUri: String,
): String? = when (soundKind) {
    ReminderSoundKind.SYSTEM_DEFAULT -> bundledRivenAlarmUri
    ReminderSoundKind.CUSTOM_URI -> customSoundUri
}

/** Transfers ownership only after preparation succeeds, releasing the local candidate otherwise. */
internal fun <T> publishPreparedAlarmAudioResource(
    create: () -> T,
    prepareAndStart: (T) -> Unit,
    publish: (T) -> Unit,
    release: (T) -> Unit,
) {
    val candidate = create()
    var published = false
    try {
        prepareAndStart(candidate)
        publish(candidate)
        published = true
    } finally {
        if (!published) release(candidate)
    }
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
        val uri = selectAlarmAudioSource(
            soundKind = reminder.soundKind,
            customSoundUri = reminder.customSoundUri,
            bundledRivenAlarmUri = bundledRivenAlarmUri(),
        )?.let(Uri::parse) ?: return AlarmAudioResult.Failure(
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
        active?.let(::releasePlayer)
        val releaseAudioFocus = hasAudioFocus
        hasAudioFocus = false
        if (releaseAudioFocus) {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
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
            publishPreparedAlarmAudioResource(
                create = ::MediaPlayer,
                prepareAndStart = { candidate ->
                    candidate.setAudioAttributes(ALARM_ATTRIBUTES)
                    candidate.setDataSource(context, uri)
                    candidate.isLooping = true
                    candidate.prepare()
                    candidate.start()
                },
                publish = { prepared -> synchronized(this) { player = prepared } },
                release = ::releasePlayer,
            )
            AlarmAudioResult.Started
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        } catch (failure: Exception) {
            stop()
            AlarmAudioResult.Failure(
                ReminderFailureCode.AUDIO_SOURCE_UNAVAILABLE,
                "Alarm audio failed with ${failure::class.java.simpleName}.",
            )
        }
    }

    private fun releasePlayer(candidate: MediaPlayer) {
        runCatching { if (candidate.isPlaying) candidate.stop() }
        runCatching { candidate.reset() }
        runCatching { candidate.release() }
    }

    private fun defaultAlarmUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun bundledRivenAlarmUri(): String =
        "android.resource://${context.packageName}/${R.raw.riven_alarm}"

    private companion object {
        val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

internal data class AlarmAudioStartAttempt(
    val primary: AlarmAudioResult,
    val systemFallback: AlarmAudioResult?,
)

internal class AlarmAudioLifecycle(private val engine: AlarmAudioEngine) {
    private var active = false

    /** Attempts the selected source once, then the Android system alarm once on any failure. */
    fun startWithSystemFallback(reminder: ReminderSnapshot): AlarmAudioStartAttempt {
        stop()
        val primary = attempt("Selected alarm audio") { engine.play(reminder) }
        if (primary == AlarmAudioResult.Started) {
            active = true
            return AlarmAudioStartAttempt(primary, null)
        }
        val fallback = attempt("Android system alarm fallback") { engine.playSystemFallback() }
        active = fallback == AlarmAudioResult.Started
        return AlarmAudioStartAttempt(primary, fallback)
    }

    fun stop() {
        if (active) engine.stop()
        active = false
    }

    private fun attempt(
        source: String,
        operation: () -> AlarmAudioResult,
    ): AlarmAudioResult = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AlarmAudioResult.Failure(
            ReminderFailureCode.AUDIO_SOURCE_UNAVAILABLE,
            "$source failed with ${failure::class.java.simpleName}.",
        )
    }
}

internal object AlarmAudioEngineProvider {
    @Volatile
    var factory: (Context) -> AlarmAudioEngine = ::MediaPlayerAlarmAudioEngine
}
