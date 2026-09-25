package com.shai.riven.data.deletion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.CommitRegeneratedAssistantResponseInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactOpenLoopDependencyEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.MemoryAuditHistoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity
import com.shai.riven.data.persistence.entity.OpenLoopAuditHistoryEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntityLinkEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.EntityKind
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryAuditAction
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.OpenLoopAuditAction
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SuppressionKind
import com.shai.riven.data.persistence.model.TemporalState
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafeDeleteServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var timelineService: ConversationTimelineService
    private lateinit var deleteService: SafeDeleteService
    private var generatedId = 0
    private var eventOrder = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timelineService = ConversationTimelineService(database)
        deleteService = newDeleteService()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun hardMemoryDeleteRemovesMeaningUpgradesForgetAndPreservesRelatedOpenLoop() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 10)
        insertExperience("experience", listOf("message"), "private transcript text")
        insertMemory("memory", "private retained meaning", listOf("experience" to "lineage"))
        insertEntityAndMemoryLink("entity", "memory")
        insertArtifact("memory-artifact", memoryIds = listOf("memory"))
        database.maintenanceDao().insertMemoryAuditHistory(
            MemoryAuditHistoryEntity(
                id = "memory-audit",
                memoryId = "memory",
                action = MemoryAuditAction.CREATED,
                triggeringExperienceId = "experience",
                occurredAt = 10,
            ),
        )
        insertOpenLoop(
            id = "loop",
            creationExperienceId = "experience",
            relatedMemoryId = "memory",
        )
        val hash = lineageHash("experience", "lineage")
        database.maintenanceDao().insertSuppressionTombstone(
            SuppressionTombstoneEntity(
                id = "existing-tombstone",
                kind = SuppressionKind.FORGET,
                sourceLineageHash = hash,
                isActive = false,
                createdAt = 4,
                expiresAt = 99,
                formatVersion = 1,
            ),
        )

        val result = assertMemoryDeleted(
            deleteService.deleteMemory(DeleteMemoryInput("memory", occurredAt = 20)),
        )

        assertEquals("memory", result.deletedMemoryId)
        assertNull(database.safeDeleteDao().memory("memory"))
        assertTrue(database.safeDeleteDao().evidenceForMemory("memory").isEmpty())
        assertEquals(0, database.memoryDao().memoryEntityLinkCount("memory"))
        assertTrue(database.safeDeleteDao().memoryAuditsForMemory("memory").isEmpty())
        assertNull(database.safeDeleteDao().openLoop("loop")?.relatedMemoryId)
        assertEquals(
            DerivedArtifactState.INVALIDATED,
            database.maintenanceDao().derivedArtifact("memory-artifact")?.state,
        )
        val tombstone = database.safeDeleteDao().suppressionTombstone(hash)!!
        assertEquals("existing-tombstone", tombstone.id)
        assertEquals(SuppressionKind.DELETE, tombstone.kind)
        assertTrue(tombstone.isActive)
        assertNull(tombstone.expiresAt)
        assertEquals(4L, tombstone.createdAt)
        assertEquals(1, database.safeDeleteDao().suppressionTombstones().size)
        assertFalse(tombstone.toString().contains("private retained meaning"))
        assertFalse(tombstone.toString().contains("private transcript text"))
        assertTrue(
            database.safeDeleteDao().repairJobs("MEMORY", "memory")
                .any { it.jobType == RepairJobType.PROPAGATE_DELETION },
        )
        assertTrue(result.invalidatedDerivedArtifactIds.contains("memory-artifact"))
    }

    @Test
    fun hardMemoryDeleteInvalidatesFullRetainedSourceLineageWithoutDeletingCanonicalSources() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 10)
        insertExperience("experience", listOf("message"), "retained source content")
        insertMemory("memory", "private retained meaning", listOf("experience" to "lineage"))
        insertOpenLoop(
            id = "loop",
            creationExperienceId = "experience",
            relatedMemoryId = "memory",
        )
        insertArtifact("artifact-memory", memoryIds = listOf("memory"))
        insertArtifact("artifact-experience", experienceIds = listOf("experience"))
        insertArtifact("artifact-message", messageIds = listOf("message"))
        insertArtifact("artifact-loop", openLoopIds = listOf("loop"))

        val result = assertMemoryDeleted(
            deleteService.deleteMemory(DeleteMemoryInput("memory", occurredAt = 20)),
        )

        assertNull(database.safeDeleteDao().memory("memory"))
        assertNotNull(database.safeDeleteDao().experience("experience"))
        assertNotNull(database.safeDeleteDao().message("message"))
        assertNull(database.safeDeleteDao().openLoop("loop")?.relatedMemoryId)
        val artifactIds = setOf(
            "artifact-memory",
            "artifact-experience",
            "artifact-message",
            "artifact-loop",
        )
        assertEquals(artifactIds, result.invalidatedDerivedArtifactIds)
        artifactIds.forEach { artifactId ->
            assertEquals(
                DerivedArtifactState.INVALIDATED,
                database.maintenanceDao().derivedArtifact(artifactId)?.state,
            )
        }
        assertEquals(0, database.maintenanceDao().memoryDependencyCount("artifact-memory"))
        assertEquals(1, database.maintenanceDao().experienceDependencyCount("artifact-experience"))
        assertEquals(1, database.maintenanceDao().messageDependencyCount("artifact-message"))
        assertEquals(1, database.safeDeleteDao().derivedOpenLoopDependencyCount("artifact-loop"))
        assertEquals(
            SuppressionKind.DELETE,
            database.safeDeleteDao().suppressionTombstone(lineageHash("experience", "lineage"))?.kind,
        )
    }

    @Test
    fun hardMemoryDeleteReassessesIndependentNeighborInsteadOfDeletingIt() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1)
        insertExperience("experience", listOf("message"))
        insertMemory("target", "target meaning", listOf("experience" to "target-lineage"))
        insertMemory("neighbor", "neighbor meaning", listOf("experience" to "neighbor-lineage"))
        database.memoryDao().insertMemoryRelationship(
            MemoryRelationshipEntity(
                sourceMemoryId = "target",
                targetMemoryId = "neighbor",
                relationshipType = MemoryRelationshipType.RELATED_TO,
                createdByExperienceId = "experience",
                createdAt = 2,
            ),
        )
        insertArtifact("neighbor-artifact", memoryIds = listOf("neighbor"))

        val result = assertMemoryDeleted(
            deleteService.deleteMemory(DeleteMemoryInput("target", occurredAt = 3)),
        )

        assertNull(database.safeDeleteDao().memory("target"))
        assertNotNull(database.safeDeleteDao().memory("neighbor"))
        assertEquals(
            0,
            database.safeDeleteDao().memoryRelationshipCount(
                "target",
                "neighbor",
                MemoryRelationshipType.RELATED_TO,
            ),
        )
        assertEquals(setOf("neighbor"), result.neighboringMemoryIdsRequiringReassessment)
        assertTrue(
            database.safeDeleteDao().repairJobs("MEMORY", "neighbor")
                .any { it.jobType == RepairJobType.REASSESS_PROVENANCE },
        )
        assertEquals(
            DerivedArtifactState.INVALIDATED,
            database.maintenanceDao().derivedArtifact("neighbor-artifact")?.state,
        )
        assertEquals(SuppressionKind.DELETE, database.safeDeleteDao().suppressionTombstones().single().kind)
    }

    @Test
    fun deletingActiveHeadMovesHeadToParentAndKeepsSequenceNumbers() = runBlocking {
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1)
        append("conversation", "a1", MessageRole.ASSISTANT, 1, 2)
        append("conversation", "u2", MessageRole.USER, 2, 3)
        append("conversation", "a2", MessageRole.ASSISTANT, 3, 4)

        val result = assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "a2", 4, occurredAt = 5),
            ),
        )

        assertEquals("u2", result.activeHeadMessageId)
        assertEquals(5L, result.timelineRevision)
        assertNull(database.safeDeleteDao().message("a2"))
        assertEquals(listOf("u1", "a1", "u2"), activeIds("conversation"))
        assertEquals(
            listOf(1L, 2L, 3L),
            database.conversationTimelineDao().allMessages("conversation").map { it.sequenceNumber },
        )
    }

    @Test
    fun deletingSingleRootHeadLeavesConversationWithEmptyTimeline() = runBlocking {
        createConversation("conversation")
        append("conversation", "root", MessageRole.USER, 0, 1)

        val result = assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "root", 1, occurredAt = 2),
            ),
        )

        assertNull(result.activeHeadMessageId)
        assertEquals(2L, result.timelineRevision)
        assertNull(database.safeDeleteDao().message("root"))
        assertNotNull(database.safeDeleteDao().conversation("conversation"))
        assertTrue(activeIds("conversation").isEmpty())
    }

    @Test
    fun deletingOffPathLeafPreservesActiveRegeneratedBranchAndIncrementsRevision() = runBlocking {
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1)
        append("conversation", "a1", MessageRole.ASSISTANT, 1, 2)
        regenerate("conversation", "a1", "a1b", expectedRevision = 2, occurredAt = 3)

        val result = assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "a1", 3, occurredAt = 4),
            ),
        )

        assertEquals("a1b", result.activeHeadMessageId)
        assertEquals(4L, result.timelineRevision)
        assertNull(database.safeDeleteDao().message("a1"))
        assertEquals(listOf("u1", "a1b"), activeIds("conversation"))
        assertEquals(listOf(1L, 3L), database.conversationTimelineDao().allMessages("conversation").map { it.sequenceNumber })
    }

    @Test
    fun nonLeafDeleteReturnsTypedErrorAndRollsBackEverything() = runBlocking {
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1)
        append("conversation", "a1", MessageRole.ASSISTANT, 1, 2)

        val error = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "u1", 2, occurredAt = 3),
            ),
        )

        assertEquals(SafeDeleteError.MessageHasChildren("u1", 1), error)
        assertNotNull(database.safeDeleteDao().message("u1"))
        assertEquals("a1", database.safeDeleteDao().timelineHead("conversation")?.activeHeadMessageId)
        assertEquals(2L, database.safeDeleteDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun staleRevisionRejectsDeleteWithoutMutation() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1)

        val error = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "message", 0, occurredAt = 2),
            ),
        )

        assertEquals(SafeDeleteError.StaleTimelineRevision(expected = 0, actual = 1), error)
        assertNotNull(database.safeDeleteDao().message("message"))
        assertEquals(1L, database.safeDeleteDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun directMessageArtifactIsInvalidatedAndDependencyRemovedBeforeDelete() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1)
        insertArtifact("message-artifact", messageIds = listOf("message"))

        assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "message", 1, occurredAt = 2),
            ),
        )

        assertNull(database.safeDeleteDao().message("message"))
        assertEquals(
            DerivedArtifactState.INVALIDATED,
            database.maintenanceDao().derivedArtifact("message-artifact")?.state,
        )
        assertEquals(0, database.maintenanceDao().messageDependencyCount("message-artifact"))
    }

    @Test
    fun singleSourceExperienceDeletesSourceCandidateAndUnsupportedMemoryWithoutTombstone() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1)
        insertExperience("experience", listOf("message"), "source content")
        insertCandidate("candidate", listOf("experience" to CandidateEvidenceRole.SEED))
        insertMemory("memory", "meaning", listOf("experience" to "lineage"))
        insertArtifact("experience-artifact", experienceIds = listOf("experience"))
        insertArtifact("memory-artifact", memoryIds = listOf("memory"))

        val result = assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "message", 1, occurredAt = 2),
            ),
        )

        assertEquals(setOf("experience"), result.deletedExperienceIds)
        assertEquals(setOf("candidate"), result.deletedCandidateMemoryIds)
        assertEquals(setOf("memory"), result.deletedMemoryIds)
        assertNull(database.safeDeleteDao().experience("experience"))
        assertNull(database.safeDeleteDao().candidateMemory("candidate"))
        assertNull(database.safeDeleteDao().memory("memory"))
        assertTrue(database.safeDeleteDao().suppressionTombstones().isEmpty())
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("experience-artifact")?.state)
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("memory-artifact")?.state)
    }

    @Test
    fun memoryWithIndependentEvidenceSurvivesSourceDeletionAndIsReassessed() = runBlocking {
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1)
        append("conversation", "a1", MessageRole.ASSISTANT, 1, 2)
        regenerate("conversation", "a1", "a1b", 2, 3)
        insertExperience("experience-a", listOf("a1"))
        insertExperience("experience-b", listOf("a1b"))
        insertMemory(
            "memory",
            "retained meaning",
            listOf("experience-a" to "lineage-a", "experience-b" to "lineage-b"),
        )
        insertArtifact("memory-artifact", memoryIds = listOf("memory"))

        assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "a1", 3, occurredAt = 4),
            ),
        )

        assertNull(database.safeDeleteDao().experience("experience-a"))
        assertNotNull(database.safeDeleteDao().experience("experience-b"))
        assertNotNull(database.safeDeleteDao().memory("memory"))
        assertEquals(
            listOf("experience-b"),
            database.safeDeleteDao().evidenceForMemory("memory").map { it.experienceId },
        )
        assertTrue(
            database.safeDeleteDao().repairJobs("MEMORY", "memory")
                .any { it.jobType == RepairJobType.REASSESS_PROVENANCE },
        )
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("memory-artifact")?.state)
    }

    @Test
    fun candidateWithIndependentEvidenceReturnsToTentativeWithoutInventingSeed() = runBlocking {
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1)
        append("conversation", "a1", MessageRole.ASSISTANT, 1, 2)
        regenerate("conversation", "a1", "a1b", 2, 3)
        insertExperience("experience-a", listOf("a1"))
        insertExperience("experience-b", listOf("a1b"))
        insertCandidate(
            "candidate",
            listOf(
                "experience-a" to CandidateEvidenceRole.SEED,
                "experience-b" to CandidateEvidenceRole.SUPPORTING,
            ),
            state = CandidateMemoryState.READY_FOR_VALIDATION,
        )

        assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "a1", 3, occurredAt = 4),
            ),
        )

        assertEquals(CandidateMemoryState.TENTATIVE, database.safeDeleteDao().candidateMemory("candidate")?.state)
        val remaining = database.safeDeleteDao().candidateEvidenceForCandidate("candidate")
        assertEquals(1, remaining.size)
        assertEquals("experience-b", remaining.single().experienceId)
        assertEquals(CandidateEvidenceRole.SUPPORTING, remaining.single().role)
        assertTrue(remaining.none { it.role == CandidateEvidenceRole.SEED })
    }

    @Test
    fun deletingOpenLoopCreationSourceHardDeletesLoopAndInvalidatesArtifact() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1)
        insertExperience("experience", listOf("message"))
        insertEntity("entity")
        insertOpenLoop("loop", "experience", title = "private loop title")
        database.openLoopDao().insertEntityLink(
            OpenLoopEntityLinkEntity("loop", "entity", EntityLinkRole.ABOUT, createdAt = 1),
        )
        database.openLoopDao().insertAuditHistory(
            OpenLoopAuditHistoryEntity(
                id = "loop-audit",
                openLoopId = "loop",
                action = OpenLoopAuditAction.CREATED,
                triggeringExperienceId = "experience",
                toState = OpenLoopState.ACTIVE,
                occurredAt = 1,
            ),
        )
        insertArtifact("loop-artifact", openLoopIds = listOf("loop"))

        val result = assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "message", 1, occurredAt = 2),
            ),
        )

        assertEquals(setOf("loop"), result.deletedOpenLoopIds)
        assertNull(database.safeDeleteDao().openLoop("loop"))
        assertEquals(0, database.openLoopDao().entityLinkCount("loop"))
        assertTrue(database.safeDeleteDao().openLoopAudits("loop").isEmpty())
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("loop-artifact")?.state)
    }

    @Test
    fun deletingResolutionSourceRestoresDeterministicPriorOpenLoopState() = runBlocking {
        createConversation("conversation")
        append("conversation", "creation-message", MessageRole.USER, 0, 1)
        append("conversation", "resolution-message", MessageRole.ASSISTANT, 1, 2)
        insertExperience("creation-experience", listOf("creation-message"))
        insertExperience("resolution-experience", listOf("resolution-message"))
        insertOpenLoop(
            id = "loop",
            creationExperienceId = "creation-experience",
            resolutionExperienceId = "resolution-experience",
            state = OpenLoopState.COMPLETED,
            closedAt = 2,
        )
        database.openLoopDao().insertAuditHistory(
            OpenLoopAuditHistoryEntity(
                id = "resolution-audit",
                openLoopId = "loop",
                action = OpenLoopAuditAction.STATE_CHANGED,
                triggeringExperienceId = "resolution-experience",
                fromState = OpenLoopState.ACTIVE,
                toState = OpenLoopState.COMPLETED,
                occurredAt = 2,
            ),
        )
        insertArtifact("loop-artifact", openLoopIds = listOf("loop"))

        assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "resolution-message", 2, occurredAt = 3),
            ),
        )

        val loop = database.safeDeleteDao().openLoop("loop")!!
        assertEquals(OpenLoopState.ACTIVE, loop.state)
        assertNull(loop.resolutionExperienceId)
        assertNull(loop.closedAt)
        assertNull(database.safeDeleteDao().experience("resolution-experience"))
        assertTrue(database.safeDeleteDao().openLoopAuditsForExperience("resolution-experience").isEmpty())
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("loop-artifact")?.state)
    }

    @Test
    fun ambiguousOpenLoopRollbackAbortsWithoutAnyMutation() = runBlocking {
        createConversation("conversation")
        append("conversation", "creation-message", MessageRole.USER, 0, 1)
        append("conversation", "resolution-message", MessageRole.ASSISTANT, 1, 2)
        insertExperience("creation-experience", listOf("creation-message"))
        insertExperience("resolution-experience", listOf("resolution-message"), "resolution source")
        insertOpenLoop(
            id = "loop",
            creationExperienceId = "creation-experience",
            resolutionExperienceId = "resolution-experience",
            state = OpenLoopState.COMPLETED,
            closedAt = 2,
        )

        val error = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "resolution-message", 2, occurredAt = 3),
            ),
        )

        assertEquals(SafeDeleteError.AmbiguousOpenLoopRollback("loop", "resolution-experience"), error)
        assertNotNull(database.safeDeleteDao().message("resolution-message"))
        assertNotNull(database.safeDeleteDao().experience("resolution-experience"))
        assertEquals(OpenLoopState.COMPLETED, database.safeDeleteDao().openLoop("loop")?.state)
        assertEquals("resolution-message", database.safeDeleteDao().timelineHead("conversation")?.activeHeadMessageId)
        assertEquals(2L, database.safeDeleteDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun openLoopRollbackRefusesAdditionalAuditMutationFromResolutionExperience() = runBlocking {
        createConversation("conversation")
        append("conversation", "creation-message", MessageRole.USER, 0, 1)
        append("conversation", "resolution-message", MessageRole.ASSISTANT, 1, 2)
        insertExperience("creation-experience", listOf("creation-message"))
        insertExperience("resolution-experience", listOf("resolution-message"))
        insertOpenLoop(
            id = "loop",
            creationExperienceId = "creation-experience",
            resolutionExperienceId = "resolution-experience",
            state = OpenLoopState.COMPLETED,
            dueAt = 99,
            closedAt = 2,
        )
        database.openLoopDao().insertAuditHistory(
            OpenLoopAuditHistoryEntity(
                id = "resolution-state-audit",
                openLoopId = "loop",
                action = OpenLoopAuditAction.STATE_CHANGED,
                triggeringExperienceId = "resolution-experience",
                fromState = OpenLoopState.ACTIVE,
                toState = OpenLoopState.COMPLETED,
                occurredAt = 2,
            ),
        )
        database.openLoopDao().insertAuditHistory(
            OpenLoopAuditHistoryEntity(
                id = "resolution-due-audit",
                openLoopId = "loop",
                action = OpenLoopAuditAction.DUE_DATE_CHANGED,
                triggeringExperienceId = "resolution-experience",
                occurredAt = 2,
            ),
        )

        val error = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "resolution-message", 2, occurredAt = 3),
            ),
        )

        assertEquals(SafeDeleteError.AmbiguousOpenLoopRollback("loop", "resolution-experience"), error)
        assertNotNull(database.safeDeleteDao().message("resolution-message"))
        assertNotNull(database.safeDeleteDao().experience("resolution-experience"))
        val loop = database.safeDeleteDao().openLoop("loop")!!
        assertEquals(OpenLoopState.COMPLETED, loop.state)
        assertEquals("resolution-experience", loop.resolutionExperienceId)
        assertEquals(99L, loop.dueAt)
        assertEquals(2, database.safeDeleteDao().openLoopAuditsForExperience("loop", "resolution-experience").size)
        assertEquals("resolution-message", database.safeDeleteDao().timelineHead("conversation")?.activeHeadMessageId)
        assertEquals(2L, database.safeDeleteDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun openLoopRollbackRefusesMultipleStateTransitionsFromResolutionExperience() = runBlocking {
        createConversation("conversation")
        append("conversation", "creation-message", MessageRole.USER, 0, 1)
        append("conversation", "resolution-message", MessageRole.ASSISTANT, 1, 2)
        insertExperience("creation-experience", listOf("creation-message"))
        insertExperience("resolution-experience", listOf("resolution-message"))
        insertOpenLoop(
            id = "loop",
            creationExperienceId = "creation-experience",
            resolutionExperienceId = "resolution-experience",
            state = OpenLoopState.COMPLETED,
            closedAt = 2,
        )
        listOf(OpenLoopState.ACTIVE, OpenLoopState.WAITING).forEachIndexed { index, priorState ->
            database.openLoopDao().insertAuditHistory(
                OpenLoopAuditHistoryEntity(
                    id = "resolution-audit-$index",
                    openLoopId = "loop",
                    action = OpenLoopAuditAction.STATE_CHANGED,
                    triggeringExperienceId = "resolution-experience",
                    fromState = priorState,
                    toState = OpenLoopState.COMPLETED,
                    occurredAt = 2,
                ),
            )
        }

        val error = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "resolution-message", 2, occurredAt = 3),
            ),
        )

        assertEquals(SafeDeleteError.AmbiguousOpenLoopRollback("loop", "resolution-experience"), error)
        assertNotNull(database.safeDeleteDao().message("resolution-message"))
        assertNotNull(database.safeDeleteDao().experience("resolution-experience"))
        val loop = database.safeDeleteDao().openLoop("loop")!!
        assertEquals(OpenLoopState.COMPLETED, loop.state)
        assertEquals("resolution-experience", loop.resolutionExperienceId)
        assertEquals(2, database.safeDeleteDao().openLoopAuditsForExperience("loop", "resolution-experience").size)
        assertEquals("resolution-message", database.safeDeleteDao().timelineHead("conversation")?.activeHeadMessageId)
        assertEquals(2L, database.safeDeleteDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun activeHeadDeleteRefusesCrossConversationParentWithoutRepairingCorruptEdge() = runBlocking {
        createConversation("conversation-a")
        append("conversation-a", "child-a", MessageRole.ASSISTANT, 0, 1)
        createConversation("conversation-b")
        append("conversation-b", "parent-b", MessageRole.USER, 0, 1)
        database.conversationTimelineDao().insertParentEdge(
            MessageParentEdgeEntity(
                childMessageId = "child-a",
                parentMessageId = "parent-b",
                createdAt = 2,
            ),
        )

        val error = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation-a", "child-a", 1, occurredAt = 3),
            ),
        )

        assertEquals(
            SafeDeleteError.ParentBelongsToDifferentConversation(
                childMessageId = "child-a",
                parentMessageId = "parent-b",
                expectedConversationId = "conversation-a",
                actualConversationId = "conversation-b",
            ),
            error,
        )
        assertEquals("child-a", database.safeDeleteDao().timelineHead("conversation-a")?.activeHeadMessageId)
        assertEquals(1L, database.safeDeleteDao().timelineHead("conversation-a")?.timelineRevision)
        assertNotNull(database.safeDeleteDao().message("child-a"))
        assertEquals("parent-b", database.safeDeleteDao().parentEdge("child-a")?.parentMessageId)
        assertNotNull(database.safeDeleteDao().message("parent-b"))
    }

    @Test
    fun multiSourceExperienceWithSemanticDependentRefusesAmbiguousDeletion() = runBlocking {
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1)
        append("conversation", "a1", MessageRole.ASSISTANT, 1, 2)
        regenerate("conversation", "a1", "a1b", 2, 3)
        insertExperience("experience", listOf("a1", "a1b"), "combined content")
        insertMemory("memory", "meaning", listOf("experience" to "lineage"))

        val error = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "a1", 3, occurredAt = 4),
            ),
        )

        assertEquals(SafeDeleteError.AmbiguousProvenanceForDeletion("a1", "experience"), error)
        assertNotNull(database.safeDeleteDao().message("a1"))
        assertEquals(2, database.safeDeleteDao().messageSourcesForExperience("experience").size)
        assertNotNull(database.safeDeleteDao().memory("memory"))
        assertEquals("a1b", database.safeDeleteDao().timelineHead("conversation")?.activeHeadMessageId)
        assertEquals(3L, database.safeDeleteDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun multiSourceExperienceWithoutSemanticDependentsDropsLinkAndClearsContent() = runBlocking {
        createConversation("conversation")
        append("conversation", "u1", MessageRole.USER, 0, 1)
        append("conversation", "a1", MessageRole.ASSISTANT, 1, 2)
        regenerate("conversation", "a1", "a1b", 2, 3)
        insertExperience("experience", listOf("a1", "a1b"), "combined source text")
        insertArtifact("experience-artifact", experienceIds = listOf("experience"))

        assertTimelineDeleted(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "a1", 3, occurredAt = 4),
            ),
        )

        assertNull(database.safeDeleteDao().message("a1"))
        val experience = database.safeDeleteDao().experience("experience")!!
        assertNull(experience.sourceContent)
        assertEquals(
            listOf("a1b"),
            database.safeDeleteDao().messageSourcesForExperience("experience").map { it.messageId },
        )
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("experience-artifact")?.state)
        assertEquals(1, database.maintenanceDao().experienceDependencyCount("experience-artifact"))
    }

    @Test
    fun controlledMidTransactionFailureRollsBackEveryDeletionSurface() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1)
        insertExperience("experience", listOf("message"), "source content")
        insertCandidate("candidate", listOf("experience" to CandidateEvidenceRole.SEED))
        insertMemory("memory", "meaning", listOf("experience" to "lineage"))
        insertOpenLoop("loop", "experience")
        insertArtifact(
            "artifact",
            memoryIds = listOf("memory"),
            experienceIds = listOf("experience"),
            messageIds = listOf("message"),
            openLoopIds = listOf("loop"),
        )
        val failingService = newDeleteService { operation ->
            if (operation == SafeDeleteOperation.DELETE_TIMELINE_MESSAGE) error("controlled failure")
        }

        val error = assertTimelineFailure(
            failingService.deleteLeafMessage(
                DeleteTimelineMessageInput("conversation", "message", 1, occurredAt = 2),
            ),
        )

        assertTrue(error is SafeDeleteError.StorageFailure)
        assertNotNull(database.safeDeleteDao().message("message"))
        assertEquals("message", database.safeDeleteDao().timelineHead("conversation")?.activeHeadMessageId)
        assertEquals(1L, database.safeDeleteDao().timelineHead("conversation")?.timelineRevision)
        assertNotNull(database.safeDeleteDao().experience("experience"))
        assertEquals(1, database.safeDeleteDao().candidateEvidenceForCandidate("candidate").size)
        assertEquals(1, database.safeDeleteDao().evidenceForMemory("memory").size)
        assertNotNull(database.safeDeleteDao().openLoop("loop"))
        assertEquals(DerivedArtifactState.CURRENT, database.maintenanceDao().derivedArtifact("artifact")?.state)
        assertEquals(1, database.maintenanceDao().messageDependencyCount("artifact"))
        assertEquals(1, database.maintenanceDao().experienceDependencyCount("artifact"))
        assertEquals(1, database.maintenanceDao().memoryDependencyCount("artifact"))
        assertTrue(database.safeDeleteDao().suppressionTombstones().isEmpty())
    }

    @Test
    fun controlledHardMemoryDeleteFailureRollsBackSuppressionAndAllDependencies() = runBlocking {
        createConversation("conversation")
        append("conversation", "message", MessageRole.USER, 0, 1)
        insertExperience("experience", listOf("message"), "source content")
        insertMemory("memory", "private meaning", listOf("experience" to "lineage"))
        insertArtifact("memory-artifact", memoryIds = listOf("memory"))
        insertOpenLoop("loop", "experience", relatedMemoryId = "memory")
        database.maintenanceDao().insertMemoryAuditHistory(
            MemoryAuditHistoryEntity(
                id = "audit",
                memoryId = "memory",
                action = MemoryAuditAction.CREATED,
                triggeringExperienceId = "experience",
                occurredAt = 1,
            ),
        )
        val failingService = newDeleteService { operation ->
            if (operation == SafeDeleteOperation.DELETE_MEMORY) error("controlled failure")
        }

        val error = assertMemoryFailure(
            failingService.deleteMemory(DeleteMemoryInput("memory", occurredAt = 2)),
        )

        assertTrue(error is SafeDeleteError.StorageFailure)
        assertNotNull(database.safeDeleteDao().memory("memory"))
        assertEquals(1, database.safeDeleteDao().evidenceForMemory("memory").size)
        assertEquals(1, database.safeDeleteDao().memoryAuditsForMemory("memory").size)
        assertEquals("memory", database.safeDeleteDao().openLoop("loop")?.relatedMemoryId)
        assertEquals(DerivedArtifactState.CURRENT, database.maintenanceDao().derivedArtifact("memory-artifact")?.state)
        assertEquals(1, database.maintenanceDao().memoryDependencyCount("memory-artifact"))
        assertTrue(database.safeDeleteDao().suppressionTombstones().isEmpty())
        assertTrue(database.safeDeleteDao().repairJobs("MEMORY", "memory").isEmpty())
    }

    @Test
    fun typedMissingAndOwnershipErrorsDoNotMutateTimeline() = runBlocking {
        val missingConversation = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("missing", "message", 0, occurredAt = 1),
            ),
        )
        assertEquals(SafeDeleteError.MissingConversation("missing"), missingConversation)

        database.conversationDao().insertConversation(
            ConversationEntity(
                id = "missing-head",
                createdAt = 1,
                updatedAt = 1,
                status = ConversationStatus.ACTIVE,
            ),
        )
        val missingHead = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("missing-head", "message", 0, occurredAt = 1),
            ),
        )
        assertEquals(SafeDeleteError.MissingTimelineHeadRecord("missing-head"), missingHead)

        createConversation("one")
        val missingMessage = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("one", "missing-message", 0, occurredAt = 1),
            ),
        )
        assertEquals(SafeDeleteError.MissingMessage("missing-message"), missingMessage)
        append("one", "message-one", MessageRole.USER, 0, 1)
        createConversation("two")
        append("two", "message-two", MessageRole.USER, 0, 1)
        val wrongConversation = assertTimelineFailure(
            deleteService.deleteLeafMessage(
                DeleteTimelineMessageInput("one", "message-two", 1, occurredAt = 2),
            ),
        )
        assertEquals(
            SafeDeleteError.MessageBelongsToDifferentConversation("message-two", "one", "two"),
            wrongConversation,
        )
        assertNotNull(database.safeDeleteDao().message("message-two"))

        val missingMemory = assertMemoryFailure(
            deleteService.deleteMemory(DeleteMemoryInput("missing-memory", occurredAt = 2)),
        )
        assertEquals(SafeDeleteError.MissingMemory("missing-memory"), missingMemory)
    }

    private fun newDeleteService(
        hook: (SafeDeleteOperation) -> Unit = {},
    ): SafeDeleteService = SafeDeleteService(
        database = database,
        idGenerator = SafeDeleteIdGenerator { "delete-id-${generatedId++}" },
        afterDependencyMutation = hook,
    )

    private suspend fun createConversation(conversationId: String) {
        val result = timelineService.createConversationWithTimeline(
            CreateTimelineConversationInput(
                conversationId = conversationId,
                createdAt = 0,
                updatedAt = 0,
                status = ConversationStatus.ACTIVE,
            ),
        )
        assertTrue(result is TimelineWriteResult.ConversationCreated)
    }

    private suspend fun append(
        conversationId: String,
        messageId: String,
        role: MessageRole,
        expectedRevision: Long,
        occurredAt: Long,
    ) {
        val result = timelineService.appendMessage(
            AppendTimelineMessageInput(
                conversationId = conversationId,
                message = NewTimelineMessageInput(
                    messageId = messageId,
                    role = role,
                    deliveryState = MessageDeliveryState.PERSISTED,
                    content = "content-$messageId",
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                ),
                expectedTimelineRevision = expectedRevision,
                occurredAt = occurredAt,
            ),
        )
        assertTrue(result is TimelineWriteResult.MessageAppended)
    }

    private suspend fun regenerate(
        conversationId: String,
        originalMessageId: String,
        replacementMessageId: String,
        expectedRevision: Long,
        occurredAt: Long,
    ) {
        val result = timelineService.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                conversationId = conversationId,
                originalMessageId = originalMessageId,
                replacement = NewTimelineMessageInput(
                    messageId = replacementMessageId,
                    role = MessageRole.ASSISTANT,
                    deliveryState = MessageDeliveryState.PERSISTED,
                    content = "content-$replacementMessageId",
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                ),
                expectedTimelineRevision = expectedRevision,
                occurredAt = occurredAt,
            ),
        )
        assertTrue(result is TimelineWriteResult.AssistantRegenerated)
    }

    private fun insertExperience(
        experienceId: String,
        messageIds: List<String>,
        sourceContent: String? = null,
    ) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = experienceId,
                eventOrder = ++eventOrder,
                experienceType = ExperienceType.CONVERSATION_MESSAGE,
                actor = ExperienceActor.SHAI,
                sourceContent = sourceContent,
                occurredAt = eventOrder,
                recordedAt = eventOrder,
                sensitivity = SensitivityLevel.STANDARD,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
        messageIds.forEachIndexed { index, messageId ->
            database.memoryDao().insertExperienceMessageSource(
                ExperienceMessageSourceEntity(
                    experienceId = experienceId,
                    messageId = messageId,
                    sourceOrder = index,
                    sourceRole = if (index == 0) {
                        ExperienceMessageSourceRole.PRIMARY
                    } else {
                        ExperienceMessageSourceRole.SUPPORTING
                    },
                    createdAt = eventOrder,
                ),
            )
        }
    }

    private fun insertMemory(
        memoryId: String,
        meaning: String,
        evidence: List<Pair<String, String>>,
    ) {
        database.memoryDao().insertMemory(
            MemoryEntity(
                id = memoryId,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = meaning,
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
        evidence.forEach { (experienceId, lineageKey) ->
            database.memoryDao().insertMemoryEvidence(
                MemoryEvidenceEntity(
                    memoryId = memoryId,
                    experienceId = experienceId,
                    role = EvidenceRole.SUPPORTS,
                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                    sourceCertainty = MemoryCertainty.CERTAIN,
                    lineageKey = lineageKey,
                    createdAt = 1,
                ),
            )
        }
    }

    private fun insertCandidate(
        candidateId: String,
        evidence: List<Pair<String, CandidateEvidenceRole>>,
        state: CandidateMemoryState = CandidateMemoryState.TENTATIVE,
    ) {
        database.memoryDao().insertCandidateMemory(
            CandidateMemoryEntity(
                id = candidateId,
                proposedKind = MemoryKind.SEMANTIC,
                proposedScope = MemoryScope.SHAI,
                proposedMeaning = "candidate meaning",
                proposedEpistemicBasis = EpistemicBasis.INFERENCE,
                proposedCertainty = MemoryCertainty.PROBABLE,
                state = state,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        evidence.forEachIndexed { index, (experienceId, role) ->
            database.memoryDao().insertCandidateMemoryEvidence(
                CandidateMemoryEvidenceEntity(
                    candidateMemoryId = candidateId,
                    experienceId = experienceId,
                    evidenceOrder = index,
                    role = role,
                    lineageKey = "candidate-lineage-$index",
                    createdAt = 1,
                ),
            )
        }
    }

    private fun insertOpenLoop(
        id: String,
        creationExperienceId: String,
        relatedMemoryId: String? = null,
        resolutionExperienceId: String? = null,
        state: OpenLoopState = OpenLoopState.ACTIVE,
        dueAt: Long? = null,
        closedAt: Long? = null,
        title: String = "loop title",
    ) {
        database.openLoopDao().insertOpenLoop(
            OpenLoopEntity(
                id = id,
                creationExperienceId = creationExperienceId,
                resolutionExperienceId = resolutionExperienceId,
                relatedMemoryId = relatedMemoryId,
                title = title,
                state = state,
                openedAt = 1,
                dueAt = dueAt,
                closedAt = closedAt,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun insertArtifact(
        artifactId: String,
        memoryIds: List<String> = emptyList(),
        experienceIds: List<String> = emptyList(),
        messageIds: List<String> = emptyList(),
        openLoopIds: List<String> = emptyList(),
    ) {
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
        memoryIds.forEach { memoryId ->
            database.maintenanceDao().insertDerivedArtifactMemoryDependency(
                DerivedArtifactMemoryDependencyEntity(artifactId, memoryId, 1),
            )
        }
        experienceIds.forEach { experienceId ->
            database.maintenanceDao().insertDerivedArtifactExperienceDependency(
                DerivedArtifactExperienceDependencyEntity(artifactId, experienceId, 1),
            )
        }
        messageIds.forEach { messageId ->
            database.maintenanceDao().insertDerivedArtifactMessageDependency(
                DerivedArtifactMessageDependencyEntity(artifactId, messageId, 1),
            )
        }
        openLoopIds.forEach { openLoopId ->
            database.maintenanceDao().insertDerivedArtifactOpenLoopDependency(
                DerivedArtifactOpenLoopDependencyEntity(artifactId, openLoopId, 1),
            )
        }
    }

    private fun insertEntity(entityId: String) {
        database.memoryDao().insertEntity(
            KnownEntityEntity(
                id = entityId,
                kind = EntityKind.PERSON,
                displayName = entityId,
                normalizedName = entityId,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun insertEntityAndMemoryLink(entityId: String, memoryId: String) {
        insertEntity(entityId)
        database.memoryDao().insertMemoryEntityLink(
            MemoryEntityLinkEntity(memoryId, entityId, EntityLinkRole.ABOUT, createdAt = 1),
        )
    }

    private suspend fun activeIds(conversationId: String): List<String> {
        val result = timelineService.activeTimeline(conversationId)
        check(result is TimelineReadResult.Success)
        return result.messages.map { it.id }
    }

    private fun lineageHash(experienceId: String, lineageKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$experienceId:$lineageKey".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun assertMemoryDeleted(result: MemoryDeleteResult): MemoryDeleteResult.Deleted {
        assertTrue("Expected memory deletion, got $result", result is MemoryDeleteResult.Deleted)
        return result as MemoryDeleteResult.Deleted
    }

    private fun assertMemoryFailure(result: MemoryDeleteResult): SafeDeleteError {
        assertTrue("Expected memory deletion failure, got $result", result is MemoryDeleteResult.Failure)
        return (result as MemoryDeleteResult.Failure).error
    }

    private fun assertTimelineDeleted(result: TimelineDeleteResult): TimelineDeleteResult.Deleted {
        assertTrue("Expected timeline deletion, got $result", result is TimelineDeleteResult.Deleted)
        return result as TimelineDeleteResult.Deleted
    }

    private fun assertTimelineFailure(result: TimelineDeleteResult): SafeDeleteError {
        assertTrue("Expected timeline deletion failure, got $result", result is TimelineDeleteResult.Failure)
        return (result as TimelineDeleteResult.Failure).error
    }
}
