package com.shai.riven.data.context

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntityLinkEntity
import com.shai.riven.data.persistence.model.EntityKind
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.SensitivityLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenLoopContextSourceTest {
    private lateinit var database: RivenDatabase
    private var clock = 1L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun onlyDeclaredActiveLifecycleStatesCanEnterContext() = runBlocking {
        OpenLoopState.entries.forEach { state -> insertLoop(state.name.lowercase(), "plan garden", state) }

        val result = success(OpenLoopContextSource(database).read(request("garden")))

        assertEquals(
            setOf("planned", "active", "waiting", "blocked"),
            result.payloads.mapTo(mutableSetOf()) { it.fragmentId },
        )
        assertFalse(result.payloads.any { it.content.contains("COMPLETED") || it.content.contains("EXPIRED") })
    }

    @Test
    fun sensitiveLoopRequiresDirectGroundedLoopIdNotSimilarityOrPersonEntity() = runBlocking {
        insertEntity("rowan")
        insertLoop("medical", "Discuss Rowan medical result", OpenLoopState.ACTIVE, SensitivityLevel.SENSITIVE)
        database.openLoopDao().insertEntityLink(
            OpenLoopEntityLinkEntity("medical", "rowan", EntityLinkRole.ABOUT, clock++),
        )
        val source = OpenLoopContextSource(database)

        val ambiguous = success(
            source.read(
                request(
                    "Rowan medical result",
                    RivenGroundedRecallCues(groundedEntityIds = setOf("rowan")),
                ),
            ),
        )
        val direct = success(
            source.read(
                request(
                    "medical result",
                    RivenGroundedRecallCues(directlyRelevantOpenLoopIds = setOf("medical")),
                ),
            ),
        )

        assertTrue(ambiguous.payloads.isEmpty())
        assertEquals(listOf("medical"), direct.payloads.map { it.fragmentId })
    }

    @Test
    fun dueDateBoostsOnlyAlreadyRelevantLoop() = runBlocking {
        insertLoop("irrelevant-overdue", "renew passport", OpenLoopState.ACTIVE, dueAt = 50)
        insertLoop("relevant-later", "buy garden soil", OpenLoopState.ACTIVE, dueAt = 500)

        val result = success(OpenLoopContextSource(database).read(request("garden soil", now = 100)))

        assertEquals(listOf("relevant-later"), result.payloads.map { it.fragmentId })
    }

    @Test
    fun deletedCreationExperienceAndUnpopulatedFeatureProduceValidEmptyResult() = runBlocking {
        insertLoop(
            "deleted-source",
            "garden task",
            OpenLoopState.ACTIVE,
            experienceAvailability = ExperienceAvailability.DELETED,
        )

        val result = success(OpenLoopContextSource(database).read(request("garden")))

        assertTrue(result.payloads.isEmpty())
    }

    @Test
    fun candidateCapacityFailureIsTypedInsteadOfReturningPartialHistory() = runBlocking {
        insertLoop("one", "topic one", OpenLoopState.ACTIVE)
        insertLoop("two", "topic two", OpenLoopState.ACTIVE)

        val result = OpenLoopContextSource(database, limits = OpenLoopContextLimits(maxCandidates = 1))
            .read(request("topic"))

        assertTrue(result is RivenContextSourceResult.Failure)
        val error = (result as RivenContextSourceResult.Failure).error as RivenContextSourceError.ReadFailure
        assertEquals("OpenLoopRecall.CAPACITY_EXCEEDED", error.errorType)
    }

    @Test
    fun unresolvedDirectCueCannotMaskOrdinaryCandidateOverflow() = runBlocking {
        insertLoop("garden-a", "plan garden beds", OpenLoopState.ACTIVE)
        insertLoop("garden-b", "buy garden soil", OpenLoopState.ACTIVE)

        val result = OpenLoopContextSource(database, limits = OpenLoopContextLimits(maxCandidates = 1))
            .read(
                request(
                    "garden",
                    RivenGroundedRecallCues(directlyRelevantOpenLoopIds = setOf("missing-direct")),
                ),
            )

        assertTrue(result is RivenContextSourceResult.Failure)
        val error = (result as RivenContextSourceResult.Failure).error as RivenContextSourceError.ReadFailure
        assertEquals("OpenLoopRecall.CAPACITY_EXCEEDED", error.errorType)
    }

    @Test
    fun oversizedGroundedCueSetsAndIdsFailBeforeDatabaseReads() = runBlocking {
        val source = OpenLoopContextSource(
            database,
            limits = OpenLoopContextLimits(maxGroundedEntityIds = 1, maxGroundedIdChars = 8),
        )

        val tooManyEntities = source.read(
            request("topic", RivenGroundedRecallCues(groundedEntityIds = setOf("one", "two"))),
        )
        val oversizedDirectId = source.read(
            request("topic", RivenGroundedRecallCues(directlyRelevantOpenLoopIds = setOf("too-long-id"))),
        )

        listOf(tooManyEntities, oversizedDirectId).forEach { result ->
            assertTrue(result is RivenContextSourceResult.Failure)
            val error = (result as RivenContextSourceResult.Failure).error as RivenContextSourceError.ReadFailure
            assertEquals("OpenLoopRecall.BUDGET_EXCEEDED", error.errorType)
        }
    }

    private fun insertLoop(
        id: String,
        title: String,
        state: OpenLoopState,
        sensitivity: SensitivityLevel = SensitivityLevel.STANDARD,
        dueAt: Long? = null,
        experienceAvailability: ExperienceAvailability = ExperienceAvailability.AVAILABLE,
    ) {
        val experienceId = "experience-$id"
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = experienceId,
                eventOrder = clock++,
                experienceType = ExperienceType.SHARED_EVENT,
                actor = ExperienceActor.SHAI,
                sourceContent = title,
                occurredAt = clock,
                recordedAt = clock,
                sensitivity = sensitivity,
                availability = experienceAvailability,
            ),
        )
        database.openLoopDao().insertOpenLoop(
            OpenLoopEntity(
                id = id,
                creationExperienceId = experienceId,
                title = title,
                state = state,
                openedAt = clock,
                dueAt = dueAt,
                closedAt = if (state in CLOSED_STATES) clock else null,
                sensitivity = sensitivity,
                createdAt = clock,
                updatedAt = clock++,
            ),
        )
    }

    private fun insertEntity(id: String) {
        database.memoryDao().insertEntity(
            KnownEntityEntity(id, EntityKind.PERSON, id, id, clock, clock),
        )
    }

    private fun request(
        interaction: String,
        cues: RivenGroundedRecallCues = RivenGroundedRecallCues(),
        now: Long = 100,
    ) = RivenContextReadRequest(
        now = now,
        conversation = RivenConversationContextRequest(
            conversationId = "conversation",
            expectedTimelineRevision = 1,
            currentInteraction = RivenCurrentInteraction(content = interaction),
            recallCues = cues,
        ),
    )

    private fun success(result: RivenContextSourceResult): RivenContextSourceResult.Success {
        assertTrue("Expected success, got $result", result is RivenContextSourceResult.Success)
        return result as RivenContextSourceResult.Success
    }

    private companion object {
        val CLOSED_STATES = setOf(OpenLoopState.COMPLETED, OpenLoopState.ABANDONED, OpenLoopState.EXPIRED)
    }
}
