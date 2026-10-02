package com.shai.riven.data.reminder.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderSoundKind
import com.shai.riven.data.reminder.ReminderStatus
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class AlarmDeliveryKey(val reminderId: String, val deliveryToken: String)

internal fun remainingRingMillis(ringUntilAt: Long, now: Long): Long =
    (ringUntilAt - now).coerceAtLeast(0L)

internal data class AlarmSessionRemoval<V>(val value: V, val wasActive: Boolean)

/** FIFO arbiter used by the service so one alarm action cannot stop a different token. */
internal class AlarmSessionArbiter<K, V> {
    private val queued = LinkedHashMap<K, V>()
    var activeKey: K? = null
        private set

    fun enqueue(key: K, value: V): Boolean = queued.putIfAbsent(key, value) == null

    fun activateNext(): V? {
        if (activeKey != null) return null
        val next = queued.entries.firstOrNull() ?: return null
        activeKey = next.key
        return next.value
    }

    fun remove(key: K): AlarmSessionRemoval<V>? {
        val value = queued.remove(key) ?: return null
        val wasActive = activeKey == key
        if (wasActive) activeKey = null
        return AlarmSessionRemoval(value, wasActive)
    }

    fun isActive(key: K): Boolean = activeKey == key

    fun removeQueued(key: K): V? {
        if (isActive(key)) return null
        return queued.remove(key)
    }

    fun complete(key: K): Boolean = remove(key)?.wasActive == true

    fun clear() {
        queued.clear()
        activeKey = null
    }

    fun isEmpty(): Boolean = queued.isEmpty()
}

/** Process-local targeted control. If the process is gone, no audio can be active. */
internal object AlarmPlaybackControl {
    @Volatile
    private var stopHandler: ((String, String) -> Unit)? = null

    fun register(handler: (String, String) -> Unit) {
        stopHandler = handler
    }

    fun unregister(handler: (String, String) -> Unit) {
        if (stopHandler === handler) stopHandler = null
    }

    fun stop(reminderId: String, deliveryToken: String) {
        stopHandler?.invoke(reminderId, deliveryToken)
    }
}

class AlarmRingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionGuard = Any()
    private val sessions = AlarmSessionArbiter<AlarmDeliveryKey, AlarmSession>()
    private lateinit var runtime: ReminderRuntime
    private lateinit var audio: AlarmAudioLifecycle
    private var activeJob: Job? = null
    private var foregroundStarted = false
    private val stopHandler: (String, String) -> Unit = ::stopSession

    override fun onCreate() {
        super.onCreate()
        runtime = ReminderRuntime.from(this)
        audio = AlarmAudioLifecycle(AlarmAudioEngineProvider.factory(this))
        AlarmPlaybackControl.register(stopHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val start = intent?.toAlarmStart() ?: return stopInvalid(startId)
        scope.launch {
            val current = runtime.repository.isCurrentDelivery(
                start.key.reminderId,
                start.scheduleRevision,
                start.key.deliveryToken,
            )
            if (current?.status != ReminderStatus.RINGING ||
                current.ringUntilAt != start.ringUntilAt
            ) {
                runtime.notifier.cancel(start.key.reminderId)
                stopSelf(startId)
                return@launch
            }
            enqueue(
                AlarmSession(
                    key = start.key,
                    scheduleRevision = start.scheduleRevision,
                    ringUntilAt = start.ringUntilAt,
                    reminder = current,
                ),
            )
        }
        return START_NOT_STICKY
    }

    private fun enqueue(session: AlarmSession) {
        synchronized(sessionGuard) {
            if (!sessions.enqueue(session.key, session)) return
            runtime.notifier.postAlarm(session.reminder, session.key.deliveryToken)
            launchNextLocked()
        }
    }

    private fun launchNextLocked() {
        if (sessions.activeKey != null) return
        val next = sessions.activateNext()
        if (next == null) {
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            stopSelf()
            return
        }
        startForeground(
            runtime.notifier.notificationId(next.key.reminderId),
            runtime.notifier.alarmNotification(next.reminder, next.key.deliveryToken),
        )
        foregroundStarted = true
        activeJob = scope.launch { runSession(next) }
    }

    private suspend fun runSession(session: AlarmSession) {
        var expired = false
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val current = runtime.repository.isCurrentDelivery(
                session.key.reminderId,
                session.scheduleRevision,
                session.key.deliveryToken,
            )
            if (current?.status != ReminderStatus.RINGING ||
                current.ringUntilAt != session.ringUntilAt
            ) {
                return
            }
            val remaining = remainingRingMillis(session.ringUntilAt, System.currentTimeMillis())
            if (remaining > 0L) {
                wakeLock = acquireWakeLock(session.key, remaining)
                startAudio(current, session.key.deliveryToken)
                delay(remaining)
            }
            expired = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            audio.stop()
            wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
            if (expired) finishExpiredSession(session)
            finishSession(session.key)
        }
    }

    private suspend fun startAudio(reminder: ReminderSnapshot, deliveryToken: String) {
        val result = audio.start(reminder)
        if (result is AlarmAudioResult.Failure) {
            runtime.repository.recordAudioWarning(
                reminder.id,
                reminder.scheduleRevision,
                deliveryToken,
                result.code,
                result.detail,
            )
            if (reminder.soundKind != ReminderSoundKind.SYSTEM_DEFAULT) {
                val fallback = audio.startFallback()
                if (fallback is AlarmAudioResult.Failure) {
                    runtime.repository.recordAudioWarning(
                        reminder.id,
                        reminder.scheduleRevision,
                        deliveryToken,
                        fallback.code,
                        fallback.detail,
                    )
                }
            }
        }
    }

    private suspend fun finishExpiredSession(session: AlarmSession) {
        val delivered = runtime.repository.markRingingAudioStopped(
            session.key.reminderId,
            session.scheduleRevision,
            session.key.deliveryToken,
        ) ?: return
        val permissions = ReminderPermissionInspector(this).snapshot()
        if (permissions.notificationPermissionGranted &&
            permissions.notificationsEnabled &&
            permissions.channelEnabled(ReminderDeliveryMode.NOTIFICATION)
        ) {
            runtime.notifier.postReminder(delivered, session.key.deliveryToken)
        } else {
            runtime.notifier.cancel(delivered.id)
        }
    }

    private fun finishSession(key: AlarmDeliveryKey) {
        synchronized(sessionGuard) {
            if (sessions.complete(key)) {
                activeJob = null
            }
            launchNextLocked()
        }
    }

    private fun stopSession(reminderId: String, deliveryToken: String) {
        val key = AlarmDeliveryKey(reminderId, deliveryToken)
        val job = synchronized(sessionGuard) {
            when {
                sessions.isActive(key) -> {
                    runtime.notifier.cancel(reminderId)
                    activeJob
                }
                sessions.removeQueued(key) != null -> {
                    runtime.notifier.cancel(reminderId)
                    null
                }
                else -> return
            }
        }
        job?.cancel()
        if (job == null) finishSession(key)
    }

    override fun onDestroy() {
        AlarmPlaybackControl.unregister(stopHandler)
        synchronized(sessionGuard) { sessions.clear() }
        activeJob?.cancel()
        scope.cancel()
        audio.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock(
        key: AlarmDeliveryKey,
        remaining: Long,
    ): PowerManager.WakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:RivenAlarm:${key.reminderId}")
        .apply { acquire(remaining + WAKE_LOCK_MARGIN_MILLIS) }

    private fun stopInvalid(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun Intent.toAlarmStart(): AlarmStart? {
        val reminderId = getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return null
        val deliveryToken = getStringExtra(ReminderIntents.EXTRA_DELIVERY_TOKEN) ?: return null
        val revision = getLongExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, -1)
        val ringUntilAt = getLongExtra(EXTRA_RING_UNTIL_AT, -1)
        if (revision < 0 || ringUntilAt < 0) return null
        return AlarmStart(
            key = AlarmDeliveryKey(reminderId, deliveryToken),
            scheduleRevision = revision,
            ringUntilAt = ringUntilAt,
        )
    }

    private data class AlarmStart(
        val key: AlarmDeliveryKey,
        val scheduleRevision: Long,
        val ringUntilAt: Long,
    )

    private data class AlarmSession(
        val key: AlarmDeliveryKey,
        val scheduleRevision: Long,
        val ringUntilAt: Long,
        val reminder: ReminderSnapshot,
    )

    companion object {
        const val EXTRA_RING_UNTIL_AT = "alarm_ring_until_at"
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_NOTE = "alarm_note"
        private const val WAKE_LOCK_MARGIN_MILLIS = 5_000L

        fun intent(
            context: android.content.Context,
            reminderId: String,
            scheduleRevision: Long,
            deliveryToken: String,
            ringUntilAt: Long,
            title: String,
            note: String?,
        ): Intent = Intent(context, AlarmRingingService::class.java)
            .putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminderId)
            .putExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, scheduleRevision)
            .putExtra(ReminderIntents.EXTRA_DELIVERY_TOKEN, deliveryToken)
            .putExtra(EXTRA_RING_UNTIL_AT, ringUntilAt)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_NOTE, note)
    }
}
