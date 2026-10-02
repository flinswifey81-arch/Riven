package com.shai.riven.data.reminder.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.shai.riven.data.reminder.ReminderSoundKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlarmRingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtime: ReminderRuntime
    private lateinit var audio: AlarmAudioLifecycle
    private var playbackJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        runtime = ReminderRuntime.from(this)
        audio = AlarmAudioLifecycle(AlarmAudioEngineProvider.factory(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reminderId = intent?.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)
            ?: return stopInvalid(startId)
        val deliveryToken = intent.getStringExtra(ReminderIntents.EXTRA_DELIVERY_TOKEN)
            ?: return stopInvalid(startId)
        val revision = intent.getLongExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, -1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Riven alarm"
        val note = intent.getStringExtra(EXTRA_NOTE)
        startForeground(
            runtime.notifier.notificationId(reminderId),
            runtime.notifier.alarmNotification(reminderId, title, note, revision, deliveryToken),
        )

        playbackJob?.cancel()
        playbackJob = scope.launch {
            val reminder = runtime.repository.isCurrentDelivery(reminderId, revision, deliveryToken)
            if (reminder == null) {
                stopSelf(startId)
                return@launch
            }
            acquireWakeLock()
            when (val result = audio.start(reminder)) {
                AlarmAudioResult.Started -> Unit
                is AlarmAudioResult.Failure -> {
                    runtime.repository.recordAudioWarning(
                        reminderId,
                        revision,
                        deliveryToken,
                        result.code,
                        result.detail,
                    )
                    if (reminder.soundKind != ReminderSoundKind.SYSTEM_DEFAULT) {
                        val fallback = audio.startFallback()
                        if (fallback is AlarmAudioResult.Failure) {
                            runtime.repository.recordAudioWarning(
                                reminderId,
                                revision,
                                deliveryToken,
                                fallback.code,
                                fallback.detail,
                            )
                        }
                    }
                }
            }
            delay(MAXIMUM_RINGING_MILLIS)
            audio.stop()
            releaseWakeLock()
            runtime.repository.markRingingAudioStopped(reminderId, revision, deliveryToken)?.let { delivered ->
                runtime.notifier.cancel(reminderId)
                runtime.notifier.postReminder(delivered, deliveryToken)
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        playbackJob?.cancel()
        audio.stop()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:RivenAlarm")
            .apply { acquire(MAXIMUM_RINGING_MILLIS + WAKE_LOCK_MARGIN_MILLIS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopInvalid(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_NOTE = "alarm_note"
        private const val MAXIMUM_RINGING_MILLIS = 10 * 60 * 1_000L
        private const val WAKE_LOCK_MARGIN_MILLIS = 5_000L

        fun intent(
            context: android.content.Context,
            reminderId: String,
            scheduleRevision: Long,
            deliveryToken: String,
            title: String,
            note: String?,
        ): Intent = Intent(context, AlarmRingingService::class.java)
            .putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminderId)
            .putExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, scheduleRevision)
            .putExtra(ReminderIntents.EXTRA_DELIVERY_TOKEN, deliveryToken)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_NOTE, note)
    }
}
