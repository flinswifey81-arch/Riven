package com.shai.riven.data.reminder.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.shai.riven.data.reminder.ReminderFailureCode
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderStatus
import com.shai.riven.data.reset.RivenStartupMutationGate
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AlarmDeliveryKey(val reminderId: String, val deliveryToken: String)

internal fun remainingRingMillis(ringUntilAt: Long, now: Long): Long =
    (ringUntilAt - now).coerceAtLeast(0L)

internal data class AlarmSessionRemoval<V>(val value: V, val wasActive: Boolean)

internal suspend fun settleAlarmAudioStartAttempt(
    attempt: AlarmAudioStartAttempt,
    recordWarning: suspend (AlarmAudioResult.Failure) -> Unit,
    terminalize: suspend (AlarmAudioResult.Failure) -> Unit,
): Boolean {
    val primaryFailure = attempt.primary as? AlarmAudioResult.Failure
    val fallbackFailure = attempt.systemFallback as? AlarmAudioResult.Failure
    if (attempt.primary == AlarmAudioResult.Started || attempt.systemFallback == AlarmAudioResult.Started) {
        primaryFailure?.let { recordWarning(it) }
        fallbackFailure?.let { recordWarning(it) }
        return true
    }
    val lastFailure = fallbackFailure ?: primaryFailure ?: AlarmAudioResult.Failure(
        ReminderFailureCode.AUDIO_SOURCE_UNAVAILABLE,
        "Alarm audio did not start.",
    )
    try {
        primaryFailure?.let { recordWarning(it) }
        fallbackFailure?.let { recordWarning(it) }
    } finally {
        terminalize(
            lastFailure.copy(
                detail = "Selected alarm audio and Android system alarm fallback both failed. " +
                    lastFailure.detail,
            ),
        )
    }
    return false
}

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

/** Service-level ownership for async validation, foreground startup, and queued sessions. */
internal class AlarmServiceLifecycle<K, V>(private val keyOf: (V) -> K) {
    private val sessions = AlarmSessionArbiter<K, V>()
    private var pendingOperations = 0

    val activeKey: K?
        get() = sessions.activeKey

    fun operationStarted() {
        pendingOperations++
    }

    fun operationFinished(): Boolean {
        check(pendingOperations > 0)
        pendingOperations--
        return shouldStop()
    }

    fun enqueue(key: K, value: V): Boolean = sessions.enqueue(key, value)

    fun startNext(
        startForeground: (V) -> Unit,
        onForegroundFailure: (V, RuntimeException) -> Unit,
    ): V? {
        if (sessions.activeKey != null) return null
        while (true) {
            val next = sessions.activateNext() ?: return null
            try {
                startForeground(next)
                return next
            } catch (failure: RuntimeException) {
                sessions.complete(keyOf(next))
                onForegroundFailure(next, failure)
            }
        }
    }

    fun isActive(key: K): Boolean = sessions.isActive(key)

    fun removeQueued(key: K): V? = sessions.removeQueued(key)

    fun complete(key: K): Boolean = sessions.complete(key)

    fun clear() = sessions.clear()

    fun shouldStop(): Boolean = pendingOperations == 0 && sessions.isEmpty()
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
    private val lifecycle = AlarmServiceLifecycle<AlarmDeliveryKey, AlarmSession>(AlarmSession::key)
    private lateinit var runtime: ReminderRuntime
    private lateinit var audio: AlarmAudioLifecycle
    private var activeJob: Job? = null
    private var foregroundStarted = false
    private var startupMutationBlocked = false
    private val stopHandler: (String, String) -> Unit = ::stopSession

