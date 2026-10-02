package com.shai.riven.data.reminder

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

enum class ReminderFeature(val key: String) {
    REMINDERS("REMINDERS"),
    MEDICATION("MEDICATION"),
    CALENDAR("CALENDAR"),
    TRACKERS("TRACKERS"),
}

enum class ReminderDeliveryMode {
    NOTIFICATION,
    AUDIBLE_ALARM,
}

enum class ReminderSoundKind {
    SYSTEM_DEFAULT,
    CUSTOM_URI,
}

enum class ReminderTimeZonePolicy {
    FIXED_ZONE,
    FOLLOW_DEVICE,
}

enum class ReminderStatus {
    SCHEDULED,
    SNOOZED,
    DELIVERING,
    RINGING,
    DELIVERED,
    COMPLETED,
    DISMISSED,
    CANCELLED,
    FAILED,
}

enum class ReminderFailureCode {
    NOTIFICATION_PERMISSION_REQUIRED,
    NOTIFICATIONS_DISABLED,
    EXACT_ALARM_PERMISSION_REQUIRED,
    FEATURE_DISABLED,
    INVALID_REQUEST,
    SCHEDULER_FAILURE,
    AUDIO_SOURCE_UNAVAILABLE,
    AUDIO_FOCUS_DENIED,
}

enum class ReminderEventKind {
    CREATED,
    EDITED,
    SCHEDULED,
    RESCHEDULED,
    DELIVERY_CLAIMED,
    DELIVERED,
    FOLLOWUP_NOTIFICATION_FAILED,
    SNOOZED,
    COMPLETED,
    DISMISSED,
    CANCELLED,
    FAILED,
    AUDIO_FALLBACK,
}

data class ReminderDraft(
    val title: String,
    val note: String? = null,
    val feature: ReminderFeature = ReminderFeature.REMINDERS,
    val localDateTime: LocalDateTime,
    val zoneId: ZoneId,
    val timeZonePolicy: ReminderTimeZonePolicy = ReminderTimeZonePolicy.FOLLOW_DEVICE,
    val deliveryMode: ReminderDeliveryMode = ReminderDeliveryMode.NOTIFICATION,
    val soundKind: ReminderSoundKind = ReminderSoundKind.SYSTEM_DEFAULT,
    val customSoundUri: String? = null,
)

data class ReminderSnapshot(
    val id: String,
    val title: String,
    val note: String?,
    val feature: ReminderFeature,
    val localDateTime: LocalDateTime,
    val zoneId: ZoneId,
    val timeZonePolicy: ReminderTimeZonePolicy,
    val requestedTriggerAt: Long,
    val scheduledTriggerAt: Long?,
    val deliveryMode: ReminderDeliveryMode,
    val soundKind: ReminderSoundKind,
    val customSoundUri: String?,
    val status: ReminderStatus,
    val scheduleRevision: Long,
    val deliveryToken: String?,
    val ringUntilAt: Long?,
    val lastFailureCode: ReminderFailureCode?,
    val lastFailureDetail: String?,
)

data class ReminderFeatureControl(
    val feature: ReminderFeature,
    val enabled: Boolean,
    val allowDuringQuietHours: Boolean,
)

data class ReminderQuietHours(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = 22 * 60,
    val endMinuteOfDay: Int = 7 * 60,
) {
    init {
        require(startMinuteOfDay in 0..1439)
        require(endMinuteOfDay in 0..1439)
    }
}

data class ReminderSettingsSnapshot(
    val quietHours: ReminderQuietHours,
    val featureControls: Map<ReminderFeature, ReminderFeatureControl>,
)

sealed interface ReminderOperationResult {
    data class Success(val reminder: ReminderSnapshot) : ReminderOperationResult

    data class Failure(
        val code: ReminderFailureCode,
        val message: String,
        val reminder: ReminderSnapshot? = null,
    ) : ReminderOperationResult
}

sealed interface ReminderDeliveryClaim {
    data class Claimed(val reminder: ReminderSnapshot, val deliveryToken: String) : ReminderDeliveryClaim
    data class Deferred(val reminder: ReminderSnapshot) : ReminderDeliveryClaim
    data class Suppressed(val reminder: ReminderSnapshot) : ReminderDeliveryClaim
    data object IgnoredDuplicateOrStale : ReminderDeliveryClaim
}

data class ReminderRescheduleReport(
    val scheduled: Int,
    val permissionBlocked: Int,
    val failed: Int,
)

interface ReminderController {
    fun observeReminders(): Flow<List<ReminderSnapshot>>

    fun observeSettings(): Flow<ReminderSettingsSnapshot>

    suspend fun create(draft: ReminderDraft): ReminderOperationResult

    suspend fun edit(reminderId: String, draft: ReminderDraft): ReminderOperationResult

    suspend fun complete(reminderId: String): ReminderOperationResult

    suspend fun cancel(reminderId: String): ReminderOperationResult

    suspend fun updateQuietHours(settings: ReminderQuietHours)

    suspend fun updateFeatureControl(control: ReminderFeatureControl)

    suspend fun rescheduleAll(reason: String): ReminderRescheduleReport
}

internal fun defaultFeatureControl(feature: ReminderFeature): ReminderFeatureControl =
    ReminderFeatureControl(
        feature = feature,
        enabled = true,
        allowDuringQuietHours = false,
    )

const val AUDIBLE_ALARM_MAX_RING_MILLIS = 10 * 60 * 1_000L
