package com.shai.riven.data.validation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.deletion.DeleteMemoryInput
import com.shai.riven.data.deletion.MemoryDeleteResult
import com.shai.riven.data.deletion.SafeDeleteService
import com.shai.riven.data.memory.MemoryStateTransitionInput
import com.shai.riven.data.memory.MemoryTransactionService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.dao.ValidationRecallEntityRow
import com.shai.riven.data.persistence.dao.ValidationRecallEvidenceRow
import com.shai.riven.data.persistence.dao.ValidationRecallMemoryRow
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.EntityKind
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.TemporalState
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
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
@Config(sdk = [35])
class TargetedValidationMemoryRetrieverTest {
    private lateinit var database: RivenDatabase
    private val closeables = mutableListOf<AutoCloseable>()
    private var eventOrder = 1L

    @Before
    fun setUp() {
        database = newDatabase()
    }

    @After
    fun tearDown() {
        closeables.reversed().forEach { it.close() }
        database.close()
    }

    @Test
    fun independentSourceExactDuplicateIsRecalledWithoutEntities() = runBlocking {
        insertMemory("rain", "Shai likes rainy afternoons.", "source-old", "Older independent wording.")

        val result = retriever().retrieve(query("Shai likes rainy afternoons.", sources = listOf("source-new")))

        assertReady(result)
        assertEquals(listOf("rain"), result.memoryIds)
    }

    @Test
    fun overlappingNegationIsRecalledWithoutRewritingMeaning() = runBlocking {
        insertMemory("coffee", "Shai does not like bitter coffee.")

        val result = retriever().retrieve(query("Shai likes bitter coffee."))

        assertReady(result)
        assertEquals(listOf("coffee"), result.memoryIds)
    }

    @Test
    fun correctionAndHistoricalMemoryAreRecalledTogetherThroughOneHopRelation() = runBlocking {
        insertMemory(
            id = "kansas-old",
            meaning = "Shai grew up in Kansas.",
            truth = MemoryTruthState.CORRECTED_FALSE,
            lifecycle = MemoryLifecycleState.SUPERSEDED,
            temporal = TemporalState.HISTORICAL,
        )
        insertMemory("brooklyn-current", "Shai grew up in Brooklyn and lives in Kansas now.")
        insertRelationship("brooklyn-current", "kansas-old", MemoryRelationshipType.CORRECTS)

        val result = retriever().retrieve(query("Shai grew up in Brooklyn."))

        assertReady(result)
        assertTrue("brooklyn-current" in result.memoryIds)
        assertTrue("kansas-old" in result.memoryIds)
    }

    @Test
    fun noOverlapParaphraseLimitationReturnsNoFalseSemanticMatch() = runBlocking {
        insertMemory("rain", "Shai enjoys rainy afternoons.")

        val result = retriever().retrieve(query("Wet weather makes her happy."))

        assertReady(result)
        assertTrue(result.memoryIds.isEmpty())
    }

    @Test
    fun unicodeAndPunctuationNormalizeDeterministicallyWhileNegationSurvives() = runBlocking {
        insertMemory("cafe", "Shai NEVER skips café-night—ever!")

        val first = retriever().retrieve(query("shai never skips CAFE night"))
        val second = retriever().retrieve(query("SHAI, never skips café night."))

        assertReady(first)
        assertEquals(listOf("cafe"), first.memoryIds)
        assertEquals(first.memoryIds, second.memoryIds)
    }

    @Test
    fun emptyAndStopwordOnlyQueriesFailClosedAsUnselective() = runBlocking {
        insertMemory("rain", "Shai likes rain.")
        val recall = retriever()

        assertEquals(ValidationRecallReadiness.QUERY_UNSELECTIVE, recall.retrieve(query(" ")).readiness)
        assertEquals(
            ValidationRecallReadiness.QUERY_UNSELECTIVE,
            recall.retrieve(query("the and is with a")).readiness,
        )
    }

