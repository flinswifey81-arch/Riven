package com.shai.riven.data.background

data class RivenBackgroundBootstrapResult(
    val attachmentCatchUp: RivenBackgroundScheduleResult,
    val repairCatchUp: RivenBackgroundScheduleResult,
    val periodicMaintenance: RivenBackgroundScheduleResult,
)

object RivenBackgroundWorkBootstrap {
    fun schedule(scheduler: RivenBackgroundWorkScheduler): RivenBackgroundBootstrapResult =
        RivenBackgroundBootstrapResult(
            attachmentCatchUp = scheduler.enqueueAttachmentMaintenanceSweep(),
            repairCatchUp = scheduler.enqueueRepairSweep(),
            periodicMaintenance = scheduler.ensurePeriodicMaintenance(),
        )
}
