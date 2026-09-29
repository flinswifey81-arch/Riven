package com.shai.riven.data.reset

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.shai.riven.RivenApplication
import com.shai.riven.data.archive.RivenArchiveRestoreError
import com.shai.riven.data.archive.RivenRestoreBootstrapResult
import com.shai.riven.data.archive.RivenRestorePaths
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.background.RivenBackgroundWorker
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RivenResetStartupTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearResetTestState(context)
    }

    @After
    fun tearDown() {
        clearResetTestState(context)
    }

    @Test
    fun resetFailureDoesNotRunRestoreOrBackgroundBootstrap() {
        val events = mutableListOf<String>()
        val result = RivenApplication().settleStartupMutationsThenBootstrap(
            reset = {
                events += "reset"
                FactoryResetBootstrapResult.Failure(
                    RivenResetError.CredentialKeyDeleteFailure("ControlledFailure"),
                )
            },
            restore = {
                events += "restore"
                RivenRestoreBootstrapResult.NoPendingRestore
            },
            scheduler = RecordingScheduler(events),
        )

        assertEquals(listOf("reset"), events)
        assertNull(result.restore)
        assertNull(result.background)
    }

    @Test
    fun pendingResetGatesWorkerBeforeInputValidationOrRuntimeCreation() = runBlocking {
        assertEquals(
            StageFactoryResetResult.Staged(),
            RivenResetService(
                context,
                credentialStore = FakeResetCredentialStore(),
                keyResetter = FakeProviderCredentialKeyResetter(),
            ).stageFactoryReset(
                StageFactoryResetInput(FactoryResetConfirmation.ERASE_ALL_LOCAL_RIVEN_STATE),
            ),
        )
        val worker = TestListenableWorkerBuilder<RivenBackgroundWorker>(context).build()

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun pendingRestoreStillGatesWorkerBeforeInputValidationOrRuntimeCreation() = runBlocking {
        File(RivenRestorePaths(context).rollbackRoot, "sentinel").apply {
            parentFile?.mkdirs()
            writeText("pending")
        }
        val worker = TestListenableWorkerBuilder<RivenBackgroundWorker>(context).build()

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun applicationStartupOrdersResetThenRestoreThenBackgroundAndSkipsAfterRestoreFailure() {
        val events = mutableListOf<String>()
        val scheduler = RecordingScheduler(events)

        val successful = RivenApplication().settleStartupMutationsThenBootstrap(
            reset = {
                events += "reset"
                FactoryResetBootstrapResult.ResetApplied
            },
            restore = {
                events += "restore"
                RivenRestoreBootstrapResult.NoPendingRestore
            },
            scheduler = scheduler,
        )

        assertEquals(listOf("reset", "restore", "attachment", "repair", "periodic"), events)
        assertEquals(FactoryResetBootstrapResult.ResetApplied, successful.reset)
        assertTrue(successful.background != null)

        events.clear()
        val restoreFailed = RivenApplication().settleStartupMutationsThenBootstrap(
            reset = {
                events += "reset"
                FactoryResetBootstrapResult.NoPendingReset
            },
            restore = {
                events += "restore-failed"
                RivenRestoreBootstrapResult.Failure(
                    RivenArchiveRestoreError.RecoveryFailure("CONTROLLED"),
                )
            },
            scheduler = scheduler,
        )
        assertEquals(listOf("reset", "restore-failed"), events)
        assertNull(restoreFailed.background)
    }

    private class RecordingScheduler(
        private val events: MutableList<String>,
    ) : RivenBackgroundWorkScheduler {
        override fun enqueueAttachmentCleanup(attachmentId: String) = enqueued("target-attachment")
        override fun enqueueAttachmentMaintenanceSweep() = enqueued("attachment")
        override fun enqueueRepairJob(repairJobId: String) = enqueued("target-repair")
        override fun enqueueRepairSweep() = enqueued("repair")
        override fun ensurePeriodicMaintenance() = enqueued("periodic")

        private fun enqueued(event: String): RivenBackgroundScheduleResult {
            events += event
            return RivenBackgroundScheduleResult.Enqueued(listOf(event))
        }
    }
}
