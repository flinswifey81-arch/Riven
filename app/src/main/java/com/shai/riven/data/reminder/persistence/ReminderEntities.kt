package com.shai.riven.data.reminder.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_reminders",
    indices = [
        Index(value = ["status", "scheduled_trigger_at"]),
        Index(value = ["feature_key"]),
    ],
)
data class LocalReminderEntity(
    @PrimaryKey
    @ColumnInfo(name = "reminder_id")
    val id: String,
    val title: String,
    val note: String?,
    @ColumnInfo(name = "feature_key")
    val featureKey: String,
    @ColumnInfo(name = "delivery_mode")
    val deliveryMode: String,
    @ColumnInfo(name = "sound_kind")
    val soundKind: String,
    @ColumnInfo(name = "custom_sound_uri")
    val customSoundUri: String?,
    @ColumnInfo(name = "requested_local_date_time")
    val requestedLocalDateTime: String,
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String,
    @ColumnInfo(name = "time_zone_policy")
    val timeZonePolicy: String,
    @ColumnInfo(name = "requested_trigger_at")
    val requestedTriggerAt: Long,
    @ColumnInfo(name = "scheduled_trigger_at")
    val scheduledTriggerAt: Long?,
    val status: String,
    @ColumnInfo(name = "schedule_revision")
    val scheduleRevision: Long,
    @ColumnInfo(name = "delivery_token")
    val deliveryToken: String?,
    @ColumnInfo(name = "ring_until_at")
    val ringUntilAt: Long?,
    @ColumnInfo(name = "last_failure_code")
    val lastFailureCode: String?,
    @ColumnInfo(name = "last_failure_detail")
    val lastFailureDetail: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long?,
)

@Entity(tableName = "reminder_feature_controls")
data class ReminderFeatureControlEntity(
    @PrimaryKey
    @ColumnInfo(name = "feature_key")
    val featureKey: String,
    val enabled: Boolean,
    @ColumnInfo(name = "allow_during_quiet_hours")
    val allowDuringQuietHours: Boolean,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(tableName = "reminder_quiet_hours")
data class ReminderQuietHoursEntity(
    @PrimaryKey
    @ColumnInfo(name = "settings_id")
    val settingsId: Int = SINGLETON_ID,
    val enabled: Boolean,
    @ColumnInfo(name = "start_minute_of_day")
    val startMinuteOfDay: Int,
    @ColumnInfo(name = "end_minute_of_day")
    val endMinuteOfDay: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "reminder_events",
    foreignKeys = [
        ForeignKey(
            entity = LocalReminderEntity::class,
            parentColumns = ["reminder_id"],
            childColumns = ["reminder_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["reminder_id", "created_at"]),
        Index(value = ["delivery_token", "event_kind"], unique = true),
    ],
)
data class ReminderEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val id: String,
    @ColumnInfo(name = "reminder_id")
    val reminderId: String,
    @ColumnInfo(name = "event_kind")
    val eventKind: String,
    @ColumnInfo(name = "schedule_revision")
    val scheduleRevision: Long,
    @ColumnInfo(name = "delivery_token")
    val deliveryToken: String,
    val detail: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
