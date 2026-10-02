package com.shai.riven.data.reminder

import com.shai.riven.data.reminder.persistence.LocalReminderEntity
import com.shai.riven.data.reminder.persistence.ReminderDao
import com.shai.riven.data.reminder.persistence.ReminderEventEntity
import com.shai.riven.data.reminder.persistence.ReminderFeatureControlEntity
import com.shai.riven.data.reminder.persistence.ReminderQuietHoursEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

internal data class ReminderRepositoryHooks(
    val afterInitialMutationRead: suspend (ReminderSnapshot) -> Unit = {},
)

class ReminderRepository(
    private val dao: ReminderDao,
    private val scheduler: ReminderPlatformScheduler,
    private val deliveryEffects: ReminderDeliveryEffects = NoOpReminderDeliveryEffects,
    private val timePolicy: ReminderTimePolicy = ReminderTimePolicy(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ReminderController {
    internal var hooks: ReminderRepositoryHooks = ReminderRepositoryHooks()

    override fun observeReminders(): Flow<List<ReminderSnapshot>> =
        dao.observeReminders().map { reminders -> reminders.map(LocalReminderEntity::toSnapshot) }

    override fun observeSettings(): Flow<ReminderSettingsSnapshot> = combine(
        dao.observeQuietHours(),
        dao.observeFeatureControls(),
    ) { quiet, controls ->
        ReminderSettingsSnapshot(
            quietHours = quiet?.toDomain() ?: ReminderQuietHours(),
            featureControls = ReminderFeature.entries.associateWith { feature ->
                controls.firstOrNull { it.featureKey == feature.key }?.toDomain()
                    ?: defaultFeatureControl(feature)
            },
        )
    }

    override suspend fun create(draft: ReminderDraft): ReminderOperationResult {
        validate(draft)?.let { return it }
        val now = timePolicy.nowMillis()
        val requested = timePolicy.resolveRequestedInstant(
            draft.localDateTime,
            draft.zoneId,
            draft.timeZonePolicy,
        )
        val control = featureControl(draft.feature)
        val quiet = quietHours()
        val effective = effectiveInstant(requested, quiet, control, schedulingZone(draft))
        val entity = LocalReminderEntity(
            id = newId(),
            title = draft.title.trim(),
            note = draft.note?.trim()?.takeIf(String::isNotEmpty),
            featureKey = draft.feature.key,
            deliveryMode = draft.deliveryMode.name,
            soundKind = draft.soundKind.name,
            customSoundUri = draft.customSoundUri,
            requestedLocalDateTime = draft.localDateTime.toString(),
            timeZoneId = draft.zoneId.id,
            timeZonePolicy = draft.timeZonePolicy.name,
            requestedTriggerAt = requested.toEpochMilli(),
            scheduledTriggerAt = effective.toEpochMilli(),
            status = ReminderStatus.SCHEDULED.name,
            scheduleRevision = 1,
            deliveryToken = null,
            ringUntilAt = null,
            lastFailureCode = null,
            lastFailureDetail = null,
            createdAt = now,
            updatedAt = now,
            finishedAt = null,
        )
        dao.insertReminder(entity)
        recordEvent(entity, ReminderEventKind.CREATED, detail = null)
        return scheduleEntity(entity, control, ReminderStatus.SCHEDULED, ReminderEventKind.SCHEDULED)
    }

    override suspend fun edit(reminderId: String, draft: ReminderDraft): ReminderOperationResult {
        validate(draft)?.let { return it }
        val existing = dao.reminder(reminderId)
            ?: return ReminderOperationResult.Failure(
                ReminderFailureCode.INVALID_REQUEST,
                "Reminder no longer exists.",
            )
        hooks.afterInitialMutationRead(existing.toSnapshot())
        val now = timePolicy.nowMillis()
        val requested = timePolicy.resolveRequestedInstant(
            draft.localDateTime,
            draft.zoneId,
            draft.timeZonePolicy,
        )
        val control = featureControl(draft.feature)
        val effective = effectiveInstant(
            requested,
            quietHours(),
            control,
            schedulingZone(draft),
        )
        var expected = existing
        repeat(MUTATION_RETRY_LIMIT) {
            if (!expected.isMutable()) return alreadyFinishedFailure(expected)
            val revised = expected.copy(
                title = draft.title.trim(),
                note = draft.note?.trim()?.takeIf(String::isNotEmpty),
                featureKey = draft.feature.key,
                deliveryMode = draft.deliveryMode.name,
                soundKind = draft.soundKind.name,
                customSoundUri = draft.customSoundUri,
                requestedLocalDateTime = draft.localDateTime.toString(),
                timeZoneId = draft.zoneId.id,
                timeZonePolicy = draft.timeZonePolicy.name,
                requestedTriggerAt = requested.toEpochMilli(),
                scheduledTriggerAt = effective.toEpochMilli(),
                status = ReminderStatus.SCHEDULED.name,
                scheduleRevision = expected.scheduleRevision + 1,
                deliveryToken = null,
                ringUntilAt = null,
                lastFailureCode = null,
                lastFailureDetail = null,
                updatedAt = now,
                finishedAt = null,
            )
            val replaced = dao.replaceActiveIfCurrent(expected, revised)
            if (replaced != null) {
                scheduler.cancel(reminderId)
                deliveryEffects.cancelDelivery(reminderId, replaced.deliveryToken)
                recordEvent(revised, ReminderEventKind.EDITED, detail = null)
                return scheduleEntity(
                    revised,
                    control,
                    ReminderStatus.SCHEDULED,
                    ReminderEventKind.SCHEDULED,
                )
            }
            expected = dao.reminder(reminderId) ?: return invalidReminderFailure()
        }
        return staleDeliveryFailure(expected)
    }

    override suspend fun complete(reminderId: String): ReminderOperationResult =
        finish(reminderId, ReminderStatus.COMPLETED, ReminderEventKind.COMPLETED)

    override suspend fun cancel(reminderId: String): ReminderOperationResult =
        finish(reminderId, ReminderStatus.CANCELLED, ReminderEventKind.CANCELLED)

    suspend fun dismissDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
    ): ReminderOperationResult = finishDelivery(
        reminderId,
        scheduleRevision,
        deliveryToken,
        ReminderStatus.DISMISSED,
        ReminderEventKind.DISMISSED,
    )

    suspend fun completeDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
    ): ReminderOperationResult = finishDelivery(
        reminderId,
        scheduleRevision,
        deliveryToken,
        ReminderStatus.COMPLETED,
        ReminderEventKind.COMPLETED,
    )

    suspend fun snoozeDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        duration: Duration,
    ): ReminderOperationResult {
        require(!duration.isNegative && !duration.isZero) { "Snooze duration must be positive." }
        val existing = dao.reminder(reminderId)
            ?: return invalidReminderFailure()
        val now = timePolicy.nowMillis()
        val requested = Instant.ofEpochMilli(now).plus(duration)
        val zone = if (existing.timeZonePolicy == ReminderTimeZonePolicy.FOLLOW_DEVICE.name) {
            timePolicy.currentDeviceZone()
        } else {
            ZoneId.of(existing.timeZoneId)
        }
        val local = requested.atZone(zone).toLocalDateTime()
        val changed = dao.snoozeDelivery(
            reminderId = reminderId,
            expectedRevision = scheduleRevision,
            deliveryToken = deliveryToken,
            requestedLocalDateTime = local.toString(),
            requestedTriggerAt = requested.toEpochMilli(),
            scheduledTriggerAt = requested.toEpochMilli(),
            now = now,
        )
        if (changed != 1) return staleDeliveryFailure(existing)
        scheduler.cancel(reminderId)
        deliveryEffects.cancelDelivery(reminderId, deliveryToken)
        val snoozed = checkNotNull(dao.reminder(reminderId))
        recordEvent(snoozed, ReminderEventKind.SNOOZED, detail = duration.toString())
        return scheduleEntity(
            snoozed,
            featureControl(ReminderFeature.valueOf(snoozed.featureKey)),
            ReminderStatus.SNOOZED,
            ReminderEventKind.SCHEDULED,
        )
    }

    suspend fun claimDelivery(
        reminderId: String,
        scheduleRevision: Long,
    ): ReminderDeliveryClaim {
        val existing = dao.reminder(reminderId) ?: return ReminderDeliveryClaim.IgnoredDuplicateOrStale
        val deliveryToken = newId()
        val now = timePolicy.nowMillis()
        val audible = existing.deliveryMode == ReminderDeliveryMode.AUDIBLE_ALARM.name
        val deliveryStatus = if (audible) {
            ReminderStatus.RINGING
        } else {
            ReminderStatus.DELIVERING
        }
        val ringUntilAt = if (audible) now + AUDIBLE_ALARM_MAX_RING_MILLIS else null
        val claimed = dao.claimDelivery(
            reminderId = reminderId,
            scheduleRevision = scheduleRevision,
            deliveryToken = deliveryToken,
            ringUntilAt = ringUntilAt,
            deliveryStatus = deliveryStatus.name,
            now = now,
            event = event(
                reminder = existing,
                kind = ReminderEventKind.DELIVERY_CLAIMED,
                detail = null,
                deliveryToken = deliveryToken,
                at = now,
            ),
        )
        if (!claimed) return ReminderDeliveryClaim.IgnoredDuplicateOrStale

        val delivered = checkNotNull(dao.reminder(reminderId))
        val feature = ReminderFeature.valueOf(delivered.featureKey)
        val control = featureControl(feature)
        if (!control.enabled) {
            finishDelivery(
                reminderId,
                delivered.scheduleRevision,
                deliveryToken,
                ReminderStatus.DISMISSED,
                ReminderEventKind.DISMISSED,
                "Feature disabled before delivery",
            )
            return ReminderDeliveryClaim.Suppressed(checkNotNull(dao.reminder(reminderId)).toSnapshot())
        }

        val zone = if (delivered.timeZonePolicy == ReminderTimeZonePolicy.FOLLOW_DEVICE.name) {
            timePolicy.currentDeviceZone()
        } else {
            ZoneId.of(delivered.timeZoneId)
        }
        val deferred = timePolicy.nextAllowedInstant(
            requested = Instant.ofEpochMilli(now),
            quietHours = quietHours(),
            featureControl = control,
            zoneId = zone,
        )
        if (deferred.toEpochMilli() > now + QUIET_HOURS_DEFER_THRESHOLD_MILLIS) {
            val result = deferDeliveryTo(reminderId, deliveryToken, delivered, deferred, zone)
            return ReminderDeliveryClaim.Deferred(
                (result as? ReminderOperationResult.Success)?.reminder
                    ?: checkNotNull(dao.reminder(reminderId)).toSnapshot(),
            )
        }

        return ReminderDeliveryClaim.Claimed(delivered.toSnapshot(), deliveryToken)
    }

    suspend fun isCurrentDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
    ): ReminderSnapshot? = dao.reminder(reminderId)
        ?.takeIf {
            it.scheduleRevision == scheduleRevision &&
                it.deliveryToken == deliveryToken &&
                it.status in setOf(
                    ReminderStatus.DELIVERING.name,
                    ReminderStatus.RINGING.name,
                    ReminderStatus.DELIVERED.name,
                )
        }
        ?.toSnapshot()

    suspend fun pendingNotificationDeliveries(): List<ReminderSnapshot> =
        dao.pendingNotificationDeliveries().map(LocalReminderEntity::toSnapshot)

    suspend fun acknowledgeNotificationDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
    ): ReminderSnapshot? {
        val current = dao.reminder(reminderId)
            ?.takeIf {
                it.scheduleRevision == scheduleRevision &&
                    it.deliveryToken == deliveryToken &&
                    it.status == ReminderStatus.DELIVERING.name
            }
            ?: return null
        val now = timePolicy.nowMillis()
        val acknowledged = dao.acknowledgeNotificationDelivery(
            reminderId = reminderId,
            scheduleRevision = scheduleRevision,
            deliveryToken = deliveryToken,
            now = now,
            event = event(
                reminder = current,
                kind = ReminderEventKind.DELIVERED,
                detail = null,
                deliveryToken = deliveryToken,
                at = now,
            ),
        )
        return if (acknowledged) dao.reminder(reminderId)?.toSnapshot() else null
    }

    suspend fun recordAudioWarning(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        code: ReminderFailureCode,
        detail: String,
    ) {
        val now = timePolicy.nowMillis()
        if (dao.recordDeliveryWarning(
                reminderId,
                scheduleRevision,
                deliveryToken,
                code.name,
                detail,
                now,
            ) == 1
        ) {
            dao.reminder(reminderId)?.let { reminder ->
                recordEvent(reminder, ReminderEventKind.AUDIO_FALLBACK, detail, deliveryToken)
            }
        }
    }

    suspend fun failClaimedDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        code: ReminderFailureCode,
        detail: String,
    ): ReminderOperationResult {
        val changed = dao.markDeliveryFailure(
            reminderId = reminderId,
            scheduleRevision = scheduleRevision,
            deliveryToken = deliveryToken,
            failureCode = code.name,
            failureDetail = detail,
            now = timePolicy.nowMillis(),
        )
        val reminder = dao.reminder(reminderId) ?: return invalidReminderFailure()
        if (changed == 1) {
            deliveryEffects.cancelDelivery(reminderId, deliveryToken)
            recordEvent(reminder, ReminderEventKind.FAILED, detail, deliveryToken)
        }
        return ReminderOperationResult.Failure(code, detail, reminder.toSnapshot())
    }

    suspend fun ringingDeliveries(): List<ReminderSnapshot> =
        dao.ringingReminders().map(LocalReminderEntity::toSnapshot)

    suspend fun prepareRingingTimeoutNotification(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
    ): ReminderSnapshot? {
        dao.markRingingReadyForNotification(
            reminderId = reminderId,
            scheduleRevision = scheduleRevision,
            deliveryToken = deliveryToken,
            now = timePolicy.nowMillis(),
        )
        return isCurrentDelivery(reminderId, scheduleRevision, deliveryToken)
    }

    override suspend fun updateQuietHours(settings: ReminderQuietHours) {
        dao.upsertQuietHours(
            ReminderQuietHoursEntity(
                enabled = settings.enabled,
                startMinuteOfDay = settings.startMinuteOfDay,
                endMinuteOfDay = settings.endMinuteOfDay,
                updatedAt = timePolicy.nowMillis(),
            ),
        )
        rescheduleAll("quiet_hours_changed")
    }

    override suspend fun updateFeatureControl(control: ReminderFeatureControl) {
        dao.upsertFeatureControl(
            ReminderFeatureControlEntity(
                featureKey = control.feature.key,
                enabled = control.enabled,
                allowDuringQuietHours = control.allowDuringQuietHours,
                updatedAt = timePolicy.nowMillis(),
            ),
        )
        rescheduleAll("feature_control_changed")
    }

    override suspend fun rescheduleAll(reason: String): ReminderRescheduleReport {
        var scheduled = 0
        var permissionBlocked = 0
        var failed = 0
        dao.activeReminders().forEach { existing ->
            val feature = ReminderFeature.valueOf(existing.featureKey)
            val control = featureControl(feature)
            val localDateTime = LocalDateTime.parse(existing.requestedLocalDateTime)
            val storedZone = ZoneId.of(existing.timeZoneId)
            val policy = ReminderTimeZonePolicy.valueOf(existing.timeZonePolicy)
            val isSnoozed = existing.status == ReminderStatus.SNOOZED.name
            val requested = if (isSnoozed) {
                // Snooze is a duration from an instant, not a wall-clock appointment.
                Instant.ofEpochMilli(existing.requestedTriggerAt)
            } else {
                timePolicy.resolveRequestedInstant(localDateTime, storedZone, policy)
            }
            val zone = if (policy == ReminderTimeZonePolicy.FOLLOW_DEVICE) {
                timePolicy.currentDeviceZone()
            } else {
                storedZone
            }
            val effective = if (isSnoozed) {
                timePolicy.coerceFuture(requested)
            } else {
                effectiveInstant(requested, quietHours(), control, zone)
            }
            val status = if (isSnoozed) {
                ReminderStatus.SNOOZED
            } else {
                ReminderStatus.SCHEDULED
            }
            val revised = existing.copy(
                requestedTriggerAt = requested.toEpochMilli(),
                scheduledTriggerAt = effective.toEpochMilli(),
                status = status.name,
                scheduleRevision = existing.scheduleRevision + 1,
                deliveryToken = null,
                ringUntilAt = null,
                lastFailureCode = null,
                lastFailureDetail = null,
                updatedAt = timePolicy.nowMillis(),
                finishedAt = null,
            )
            val replaced = dao.replaceActiveIfCurrent(existing, revised) ?: return@forEach
            scheduler.cancel(existing.id)
            deliveryEffects.cancelDelivery(existing.id, replaced.deliveryToken)
            when (scheduleEntity(revised, control, status, ReminderEventKind.RESCHEDULED, reason)) {
                is ReminderOperationResult.Success -> scheduled++
                is ReminderOperationResult.Failure -> {
                    val current = checkNotNull(dao.reminder(existing.id))
                    val code = current.lastFailureCode?.let(ReminderFailureCode::valueOf)
                    if (code == ReminderFailureCode.EXACT_ALARM_PERMISSION_REQUIRED ||
                        code == ReminderFailureCode.NOTIFICATION_PERMISSION_REQUIRED ||
                        code == ReminderFailureCode.NOTIFICATIONS_DISABLED
                    ) {
                        permissionBlocked++
                    } else {
                        failed++
                    }
                }
            }
        }
        return ReminderRescheduleReport(scheduled, permissionBlocked, failed)
    }

    private suspend fun scheduleEntity(
        entity: LocalReminderEntity,
        control: ReminderFeatureControl,
        scheduledStatus: ReminderStatus,
        eventKind: ReminderEventKind,
        detail: String? = null,
    ): ReminderOperationResult {
        if (!control.enabled) {
            return markScheduleFailure(
                entity,
                ReminderFailureCode.FEATURE_DISABLED,
                "${control.feature.name.lowercase().replaceFirstChar(Char::uppercase)} are disabled.",
            )
        }
        val triggerAt = checkNotNull(entity.scheduledTriggerAt)
        return when (
            val result = scheduler.schedule(
                ReminderScheduleRequest(
                    reminderId = entity.id,
                    scheduleRevision = entity.scheduleRevision,
                    triggerAtMillis = triggerAt,
                    deliveryMode = ReminderDeliveryMode.valueOf(entity.deliveryMode),
                ),
            )
        ) {
            ReminderScheduleResult.Scheduled -> {
                val scheduled = dao.reminder(entity.id)
                if (scheduled == null ||
                    scheduled.scheduleRevision != entity.scheduleRevision ||
                    scheduled.status != scheduledStatus.name ||
                    scheduled.deliveryToken != null
                ) {
                    scheduler.cancel(entity.id)
                    deliveryEffects.cancelDelivery(entity.id, entity.deliveryToken)
                    return if (scheduled == null) {
                        invalidReminderFailure()
                    } else {
                        staleDeliveryFailure(scheduled)
                    }
                }
                recordEvent(scheduled, eventKind, detail)
                ReminderOperationResult.Success(scheduled.toSnapshot())
            }
            is ReminderScheduleResult.PermissionRequired ->
                markScheduleFailure(entity, result.code, result.detail)
            is ReminderScheduleResult.Failure ->
                markScheduleFailure(entity, ReminderFailureCode.SCHEDULER_FAILURE, result.detail)
        }
    }

    private suspend fun markScheduleFailure(
        entity: LocalReminderEntity,
        code: ReminderFailureCode,
        detail: String,
    ): ReminderOperationResult {
        val changed = dao.markScheduleFailure(
            reminderId = entity.id,
            scheduleRevision = entity.scheduleRevision,
            failureCode = code.name,
            failureDetail = detail,
            now = timePolicy.nowMillis(),
        )
        val failed = checkNotNull(dao.reminder(entity.id))
        if (changed != 1) return staleDeliveryFailure(failed)
        recordEvent(failed, ReminderEventKind.FAILED, detail)
        return ReminderOperationResult.Failure(code, detail, failed.toSnapshot())
    }

    private suspend fun finish(
        reminderId: String,
        status: ReminderStatus,
        kind: ReminderEventKind,
    ): ReminderOperationResult {
        val existing = dao.reminder(reminderId) ?: return invalidReminderFailure()
        hooks.afterInitialMutationRead(existing.toSnapshot())
        var expected = existing
        repeat(MUTATION_RETRY_LIMIT) {
            if (!expected.isMutable()) return alreadyFinishedFailure(expected)
            val won = dao.finishActiveIfCurrent(
                expected = expected,
                newStatus = status.name,
                finishedAt = timePolicy.nowMillis(),
            )
            if (won != null) {
                scheduler.cancel(reminderId)
                deliveryEffects.cancelDelivery(reminderId, won.deliveryToken)
                val finished = checkNotNull(dao.reminder(reminderId))
                recordEvent(finished, kind, detail = null)
                return ReminderOperationResult.Success(finished.toSnapshot())
            }
            expected = dao.reminder(reminderId) ?: return invalidReminderFailure()
        }
        return staleDeliveryFailure(expected)
    }

    private suspend fun finishDelivery(
        reminderId: String,
        scheduleRevision: Long,
        deliveryToken: String,
        status: ReminderStatus,
        kind: ReminderEventKind,
        detail: String? = null,
    ): ReminderOperationResult {
        val existing = dao.reminder(reminderId) ?: return invalidReminderFailure()
        val now = timePolicy.nowMillis()
        if (dao.finishDelivery(reminderId, scheduleRevision, deliveryToken, status.name, now) != 1) {
            return staleDeliveryFailure(existing)
        }
        scheduler.cancel(reminderId)
        deliveryEffects.cancelDelivery(reminderId, deliveryToken)
        val finished = checkNotNull(dao.reminder(reminderId))
        recordEvent(finished, kind, detail, deliveryToken)
        return ReminderOperationResult.Success(finished.toSnapshot())
    }

    private suspend fun deferDeliveryTo(
        reminderId: String,
        deliveryToken: String,
        existing: LocalReminderEntity,
        deferred: Instant,
        zone: ZoneId,
    ): ReminderOperationResult {
        val changed = dao.snoozeDelivery(
            reminderId = reminderId,
            expectedRevision = existing.scheduleRevision,
            deliveryToken = deliveryToken,
            requestedLocalDateTime = deferred.atZone(zone).toLocalDateTime().toString(),
            requestedTriggerAt = deferred.toEpochMilli(),
            scheduledTriggerAt = deferred.toEpochMilli(),
            now = timePolicy.nowMillis(),
        )
        if (changed != 1) return staleDeliveryFailure(existing)
        scheduler.cancel(reminderId)
        deliveryEffects.cancelDelivery(reminderId, deliveryToken)
        val deferredEntity = checkNotNull(dao.reminder(reminderId))
        recordEvent(
            deferredEntity,
            ReminderEventKind.SNOOZED,
            "Deferred until quiet hours end",
            deliveryToken,
        )
        return scheduleEntity(
            deferredEntity,
            featureControl(ReminderFeature.valueOf(existing.featureKey)),
            ReminderStatus.SNOOZED,
            ReminderEventKind.RESCHEDULED,
            "quiet_hours_delivery_guard",
        )
    }

    private fun effectiveInstant(
        requested: Instant,
        quiet: ReminderQuietHours,
        control: ReminderFeatureControl,
        zone: ZoneId,
    ): Instant = timePolicy.coerceFuture(
        timePolicy.nextAllowedInstant(requested, quiet, control, zone),
    )

    private fun schedulingZone(draft: ReminderDraft): ZoneId =
        if (draft.timeZonePolicy == ReminderTimeZonePolicy.FOLLOW_DEVICE) {
            timePolicy.currentDeviceZone()
        } else {
            draft.zoneId
        }

    private suspend fun featureControl(feature: ReminderFeature): ReminderFeatureControl =
        dao.featureControl(feature.key)?.toDomain() ?: defaultFeatureControl(feature)

    private suspend fun quietHours(): ReminderQuietHours =
        dao.quietHours()?.toDomain() ?: ReminderQuietHours()

    private fun validate(draft: ReminderDraft): ReminderOperationResult.Failure? = when {
        draft.title.isBlank() -> ReminderOperationResult.Failure(
            ReminderFailureCode.INVALID_REQUEST,
            "A reminder title is required.",
        )
        draft.soundKind == ReminderSoundKind.CUSTOM_URI && draft.customSoundUri.isNullOrBlank() ->
            ReminderOperationResult.Failure(
                ReminderFailureCode.INVALID_REQUEST,
                "Choose a local audio file before selecting custom sound.",
            )
        else -> null
    }

    private suspend fun recordEvent(
        reminder: LocalReminderEntity,
        kind: ReminderEventKind,
        detail: String?,
        deliveryToken: String = newId(),
    ) {
        dao.insertEvent(event(reminder, kind, detail, deliveryToken, timePolicy.nowMillis()))
    }

    private fun event(
        reminder: LocalReminderEntity,
        kind: ReminderEventKind,
        detail: String?,
        deliveryToken: String,
        at: Long,
    ) = ReminderEventEntity(
        id = newId(),
        reminderId = reminder.id,
        eventKind = kind.name,
        scheduleRevision = reminder.scheduleRevision,
        deliveryToken = deliveryToken,
        detail = detail,
        createdAt = at,
    )

    private fun invalidReminderFailure() = ReminderOperationResult.Failure(
        ReminderFailureCode.INVALID_REQUEST,
        "Reminder no longer exists.",
    )

    private fun staleDeliveryFailure(existing: LocalReminderEntity) = ReminderOperationResult.Failure(
        ReminderFailureCode.INVALID_REQUEST,
        "That alarm action is stale or was already handled.",
        existing.toSnapshot(),
    )

    private fun alreadyFinishedFailure(existing: LocalReminderEntity) = ReminderOperationResult.Failure(
        ReminderFailureCode.INVALID_REQUEST,
        "Reminder is already ${existing.status.lowercase()}.",
        existing.toSnapshot(),
    )

    private fun LocalReminderEntity.isMutable(): Boolean = status in setOf(
        ReminderStatus.SCHEDULED.name,
        ReminderStatus.SNOOZED.name,
        ReminderStatus.DELIVERING.name,
        ReminderStatus.RINGING.name,
        ReminderStatus.DELIVERED.name,
        ReminderStatus.FAILED.name,
    )

    private companion object {
        const val QUIET_HOURS_DEFER_THRESHOLD_MILLIS = 1_000L
        const val MUTATION_RETRY_LIMIT = 8
    }
}

