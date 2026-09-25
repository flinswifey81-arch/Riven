package com.shai.riven.data.attachment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.CommitRegeneratedAssistantResponseInput
import com.shai.riven.data.conversation.ConversationTimelineError
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.RewindTimelineInput
import com.shai.riven.data.conversation.TimelineOperation
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.deletion.DeleteMemoryInput
import com.shai.riven.data.deletion.DeleteTimelineMessageInput
import com.shai.riven.data.deletion.MemoryDeleteResult
import com.shai.riven.data.deletion.SafeDeleteIdGenerator
import com.shai.riven.data.deletion.SafeDeleteOperation
import com.shai.riven.data.deletion.SafeDeleteService
import com.shai.riven.data.deletion.TimelineDeleteResult
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactAttachmentDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.GeneratedMediaKind
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
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
@Config(sdk = [35])
class AttachmentServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: RivenDatabase
    private lateinit var blobStore: FileAttachmentBlobStore
    private lateinit var attachmentService: AttachmentService
    private lateinit var timelineService: ConversationTimelineService
    private lateinit var safeDeleteService: SafeDeleteService
    private var generatedId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        blobStore = FileAttachmentBlobStore(temporaryFolder.newFolder("attachments"))
        attachmentService = newAttachmentService(blobStore)
        timelineService = ConversationTimelineService(database)
        safeDeleteService = newSafeDeleteService()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importedImageFinalizesWithComputedMetadataAndRoundTripsPrivateBytes() = runBlocking {
        val bytes = "known imported image bytes".toByteArray()

        val attachment = assertCreated(
            attachmentService.createImportedAttachment(
                ImportedAttachmentInput(
                    attachmentId = "imported-image",
                    kind = AttachmentKind.IMAGE,
                    mimeType = "image/png",
                    occurredAt = 10,
                    bytes = AttachmentByteSource.fromBytes(bytes),
                ),
            ),
        )

        assertEquals(AttachmentState.AVAILABLE, attachment.state)
        assertEquals(AttachmentSource.SHAI_IMPORT, attachment.source)
        assertEquals(bytes.size.toLong(), attachment.byteSize)
        assertEquals(sha256(bytes), attachment.contentSha256)
        assertFalse(attachment.storageKey.contains(":"))
        assertFalse(attachment.storageKey.contains("content://"))
        assertFalse(attachment.storageKey.startsWith("/"))
        assertTrue(blobStore.exists(attachment.storageKey))
        val read = attachmentService.readAvailableBlob(attachment.attachmentId)
        assertTrue(read is AttachmentBlobReadResult.Success)
        assertArrayEquals(bytes, (read as AttachmentBlobReadResult.Success).bytes)
    }

    @Test
    fun generatedSelfieStyleMediaStoresSyntheticProvenanceWithoutRawPromptSurface() = runBlocking {
        val bytes = "synthetic image bytes".toByteArray()
        val attachment = createSelfie("generated-selfie", bytes)

        assertEquals(AttachmentSource.RIVEN_GENERATED, attachment.source)
        assertEquals(sha256(bytes), attachment.contentSha256)
        val result = attachmentService.generatedProvenance(attachment.attachmentId)
        assertTrue(result is GeneratedMediaProvenanceResult.Success)
        val provenance = (result as GeneratedMediaProvenanceResult.Success).provenance
        assertNotNull(provenance)
        assertEquals(GeneratedMediaKind.RIVEN_SELFIE_STYLE, provenance?.generationKind)
        assertEquals(RIVEN_APPEARANCE_CANON_AUTHORITY, provenance?.appearanceAuthority)
        assertEquals("appearance-v1-fingerprint", provenance?.appearanceAuthorityFingerprint)

        val columns = database.openHelper.writableDatabase
            .query("PRAGMA table_info(`generated_media_provenance`)")
            .use { cursor ->
                buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        assertFalse(columns.any { it.contains("prompt", ignoreCase = true) })
    }

    @Test
    fun invalidSelfieAuthorityOrFingerprintFailsBeforeCanonicalCreation() = runBlocking {
        val invalid = listOf(
            GeneratedMediaProvenanceInput(
                generationKind = GeneratedMediaKind.RIVEN_SELFIE_STYLE,
                appearanceAuthority = "CAMERA",
                appearanceAuthorityFingerprint = "fingerprint",
            ),
            GeneratedMediaProvenanceInput(
                generationKind = GeneratedMediaKind.RIVEN_SELFIE_STYLE,
                appearanceAuthority = RIVEN_APPEARANCE_CANON_AUTHORITY,
                appearanceAuthorityFingerprint = " ",
            ),
        )

        invalid.forEachIndexed { index, provenance ->
            val result = attachmentService.createGeneratedAttachment(
                GeneratedAttachmentInput(
                    attachmentId = "invalid-$index",
                    kind = AttachmentKind.IMAGE,
                    mimeType = "image/png",
                    provenance = provenance,
                    occurredAt = 1,
                    bytes = AttachmentByteSource.fromBytes(byteArrayOf(1, 2, 3)),
                ),
            )
            assertTrue(result is AttachmentCreateResult.Failure)
            assertTrue((result as AttachmentCreateResult.Failure).error is AttachmentError.InvalidGeneratedMediaProvenance)
            assertNull(database.attachmentDao().attachment("invalid-$index"))
            assertNull(database.attachmentDao().generatedMediaProvenance("invalid-$index"))
        }
    }

    @Test
    fun blobWriteFailureCleansStagingMetadataAndPartialBytesWhenPossible() = runBlocking {
        val failingStore = TestBlobStore(failWrite = true)
        val service = newAttachmentService(failingStore)

        val result = service.createImportedAttachment(
            ImportedAttachmentInput(
                attachmentId = "write-failure",
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                occurredAt = 1,
                bytes = AttachmentByteSource.fromBytes("partial".toByteArray()),
            ),
        )

        assertTrue(result is AttachmentCreateResult.Failure)
        assertTrue((result as AttachmentCreateResult.Failure).error is AttachmentError.BlobWriteFailure)
        assertNull(database.attachmentDao().attachment("write-failure"))
        assertNull(database.attachmentDao().generatedMediaProvenance("write-failure"))
        assertTrue(failingStore.keys().isEmpty())
    }

    @Test
    fun finalizationFailureLeavesRecoverableStagingRowAndBlobForExplicitCleanup() = runBlocking {
        val failingService = newAttachmentService(blobStore) { error("controlled finalization failure") }

        val result = failingService.createImportedAttachment(
            ImportedAttachmentInput(
                attachmentId = "finalization-failure",
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                occurredAt = 1,
                bytes = AttachmentByteSource.fromBytes("complete blob".toByteArray()),
            ),
        )

        assertTrue(result is AttachmentCreateResult.Failure)
        assertTrue((result as AttachmentCreateResult.Failure).error is AttachmentError.StorageFailure)
        val staging = database.attachmentDao().attachment("finalization-failure")
        assertNotNull(staging)
        assertEquals(AttachmentState.STAGING, staging?.state)
        assertNull(staging?.byteSize)
        assertNull(staging?.contentSha256)
        assertTrue(blobStore.exists(checkNotNull(staging).storageKey))
        val read = attachmentService.readAvailableBlob("finalization-failure")
        assertTrue(read is AttachmentBlobReadResult.Failure)
        assertTrue((read as AttachmentBlobReadResult.Failure).error is AttachmentError.AttachmentUnavailable)

        val cleanup = attachmentService.cleanupStagingAttachment("finalization-failure")
        assertEquals(AttachmentCleanupResult.Removed("finalization-failure"), cleanup)
        assertNull(database.attachmentDao().attachment("finalization-failure"))
        assertFalse(blobStore.exists(staging.storageKey))
    }

    @Test
    fun timelineAppendPreservesAttachmentOrderAndRollsBackInvalidReferences() = runBlocking {
        val first = createImported("attachment-a", "a".toByteArray())
        val second = createImported("attachment-b", "b".toByteArray())
        createConversation("conversation")

        val appended = append(
            conversationId = "conversation",
            messageId = "message-valid",
            role = MessageRole.USER,
            expectedRevision = 0,
            occurredAt = 1,
            attachmentIds = listOf(first.attachmentId, second.attachmentId),
        )
        assertTrue(appended is TimelineWriteResult.MessageAppended)
        assertEquals(1L, (appended as TimelineWriteResult.MessageAppended).timelineRevision)
        val ordered = attachmentService.orderedAvailableAttachmentsForMessage("message-valid")
        assertTrue(ordered is MessageAttachmentsResult.Success)
        assertEquals(
            listOf(first.attachmentId, second.attachmentId),
            (ordered as MessageAttachmentsResult.Success).attachments.map { it.attachmentId },
        )

        val duplicate = append(
            "conversation",
            "message-duplicate",
            MessageRole.ASSISTANT,
            1,
            2,
            listOf(first.attachmentId, first.attachmentId),
        )
        assertTimelineError<ConversationTimelineError.DuplicateAttachmentReference>(duplicate)
        assertNull(database.conversationTimelineDao().message("message-duplicate"))

        val missing = append(
            "conversation",
            "message-missing",
            MessageRole.ASSISTANT,
            1,
            2,
            listOf("missing-attachment"),
        )
        assertTimelineError<ConversationTimelineError.MissingAttachment>(missing)
        assertNull(database.conversationTimelineDao().message("message-missing"))

        insertAttachment("staging", AttachmentState.STAGING)
        insertAttachment("delete-pending", AttachmentState.DELETE_PENDING)
        listOf("staging", "delete-pending").forEach { attachmentId ->
            val unavailable = append(
                "conversation",
                "message-$attachmentId",
                MessageRole.ASSISTANT,
                1,
                2,
                listOf(attachmentId),
            )
            assertTimelineError<ConversationTimelineError.AttachmentUnavailable>(unavailable)
            assertNull(database.conversationTimelineDao().message("message-$attachmentId"))
        }
        assertEquals(1L, database.conversationTimelineDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun regenerateUsesExplicitNewAttachmentsAndRewindPreservesBothBranchAssociations() = runBlocking {
        val oldAttachment = createImported("old-response-attachment", "old".toByteArray())
        val newAttachment = createImported("new-response-attachment", "new".toByteArray())
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1)
        append(
            "conversation",
            "a1",
            MessageRole.ASSISTANT,
            1,
            2,
            listOf(oldAttachment.attachmentId),
        )

        val regenerated = timelineService.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                conversationId = "conversation",
                originalMessageId = "a1",
                replacement = messageInput(
                    "a1b",
                    MessageRole.ASSISTANT,
                    3,
                    listOf(newAttachment.attachmentId),
                ),
                expectedTimelineRevision = 2,
                occurredAt = 3,
            ),
        )
        assertTrue(regenerated is TimelineWriteResult.AssistantRegenerated)
        assertEquals(listOf(oldAttachment.attachmentId), database.attachmentDao().attachmentIdsForMessage("a1"))
        assertEquals(listOf(newAttachment.attachmentId), database.attachmentDao().attachmentIdsForMessage("a1b"))
        assertEquals(
            database.conversationTimelineDao().parentEdge("a1")?.parentMessageId,
            database.conversationTimelineDao().parentEdge("a1b")?.parentMessageId,
        )

        val rewound = timelineService.rewindTo(
            RewindTimelineInput("conversation", "u1", expectedTimelineRevision = 3, occurredAt = 4),
        )
        assertTrue(rewound is TimelineWriteResult.Rewound)
        assertEquals(listOf(oldAttachment.attachmentId), database.attachmentDao().attachmentIdsForMessage("a1"))
        assertEquals(listOf(newAttachment.attachmentId), database.attachmentDao().attachmentIdsForMessage("a1b"))
    }

    @Test
    fun controlledAssociationFailureRollsBackMessageLinksHeadAndRevision() = runBlocking {
        val attachment = createImported("atomic-attachment", "bytes".toByteArray())
        createConversation("conversation")
        val failingTimeline = ConversationTimelineService(database) { operation ->
            if (operation == TimelineOperation.APPEND_MESSAGE) error("controlled association failure")
        }

        val result = failingTimeline.appendMessage(
            AppendTimelineMessageInput(
                conversationId = "conversation",
                message = messageInput(
                    "message",
                    MessageRole.USER,
                    1,
                    listOf(attachment.attachmentId),
                ),
                expectedTimelineRevision = 0,
                occurredAt = 1,
            ),
        )

        assertTimelineError<ConversationTimelineError.StorageFailure>(result)
        assertNull(database.conversationTimelineDao().message("message"))
        assertTrue(database.attachmentDao().attachmentIdsForMessage("message").isEmpty())
        val head = database.conversationTimelineDao().timelineHead("conversation")
        assertNull(head?.activeHeadMessageId)
        assertEquals(0L, head?.timelineRevision)
    }

    @Test
    fun safeDeleteLastReferenceDefersBlobRemovalAndInvalidatesAttachmentDerivedArtifact() = runBlocking {
        val attachment = createSelfie("sole-attachment", "generated".toByteArray())
        createConversation("conversation")
        append(
            "conversation",
            "message",
            MessageRole.ASSISTANT,
            0,
            1,
            listOf(attachment.attachmentId),
        )
        insertAttachmentArtifact("attachment-artifact", attachment.attachmentId)

        val deleted = safeDeleteService.deleteLeafMessage(
            DeleteTimelineMessageInput("conversation", "message", expectedTimelineRevision = 1, occurredAt = 2),
        )
        assertTrue(deleted is TimelineDeleteResult.Deleted)
        deleted as TimelineDeleteResult.Deleted
        assertEquals(setOf(attachment.attachmentId), deleted.attachmentIdsPendingDeletion)
        assertNull(database.conversationTimelineDao().message("message"))
        assertTrue(database.attachmentDao().attachmentIdsForMessage("message").isEmpty())
        val pending = database.attachmentDao().attachment(attachment.attachmentId)
        assertEquals(AttachmentState.DELETE_PENDING, pending?.state)
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("attachment-artifact")?.state)
        assertEquals(0, database.attachmentDao().derivedArtifactDependencyCount(attachment.attachmentId))
        assertTrue(blobStore.exists(checkNotNull(pending).storageKey))

        assertEquals(
            AttachmentCleanupResult.Removed(attachment.attachmentId),
            attachmentService.finalizePendingDeletion(attachment.attachmentId),
        )
        assertFalse(blobStore.exists(pending.storageKey))
        assertNull(database.attachmentDao().attachment(attachment.attachmentId))
        assertNull(database.attachmentDao().generatedMediaProvenance(attachment.attachmentId))
    }

    @Test
    fun safeDeleteSharedAttachmentPreservesAvailableMediaAndDerivedArtifact() = runBlocking {
        val attachment = createImported("shared-attachment", "shared".toByteArray())
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1, listOf(attachment.attachmentId))
        append("conversation", "a1", MessageRole.ASSISTANT, 1, 2, listOf(attachment.attachmentId))
        insertAttachmentArtifact("shared-artifact", attachment.attachmentId)

        val deleted = safeDeleteService.deleteLeafMessage(
            DeleteTimelineMessageInput("conversation", "a1", expectedTimelineRevision = 2, occurredAt = 3),
        )
        assertTrue(deleted is TimelineDeleteResult.Deleted)
        deleted as TimelineDeleteResult.Deleted
        assertTrue(deleted.attachmentIdsPendingDeletion.isEmpty())
        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment(attachment.attachmentId)?.state)
        assertEquals(listOf(attachment.attachmentId), database.attachmentDao().attachmentIdsForMessage("u1"))
        assertEquals(DerivedArtifactState.CURRENT, database.maintenanceDao().derivedArtifact("shared-artifact")?.state)
        assertEquals(1, database.attachmentDao().derivedArtifactDependencyCount(attachment.attachmentId))
    }

    @Test
    fun safeDeleteAttachmentMutationRollsBackWithEntireTimelineDelete() = runBlocking {
        val attachment = createImported("rollback-attachment", "rollback".toByteArray())
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1, listOf(attachment.attachmentId))
        insertAttachmentArtifact("rollback-artifact", attachment.attachmentId)
        val failingDelete = newSafeDeleteService { operation ->
            if (operation == SafeDeleteOperation.DELETE_TIMELINE_MESSAGE) error("controlled delete failure")
        }

        val result = failingDelete.deleteLeafMessage(
            DeleteTimelineMessageInput("conversation", "message", expectedTimelineRevision = 1, occurredAt = 2),
        )

        assertTrue(result is TimelineDeleteResult.Failure)
        assertNotNull(database.conversationTimelineDao().message("message"))
        assertEquals(listOf(attachment.attachmentId), database.attachmentDao().attachmentIdsForMessage("message"))
        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment(attachment.attachmentId)?.state)
        assertEquals(DerivedArtifactState.CURRENT, database.maintenanceDao().derivedArtifact("rollback-artifact")?.state)
        assertEquals(1, database.attachmentDao().derivedArtifactDependencyCount(attachment.attachmentId))
        assertEquals(1L, database.conversationTimelineDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun physicalDeleteFailureKeepsPendingRowAndMissingBlobCleanupIsIdempotent() = runBlocking {
        val failingStore = TestBlobStore(failDelete = true)
        failingStore.seed("attachments/pending.blob", "pending".toByteArray())
        insertAttachment(
            id = "pending",
            state = AttachmentState.DELETE_PENDING,
            storageKey = "attachments/pending.blob",
            byteSize = 7,
            contentSha256 = sha256("pending".toByteArray()),
        )
        val failingService = newAttachmentService(failingStore)

        val failure = failingService.finalizePendingDeletion("pending")
        assertTrue(failure is AttachmentCleanupResult.Failure)
        assertTrue((failure as AttachmentCleanupResult.Failure).error is AttachmentError.BlobDeleteFailure)
        assertEquals(AttachmentState.DELETE_PENDING, database.attachmentDao().attachment("pending")?.state)
        assertTrue(failingStore.exists("attachments/pending.blob"))

        insertAttachment(
            id = "already-missing",
            state = AttachmentState.DELETE_PENDING,
            storageKey = "attachments/already-missing.blob",
            byteSize = 3,
            contentSha256 = sha256("old".toByteArray()),
        )
        val healthyStore = TestBlobStore()
        val healthyService = newAttachmentService(healthyStore)
        assertEquals(
            AttachmentCleanupResult.Removed("already-missing"),
            healthyService.finalizePendingDeletion("already-missing"),
        )
        assertNull(database.attachmentDao().attachment("already-missing"))
    }

    @Test
    fun hardMemoryDeleteInvalidatesAttachmentLineageWithoutDeletingHistoricalMedia() = runBlocking {
        val attachment = createImported("lineage-attachment", "lineage".toByteArray())
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1, listOf(attachment.attachmentId))
        insertExperienceAndMemory("experience", "memory", "message")
        insertAttachmentArtifact("lineage-artifact", attachment.attachmentId)

        val deleted = safeDeleteService.deleteMemory(DeleteMemoryInput("memory", occurredAt = 2))

        assertTrue(deleted is MemoryDeleteResult.Deleted)
        assertNull(database.memoryDao().memory("memory"))
        assertNotNull(database.conversationTimelineDao().message("message"))
        assertEquals(1, database.memoryDao().experienceCount("experience"))
        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment(attachment.attachmentId)?.state)
        assertEquals(listOf(attachment.attachmentId), database.attachmentDao().attachmentIdsForMessage("message"))
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("lineage-artifact")?.state)
        assertEquals(1, database.attachmentDao().derivedArtifactDependencyCount(attachment.attachmentId))
    }

    @Test
    fun attachmentCreationAndAssociationHaveNoAutomaticCognitiveSideEffects() = runBlocking {
        val before = COGNITIVE_TABLES.associateWith(::rowCount)
        val imported = createImported("no-cognition-import", "import".toByteArray())
        val generated = createSelfie("no-cognition-generated", "generated".toByteArray())
        createConversation("conversation")
        append(
            "conversation",
            "message",
            MessageRole.USER,
            0,
            1,
            listOf(imported.attachmentId, generated.attachmentId),
        )
        val after = COGNITIVE_TABLES.associateWith(::rowCount)

        assertEquals(before, after)
    }

    private fun newAttachmentService(
        store: AttachmentBlobStore,
        hook: () -> Unit = {},
    ) = AttachmentService(
        database = database,
        blobStore = store,
        idGenerator = AttachmentIdGenerator { "generated-attachment-${generatedId++}" },
        afterFinalizationMutation = hook,
    )

    private fun newSafeDeleteService(
        hook: (SafeDeleteOperation) -> Unit = {},
    ) = SafeDeleteService(
        database = database,
        idGenerator = SafeDeleteIdGenerator { "safe-delete-${generatedId++}" },
        afterDependencyMutation = hook,
    )

    private suspend fun createImported(id: String, bytes: ByteArray): AttachmentMetadata =
        assertCreated(
            attachmentService.createImportedAttachment(
                ImportedAttachmentInput(
                    attachmentId = id,
                    kind = AttachmentKind.IMAGE,
                    mimeType = "image/png",
                    occurredAt = 1,
                    bytes = AttachmentByteSource.fromBytes(bytes),
                ),
            ),
        )

    private suspend fun createSelfie(id: String, bytes: ByteArray): AttachmentMetadata =
        assertCreated(
            attachmentService.createGeneratedAttachment(
                GeneratedAttachmentInput(
                    attachmentId = id,
                    kind = AttachmentKind.IMAGE,
                    mimeType = "image/png",
                    provenance = GeneratedMediaProvenanceInput(
                        generationKind = GeneratedMediaKind.RIVEN_SELFIE_STYLE,
                        generatorProvider = "future-provider",
                        generatorModel = "future-model",
                        providerRequestId = "opaque-request",
                        appearanceAuthority = RIVEN_APPEARANCE_CANON_AUTHORITY,
                        appearanceAuthorityFingerprint = "appearance-v1-fingerprint",
                        requestFingerprint = sha256("request".toByteArray()),
                    ),
                    occurredAt = 1,
                    bytes = AttachmentByteSource.fromBytes(bytes),
                ),
            ),
        )

    private suspend fun createConversation(id: String) {
        val result = timelineService.createConversationWithTimeline(
            CreateTimelineConversationInput(id, 0, 0, ConversationStatus.ACTIVE),
        )
        assertTrue(result is TimelineWriteResult.ConversationCreated)
    }

    private suspend fun append(
        conversationId: String,
        messageId: String,
        role: MessageRole,
        expectedRevision: Long,
        occurredAt: Long,
        attachmentIds: List<String> = emptyList(),
    ): TimelineWriteResult = timelineService.appendMessage(
        AppendTimelineMessageInput(
            conversationId = conversationId,
            message = messageInput(messageId, role, occurredAt, attachmentIds),
            expectedTimelineRevision = expectedRevision,
            occurredAt = occurredAt,
        ),
    )

    private fun messageInput(
        messageId: String,
        role: MessageRole,
        occurredAt: Long,
        attachmentIds: List<String> = emptyList(),
    ) = NewTimelineMessageInput(
        messageId = messageId,
        role = role,
        deliveryState = MessageDeliveryState.PERSISTED,
        content = "content-$messageId",
        createdAt = occurredAt,
        updatedAt = occurredAt,
        attachmentIds = attachmentIds,
    )

    private fun insertAttachmentArtifact(artifactId: String, attachmentId: String) {
        database.maintenanceDao().insertDerivedArtifact(
            DerivedArtifactEntity(
                id = artifactId,
                artifactType = DerivedArtifactType.SUMMARY,
                state = DerivedArtifactState.CURRENT,
                producerVersion = "test",
                sourceRevision = 1,
                createdAt = 1,
            ),
        )
        database.maintenanceDao().insertDerivedArtifactAttachmentDependency(
            DerivedArtifactAttachmentDependencyEntity(artifactId, attachmentId, createdAt = 1),
        )
    }

    private fun insertAttachment(
        id: String,
        state: AttachmentState,
        storageKey: String = "attachments/$id.blob",
        byteSize: Long? = null,
        contentSha256: String? = null,
    ) {
        database.attachmentDao().insertAttachment(
            AttachmentEntity(
                id = id,
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                state = state,
                storageKey = storageKey,
                byteSize = byteSize,
                contentSha256 = contentSha256,
                source = AttachmentSource.SHAI_IMPORT,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun insertExperienceAndMemory(experienceId: String, memoryId: String, messageId: String) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = experienceId,
                eventOrder = 1,
                experienceType = ExperienceType.CONVERSATION_MESSAGE,
                actor = ExperienceActor.SHAI,
                sourceContent = "source",
                occurredAt = 1,
                recordedAt = 1,
                sensitivity = SensitivityLevel.STANDARD,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId = experienceId,
                messageId = messageId,
                sourceOrder = 0,
                sourceRole = ExperienceMessageSourceRole.PRIMARY,
                createdAt = 1,
            ),
        )
        database.memoryDao().insertMemory(
            MemoryEntity(
                id = memoryId,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = "retained meaning",
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                certainty = MemoryCertainty.CERTAIN,
                truthState = MemoryTruthState.SUPPORTED,
                retentionState = MemoryRetentionState.ACTIVE,
                lifecycleState = MemoryLifecycleState.VALIDATED,
                temporalState = TemporalState.CURRENT,
                learnedAt = 1,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = memoryId,
                experienceId = experienceId,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = MemoryCertainty.CERTAIN,
                lineageKey = "attachment-lineage",
                createdAt = 1,
            ),
        )
    }

    private fun rowCount(table: String): Long = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun assertCreated(result: AttachmentCreateResult): AttachmentMetadata {
        assertTrue("Expected attachment creation success, got $result", result is AttachmentCreateResult.Success)
        return (result as AttachmentCreateResult.Success).attachment
    }

    private inline fun <reified T : ConversationTimelineError> assertTimelineError(
        result: TimelineWriteResult,
    ) {
        assertTrue("Expected timeline failure, got $result", result is TimelineWriteResult.Failure)
        assertTrue((result as TimelineWriteResult.Failure).error is T)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private class TestBlobStore(
        private val failWrite: Boolean = false,
        private val failDelete: Boolean = false,
    ) : AttachmentBlobStore {
        private val blobs = linkedMapOf<String, ByteArray>()

        override fun write(storageKey: String, source: AttachmentByteSource): AttachmentBlobWriteResult {
            val bytes = source.openStream().use { it.readBytes() }
            blobs[storageKey] = bytes
            if (failWrite) error("controlled blob write failure")
            return AttachmentBlobWriteResult(bytes.size.toLong(), digest(bytes))
        }

        override fun exists(storageKey: String): Boolean = blobs.containsKey(storageKey)

        override fun open(storageKey: String): InputStream =
            blobs[storageKey]?.let(::ByteArrayInputStream) ?: throw FileNotFoundException(storageKey)

        override fun delete(storageKey: String) {
            if (failDelete) error("controlled blob delete failure")
            blobs.remove(storageKey)
        }

        fun seed(storageKey: String, bytes: ByteArray) {
            blobs[storageKey] = bytes
        }

        fun keys(): Set<String> = blobs.keys

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        val COGNITIVE_TABLES = listOf(
            "experiences",
            "candidate_memories",
            "memories",
            "open_loops",
            "suppression_tombstones",
        )
    }
}