    @Test
    fun exactGroundedEntityProvidesStructuralRecallWithoutNameTokenGuessing() = runBlocking {
        insertEntity("entity-rain", "Rain")
        insertMemory("rain", "Afternoon weather preference.", entityId = "entity-rain")

        val result = retriever().retrieve(query("Completely different words.", entities = setOf("entity-rain")))

        assertReady(result)
        assertEquals(listOf("rain"), result.memoryIds)
    }

    @Test
    fun sensitiveMemoryRequiresExactSourceOrExplicitRelation() = runBlocking {
        insertMemory("sensitive", "Private medical detail alpha.", sensitivity = SensitivityLevel.SENSITIVE)
        val recall = retriever()

        val lexical = recall.retrieve(query("Private medical detail alpha."))
        val provenance = recall.retrieve(query("Unrelated safe words.", sources = listOf("exp-sensitive")))

        assertReady(lexical)
        assertFalse("sensitive" in lexical.memoryIds)
        assertReady(provenance)
        assertEquals(listOf("sensitive"), provenance.memoryIds)
    }

    @Test
    fun forgottenMeaningIsNotBroadlyIndexedButExplicitRelationCanSurfaceItsId() = runBlocking {
        insertMemory("forgotten", "Shai once preferred sardines.", retention = MemoryRetentionState.FORGOTTEN)
        insertMemory("current", "Shai currently avoids sardines.")
        insertRelationship("current", "forgotten", MemoryRelationshipType.SUPERSEDES)
        val recall = retriever()

        val broad = recall.retrieve(query("once preferred"))
        val related = recall.retrieve(query("currently avoids sardines"))

        assertReady(broad)
        assertFalse("forgotten" in broad.memoryIds)
        assertReady(related)
        assertTrue("forgotten" in related.memoryIds)
    }

    @Test
    fun graphTraversalIsOneHopAndCycleSafe() = runBlocking {
        insertMemory("a", "alpha anchor")
        insertMemory("b", "bravo only")
        insertMemory("c", "charlie only")
        insertRelationship("a", "b", MemoryRelationshipType.REFINES)
        insertRelationship("b", "c", MemoryRelationshipType.CORRECTS)
        insertRelationship("c", "b", MemoryRelationshipType.CONTRADICTS)

        val result = retriever().retrieve(query("alpha anchor"))

        assertReady(result)
        assertTrue("a" in result.memoryIds)
        assertTrue("b" in result.memoryIds)
        assertFalse("c" in result.memoryIds)
        assertTrue(result.budgetUsage.relationshipRowsVisited <= 3)
    }

    @Test
    fun unsupportedRelationshipDoesNotExpandCandidatePool() = runBlocking {
        insertMemory("a", "alpha anchor")
        insertMemory("b", "bravo only")
        insertRelationship("a", "b", MemoryRelationshipType.RELATED_TO)

        val result = retriever().retrieve(query("alpha anchor"))

        assertReady(result)
        assertEquals(listOf("a"), result.memoryIds)
    }

    @Test
    fun densePostingBudgetFailsClosedDeterministically() = runBlocking {
        repeat(8) { insertMemory("memory-$it", "shared dense token $it") }
        val recall = retriever(TargetedValidationRecallLimits(maxPostingVisits = 3))

        val first = recall.retrieve(query("shared dense token"))
        val second = recall.retrieve(query("shared dense token"))

        assertEquals(ValidationRecallReadiness.BUDGET_EXCEEDED, first.readiness)
        assertEquals(first, second)
    }

    @Test
    fun denseEntityBudgetFailsClosedBeforeUnboundedWork() = runBlocking {
        insertEntity("shared-entity", "Shared")
        repeat(6) { insertMemory("memory-$it", "meaning $it", entityId = "shared-entity") }
        val recall = retriever(TargetedValidationRecallLimits(maxStructuralVisits = 2))

        val result = recall.retrieve(query("different words", entities = setOf("shared-entity")))

        assertEquals(ValidationRecallReadiness.BUDGET_EXCEEDED, result.readiness)
        assertTrue(result.memoryIds.isEmpty())
    }