internal fun LocalReminderEntity.toSnapshot() = ReminderSnapshot(
    id = id,
    title = title,
    note = note,
    feature = ReminderFeature.valueOf(featureKey),
    localDateTime = LocalDateTime.parse(requestedLocalDateTime),
    zoneId = ZoneId.of(timeZoneId),
    timeZonePolicy = ReminderTimeZonePolicy.valueOf(timeZonePolicy),
    requestedTriggerAt = requestedTriggerAt,
    scheduledTriggerAt = scheduledTriggerAt,
    deliveryMode = ReminderDeliveryMode.valueOf(deliveryMode),
    soundKind = ReminderSoundKind.valueOf(soundKind),
    customSoundUri = customSoundUri,
    status = ReminderStatus.valueOf(status),
    scheduleRevision = scheduleRevision,
    deliveryToken = deliveryToken,
    ringUntilAt = ringUntilAt,
    lastFailureCode = lastFailureCode?.let(ReminderFailureCode::valueOf),
    lastFailureDetail = lastFailureDetail,
)

private fun ReminderFeatureControlEntity.toDomain() = ReminderFeatureControl(
    feature = ReminderFeature.valueOf(featureKey),
    enabled = enabled,
    allowDuringQuietHours = allowDuringQuietHours,
)

private fun ReminderQuietHoursEntity.toDomain() = ReminderQuietHours(
    enabled = enabled,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
)
