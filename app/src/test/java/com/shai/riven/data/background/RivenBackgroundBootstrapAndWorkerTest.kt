package com.shai.riven.data.background

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.shai.riven.RivenApplication
import com.shai.riven.data.persistence.RivenDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RivenBackgroundBootstrapAndWorkerTest {
    @Test
    fun applicationBootstrapSchedulesCatchUpAndPeriodicWorkWithoutCanonicalSideEffects() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val before = canonicalCounts(database)
            val scheduler = RecordingScheduler()

            RivenApplication().bootstrapBackgroundWork(scheduler)

            assertEquals(1, scheduler.attachmentSweepCalls)
            assertEquals(1, scheduler.repairSweepCalls)
            assertEquals(1, scheduler.periodicCalls)
            assertTrue(scheduler.targetedIds.isEmpty())
            assertEquals(before, canonicalCounts(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun workerMalformedOrUnknownInputReturnsFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missingKind = TestListenableWorkerBuilder<RivenBackgroundWorker>(context).build()
        val unknownKind = TestListenableWorkerBuilder<RivenBackgroundWorker>(context)
            .setInputData(workDataOf(RivenBackgroundWorkData.KIND to "UNKNOWN_KIND"))
            .build()

        assertEquals(ListenableWorker.Result.failure(), missingKind.doWork())
        assertEquals(ListenableWorker.Result.failure(), unknownKind.doWork())
    }

    @Test
    fun retryableDomainOutcomeMapsToWorkerRetry() {
        assertEquals(
            ListenableWorker.Result.retry(),
            RivenBackgroundWorker.mapOutcome(RivenBackgroundExecutionOutcome.Retryable),
        )
    }

    @Test
    fun completedDomainOutcomeMapsToWorkerSuccess() {
        assertEquals(
            ListenableWorker.Result.success(),
            RivenBackgroundWorker.mapOutcome(RivenBackgroundExecutionOutcome.Completed),
        )
    }

    private fun canonicalCounts(database: RivenDatabase): Map<String, Long> {
        val tables = listOf(
            "messages",
            "experiences",
            "candidate_memories",
            "memories",
            "open_loops",
            "shai_system_instructions",
            "derived_artifacts",
            "attachments",
            "provider_profiles",
        )
        return tables.associateWith { table ->
            database.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM $table")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
        }
    }

    private class RecordingScheduler : RivenBackgroundWorkScheduler {
        var attachmentSweepCalls = 0
        var repairSweepCalls = 0
        var periodicCalls = 0
        val targetedIds = mutableListOf<String>()

        override fun enqueueAttachmentCleanup(attachmentId: String): RivenBackgroundScheduleResult {
            targetedIds += attachmentId
            return enqueued("attachment")
        }

        override fun enqueueAttachmentMaintenanceSweep(): RivenBackgroundScheduleResult {
            attachmentSweepCalls += 1
            return enqueued("attachment-sweep")
        }

        override fun enqueueRepairJob(repairJobId: String): RivenBackgroundScheduleResult {
            targetedIds += repairJobId
            return enqueued("repair")
        }

        override fun enqueueRepairSweep(): RivenBackgroundScheduleResult {
            repairSweepCalls += 1
            return enqueued("repair-sweep")
        }

        override fun ensurePeriodicMaintenance(): RivenBackgroundScheduleResult {
            periodicCalls += 1
            return enqueued("periodic")
        }

        private fun enqueued(name: String) = RivenBackgroundScheduleResult.Enqueued(listOf(name))
    }
}