    @Test
    fun resultIsDeterministicUniqueAndCappedAtTwentyFour() = runBlocking {
        repeat(30) { insertMemory("memory-${it.toString().padStart(2, '0')}", "shared bounded recall") }
        val result = retriever(
            TargetedValidationRecallLimits(
                maxPostingVisits = 1_000,
                maxCandidatePool = 64,
            ),
        ).retrieve(query("shared bounded recall"))

        assertReady(result)
        assertEquals(24, result.memoryIds.size)
        assertEquals(result.memoryIds.distinct(), result.memoryIds)
        assertEquals(result.memoryIds.sorted(), result.memoryIds)
    }

    @Test
    fun corpusMemoryCapacityFailsClosedWithoutPublishingPartialIndex() = runBlocking {
        repeat(3) { insertMemory("memory-$it", "bounded $it") }
        val recall = retriever(TargetedValidationRecallLimits(pageSize = 2, maxMemories = 2))

        val result = recall.retrieve(query("bounded"))

        assertEquals(ValidationRecallReadiness.CAPACITY_EXCEEDED, result.readiness)
        assertTrue(result.memoryIds.isEmpty())
    }

    @Test
    fun oversizedSourceFailsClosedRatherThanIndexingATruncatedMeaning() = runBlocking {
        insertMemory("large", "small meaning", sourceContent = "x".repeat(40))
        val recall = retriever(TargetedValidationRecallLimits(maxSourceChars = 16))

        assertEquals(ValidationRecallReadiness.CAPACITY_EXCEEDED, recall.retrieve(query("small")).readiness)
    }

    @Test
    fun storageFailureDoesNotPublishAnEmptyReadyIndex() = runBlocking {
        val recall = TargetedValidationMemoryRetriever(
            database,
            Dispatchers.Unconfined,
            TargetedValidationRecallLimits(),
            FailingReader,
        ).also(closeables::add)

        val result = recall.retrieve(query("private raw candidate content"))
        val retry = recall.retrieve(query("private raw candidate content"))

        assertEquals(ValidationRecallReadiness.FAILED, result.readiness)
        assertEquals(ValidationRecallReadiness.FAILED, retry.readiness)
        assertTrue(result.memoryIds.isEmpty())
        assertFalse(result.toString().contains("private raw candidate content"))
    }

