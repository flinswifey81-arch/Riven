package com.shai.riven.data.reset

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.archive.RIVEN_ARCHIVE_STAGING_DIRECTORY_NAME
import com.shai.riven.data.archive.RivenRestorePaths
import com.shai.riven.data.attachment.AttachmentService
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.background.AttachmentCleanupNoOpReason
import com.shai.riven.data.background.AttachmentMaintenanceService
import com.shai.riven.data.background.RepairJobHandlerRegistry
import com.shai.riven.data.background.RepairJobNoOpReason
import com.shai.riven.data.background.RepairJobRunResult
import com.shai.riven.data.background.RepairJobRunner
import com.shai.riven.data.background.TargetedAttachmentCleanupResult
import com.shai.riven.data.credential.FileProviderCredentialStore
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RivenResetBootstrapTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var resetRoot: File
    private lateinit var keyResetter: FakeProviderCredentialKeyResetter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearResetTestState(context)
        root = temporaryFolder.newFolder("reset-bootstrap-${UUID.randomUUID()}")
        resetRoot = File(root, "reset")
        keyResetter = FakeProviderCredentialKeyResetter()
    }

    @After
    fun tearDown() {
        clearResetTestState(context)
        context.deleteDatabase("unrelated-reset-test.db")
    }

    @Test
    fun factoryResetClearsEveryRoomApplicationTable() {
        seedCanonicalState()
        val seeded = openCanonicalDatabase()
        try {
            assertEquals(1L, applicationTableCounts(seeded).getValue("suppression_source_coverages"))
        } finally {
            seeded.close()
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        val fresh = openCanonicalDatabase()
        try {
            val counts = applicationTableCounts(fresh)
            assertEquals(39, counts.size)
            assertTrue(counts.values.all { it == 0L })
        } finally {
            fresh.close()
        }
    }

    @Test
    fun factoryResetClearsConversationDraftAndDraftAttachmentTables() {
        seedCanonicalState()
        val seeded = openCanonicalDatabase()
        try {
            assertEquals(1L, applicationTableCounts(seeded).getValue("conversation_drafts"))
            assertEquals(1L, applicationTableCounts(seeded).getValue("draft_attachments"))
        } finally {
            seeded.close()
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        val fresh = openCanonicalDatabase()
        try {
            assertEquals(0L, applicationTableCounts(fresh).getValue("conversation_drafts"))
            assertEquals(0L, applicationTableCounts(fresh).getValue("draft_attachments"))
        } finally {
            fresh.close()
        }
    }

    @Test
    fun emptyDatabaseVerifierAcceptsFreshThirtyNineTableVersionTenDatabase() {
        val fresh = openCanonicalDatabase()
        fresh.openHelper.writableDatabase
        fresh.close()
        createFreshReminderDatabase()

        assertTrue(
            RivenEmptyDatabaseVerifier.verify(
                context,
                context.getDatabasePath(RivenDatabase.DATABASE_NAME),
            ),
        )
    }

    @Test
    fun factoryResetClearsAttentionAssessmentAndSignalTables() {
        seedCanonicalState()
        val seeded = openCanonicalDatabase()
        try {
            assertEquals(1L, applicationTableCounts(seeded).getValue("experience_attention_assessments"))
            assertEquals(1L, applicationTableCounts(seeded).getValue("experience_attention_signals"))
        } finally {
            seeded.close()
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())
        val fresh = openCanonicalDatabase()
        try {
            assertEquals(0L, applicationTableCounts(fresh).getValue("experience_attention_assessments"))
            assertEquals(0L, applicationTableCounts(fresh).getValue("experience_attention_signals"))
        } finally {
            fresh.close()
        }
    }

    @Test
    fun freshVersionTenResetDatabaseHasExactlyThirtyNineEmptyApplicationTables() {
        val fresh = openCanonicalDatabase()
        fresh.openHelper.writableDatabase
        val counts = applicationTableCounts(fresh)
        fresh.close()
        createFreshReminderDatabase()

        assertEquals(39, counts.size)
        assertTrue(counts.values.all { it == 0L })
        assertTrue(
            RivenEmptyDatabaseVerifier.verify(
                context,
                context.getDatabasePath(RivenDatabase.DATABASE_NAME),
            ),
        )
    }

    @Test
    fun factoryResetRemovesCompleteAttachmentRoot() {
        seedCanonicalState()
        File(context.filesDir, "riven_attachments/nested/blob.bin").apply {
            parentFile?.mkdirs()
            writeText("blob")
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertFalse(FileAttachmentBlobStore.rootForContext(context).exists())
    }

    @Test
    fun factoryResetRemovesCredentialDirectory() {
        seedCanonicalState()
        File(FileProviderCredentialStore.rootForContext(context), "slot.cred").apply {
            parentFile?.mkdirs()
            writeText("ciphertext")
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertFalse(FileProviderCredentialStore.rootForContext(context).exists())
    }

    @Test
    fun factoryResetInvokesProviderCredentialKeyDeletion() {
        seedCanonicalState()
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertEquals(1, keyResetter.calls)
    }

    @Test
    fun factoryResetRemovesInternalArchiveStaging() {
        seedCanonicalState()
        val staging = File(context.noBackupFilesDir, RIVEN_ARCHIVE_STAGING_DIRECTORY_NAME)
        File(staging, "abandoned/snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("temporary")
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertFalse(staging.exists())
    }

    @Test
    fun factoryResetRemovesPendingRestoreState() {
        seedCanonicalState()
        val restore = File(context.noBackupFilesDir, RivenRestorePaths.RESTORE_DIRECTORY)
        File(restore, "pending/package/database/riven.db").apply {
            parentFile?.mkdirs()
            writeText("pending restore")
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertFalse(restore.exists())
    }

    @Test
    fun factoryResetPreservesUnrelatedFilesDirChild() {
        seedCanonicalState()
        val unrelated = File(context.filesDir, "unrelated-reset-sentinel.txt").apply {
            writeText("preserve")
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertEquals("preserve", unrelated.readText())
        unrelated.delete()
    }

    @Test
    fun factoryResetPreservesUnrelatedNoBackupChild() {
        seedCanonicalState()
        val unrelated = File(context.noBackupFilesDir, "unrelated-reset-sentinel.txt").apply {
            writeText("preserve")
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertEquals("preserve", unrelated.readText())
        unrelated.delete()
    }

    @Test
    fun factoryResetPreservesUnrelatedDatabase() {
        seedCanonicalState()
        context.openOrCreateDatabase("unrelated-reset-test.db", 0, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE sentinel(value TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO sentinel VALUES ('preserve')")
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        context.openOrCreateDatabase("unrelated-reset-test.db", 0, null).use { sqlite ->
            val value = sqlite.rawQuery("SELECT value FROM sentinel", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("preserve", value)
        }
    }

    @Test
    fun factoryResetPreservesExternalPortableArchive() {
        seedCanonicalState()
        val externalArchive = File(root, "user-owned-riven.zip").apply { writeText("archive") }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertEquals("archive", externalArchive.readText())
    }

    @Test
    fun recoveryResumesAfterDatabaseDeletion() {
        assertRecoveryAfterInterruption(
            RivenResetBootstrapHooks(afterDatabaseDeleted = { error("death") }),
            RivenResetJournalStage.ERASING,
        )
    }

    @Test
    fun recoveryResumesAfterAttachmentDeletion() {
        assertRecoveryAfterInterruption(
            RivenResetBootstrapHooks(afterAttachmentsDeleted = { error("death") }),
            RivenResetJournalStage.ERASING,
        )
    }

    @Test
    fun recoveryResumesAfterCredentialFileDeletion() {
        assertRecoveryAfterInterruption(
            RivenResetBootstrapHooks(afterCredentialFilesDeleted = { error("death") }),
            RivenResetJournalStage.ERASING,
        )
    }

    @Test
    fun recoveryResumesAfterCredentialKeyDeletion() {
        assertRecoveryAfterInterruption(
            RivenResetBootstrapHooks(afterCredentialKeyDeleted = { error("death") }),
            RivenResetJournalStage.ERASING,
        )
        assertEquals(2, keyResetter.calls)
    }

    @Test
    fun recoveryResumesAfterFreshDatabaseCreation() {
        assertRecoveryAfterInterruption(
            RivenResetBootstrapHooks(afterFreshDatabaseCreated = { error("death") }),
            RivenResetJournalStage.FRESH_DATABASE_CREATED,
        )
    }

    @Test
    fun recoveryFinishesCleanupFromVerifiedJournal() {
        assertRecoveryAfterInterruption(
            RivenResetBootstrapHooks(afterVerifiedJournalWritten = { error("death") }),
            RivenResetJournalStage.VERIFIED,
        )
    }

    @Test
    fun seededFreshReminderDatabaseBlocksResetVerification() {
        seedCanonicalState()
        stageReset()
        val interrupted = bootstrap().apply {
            hooks = RivenResetBootstrapHooks(
                afterFreshDatabaseCreated = {
                    mutateReminderDatabase(
                        "INSERT INTO reminder_feature_controls VALUES ('REMINDERS', 1, 0, 1)",
                    )
                },
            )
        }

        val result = interrupted.recoverAndApply()

        assertEquals(
            FactoryResetBootstrapResult.Failure(
                RivenResetError.FreshDatabaseVerificationFailure("NOT_EMPTY_OR_INVALID"),
            ),
            result,
        )
        assertTrue(RivenResetGate.isPending(context, resetRoot))
    }

    @Test
    fun corruptFreshReminderSchemaBlocksResetVerification() {
        seedCanonicalState()
        stageReset()
        val interrupted = bootstrap().apply {
            hooks = RivenResetBootstrapHooks(
                afterFreshDatabaseCreated = {
                    mutateReminderDatabase("PRAGMA user_version = 999")
                },
            )
        }

        val result = interrupted.recoverAndApply()

        assertEquals(
            FactoryResetBootstrapResult.Failure(
                RivenResetError.FreshDatabaseVerificationFailure("NOT_EMPTY_OR_INVALID"),
            ),
            result,
        )
        assertTrue(RivenResetGate.isPending(context, resetRoot))
    }

    @Test
    fun verifiedJournalRecoveryReerasesReminderRowsBeforeSuccess() {
        seedCanonicalState()
        stageReset()
        val interrupted = bootstrap().apply {
            hooks = RivenResetBootstrapHooks(afterVerifiedJournalWritten = { error("death") })
        }
        assertTrue(interrupted.recoverAndApply() is FactoryResetBootstrapResult.Failure)
        mutateReminderDatabase(
            "INSERT INTO reminder_feature_controls VALUES ('REMINDERS', 1, 0, 1)",
        )

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertEquals(2, keyResetter.calls)
        assertTrue(RivenEmptyDatabaseVerifier.verify(context, context.getDatabasePath(RivenDatabase.DATABASE_NAME)))
    }

    @Test
    fun repeatedFactoryResetOperationsAreIdempotent() {
        seedCanonicalState()
        stageReset()
        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertTrue(RivenEmptyDatabaseVerifier.verify(context, context.getDatabasePath(RivenDatabase.DATABASE_NAME)))
        assertEquals(2, keyResetter.calls)
    }

    @Test
    fun resetFailureKeepsJournalAndUnifiedGate() {
        seedCanonicalState()
        stageReset()
        keyResetter.failuresRemaining = 1

        val result = bootstrap().recoverAndApply()

        assertTrue(result is FactoryResetBootstrapResult.Failure)
        assertTrue((result as FactoryResetBootstrapResult.Failure).error is RivenResetError.CredentialKeyDeleteFailure)
        assertTrue(RivenResetGate.isPending(context, resetRoot))
        assertTrue(
            RivenStartupMutationGate.isPending(
                context,
                resetRoot,
                File(root, "restore"),
            ),
        )
    }

    @Test
    fun factoryResetSupersedesStagedRestoreMaterial() {
        seedCanonicalState()
        val restore = File(context.noBackupFilesDir, RivenRestorePaths.RESTORE_DIRECTORY)
        File(restore, "journal/restore-journal.json").apply {
            parentFile?.mkdirs()
            writeText("staged restore sentinel")
        }
        stageReset()

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertFalse(restore.exists())
        assertTrue(RivenEmptyDatabaseVerifier.verify(context, context.getDatabasePath(RivenDatabase.DATABASE_NAME)))
    }

    @Test
    fun resetGateClearsAfterSuccessfulReset() {
        seedCanonicalState()
        stageReset()
        assertTrue(RivenResetGate.isPending(context, resetRoot))

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        assertFalse(RivenResetGate.isPending(context, resetRoot))
        assertFalse(
            RivenStartupMutationGate.isPending(
                context,
                resetRoot,
                File(root, "restore"),
            ),
        )
    }

    @Test
    fun oldTargetedAttachmentWorkIsSafeNoOpAfterFactoryReset() = runBlocking {
        seedCanonicalState()
        stageReset()
        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())
        val database = openCanonicalDatabase()
        try {
            val maintenance = AttachmentMaintenanceService(
                database.attachmentDao(),
                AttachmentService(database, FileAttachmentBlobStore.fromContext(context)),
            )

            assertEquals(
                TargetedAttachmentCleanupResult.NoOp(
                    "old-attachment-id",
                    AttachmentCleanupNoOpReason.MISSING,
                ),
                maintenance.cleanupTarget("old-attachment-id"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun oldTargetedRepairWorkIsSafeNoOpAfterFactoryReset() = runBlocking {
        seedCanonicalState()
        stageReset()
        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())
        val database = openCanonicalDatabase()
        try {
            assertEquals(
                RepairJobRunResult.NoOp("old-repair-id", RepairJobNoOpReason.MISSING),
                RepairJobRunner(database, RepairJobHandlerRegistry(emptyList())).run("old-repair-id"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun sixtyFourAttachmentAndRepairJobsAreClearedAndEveryStaleTargetIsSafeNoOp() = runBlocking {
        seedCanonicalState()
        val seeded = openCanonicalDatabase()
        try {
            seeded.runInTransaction {
                repeat(64) { index ->
                    seeded.attachmentDao().insertAttachment(
                        AttachmentEntity(
                            id = "stress-attachment-$index",
                            kind = AttachmentKind.IMAGE,
                            mimeType = "image/png",
                            state = AttachmentState.DELETE_PENDING,
                            storageKey = "attachments/stress-attachment-$index.blob",
                            byteSize = 4,
                            contentSha256 = "0".repeat(64),
                            source = AttachmentSource.SHAI_IMPORT,
                            createdAt = index.toLong(),
                            updatedAt = index.toLong(),
                        ),
                    )
                    seeded.maintenanceDao().insertRepairJob(
                        RepairJobEntity(
                            id = "stress-repair-$index",
                            jobType = RepairJobType.INVALIDATE_DERIVED,
                            state = RepairJobState.PENDING,
                            targetType = "MEMORY",
                            targetId = "stress-memory-$index",
                            attemptCount = 0,
                            createdAt = index.toLong(),
                            updatedAt = index.toLong(),
                        ),
                    )
                }
            }
        } finally {
            seeded.close()
        }
        repeat(64) { index ->
            File(context.filesDir, "riven_attachments/attachments/stress-attachment-$index.blob").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        }
        stageReset()
        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())

        val fresh = openCanonicalDatabase()
        try {
            val counts = applicationTableCounts(fresh)
            assertTrue(counts.values.all { it == 0L })
            val maintenance = AttachmentMaintenanceService(
                fresh.attachmentDao(),
                AttachmentService(fresh, FileAttachmentBlobStore.fromContext(context)),
            )
            val runner = RepairJobRunner(fresh, RepairJobHandlerRegistry(emptyList()))
            repeat(64) { index ->
                assertEquals(
                    TargetedAttachmentCleanupResult.NoOp(
                        "stress-attachment-$index",
                        AttachmentCleanupNoOpReason.MISSING,
                    ),
                    maintenance.cleanupTarget("stress-attachment-$index"),
                )
                assertEquals(
                    RepairJobRunResult.NoOp("stress-repair-$index", RepairJobNoOpReason.MISSING),
                    runner.run("stress-repair-$index"),
                )
            }
        } finally {
            fresh.close()
        }
        assertFalse(
            File(context.filesDir, "riven_attachments").walkTopDown().any { candidate -> candidate.isFile },
        )
    }

    private fun assertRecoveryAfterInterruption(
        hooks: RivenResetBootstrapHooks,
        expectedStage: RivenResetJournalStage,
    ) {
        seedCanonicalState()
        seedOwnedFiles()
        stageReset()
        val interrupted = bootstrap().apply { this.hooks = hooks }

        assertTrue(interrupted.recoverAndApply() is FactoryResetBootstrapResult.Failure)
        assertEquals(expectedStage, checkNotNull(journal().read()).stage)
        assertTrue(RivenResetGate.isPending(context, resetRoot))

        assertEquals(FactoryResetBootstrapResult.ResetApplied, bootstrap().recoverAndApply())
        assertTrue(RivenEmptyDatabaseVerifier.verify(context, context.getDatabasePath(RivenDatabase.DATABASE_NAME)))
        assertFalse(RivenResetGate.isPending(context, resetRoot))
    }

    private fun seedCanonicalState() {
        val database = openCanonicalDatabase()
        try {
            seedRepresentativeState(database)
        } finally {
            database.close()
        }
    }

    private fun seedOwnedFiles() {
        File(context.filesDir, "riven_attachments/nested/blob.bin").apply {
            parentFile?.mkdirs()
            writeText("blob")
        }
        File(context.noBackupFilesDir, "riven_provider_credentials/slot.cred").apply {
            parentFile?.mkdirs()
            writeText("ciphertext")
        }
    }

    private fun stageReset() {
        assertEquals(
            StageFactoryResetResult.Staged(),
            RivenResetService(
                context,
                resetRoot = resetRoot,
                restoreRoot = File(root, "restore-gate"),
                credentialStore = FakeResetCredentialStore(),
                keyResetter = keyResetter,
            ).stageFactoryReset(
                StageFactoryResetInput(FactoryResetConfirmation.ERASE_ALL_LOCAL_RIVEN_STATE),
            ),
        )
    }

    private fun bootstrap(): RivenResetBootstrap = RivenResetBootstrap(
        context,
        resetRoot = resetRoot,
        keyResetter = keyResetter,
    )

    private fun journal(): RivenResetJournal = RivenResetJournal(
        RivenResetPaths(context, resetRoot).journalFile,
    )

    private fun openCanonicalDatabase(): RivenDatabase =
        RivenDatabase.buildNamedForRestoreValidation(
            context,
            context.getDatabasePath(RivenDatabase.DATABASE_NAME).absolutePath,
        )

    private fun mutateReminderDatabase(sql: String) {
        val file = context.getDatabasePath(ReminderDatabase.DATABASE_NAME)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL(sql)
        }
    }

    private fun createFreshReminderDatabase() {
        val reminders = ReminderDatabase.build(context)
        try {
            reminders.openHelper.writableDatabase
        } finally {
            reminders.close()
        }
    }
}