    override fun onCreate() {
        super.onCreate()
        if (RivenStartupMutationGate.isPending(this)) {
            startupMutationBlocked = true
            stopSelf()
            return
        }
        runtime = ReminderRuntime.from(this)
        audio = AlarmAudioLifecycle(AlarmAudioEngineProvider.factory(this))
        AlarmPlaybackControl.register(stopHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupMutationBlocked || RivenStartupMutationGate.isPending(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val start = intent?.toAlarmStart() ?: return stopInvalid()
        synchronized(sessionGuard) { lifecycle.operationStarted() }
        scope.launch {
            try {
                val current = runtime.repository.isCurrentDelivery(
                    start.key.reminderId,
                    start.scheduleRevision,
                    start.key.deliveryToken,
                )
                if (current?.status != ReminderStatus.RINGING ||
                    current.ringUntilAt != start.ringUntilAt
                ) {
                    dropInvalidStart(start.key)
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                terminalizeStartFailure(start, failure)
            } finally {
                synchronized(sessionGuard) {
                    lifecycle.operationFinished()
                    stopIfIdleLocked()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun enqueue(session: AlarmSession) {
        synchronized(sessionGuard) {
            if (!lifecycle.enqueue(session.key, session)) return
            runtime.notifier.postAlarm(session.reminder, session.key.deliveryToken)
            launchNextLocked()
        }
    }

    private fun launchNextLocked() {
        if (lifecycle.activeKey != null) return
        val next = lifecycle.startNext(
            startForeground = { session ->
                startForeground(
                    runtime.notifier.notificationId(session.key.reminderId),
                    runtime.notifier.alarmNotification(session.reminder, session.key.deliveryToken),
                )
                foregroundStarted = true
            },
            onForegroundFailure = { session, failure ->
                lifecycle.operationStarted()
                scope.launch {
                    try {
                        withContext(NonCancellable) {
                            runtime.repository.failClaimedDelivery(
                                reminderId = session.key.reminderId,
                                scheduleRevision = session.scheduleRevision,
                                deliveryToken = session.key.deliveryToken,
                                code = ReminderFailureCode.SCHEDULER_FAILURE,
                                detail = "Android could not enter foreground alarm playback: " +
                                    "${failure::class.java.simpleName}.",
                            )
                        }
                    } finally {
                        synchronized(sessionGuard) {
                            lifecycle.operationFinished()
                            stopIfIdleLocked()
                        }
                    }
                }
            },
        )
        if (next == null) {
            stopIfIdleLocked()
            return
        }
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
                if (!startAudio(current, session.key.deliveryToken)) return
                delay(remaining)
            }
            expired = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            audio.stop()
            wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
            try {
                if (expired) {
                    withContext(NonCancellable) { finishExpiredSession(session) }
                }
            } finally {
                finishSession(session.key)
            }
        }
    }

    private suspend fun startAudio(reminder: ReminderSnapshot, deliveryToken: String): Boolean {
        val attempt = audio.startWithSystemFallback(reminder)
        return withContext(NonCancellable) {
            settleAlarmAudioStartAttempt(
                attempt = attempt,
                recordWarning = { failure ->
                    runtime.repository.recordAudioWarning(
                        reminder.id,
                        reminder.scheduleRevision,
                        deliveryToken,
                        failure.code,
                        failure.detail,
                    )
                },
                terminalize = { failure ->
                    runtime.repository.failClaimedDelivery(
                        reminderId = reminder.id,
                        scheduleRevision = reminder.scheduleRevision,
                        deliveryToken = deliveryToken,
                        code = failure.code,
                        detail = failure.detail,
                    )
                },
            )
        }
    }

    private suspend fun finishExpiredSession(session: AlarmSession) {
        val delivering = runtime.repository.prepareRingingTimeoutNotification(
            session.key.reminderId,
            session.scheduleRevision,
            session.key.deliveryToken,
        ) ?: return
        ReminderDeliveryDispatcher.create(this, runtime).recoverPending(delivering)
    }

    private fun finishSession(key: AlarmDeliveryKey) {
        synchronized(sessionGuard) {
            if (lifecycle.complete(key)) {
                activeJob = null
            }
            launchNextLocked()
        }
    }

    private fun stopSession(reminderId: String, deliveryToken: String) {
        val key = AlarmDeliveryKey(reminderId, deliveryToken)
        val job = synchronized(sessionGuard) {
            when {
                lifecycle.isActive(key) -> {
                    runtime.notifier.cancel(reminderId)
                    activeJob
                }
                lifecycle.removeQueued(key) != null -> {
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
        synchronized(sessionGuard) { lifecycle.clear() }
        activeJob?.cancel()
        scope.cancel()
        if (::audio.isInitialized) audio.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock(
        key: AlarmDeliveryKey,
        remaining: Long,
    ): PowerManager.WakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:RivenAlarm:${key.reminderId}")
        .apply { acquire(remaining + WAKE_LOCK_MARGIN_MILLIS) }

    private fun stopInvalid(): Int {
        synchronized(sessionGuard) { stopIfIdleLocked() }
        return START_NOT_STICKY
    }

    private fun dropInvalidStart(key: AlarmDeliveryKey) {
        stopSession(key.reminderId, key.deliveryToken)
    }

    private suspend fun terminalizeStartFailure(start: AlarmStart, failure: RuntimeException) {
        runtime.repository.failClaimedDelivery(
            reminderId = start.key.reminderId,
            scheduleRevision = start.scheduleRevision,
            deliveryToken = start.key.deliveryToken,
            code = ReminderFailureCode.SCHEDULER_FAILURE,
            detail = "Alarm playback validation failed with ${failure::class.java.simpleName}.",
        )
    }

    private fun stopIfIdleLocked() {
        if (!lifecycle.shouldStop()) return
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
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
