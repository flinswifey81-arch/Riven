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
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity
import com.shai.riven.data.persistence.entity.ProviderProfileEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
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
    private lateinit var sourceReminderDatabase: ReminderDatabase
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
        sourceReminderDatabase = ReminderDatabase.buildNamedForRestoreValidation(
            context,
            File(root, "source-reminders.db").absolutePath,
        )
        sourceReminderDatabase.openHelper.writableDatabase
        insertSourceAttachment("new-attachment", "restored attachment bytes".toByteArray())
    }

    @After
    fun tearDown() {
        sourceReminderDatabase.close()
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
    fun applyValidRestoreReplacesReminderDatabaseWithoutReplayingRingingAlarm() {
        prepareOldState()
        insertCanonicalReminder("old-reminder", "SCHEDULED", null)
        insertSourceReminder("new-scheduled", "SCHEDULED", null)
        insertSourceReminder("new-ringing", "RINGING", "delivery-token")
        stageValidArchive()

        val result = bootstrap().recoverAndApply()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, result)
        assertNull(canonicalReminderStatus("old-reminder"))
        assertEquals("SCHEDULED", canonicalReminderStatus("new-scheduled"))
        assertEquals("DISMISSED", canonicalReminderStatus("new-ringing"))
        assertFalse(paths.canonicalReminderWal.exists())
        assertFalse(paths.canonicalReminderShm.exists())
    }

    @Test
    fun legacyV1RestoreLeavesCurrentReminderDatabaseUntouched() {
        prepareOldState()
        insertCanonicalReminder("local-reminder", "SCHEDULED", null)
        stageValidArchive()
        val staged = checkNotNull(journal().read())
        // A v1 staged package has no reminder snapshot and its journal never opts into replacing it.
        assertTrue(paths.pendingReminderDatabase.delete())
        journal().write(
            staged.copy(
                includesReminderDatabase = false,
                hadReminderDatabase = false,
                hadReminderWal = false,
                hadReminderShm = false,
            ),
        )

        val result = bootstrap().recoverAndApply()

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, result)
        assertEquals("SCHEDULED", canonicalReminderStatus("local-reminder"))
    }

    @Test
    fun processDeathAfterMainDatabaseMoveRollsBackBothDatabases() {
        assertPerDatabaseMoveCrashRecovery(
            RivenRestoreBootstrapHooks(afterMainDatabaseMovedAside = { throw SimulatedDeath() }),
        )
    }

    @Test
    fun processDeathAfterReminderDatabaseMoveRollsBackBothDatabases() {
        assertPerDatabaseMoveCrashRecovery(
            RivenRestoreBootstrapHooks(afterReminderDatabaseMovedAside = { throw SimulatedDeath() }),
        )
    }

    @Test
    fun processDeathAfterMainDatabaseInstallRollsBackBothDatabases() {
        assertPerDatabaseMoveCrashRecovery(
            RivenRestoreBootstrapHooks(afterMainDatabaseInstalled = { throw SimulatedDeath() }),
        )
    }

    @Test
    fun processDeathAfterReminderDatabaseInstallRollsBackBothDatabases() {
        assertPerDatabaseMoveCrashRecovery(
            RivenRestoreBootstrapHooks(afterReminderDatabaseInstalled = { throw SimulatedDeath() }),
        )
    }

    @Test
    fun eightMiBLargeRestoreInterruptedAtNewInstalledResumesWithCanonicalCountsAndHashes() {
        seedLargeSourceState()
        prepareOldState(withAttachment = true, withCredential = true)
        stageValidArchive()
        simulateInstalledState(RivenRestoreJournalStage.NEW_INSTALLED)

        assertEquals(RivenRestoreBootstrapResult.RestoreApplied, bootstrap().recoverAndApply())

        val restored = RivenDatabase.build(context)
        try {
            val sqlite = restored.openHelper.writableDatabase
            assertEquals(251L, sqlite.rowCount("conversations"))
            assertEquals(5_000L, sqlite.rowCount("messages"))
            assertEquals(250L, sqlite.rowCount("conversation_timeline_heads"))
            assertEquals(4_750L, sqlite.rowCount("message_parent_edges"))
            assertEquals(2_000L, sqlite.rowCount("experiences"))
            assertEquals(2_000L, sqlite.rowCount("memories"))
            assertEquals(2_000L, sqlite.rowCount("memory_evidence"))
            assertEquals(128L, sqlite.rowCount("attachments"))
        } finally {
            restored.close()
        }
        assertArrayEquals(
            stressAttachmentBytes(127),
            File(paths.canonicalAttachments, "attachments/stress-attachment-127.blob").readBytes(),
        )
        assertFalse(paths.canonicalCredentials.exists())
        assertFalse(RivenRestoreGate.isPending(context, restoreRoot))
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
        insertCanonicalReminder("old-reminder", "SCHEDULED", null)
        insertSourceReminder("new-reminder", "SCHEDULED", null)
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
        assertEquals("SCHEDULED", canonicalReminderStatus("old-reminder"))
        assertNull(canonicalReminderStatus("new-reminder"))
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

    private fun seedLargeSourceState() {
        sourceDatabase.runInTransaction {
            val timelineDao = sourceDatabase.conversationTimelineDao()
            repeat(250) { conversationIndex ->
                val conversationId = "stress-conversation-${conversationIndex.toString().padStart(3, '0')}"
                timelineDao.insertConversation(
                    ConversationEntity(
                        id = conversationId,
                        createdAt = conversationIndex.toLong(),
                        updatedAt = conversationIndex.toLong(),
                        status = ConversationStatus.ACTIVE,
                        title = "Stress $conversationIndex",
                    ),
                )
                var parentId: String? = null
                repeat(20) { messageIndex ->
                    val messageId = "$conversationId-message-${messageIndex.toString().padStart(2, '0')}"
                    timelineDao.insertMessage(
                        MessageEntity(
                            id = messageId,
                            conversationId = conversationId,
                            sequenceNumber = (messageIndex + 1).toLong(),
                            role = if (messageIndex % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                            deliveryState = if (messageIndex % 2 == 0) {
                                MessageDeliveryState.PERSISTED
                            } else {
                                MessageDeliveryState.SUCCEEDED
                            },
                            content = "stress-content-$conversationIndex-$messageIndex",
                            createdAt = messageIndex.toLong(),
                            updatedAt = messageIndex.toLong(),
                        ),
                    )
                    parentId?.let { parent ->
                        timelineDao.insertParentEdge(MessageParentEdgeEntity(messageId, parent, messageIndex.toLong()))
                    }
                    parentId = messageId
                }
                timelineDao.insertTimelineHead(
                    ConversationTimelineHeadEntity(conversationId, parentId, timelineRevision = 20, updatedAt = 20),
                )
            }
            repeat(2_000) { index ->
                val experienceId = "stress-experience-${index.toString().padStart(4, '0')}"
                val memoryId = "stress-memory-${index.toString().padStart(4, '0')}"
                sourceDatabase.memoryDao().insertExperience(
                    ExperienceEntity(
                        id = experienceId,
                        eventOrder = 10_000L + index,
                        experienceType = ExperienceType.SHARED_EVENT,
                        actor = ExperienceActor.SHAI,
                        sourceContent = "stress evidence $index",
                        occurredAt = index.toLong(),
                        recordedAt = index.toLong(),
                        sensitivity = SensitivityLevel.STANDARD,
                        availability = ExperienceAvailability.AVAILABLE,
                    ),
                )
                sourceDatabase.memoryDao().insertMemory(
                    MemoryEntity(
                        id = memoryId,
                        kind = MemoryKind.SEMANTIC,
                        scope = MemoryScope.SHAI,
                        meaning = "stress memory $index",
                        epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                        certainty = MemoryCertainty.CERTAIN,
                        truthState = MemoryTruthState.SUPPORTED,
                        retentionState = MemoryRetentionState.ACTIVE,
                        lifecycleState = MemoryLifecycleState.VALIDATED,
                        temporalState = TemporalState.CURRENT,
                        learnedAt = index.toLong(),
                        sensitivity = SensitivityLevel.STANDARD,
                        createdAt = index.toLong(),
                        updatedAt = index.toLong(),
                    ),
                )
                sourceDatabase.memoryDao().insertMemoryEvidence(
                    MemoryEvidenceEntity(
                        memoryId = memoryId,
                        experienceId = experienceId,
                        role = EvidenceRole.SUPPORTS,
                        epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                        sourceCertainty = MemoryCertainty.CERTAIN,
                        lineageKey = "stress-lineage-$index",
                        createdAt = index.toLong(),
                    ),
                )
            }
        }
        repeat(127) { zeroBased ->
            val index = zeroBased + 1
            insertSourceAttachment("stress-attachment-$index", stressAttachmentBytes(index))
        }
    }

    private fun stressAttachmentBytes(index: Int): ByteArray =
        ByteArray(64 * 1_024) { offset -> ((index + offset) % 251).toByte() }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.rowCount(table: String): Long =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun stageValidArchive() {
        val output = ByteArrayOutputStream()
        val export = RivenArchiveExportService(
            context,
            sourceDatabase,
            sourceBlobStore,
            File(root, "export-staging"),
            sourceReminderDatabase,
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
        if (record.includesReminderDatabase) {
            moveReplacing(paths.pendingReminderDatabase, paths.canonicalReminderDatabase)
        }
        paths.canonicalAttachments.deleteRecursively()
        moveReplacing(paths.pendingAttachmentRoot, paths.canonicalAttachments)
        paths.canonicalCredentials.deleteRecursively()
        journal().write(record.copy(stage = stage))
    }

    private fun moveOldStateToRollback() {
        moveIfExists(paths.canonicalDatabase, paths.rollbackDatabase)
        moveIfExists(paths.canonicalWal, paths.rollbackWal)
        moveIfExists(paths.canonicalShm, paths.rollbackShm)
        if (journal().read()?.includesReminderDatabase == true) {
            moveIfExists(paths.canonicalReminderDatabase, paths.rollbackReminderDatabase)
            moveIfExists(paths.canonicalReminderWal, paths.rollbackReminderWal)
            moveIfExists(paths.canonicalReminderShm, paths.rollbackReminderShm)
        }
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

    private fun assertPerDatabaseMoveCrashRecovery(hooks: RivenRestoreBootstrapHooks) {
        prepareOldState()
        insertCanonicalReminder("old-reminder", "SCHEDULED", null)
        insertSourceReminder("new-reminder", "SCHEDULED", null)
        stageValidArchive()
        val interrupted = bootstrap().apply { this.hooks = hooks }
        var died = false
        try {
            interrupted.recoverAndApply()
        } catch (_: SimulatedDeath) {
            died = true
        }
        assertTrue(died)

        val recovered = bootstrap().recoverAndApply()

        assertTrue(recovered is RivenRestoreBootstrapResult.PreviousStateRestored)
        assertTrue(canonicalConversationExists("old-conversation"))
        assertFalse(canonicalConversationExists("new-conversation"))
        assertEquals("SCHEDULED", canonicalReminderStatus("old-reminder"))
        assertNull(canonicalReminderStatus("new-reminder"))
        assertFalse(RivenRestoreGate.isPending(context, restoreRoot))
    }

    private class SimulatedDeath : CancellationException("simulated process death")

    private fun insertSourceReminder(id: String, status: String, deliveryToken: String?) {
        insertReminder(sourceReminderDatabase, id, status, deliveryToken)
    }

    private fun insertCanonicalReminder(id: String, status: String, deliveryToken: String?) {
        val current = ReminderDatabase.build(context)
        try {
            current.openHelper.writableDatabase
            insertReminder(current, id, status, deliveryToken)
        } finally {
            current.close()
        }
    }

    private fun insertReminder(
        target: ReminderDatabase,
        id: String,
        status: String,
        deliveryToken: String?,
    ) {
        target.openHelper.writableDatabase.execSQL(
            "INSERT INTO local_reminders (reminder_id, title, note, feature_key, " +
                "delivery_mode, sound_kind, custom_sound_uri, requested_local_date_time, " +
                "time_zone_id, time_zone_policy, requested_trigger_at, scheduled_trigger_at, " +
                "status, schedule_revision, delivery_token, last_failure_code, " +
                "last_failure_detail, created_at, updated_at, finished_at) " +
                "VALUES (?, ?, NULL, 'REMINDERS', 'AUDIBLE_ALARM', 'SYSTEM_DEFAULT', NULL, " +
                "'2026-10-04T09:00', 'UTC', 'FIXED_ZONE', 1000, 1000, ?, 1, ?, NULL, " +
                "NULL, 1, 1, NULL)",
            arrayOf(id, id, status, deliveryToken),
        )
    }

    private fun canonicalReminderStatus(id: String): String? {
        val current = ReminderDatabase.build(context)
        return try {
            current.openHelper.writableDatabase.query(
                "SELECT status FROM local_reminders WHERE reminder_id = ?",
                arrayOf(id),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } finally {
            current.close()
        }
    }

    private fun bootstrap(): RivenRestoreBootstrap = RivenRestoreBootstrap(context, restoreRoot)

    private fun journal(): RivenRestoreJournal = RivenRestoreJournal(paths.journalFile)

    private fun clearCanonicalState() {
        context.deleteDatabase(RivenDatabase.DATABASE_NAME)
        context.deleteDatabase(ReminderDatabase.DATABASE_NAME)
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
