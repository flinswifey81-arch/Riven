package com.shai.riven.data.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.shai.riven.RivenApplication
import com.shai.riven.data.attachment.AttachmentByteSource
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.background.RivenBackgroundWorker
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.ProviderProfileEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RivenRestoreBootstrapTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var restoreRoot: File
    private lateinit var sourceDatabase: RivenDatabase
    private lateinit var sourceBlobStore: FileAttachmentBlobStore
    private lateinit var paths: RivenRestorePaths

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearCanonicalState()
        root = temporaryFolder.newFolder("restore-${UUID.randomUUID()}")
        restoreRoot = File(root, "restore-root")
        paths = RivenRestorePaths(context, restoreRoot)
        sourceDatabase = RivenDatabase.buildNamedForRestoreValidation(
            context,
            File(root, "source.db").absolutePath,
        )
        sourceDatabase.openHelper.writableDatabase.execSQL(
            "INSERT INTO conversations VALUES ('new-conversation', 2, 2, 'ACTIVE', 'Restored')",
        )
        sourceBlobStore = FileAttachmentBlobStore(File(root, "source-attachments"))
        insertSourceAttachment("new-attachment", "restored attachment bytes".toByteArray())
    }

    @After
    fun tearDown() {
        sourceDatabase.close()
        clearCanonicalState()
    }

    @Test
    fun applyValidRestoreReplacesDatabaseAndAttachmentRoot() {
        prepareOldState()
        stageValidArchive()

        val result = bootstrap().recoverAndApply()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, result)
        assertTrue(canonicalConversationExists("new-conversation"))
        assertFalse(canonicalConversationExists("old-conversation"))
        assertArrayEquals(
            "restored attachment bytes".toByteArray(),
            File(paths.canonicalAttachments, "attachments/new-attachment.blob").readBytes(),
        )
    }

    @Test
    fun restoredProviderProfileSurvivesWithoutOldCredential() {
        sourceDatabase.providerProfileDao().insertProfile(
            ProviderProfileEntity(
                profileId = "restored-profile",
                displayName = "Restored Profile",
                adapterId = "adapter",
                endpointBaseUrl = "https://example.invalid",
                modelId = "model",
                credentialSlotId = "same-slot",
                isEnabled = true,
                revision = 1,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        prepareOldState(withCredential = true)
        stageValidArchive()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, bootstrap().recoverAndApply())
        val restored = RivenDatabase.buildNamedForRestoreValidation(
            context,
            context.getDatabasePath(RivenDatabase.DATABASE_NAME).absolutePath,
        )
        try {
            assertEquals("same-slot", restored.providerProfileDao().profile("restored-profile")?.credentialSlotId)
        } finally {
            restored.close()
        }
        assertFalse(paths.canonicalCredentials.exists())
    }

    @Test
    fun oldCredentialDirectoryIsRestoredWhenInstallRollsBack() {
        prepareOldState(withCredential = true)
        stageValidArchive()
        val bootstrap = bootstrap().apply {
            hooks = RivenRestoreBootstrapHooks(beforeDatabaseInstall = { error("controlled") })
        }

        val result = bootstrap.recoverAndApply()

        assertTrue(result is RivenRestoreBootstrapResult.PreviousStateRestored)
        assertEquals("old-credential", File(paths.canonicalCredentials, "old.cred").readText())
        assertTrue(canonicalConversationExists("old-conversation"))
    }

    @Test
    fun oldSqliteSidecarsCannotContaminateNewDatabase() {
        prepareOldState()
        paths.canonicalWal.writeText("old-wal-marker")
        paths.canonicalShm.writeText("old-shm-marker")
        stageValidArchive()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, bootstrap().recoverAndApply())

        assertFalse(paths.canonicalWal.exists())
        assertFalse(paths.canonicalShm.exists())
        assertTrue(canonicalConversationExists("new-conversation"))
    }

    @Test
    fun databaseInstallFailureRollsBackOldState() {
        prepareOldState()
        stageValidArchive()
        val bootstrap = bootstrap().apply {
            hooks = RivenRestoreBootstrapHooks(beforeDatabaseInstall = { error("controlled") })
        }

        val result = bootstrap.recoverAndApply()

        assertTrue(result is RivenRestoreBootstrapResult.PreviousStateRestored)
        assertTrue((result as RivenRestoreBootstrapResult.PreviousStateRestored).trigger is RivenArchiveRestoreError.RestoreInstallFailure)
        assertTrue(canonicalConversationExists("old-conversation"))
        assertFalse(RivenRestoreGate.isPending(context, restoreRoot))
    }

    @Test
    fun attachmentInstallFailureRollsBackOldDatabaseAndAttachments() {
        prepareOldState(withAttachment = true)
        stageValidArchive()
        val bootstrap = bootstrap().apply {
            hooks = RivenRestoreBootstrapHooks(beforeAttachmentInstall = { error("controlled") })
        }

        val result = bootstrap.recoverAndApply()

        assertTrue(result is RivenRestoreBootstrapResult.PreviousStateRestored)
        assertTrue(canonicalConversationExists("old-conversation"))
        assertEquals("old attachment", File(paths.canonicalAttachments, "old.txt").readText())
    }

    @Test
    fun postInstallVerificationFailureRollsBackOldState() {
        prepareOldState()
        stageValidArchive()
        val bootstrap = bootstrap().apply {
            hooks = RivenRestoreBootstrapHooks(beforePostInstallVerification = { error("controlled") })
        }

        val result = bootstrap.recoverAndApply()

        assertTrue(result is RivenRestoreBootstrapResult.PreviousStateRestored)
        assertTrue(
            (result as RivenRestoreBootstrapResult.PreviousStateRestored).trigger is
                RivenArchiveRestoreError.RestoreVerificationFailure,
        )
        assertTrue(canonicalConversationExists("old-conversation"))
    }

    @Test
    fun recoveryResumesRollbackAfterDatabaseAndAttachmentsWereAlreadyRestored() {
        prepareOldState(withAttachment = true, withCredential = true)
        stageValidArchive()
        val interrupted = bootstrap().apply {
            hooks = RivenRestoreBootstrapHooks(
                beforePostInstallVerification = { error("force rollback") },
                afterRollbackAttachmentsRestored = { error("simulate process death") },
            )
        }

        assertTrue(interrupted.recoverAndApply() is RivenRestoreBootstrapResult.Failure)
        assertEquals(RivenRestoreJournalStage.ROLLING_BACK, checkNotNull(journal().read()).stage)
        assertTrue(canonicalConversationExists("old-conversation"))
        assertFalse(canonicalConversationExists("new-conversation"))
        assertEquals("old attachment", File(paths.canonicalAttachments, "old.txt").readText())
        assertFalse(paths.canonicalCredentials.exists())
        assertTrue(paths.rollbackCredentials.exists())

        val resumed = bootstrap().recoverAndApply()

        assertEquals(
            RivenRestoreBootstrapResult.PreviousStateRestored(
                RivenArchiveRestoreError.RecoveryFailure("ROLLBACK_RESUMED"),
            ),
            resumed,
        )
        assertTrue(canonicalConversationExists("old-conversation"))
        assertFalse(canonicalConversationExists("new-conversation"))
        assertEquals("old attachment", File(paths.canonicalAttachments, "old.txt").readText())
        assertEquals("old-credential", File(paths.canonicalCredentials, "old.cred").readText())
        assertFalse(paths.rollbackRoot.exists())
        assertFalse(paths.pendingRoot.exists())
        assertFalse(paths.journalFile.exists())
        assertFalse(RivenRestoreGate.isPending(context, restoreRoot))
    }

    @Test
    fun recoveryFromStagedJournalAppliesRestore() {
        prepareOldState()
        stageValidArchive()
        assertEquals(RivenRestoreJournalStage.STAGED, checkNotNull(journal().read()).stage)

        val result = bootstrap().recoverAndApply()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, result)
        assertTrue(canonicalConversationExists("new-conversation"))
    }

    @Test
    fun recoveryAfterCurrentMovedAsideRestoresOldState() {
        prepareOldState(withAttachment = true, withCredential = true)
        stageValidArchive()
        val record = checkNotNull(journal().read())
        moveOldStateToRollback()
        journal().write(record.copy(stage = RivenRestoreJournalStage.CURRENT_MOVED_ASIDE))

        val result = bootstrap().recoverAndApply()

        assertTrue(result is RivenRestoreBootstrapResult.PreviousStateRestored)
        assertTrue(canonicalConversationExists("old-conversation"))
        assertEquals("old attachment", File(paths.canonicalAttachments, "old.txt").readText())
        assertEquals("old-credential", File(paths.canonicalCredentials, "old.cred").readText())
    }

    @Test
    fun recoveryAfterNewInstalledCompletesVerifiedRestore() {
        prepareOldState(withCredential = true)
        stageValidArchive()
        simulateInstalledState(RivenRestoreJournalStage.NEW_INSTALLED)

        val result = bootstrap().recoverAndApply()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, result)
        assertTrue(canonicalConversationExists("new-conversation"))
        assertFalse(paths.canonicalCredentials.exists())
        assertFalse(paths.rollbackRoot.exists())
    }

    @Test
    fun recoveryAfterVerifiedBeforeCleanupFinishesCleanup() {
        prepareOldState()
        stageValidArchive()
        simulateInstalledState(RivenRestoreJournalStage.VERIFIED)

        val result = bootstrap().recoverAndApply()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, result)
        assertFalse(paths.rollbackRoot.exists())
        assertFalse(paths.pendingRoot.exists())
        assertFalse(paths.journalFile.exists())
    }

    @Test
    fun freshInstallRecoveryAfterCurrentMovedAsideInstallsPendingState() {
        stageValidArchive()
        val record = checkNotNull(journal().read())
        journal().write(record.copy(stage = RivenRestoreJournalStage.CURRENT_MOVED_ASIDE))

        val result = bootstrap().recoverAndApply()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, result)
        assertTrue(canonicalConversationExists("new-conversation"))
        assertArrayEquals(
            "restored attachment bytes".toByteArray(),
            File(paths.canonicalAttachments, "attachments/new-attachment.blob").readBytes(),
        )
        assertFalse(paths.canonicalCredentials.exists())
        assertFalse(paths.journalFile.exists())
        assertFalse(RivenRestoreGate.isPending(context, restoreRoot))
    }

    @Test
    fun freshInstallRecoveryInstallsPendingAttachmentsAfterDatabaseWasInstalled() {
        stageValidArchive()
        val record = checkNotNull(journal().read())
        paths.canonicalDatabase.parentFile?.mkdirs()
        moveReplacing(paths.pendingDatabase, paths.canonicalDatabase)
        journal().write(record.copy(stage = RivenRestoreJournalStage.CURRENT_MOVED_ASIDE))

        val result = bootstrap().recoverAndApply()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, result)
        assertTrue(canonicalConversationExists("new-conversation"))
        assertArrayEquals(
            "restored attachment bytes".toByteArray(),
            File(paths.canonicalAttachments, "attachments/new-attachment.blob").readBytes(),
        )
        assertFalse(paths.journalFile.exists())
        assertFalse(RivenRestoreGate.isPending(context, restoreRoot))
    }

    @Test
    fun freshInstallRecoveryWithMissingDatabaseReturnsTypedFailureAndKeepsGate() {
        stageValidArchive()
        val record = checkNotNull(journal().read())
        paths.pendingDatabase.delete()
        journal().write(record.copy(stage = RivenRestoreJournalStage.CURRENT_MOVED_ASIDE))

        val result = bootstrap().recoverAndApply()

        assertEquals(
            RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RecoveryFailure("FRESH_INSTALL_DATABASE_MISSING"),
            ),
            result,
        )
        assertTrue(paths.journalFile.isFile)
        assertFalse(paths.canonicalDatabase.exists())
        assertTrue(RivenRestoreGate.isPending(context, restoreRoot))
    }

    @Test
    fun invalidNewInstalledFreshStateReturnsTypedFailureAndKeepsGate() {
        stageValidArchive()
        val record = checkNotNull(journal().read())
        paths.canonicalDatabase.parentFile?.mkdirs()
        moveReplacing(paths.pendingDatabase, paths.canonicalDatabase)
        moveReplacing(paths.pendingAttachmentRoot, paths.canonicalAttachments)
        journal().write(record.copy(stage = RivenRestoreJournalStage.NEW_INSTALLED))
        assertTrue(paths.canonicalDatabase.delete())

        val result = bootstrap().recoverAndApply()

        assertEquals(
            RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RecoveryFailure("FRESH_INSTALL_STATE_INVALID"),
            ),
            result,
        )
        assertTrue(paths.journalFile.isFile)
        assertFalse(paths.canonicalDatabase.exists())
        assertTrue(RivenRestoreGate.isPending(context, restoreRoot))
    }

    @Test
    fun pendingRestoreGatesWorkerBeforeInputValidationOrRuntimeCreation() = runBlocking {
        prepareOldState()
        stageValidArchive()
        val productionJournal = RivenRestoreJournal(RivenRestorePaths(context).journalFile)
        productionJournal.write(checkNotNull(journal().read()))
        val worker = TestListenableWorkerBuilder<RivenBackgroundWorker>(context).build()

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun restoreGateClearsAfterSuccessfulRestore() {
        prepareOldState()
        stageValidArchive()
        assertTrue(RivenRestoreGate.isPending(context, restoreRoot))

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, bootstrap().recoverAndApply())

        assertFalse(RivenRestoreGate.isPending(context, restoreRoot))
    }

    @Test
    fun restoreGateClearsAfterSuccessfulRollback() {
        prepareOldState()
        stageValidArchive()
        val bootstrap = bootstrap().apply {
            hooks = RivenRestoreBootstrapHooks(beforeDatabaseInstall = { error("controlled") })
        }

        assertTrue(bootstrap.recoverAndApply() is RivenRestoreBootstrapResult.PreviousStateRestored)

        assertFalse(RivenRestoreGate.isPending(context, restoreRoot))
    }

    @Test
    fun applicationStartupSettlesRestoreBeforeSchedulingBackgroundWork() {
        val events = mutableListOf<String>()
        val scheduler = EventRecordingScheduler(events)

        val result = RivenApplication().settleRestoreThenBootstrap(
            restore = {
                events += "restore"
                RivenRestoreBootstrapResult.NoPendingRestore
            },
            scheduler = scheduler,
        )

        assertEquals(listOf("restore", "attachment", "repair", "periodic"), events)
        assertEquals(RivenRestoreBootstrapResult.NoPendingRestore, result.restore)
        assertTrue(result.background != null)

        events.clear()
        val failed = RivenApplication().settleRestoreThenBootstrap(
            restore = {
                events += "restore-failed"
                RivenRestoreBootstrapResult.Failure(
                    RivenArchiveRestoreError.RecoveryFailure("CONTROLLED"),
                )
            },
            scheduler = scheduler,
        )
        assertEquals(listOf("restore-failed"), events)
        assertNull(failed.background)
    }

    private fun prepareOldState(
        withAttachment: Boolean = false,
        withCredential: Boolean = false,
    ) {
        val old = RivenDatabase.build(context)
        try {
            old.openHelper.writableDatabase.execSQL(
                "INSERT INTO conversations VALUES ('old-conversation', 1, 1, 'ACTIVE', 'Old')",
            )
        } finally {
            old.close()
        }
        if (withAttachment) {
            File(paths.canonicalAttachments, "old.txt").apply {
                parentFile?.mkdirs()
                writeText("old attachment")
            }
        }
        if (withCredential) {
            File(paths.canonicalCredentials, "old.cred").apply {
                parentFile?.mkdirs()
                writeText("old-credential")
            }
        }
    }

    private fun insertSourceAttachment(id: String, bytes: ByteArray) {
        val storageKey = "attachments/$id.blob"
        sourceBlobStore.write(storageKey, AttachmentByteSource.fromBytes(bytes))
        sourceDatabase.attachmentDao().insertAttachment(
            AttachmentEntity(
                id = id,
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                state = AttachmentState.AVAILABLE,
                storageKey = storageKey,
                byteSize = bytes.size.toLong(),
                contentSha256 = sha256Hex(bytes),
                source = AttachmentSource.SHAI_IMPORT,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun stageValidArchive() {
        val output = ByteArrayOutputStream()
        val export = RivenArchiveExportService(
            context,
            sourceDatabase,
            sourceBlobStore,
            File(root, "export-staging"),
        ).export(ExportRivenArchiveInput(output, 10))
        assertTrue(export is ExportRivenArchiveResult.Exported)
        val staged = RivenArchiveRestoreService(
            context,
            restoreRoot = restoreRoot,
        ).stageRestore(StageRivenRestoreInput(ByteArrayInputStream(output.toByteArray()), 20))
        assertTrue("Expected staged restore but got $staged", staged is StageRivenRestoreResult.RestoreStaged)
    }

    private fun simulateInstalledState(stage: RivenRestoreJournalStage) {
        val record = checkNotNull(journal().read())
        moveOldStateToRollback()
        moveReplacing(paths.pendingDatabase, paths.canonicalDatabase)
        paths.canonicalAttachments.deleteRecursively()
        moveReplacing(paths.pendingAttachmentRoot, paths.canonicalAttachments)
        paths.canonicalCredentials.deleteRecursively()
        journal().write(record.copy(stage = stage))
    }

    private fun moveOldStateToRollback() {
        moveIfExists(paths.canonicalDatabase, paths.rollbackDatabase)
        moveIfExists(paths.canonicalWal, paths.rollbackWal)
        moveIfExists(paths.canonicalShm, paths.rollbackShm)
        moveIfExists(paths.canonicalAttachments, paths.rollbackAttachments)
        moveIfExists(paths.canonicalCredentials, paths.rollbackCredentials)
    }

    private fun moveIfExists(source: File, target: File) {
        if (source.exists()) moveReplacing(source, target)
    }

    private fun canonicalConversationExists(id: String): Boolean {
        val current = RivenDatabase.build(context)
        return try {
            current.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM conversations WHERE conversation_id = ?",
                arrayOf(id),
            ).use {
                it.moveToFirst()
                it.getInt(0) == 1
            }
        } finally {
            current.close()
        }
    }

    private fun bootstrap(): RivenRestoreBootstrap = RivenRestoreBootstrap(context, restoreRoot)

    private fun journal(): RivenRestoreJournal = RivenRestoreJournal(paths.journalFile)

    private fun clearCanonicalState() {
        context.deleteDatabase(RivenDatabase.DATABASE_NAME)
        File(context.filesDir, "riven_attachments").deleteRecursively()
        File(context.noBackupFilesDir, "riven_provider_credentials").deleteRecursively()
        File(context.noBackupFilesDir, RivenRestorePaths.RESTORE_DIRECTORY).deleteRecursively()
    }

    private class EventRecordingScheduler(
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