    @Test
    fun cancelledRetrievalPropagatesCancellation() {
        insertMemory("rain", "rain memory")
        val recall = retriever()
        val cancelledJob = Job().apply { cancel() }
        var cancelled = false

        try {
            runBlocking(EmptyCoroutineContext + cancelledJob) { recall.retrieve(query("rain")) }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

    @Test
    fun canonicalWriterInvalidatesAndRebuildsCompleteIndex() = runBlocking {
        insertMemory("first", "first topic")
        val recall = retriever()
        assertReady(recall.retrieve(query("first topic")))
        insertMemory("second", "zebraquasar")
        database.validationRecallCorpusFence().markCanonicalMutation()

        val result = recall.retrieve(query("zebraquasar"))

        assertReady(result)
        assertEquals(listOf("second"), result.memoryIds)
    }

    @Test
    fun forgetRemovesBroadAccessBeforeOperationCompletes() = runBlocking {
        insertMemory("rain", "Shai likes rain.")
        val recall = retriever()
        assertEquals(listOf("rain"), recall.retrieve(query("likes rain")).memoryIds)
        val before = database.validationRecallCorpusFence().snapshot()

        val result = MemoryTransactionService(database).forget(MemoryStateTransitionInput("rain", 100))

        assertTrue(result is com.shai.riven.data.memory.MemoryWriteResult.Success)
        assertEquals(before.corpusGeneration + 1L, database.validationRecallCorpusFence().snapshot().corpusGeneration)
        val after = recall.retrieve(query("likes rain"))
        assertReady(after)
        assertTrue(after.memoryIds.isEmpty())
    }

    @Test
    fun deleteRemovesContentAndReferencesBeforeOperationCompletes() = runBlocking {
        insertMemory("rain", "Shai likes rain.")
        val recall = retriever()
        assertEquals(listOf("rain"), recall.retrieve(query("likes rain")).memoryIds)
        val before = database.validationRecallCorpusFence().snapshot()

        val result = SafeDeleteService(database).deleteMemory(DeleteMemoryInput("rain", 100))

        assertTrue(result is MemoryDeleteResult.Deleted)
        assertEquals(before.corpusGeneration + 1L, database.validationRecallCorpusFence().snapshot().corpusGeneration)
        val after = recall.retrieve(query("likes rain"))
        assertReady(after)
        assertTrue(after.memoryIds.isEmpty())
    }

    @Test
    fun resetRestoreOrRelaunchDatabaseReplacementGetsNewSession() {
        val first = database.validationRecallCorpusFence().snapshot()
        val replacement = newDatabase()
        try {
            val second = replacement.validationRecallCorpusFence().snapshot()
            assertNotEquals(first.databaseSessionId, second.databaseSessionId)
            assertNotEquals(first, second)
        } finally {
            replacement.close()
        }
    }

    @Test
    fun closingRetrieverDiscardsPublishedProcessLocalIndex() = runBlocking {
        insertMemory("rain", "Shai likes rain.")
        val recall = retriever()
        assertReady(recall.retrieve(query("likes rain")))

        recall.close()

        val afterClose = recall.retrieve(query("likes rain"))
        assertEquals(ValidationRecallReadiness.NOT_READY, afterClose.readiness)
        assertTrue(afterClose.memoryIds.isEmpty())
    }

    @Test
    fun boundedCorpusRoutesUseExistingIndexes() {
        val plans = listOf(
            queryPlan("SELECT * FROM memories WHERE memory_id > 'a' ORDER BY memory_id LIMIT 10"),
            queryPlan("SELECT * FROM memory_evidence WHERE memory_id IN ('a') ORDER BY memory_id, experience_id LIMIT 10"),
            queryPlan("SELECT * FROM memory_entity_links WHERE memory_id IN ('a') ORDER BY memory_id, entity_id, role LIMIT 10"),
            queryPlan("SELECT * FROM memory_relationships WHERE source_memory_id IN ('a') ORDER BY source_memory_id, target_memory_id, relationship_type LIMIT 10"),
            queryPlan("SELECT * FROM memory_relationships WHERE target_memory_id IN ('a') ORDER BY target_memory_id, source_memory_id, relationship_type LIMIT 10"),
        )

        assertTrue(plans.all { plan -> plan.any { "INDEX" in it.uppercase() } })
        assertTrue(plans.none { plan -> plan.any { it.uppercase().contains("SCAN MEMORIES") } })
    }

    private fun retriever(
        limits: TargetedValidationRecallLimits = TargetedValidationRecallLimits(),
    ): TargetedValidationMemoryRetriever = TargetedValidationMemoryRetriever(
        database,
        Dispatchers.Unconfined,
        limits,
    ).also(closeables::add)

    private fun query(
        meaning: String,
        entities: Set<String> = emptySet(),
        sources: List<String> = emptyList(),
    ) = ValidationMemoryQuery(
        candidateId = "candidate",
        proposedMeaning = meaning,
        proposedKind = MemoryKind.SEMANTIC,
        proposedScope = MemoryScope.SHAI,
        proposedEpistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
        proposedCertainty = MemoryCertainty.PROBABLE,
        sensitivity = SensitivityLevel.STANDARD,
        groundedEntityIds = entities,
        sourceExperienceIds = sources,
        seedAttention = ValidationAttentionSignals(
            AttentionOutcome.FORWARD_FOR_INTERPRETATION,
            1,
            emptySet(),
            emptySet(),
        ),
    )

    private fun insertMemory(
        id: String,
        meaning: String,
        sourceId: String = "exp-$id",
        sourceContent: String = meaning,
        sensitivity: SensitivityLevel = SensitivityLevel.STANDARD,
        retention: MemoryRetentionState = MemoryRetentionState.ACTIVE,
        truth: MemoryTruthState = MemoryTruthState.SUPPORTED,
        lifecycle: MemoryLifecycleState = MemoryLifecycleState.VALIDATED,
        temporal: TemporalState = TemporalState.CURRENT,
        entityId: String? = null,
    ) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = sourceId,
                eventOrder = eventOrder++,
                experienceType = ExperienceType.SHARED_EVENT,
                actor = ExperienceActor.SHAI,
                sourceContent = sourceContent,
                occurredAt = eventOrder,
                recordedAt = eventOrder,
                sensitivity = sensitivity,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
        database.memoryDao().insertMemory(
            MemoryEntity(
                id = id,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = meaning,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                certainty = MemoryCertainty.PROBABLE,
                truthState = truth,
                retentionState = retention,
                lifecycleState = lifecycle,
                temporalState = temporal,
                learnedAt = eventOrder,
                lastConfirmedAt = eventOrder,
                sensitivity = sensitivity,
                createdAt = eventOrder,
                updatedAt = eventOrder,
            ),
        )
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = id,
                experienceId = sourceId,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = MemoryCertainty.PROBABLE,
                lineageKey = "lineage-$id",
                createdAt = eventOrder,
            ),
        )
        entityId?.let {
            database.memoryDao().insertMemoryEntityLink(
                MemoryEntityLinkEntity(id, it, EntityLinkRole.ABOUT, eventOrder),
            )
        }
    }

    private fun insertEntity(id: String, name: String) {
        database.memoryDao().insertEntity(KnownEntityEntity(id, EntityKind.SUBJECT, name, name.lowercase(), 1, 1))
    }

    private fun insertRelationship(source: String, target: String, type: MemoryRelationshipType) {
        database.memoryDao().insertMemoryRelationship(
            MemoryRelationshipEntity(source, target, type, createdByExperienceId = null, createdAt = eventOrder++),
        )
    }

    private fun queryPlan(sql: String): List<String> = database.openHelper.readableDatabase
        .query("EXPLAIN QUERY PLAN $sql")
        .use { cursor ->
            val detail = cursor.getColumnIndexOrThrow("detail")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(detail))
            }
        }

    private fun assertReady(result: ValidationMemoryRetrieval) {
        assertEquals(ValidationRecallReadiness.READY, result.readiness)
        assertEquals(TARGETED_VALIDATION_RECALL_ALGORITHM_VERSION, result.generation?.algorithmVersion)
        assertTrue(result.memoryIds.size <= MAX_VALIDATION_RELATED_MEMORIES)
    }

    private fun newDatabase(): RivenDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private object FailingReader : ValidationRecallCorpusReader {
        override fun memoryPage(
            afterMemoryId: String,
            limit: Int,
            maxMeaningCharsPlusOne: Int,
        ): List<ValidationRecallMemoryRow> = error("SyntheticStorageFailure")

        override fun evidenceRows(
            memoryIds: List<String>,
            limit: Int,
            maxSourceCharsPlusOne: Int,
        ): List<ValidationRecallEvidenceRow> = error("unused")

        override fun memoryEntityRows(memoryIds: List<String>, limit: Int): List<ValidationRecallEntityRow> =
            error("unused")

        override fun outgoingRelationships(
            memoryIds: List<String>,
            limit: Int,
        ): List<MemoryRelationshipEntity> = error("unused")

        override fun incomingRelationships(
            memoryIds: List<String>,
            limit: Int,
        ): List<MemoryRelationshipEntity> = error("unused")
    }
}
