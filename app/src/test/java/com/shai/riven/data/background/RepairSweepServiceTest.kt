package com.shai.riven.data.background

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepairSweepServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var scheduler: RecordingScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = RecordingScheduler()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun supportedPendingJobSchedulesTargetedRepairWork() {
        insertJob("supported", RepairJobType.REASSESS_PROVENANCE, createdAt = 1)
        val service = serviceWith(RepairJobType.REASSESS_PROVENANCE)

        val result = service.runSweep() as RepairSweepResult.Completed

        assertEquals(listOf("supported"), result.scheduledRepairJobIds)
        assertEquals(listOf("supported"), scheduler.repairJobIds)
        assertEquals(0, database.maintenanceDao().repairJob("supported")?.attemptCount)
        assertEquals(RepairJobState.PENDING, database.maintenanceDao().repairJob("supported")?.state)
    }

    @Test
    fun unsupportedPendingJobRemainsUntouchedAndIsNotScheduled() {
        insertJob("unsupported", RepairJobType.PROPAGATE_DELETION, createdAt = 1)
        val service = serviceWith(RepairJobType.REASSESS_PROVENANCE)

        val result = service.runSweep() as RepairSweepResult.Completed

        assertTrue(result.scheduledRepairJobIds.isEmpty())
        assertTrue(scheduler.repairJobIds.isEmpty())
        val stored = database.maintenanceDao().repairJob("unsupported")
        assertEquals(RepairJobState.PENDING, stored?.state)
        assertEquals(0, stored?.attemptCount)
        assertEquals(null, stored?.lastErrorCode)
    }

    @Test
    fun supportedJobsAreScheduledInDeterministicBoundedOrder() {
        insertJob("job-b", RepairJobType.REASSESS_PROVENANCE, createdAt = 1)
        insertJob("job-a", RepairJobType.REASSESS_PROVENANCE, createdAt = 1)
        insertJob("job-c", RepairJobType.REASSESS_PROVENANCE, createdAt = 2)
        val service = serviceWith(RepairJobType.REASSESS_PROVENANCE, itemLimit = 2)

        val result = service.runSweep() as RepairSweepResult.Completed

        assertEquals(listOf("job-a", "job-b"), result.scheduledRepairJobIds)
        assertEquals(listOf("job-a", "job-b"), scheduler.repairJobIds)
        assertTrue(result.moreWorkRemaining)
        assertEquals(0, database.maintenanceDao().repairJob("job-c")?.attemptCount)
    }

    @Test
    fun duplicateHandlerRegistrationIsRejected() {
        val first = NoOpHandler(RepairJobType.REASSESS_PROVENANCE)
        val second = NoOpHandler(RepairJobType.REASSESS_PROVENANCE)

        assertThrows(IllegalArgumentException::class.java) {
            RepairJobHandlerRegistry(listOf(first, second))
        }
    }

    private fun serviceWith(
        supportedType: RepairJobType,
        itemLimit: Int = DEFAULT_REPAIR_SWEEP_LIMIT,
    ): RepairSweepService = RepairSweepService(
        maintenanceDao = database.maintenanceDao(),
        handlerRegistry = RepairJobHandlerRegistry(listOf(NoOpHandler(supportedType))),
        scheduler = scheduler,
        itemLimit = itemLimit,
    )

    private fun insertJob(id: String, type: RepairJobType, createdAt: Long) {
        database.maintenanceDao().insertRepairJob(
            RepairJobEntity(
                id = id,
                jobType = type,
                state = RepairJobState.PENDING,
                targetType = "MEMORY",
                targetId = "opaque-target-$id",
                attemptCount = 0,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
    }

    private class NoOpHandler(
        override val supportedType: RepairJobType,
    ) : RepairJobHandler {
        override suspend fun execute(targetType: String, targetId: String): RepairJobHandlerResult =
            RepairJobHandlerResult.Success
    }

    private class RecordingScheduler : RivenBackgroundWorkScheduler {
        val repairJobIds = mutableListOf<String>()

        override fun enqueueAttachmentCleanup(attachmentId: String) = enqueued("attachment")

        override fun enqueueAttachmentMaintenanceSweep() = enqueued("attachment-sweep")

        override fun enqueueRepairJob(repairJobId: String): RivenBackgroundScheduleResult {
            repairJobIds += repairJobId
            return enqueued("repair-$repairJobId")
        }

        override fun enqueueRepairSweep() = enqueued("repair-sweep")

        override fun ensurePeriodicMaintenance() = enqueued("periodic")

        private fun enqueued(name: String) = RivenBackgroundScheduleResult.Enqueued(listOf(name))
    }
}
