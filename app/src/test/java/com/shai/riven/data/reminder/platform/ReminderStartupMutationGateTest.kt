package com.shai.riven.data.reminder.platform

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.shai.riven.data.archive.RivenRestoreJournal
import com.shai.riven.data.archive.RivenRestoreJournalRecord
import com.shai.riven.data.archive.RivenRestoreJournalStage
import com.shai.riven.data.archive.RivenRestorePaths
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import com.shai.riven.data.reset.RivenResetJournal
import com.shai.riven.data.reset.RivenResetJournalRecord
import com.shai.riven.data.reset.RivenResetJournalStage
import com.shai.riven.data.reset.RivenResetPaths
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReminderStartupMutationGateTest {
    private lateinit var context: Context
    private lateinit var resetRoot: File
    private lateinit var restoreRoot: File
    private lateinit var reminderDatabase: File
    private lateinit var originalAudioFactory: (Context) -> AlarmAudioEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetRoot = File(context.noBackupFilesDir, RivenResetPaths.RESET_DIRECTORY)
        restoreRoot = File(context.noBackupFilesDir, RivenRestorePaths.RESTORE_DIRECTORY)
        reminderDatabase = context.getDatabasePath(ReminderDatabase.DATABASE_NAME)
        resetRoot.deleteRecursively()
        restoreRoot.deleteRecursively()
        context.deleteDatabase(ReminderDatabase.DATABASE_NAME)
        originalAudioFactory = AlarmAudioEngineProvider.factory
    }

    @After
    fun tearDown() {
        AlarmAudioEngineProvider.factory = originalAudioFactory
        resetRoot.deleteRecursively()
        restoreRoot.deleteRecursively()
        context.deleteDatabase(ReminderDatabase.DATABASE_NAME)
    }

    @Test
    fun pendingResetBlocksEveryProcessEntrypointUntilOneRecoveryPassAfterClear() = runBlocking {
        val paths = RivenResetPaths(context, resetRoot)
        RivenResetJournal(paths.journalFile).write(
            RivenResetJournalRecord(stage = RivenResetJournalStage.ERASING),
        )

        assertEntrypointsBlockedUntilCleared { resetRoot.deleteRecursively() }
    }

    @Test
    fun pendingRestoreBlocksEveryProcessEntrypointUntilOneRecoveryPassAfterClear() = runBlocking {
        val paths = RivenRestorePaths(context, restoreRoot)
        RivenRestoreJournal(paths.journalFile).write(
            RivenRestoreJournalRecord(
                stage = RivenRestoreJournalStage.ROLLING_BACK,
                hadDatabase = true,
                hadWal = false,
                hadShm = false,
                hadAttachments = false,
                hadCredentials = false,
                includesReminderDatabase = true,
                hadReminderDatabase = true,
            ),
        )

        assertEntrypointsBlockedUntilCleared { restoreRoot.deleteRecursively() }
    }

    private suspend fun assertEntrypointsBlockedUntilCleared(clearGate: () -> Unit) {
        var recoveryPasses = 0
        val blockedWorker = TestListenableWorkerBuilder<ReminderRecoveryWorker>(context).build()
        assertEquals(
            ListenableWorker.Result.retry(),
            blockedWorker.doWork(),
        )

        ReminderDeliveryReceiver().onReceive(context, deliveryIntent())
        ReminderActionReceiver().onReceive(context, actionIntent())
        // WorkManager is deliberately not initialized in this test. Reaching enqueue while the
        // gate is pending would throw instead of returning cleanly.
        ReminderRecoveryReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        var audioFactoryCalls = 0
        AlarmAudioEngineProvider.factory = {
            audioFactoryCalls++
            error("Audio must not be initialized while a startup mutation is pending.")
        }
        val service = Robolectric.buildService(AlarmRingingService::class.java).create()
        service.destroy()

        assertEquals(0, recoveryPasses)
        assertEquals(0, audioFactoryCalls)
        assertFalse(reminderDatabase.exists())
        assertTrue(
            shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty(),
        )
        assertTrue(
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .allNotifications
                .isEmpty(),
        )

        clearGate()
        val allowedWorker = TestListenableWorkerBuilder<ReminderRecoveryWorker>(context).build()
        assertEquals(
            ListenableWorker.Result.success(),
            allowedWorker.runRecovery { recoveryPasses++ },
        )
        assertEquals(1, recoveryPasses)
    }

    private fun deliveryIntent(): Intent = Intent(ReminderIntents.ACTION_DELIVER)
        .putExtra(ReminderIntents.EXTRA_REMINDER_ID, "blocked-delivery")
        .putExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, 1L)

    private fun actionIntent(): Intent = Intent(ReminderIntents.ACTION_DISMISS)
        .putExtra(ReminderIntents.EXTRA_REMINDER_ID, "blocked-action")
        .putExtra(ReminderIntents.EXTRA_SCHEDULE_REVISION, 1L)
        .putExtra(ReminderIntents.EXTRA_DELIVERY_TOKEN, "blocked-token")
}
