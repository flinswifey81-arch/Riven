package com.shai.riven.data.reminder.platform

import android.content.Context
import androidx.core.content.ContextCompat
import com.shai.riven.data.reminder.ReminderDeliveryClaim
import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderFailureCode
import com.shai.riven.data.reminder.ReminderRepository
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderStatus

internal data class ReminderDeliveryDispatchHooks(
    val afterClaim: suspend (ReminderSnapshot) -> Unit = {},
    val afterNotificationPostedBeforeAck: suspend (ReminderSnapshot) -> Unit = {},
)

/** Durable dispatcher: a notification remains DELIVERING until notify() is acknowledged. */
internal class ReminderDeliveryDispatcher(
    private val repository: ReminderRepository,
    private val notifier: ReminderNotificationSink,
    private val permissions: ReminderPermissionSource,
    private val startAlarmService: (ReminderSnapshot, String) -> Unit,
    private val stopAlarmSession: (String, String) -> Unit = AlarmPlaybackControl::stop,
    internal var hooks: ReminderDeliveryDispatchHooks = ReminderDeliveryDispatchHooks(),
) {
    suspend fun dispatchScheduled(reminderId: String, scheduleRevision: Long) {
        when (val claim = repository.claimDelivery(reminderId, scheduleRevision)) {
            is ReminderDeliveryClaim.Claimed -> {
                hooks.afterClaim(claim.reminder)
                dispatchClaimed(claim.reminder, claim.deliveryToken)
            }
            is ReminderDeliveryClaim.Deferred -> notifier.cancel(claim.reminder.id)
            is ReminderDeliveryClaim.Suppressed -> notifier.cancel(claim.reminder.id)
            ReminderDeliveryClaim.IgnoredDuplicateOrStale -> Unit
        }
    }

    suspend fun recoverPending(reminder: ReminderSnapshot) {
        val token = reminder.deliveryToken ?: return
        dispatchClaimed(reminder, token)
    }

    private suspend fun dispatchClaimed(reminder: ReminderSnapshot, deliveryToken: String) {
        permissionFailure(reminder.deliveryMode, permissions.snapshot())?.let { (code, detail) ->
            repository.failClaimedDelivery(
                reminder.id,
                reminder.scheduleRevision,
                deliveryToken,
                code,
                detail,
            )
            return
        }
        val current = repository.isCurrentDelivery(
            reminder.id,
            reminder.scheduleRevision,
            deliveryToken,
        ) ?: return
        when (reminder.deliveryMode) {
            ReminderDeliveryMode.NOTIFICATION -> {
                if (current.status != ReminderStatus.DELIVERING) return
                if (!notifier.postReminder(current, deliveryToken)) {
                    repository.failClaimedDelivery(
                        current.id,
                        current.scheduleRevision,
                        deliveryToken,
                        ReminderFailureCode.NOTIFICATION_PERMISSION_REQUIRED,
                        "Android blocked the reminder notification at delivery time.",
                    )
                    return
                }
                hooks.afterNotificationPostedBeforeAck(current)
                val stillCurrent = repository.isCurrentDelivery(
                    current.id,
                    current.scheduleRevision,
                    deliveryToken,
                )
                if (stillCurrent?.status != ReminderStatus.DELIVERING) {
                    notifier.cancel(current.id)
                    return
                }
                if (repository.acknowledgeNotificationDelivery(
                        current.id,
                        current.scheduleRevision,
                        deliveryToken,
                    ) == null
                ) {
                    notifier.cancel(current.id)
                }
            }
            ReminderDeliveryMode.AUDIBLE_ALARM -> {
                if (current.status != ReminderStatus.RINGING) return
                if (!notifier.postAlarm(current, deliveryToken)) {
                    repository.failClaimedDelivery(
                        current.id,
                        current.scheduleRevision,
                        deliveryToken,
                        ReminderFailureCode.NOTIFICATION_PERMISSION_REQUIRED,
                        "Android blocked the alarm notification at delivery time.",
                    )
                    return
                }
                val beforeStart = repository.isCurrentDelivery(
                    current.id,
                    current.scheduleRevision,
                    deliveryToken,
                )
                if (beforeStart?.status != ReminderStatus.RINGING) {
                    notifier.cancel(current.id)
                    return
                }
                try {
                    startAlarmService(beforeStart, deliveryToken)
                } catch (failure: RuntimeException) {
                    notifier.cancel(current.id)
                    repository.failClaimedDelivery(
                        current.id,
                        current.scheduleRevision,
                        deliveryToken,
                        ReminderFailureCode.SCHEDULER_FAILURE,
                        "Android could not start alarm playback: ${failure::class.java.simpleName}.",
                    )
                    return
                }
                val afterStart = repository.isCurrentDelivery(
                    current.id,
                    current.scheduleRevision,
                    deliveryToken,
                )
                if (afterStart?.status != ReminderStatus.RINGING) {
                    notifier.cancel(current.id)
                    stopAlarmSession(current.id, deliveryToken)
                }
            }
        }
    }

    private fun permissionFailure(
        mode: ReminderDeliveryMode,
        snapshot: ReminderPermissionSnapshot,
    ): Pair<ReminderFailureCode, String>? = when {
        !snapshot.notificationPermissionGranted ->
            ReminderFailureCode.NOTIFICATION_PERMISSION_REQUIRED to
                "Notification permission was revoked before delivery."
        !snapshot.notificationsEnabled ->
            ReminderFailureCode.NOTIFICATIONS_DISABLED to
                "Notifications were disabled before delivery."
        !snapshot.channelEnabled(mode) ->
            ReminderFailureCode.NOTIFICATIONS_DISABLED to
                "The ${if (mode == ReminderDeliveryMode.AUDIBLE_ALARM) "alarms" else "reminders"} " +
                "notification channel was disabled before delivery."
        mode == ReminderDeliveryMode.AUDIBLE_ALARM && !snapshot.exactAlarmsAllowed ->
            ReminderFailureCode.EXACT_ALARM_PERMISSION_REQUIRED to
                "Exact-alarm access was revoked before delivery."
        else -> null
    }

    companion object {
        fun create(context: Context, runtime: ReminderRuntime): ReminderDeliveryDispatcher =
            ReminderDeliveryDispatcher(
                repository = runtime.repository,
                notifier = runtime.notifier,
                permissions = ReminderPermissionInspector(context),
                startAlarmService = { reminder, token ->
                    ContextCompat.startForegroundService(
                        context,
                        AlarmRingingService.intent(
                            context = context,
                            reminderId = reminder.id,
                            scheduleRevision = reminder.scheduleRevision,
                            deliveryToken = token,
                            ringUntilAt = checkNotNull(reminder.ringUntilAt),
                            title = reminder.title,
                            note = reminder.note,
                        ),
                    )
                },
            )
    }
}
