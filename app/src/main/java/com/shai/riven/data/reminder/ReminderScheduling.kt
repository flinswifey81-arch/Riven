package com.shai.riven.data.reminder

data class ReminderScheduleRequest(
    val reminderId: String,
    val scheduleRevision: Long,
    val triggerAtMillis: Long,
    val deliveryMode: ReminderDeliveryMode,
)

sealed interface ReminderScheduleResult {
    data object Scheduled : ReminderScheduleResult

    data class PermissionRequired(
        val code: ReminderFailureCode,
        val detail: String,
    ) : ReminderScheduleResult

    data class Failure(
        val detail: String,
    ) : ReminderScheduleResult
}

interface ReminderPlatformScheduler {
    fun schedule(request: ReminderScheduleRequest): ReminderScheduleResult

    fun cancel(reminderId: String)
}
