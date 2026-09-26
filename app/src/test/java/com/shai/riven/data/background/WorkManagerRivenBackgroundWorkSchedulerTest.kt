package com.shai.riven.data.background

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WorkManagerRivenBackgroundWorkSchedulerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerRivenBackgroundWorkScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val synchronousExecutor = SynchronousExecutor()
        val configuration = Configuration.Builder()
            .setExecutor(synchronousExecutor)
            .setTaskExecutor(synchronousExecutor)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerRivenBackgroundWorkScheduler(workManager)
    }

    @Test
    fun targetedAttachmentCleanupUsesOneUniqueActiveWorkChain() {
        val first = scheduler.enqueueAttachmentCleanup("attachment-opaque-id")
        val second = scheduler.enqueueAttachmentCleanup("attachment-opaque-id")

        assertTrue(first is RivenBackgroundScheduleResult.Enqueued)
        assertTrue(second is RivenBackgroundScheduleResult.Enqueued)
        val name = scheduler.uniqueNameFor(
            RivenBackgroundWorkKind.ATTACHMENT_CLEANUP,
            "attachment-opaque-id",
        )
        val work = workManager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
        assertEquals(1, work.count { !it.state.isFinished })
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
    }

    @Test
    fun targetedRepairUsesOneUniqueActiveWorkChain() {
        scheduler.enqueueRepairJob("repair-job-opaque-id")
        scheduler.enqueueRepairJob("repair-job-opaque-id")

        val name = scheduler.uniqueNameFor(
            RivenBackgroundWorkKind.REPAIR_JOB,
            "repair-job-opaque-id",
        )
        val work = workManager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
        assertEquals(1, work.count { !it.state.isFinished })
    }

    @Test
    fun workInputContainsOnlyKindAndOpaqueTargetId() {
        val request = scheduler.createOneTimeRequest(
            RivenBackgroundWorkKind.ATTACHMENT_CLEANUP,
            "opaque-attachment-id",
        )

        assertEquals(
            setOf(RivenBackgroundWorkData.KIND, RivenBackgroundWorkData.TARGET_ID),
            request.workSpec.input.keyValueMap.keys,
        )
        assertEquals(
            RivenBackgroundWorkKind.ATTACHMENT_CLEANUP.name,
            request.workSpec.input.getString(RivenBackgroundWorkData.KIND),
        )
        assertEquals("opaque-attachment-id", request.workSpec.input.getString(RivenBackgroundWorkData.TARGET_ID))
        val encoded = request.workSpec.input.keyValueMap.values.joinToString()
        assertFalse(encoded.contains("api-key"))
        assertFalse(encoded.contains("message content"))
        assertFalse(encoded.contains("memory meaning"))
        assertEquals(
            setOf(RivenBackgroundWorkTags.BACKGROUND, RivenBackgroundWorkTags.ATTACHMENT),
            request.tags - RivenBackgroundWorker::class.java.name,
        )
    }

    @Test
    fun hashedWorkNamesAreStableAndDoNotExposeRawTargetIds() {
        val rawId = "attachment-private-opaque-id"
        val first = scheduler.uniqueNameFor(RivenBackgroundWorkKind.ATTACHMENT_CLEANUP, rawId)
        val second = scheduler.uniqueNameFor(RivenBackgroundWorkKind.ATTACHMENT_CLEANUP, rawId)
        val different = scheduler.uniqueNameFor(
            RivenBackgroundWorkKind.ATTACHMENT_CLEANUP,
            "attachment-different-id",
        )

        assertEquals(first, second)
        assertNotEquals(first, different)
        assertFalse(first.contains(rawId))
        assertTrue(first.endsWith(stableTargetHash(rawId)))
    }

    @Test
    fun periodicBootstrapIsIdempotent() {
        scheduler.ensurePeriodicMaintenance()
        scheduler.ensurePeriodicMaintenance()

        val attachment = workManager
            .getWorkInfosForUniqueWork(RivenBackgroundWorkNames.PERIODIC_ATTACHMENT_MAINTENANCE)
            .get(10, TimeUnit.SECONDS)
        val repair = workManager
            .getWorkInfosForUniqueWork(RivenBackgroundWorkNames.PERIODIC_REPAIR_SWEEP)
            .get(10, TimeUnit.SECONDS)
        assertEquals(1, attachment.count { !it.state.isFinished })
        assertEquals(1, repair.count { !it.state.isFinished })
    }

    @Test
    fun blankTargetReturnsTypedSchedulingFailure() {
        val result = scheduler.enqueueAttachmentCleanup("   ")

        assertEquals(
            RivenBackgroundScheduleResult.Failure(RivenBackgroundScheduleError.InvalidTargetId),
            result,
        )
    }
}
