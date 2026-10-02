package com.shai.riven.data.reminder.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReminder(reminder: LocalReminderEntity)

    @Update
    suspend fun updateReminder(reminder: LocalReminderEntity): Int

    @Query("SELECT * FROM local_reminders WHERE reminder_id = :reminderId")
    suspend fun reminder(reminderId: String): LocalReminderEntity?

    @Query(
        """
        SELECT * FROM local_reminders
        ORDER BY
            CASE WHEN status IN ('SCHEDULED', 'SNOOZED', 'DELIVERING', 'RINGING', 'DELIVERED', 'FAILED') THEN 0 ELSE 1 END,
            COALESCE(scheduled_trigger_at, requested_trigger_at),
            created_at
        """,
    )
    fun observeReminders(): Flow<List<LocalReminderEntity>>

    @Query(
        """
        SELECT * FROM local_reminders
        WHERE status IN ('SCHEDULED', 'SNOOZED', 'FAILED')
        ORDER BY requested_trigger_at, reminder_id
        """,
    )
    suspend fun activeReminders(): List<LocalReminderEntity>

    @Query("SELECT * FROM local_reminders WHERE status = 'RINGING' ORDER BY updated_at")
    suspend fun ringingReminders(): List<LocalReminderEntity>

    @Query("SELECT * FROM local_reminders WHERE status = 'DELIVERING' ORDER BY updated_at")
    suspend fun pendingNotificationDeliveries(): List<LocalReminderEntity>

    @Query(
        """
        UPDATE local_reminders
        SET status = :deliveryStatus,
            delivery_token = :deliveryToken,
            ring_until_at = :ringUntilAt,
            last_failure_code = NULL,
            last_failure_detail = NULL,
            updated_at = :now
        WHERE reminder_id = :reminderId
          AND schedule_revision = :scheduleRevision
          AND status IN ('SCHEDULED', 'SNOOZED')
          AND delivery_token IS NULL
        """,
    )
    suspend fun claimDeliveryRow(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        ringUntilAt: Long?,
        deliveryStatus: String,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: ReminderEventEntity): Long

    @Transaction
    suspend fun claimDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        ringUntilAt: Long?,
        deliveryStatus: String,
        now: Long,
        event: ReminderEventEntity,
    ): Boolean {
        val claimed = claimDeliveryRow(
            reminderId = reminderId,
            scheduleRevision = scheduleRevision,
            deliveryToken = deliveryToken,
            ringUntilAt = ringUntilAt,
            deliveryStatus = deliveryStatus,
            now = now,
        ) == 1
        if (claimed) insertEvent(event)
        return claimed
    }

    @Query(
        """
        UPDATE local_reminders
        SET status = :newStatus,
            delivery_token = NULL,
            ring_until_at = NULL,
            schedule_revision = schedule_revision + 1,
            finished_at = :finishedAt,
            updated_at = :finishedAt
        WHERE reminder_id = :reminderId
          AND schedule_revision = :expectedRevision
          AND status IN ('SCHEDULED', 'SNOOZED', 'DELIVERING', 'RINGING', 'DELIVERED', 'FAILED')
        """,
    )
    suspend fun finishActive(
        reminderId: String,
        expectedRevision: Long,
        newStatus: String,
        finishedAt: Long,
    ): Int

    @Query(
        """
        UPDATE local_reminders
        SET status = :newStatus,
            delivery_token = NULL,
            ring_until_at = NULL,
            schedule_revision = schedule_revision + 1,
            finished_at = :finishedAt,
            updated_at = :finishedAt
        WHERE reminder_id = :reminderId
          AND schedule_revision = :expectedRevision
          AND delivery_token = :deliveryToken
          AND status IN ('DELIVERING', 'RINGING', 'DELIVERED')
        """,
    )
    suspend fun finishDelivery(
        reminderId: String,
        expectedRevision: Long,
        deliveryToken: String,
        newStatus: String,
        finishedAt: Long,
    ): Int

    @Query(
        """
        UPDATE local_reminders
        SET requested_local_date_time = :requestedLocalDateTime,
            requested_trigger_at = :requestedTriggerAt,
            scheduled_trigger_at = :scheduledTriggerAt,
            status = 'SNOOZED',
            schedule_revision = schedule_revision + 1,
            delivery_token = NULL,
            ring_until_at = NULL,
            last_failure_code = NULL,
            last_failure_detail = NULL,
            updated_at = :now,
            finished_at = NULL
        WHERE reminder_id = :reminderId
          AND schedule_revision = :expectedRevision
          AND delivery_token = :deliveryToken
          AND status IN ('DELIVERING', 'RINGING', 'DELIVERED')
        """,
    )
    suspend fun snoozeDelivery(
        reminderId: String,
        expectedRevision: Long,
        deliveryToken: String,
        requestedLocalDateTime: String,
        requestedTriggerAt: Long,
        scheduledTriggerAt: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE local_reminders
        SET status = 'FAILED',
            scheduled_trigger_at = NULL,
            delivery_token = NULL,
            ring_until_at = NULL,
            last_failure_code = :failureCode,
            last_failure_detail = :failureDetail,
            updated_at = :now
        WHERE reminder_id = :reminderId
          AND schedule_revision = :scheduleRevision
          AND status IN ('SCHEDULED', 'SNOOZED', 'FAILED')
        """,
    )
    suspend fun markScheduleFailure(
        reminderId: String,
        scheduleRevision: Long,
        failureCode: String,
        failureDetail: String?,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE local_reminders
        SET last_failure_code = :failureCode,
            last_failure_detail = :failureDetail,
            updated_at = :now
        WHERE reminder_id = :reminderId
          AND schedule_revision = :scheduleRevision
          AND delivery_token = :deliveryToken
        """,
    )
    suspend fun recordDeliveryWarning(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        failureCode: String,
        failureDetail: String?,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE local_reminders
        SET status = 'FAILED',
            scheduled_trigger_at = NULL,
            delivery_token = NULL,
            ring_until_at = NULL,
            last_failure_code = :failureCode,
            last_failure_detail = :failureDetail,
            updated_at = :now
        WHERE reminder_id = :reminderId
          AND schedule_revision = :scheduleRevision
          AND delivery_token = :deliveryToken
          AND status IN ('DELIVERING', 'RINGING', 'DELIVERED')
        """,
    )
    suspend fun markDeliveryFailure(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        failureCode: String,
        failureDetail: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE local_reminders
        SET status = 'DELIVERED',
            ring_until_at = NULL,
            updated_at = :now
        WHERE reminder_id = :reminderId
          AND schedule_revision = :scheduleRevision
          AND delivery_token = :deliveryToken
          AND status = 'RINGING'
        """,
    )
    suspend fun markRingingDelivered(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE local_reminders
        SET status = 'DELIVERED', updated_at = :now
        WHERE reminder_id = :reminderId
          AND schedule_revision = :scheduleRevision
          AND delivery_token = :deliveryToken
          AND status = 'DELIVERING'
        """,
    )
    suspend fun acknowledgeNotificationDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        now: Long,
    ): Int

    @Transaction
    suspend fun acknowledgeNotificationDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        now: Long,
        event: ReminderEventEntity,
    ): Boolean {
        val acknowledged = acknowledgeNotificationDelivery(
            reminderId = reminderId,
            scheduleRevision = scheduleRevision,
            deliveryToken = deliveryToken,
            now = now,
        ) == 1
        if (acknowledged) insertEvent(event)
        return acknowledged
    }

    @Transaction
    suspend fun replaceActiveIfCurrent(
        expectedRevision: Long,
        replacement: LocalReminderEntity,
    ): Boolean {
        val current = reminder(replacement.id) ?: return false
        if (current.scheduleRevision != expectedRevision ||
            current.status !in setOf(
                "SCHEDULED",
                "SNOOZED",
                "DELIVERING",
                "RINGING",
                "DELIVERED",
                "FAILED",
            ) ||
            replacement.scheduleRevision != expectedRevision + 1
        ) {
            return false
        }
        return updateReminder(replacement) == 1
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeatureControl(control: ReminderFeatureControlEntity)

    @Query("SELECT * FROM reminder_feature_controls WHERE feature_key = :featureKey")
    suspend fun featureControl(featureKey: String): ReminderFeatureControlEntity?

    @Query("SELECT * FROM reminder_feature_controls ORDER BY feature_key")
    fun observeFeatureControls(): Flow<List<ReminderFeatureControlEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuietHours(settings: ReminderQuietHoursEntity)

    @Query("SELECT * FROM reminder_quiet_hours WHERE settings_id = 1")
    suspend fun quietHours(): ReminderQuietHoursEntity?

    @Query("SELECT * FROM reminder_quiet_hours WHERE settings_id = 1")
    fun observeQuietHours(): Flow<ReminderQuietHoursEntity?>

    @Query(
        """
        SELECT COUNT(*) FROM reminder_events
        WHERE reminder_id = :reminderId AND event_kind = :eventKind
        """,
    )
    suspend fun eventCount(reminderId: String, eventKind: String): Int
}
