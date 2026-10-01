package com.shai.riven.data.validation

import androidx.room.InvalidationTracker
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.dao.MemoryDao
import com.shai.riven.data.persistence.dao.ValidationRecallEntityRow
import com.shai.riven.data.persistence.dao.ValidationRecallEvidenceRow
import com.shai.riven.data.persistence.dao.ValidationRecallMemoryRow
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.recall.BoundedRecallCapacityExceeded
import com.shai.riven.data.recall.BoundedRecallDocument
import com.shai.riven.data.recall.BoundedRecallIndex
import com.shai.riven.data.recall.BoundedRecallLimits
import com.shai.riven.data.recall.BoundedRecallPolicy
import com.shai.riven.data.recall.BoundedRecallQueryStatus
import com.shai.riven.data.recall.BoundedRecallRelationship
import com.shai.riven.data.recall.boundedRecallMeaningHash
import java.lang.ref.WeakReference
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hard limits for the process-local validation comparison index and each retrieval. Exceeding a
 * limit fails closed; it never silently turns a partial corpus or partial query into READY.
 */
data class TargetedValidationRecallLimits(
    val pageSize: Int = 128,
    val maxMemories: Int = 10_000,
    val maxMeaningChars: Int = 4_096,
    val maxSourceChars: Int = 8_192,
    val maxTextCharsPerDocument: Int = 16_384,
    val maxTokensPerDocument: Int = 512,
    val maxTokenChars: Int = 64,
    val maxCorpusTextChars: Long = 32L * 1_024L * 1_024L,
    val maxCorpusTokens: Int = 2_000_000,
    val maxCorpusPostings: Int = 500_000,
    val maxCorpusStructuralRows: Int = 200_000,
    val maxStructuralRowsPerPage: Int = 1_024,
    val maxIncrementalChangedMemories: Int = 64,
    val maxQueryChars: Int = 4_096,
    val maxQueryTerms: Int = 32,
    val maxQuerySourceIds: Int = 32,
    val maxQueryEntityIds: Int = 32,
    val maxPostingVisits: Int = 20_000,
    val maxStructuralVisits: Int = 2_048,
    val maxGraphSeeds: Int = 64,
    val maxRelationshipVisits: Int = 512,
    val maxCandidatePool: Int = 256,
    val maxFinalIds: Int = MAX_VALIDATION_RELATED_MEMORIES,
) {
    init {
        require(
            listOf(
                pageSize,
                maxMemories,
                maxMeaningChars,
                maxSourceChars,
                maxTextCharsPerDocument,
                maxTokensPerDocument,
                maxTokenChars,
                maxCorpusTokens,
                maxCorpusPostings,
                maxCorpusStructuralRows,
                maxStructuralRowsPerPage,
                maxIncrementalChangedMemories,
                maxQueryChars,
                maxQueryTerms,
                maxQuerySourceIds,
                maxQueryEntityIds,
                maxPostingVisits,
                maxStructuralVisits,
                maxGraphSeeds,
                maxRelationshipVisits,
                maxCandidatePool,
                maxFinalIds,
            ).all { it > 0 },
        )
        require(maxCorpusTextChars > 0L)
        require(maxFinalIds <= MAX_VALIDATION_RELATED_MEMORIES)
        require(maxCandidatePool >= maxFinalIds)
        require(maxTextCharsPerDocument >= maxMeaningChars)
        require(pageSize <= 256)
    }
}

internal data class ValidationRecallIncrementalWork(
    val changedMemories: Int,
    val incidentRelationshipsRemoved: Int,
    val adjacencyVerticesVisitedForRemoval: Int,
)

/**
 * Best-effort lexical and structural recall for validation comparison only.
 *
 * This component does not decide truth, hydrate canonical snapshots, call a provider, or provide
 * conversational recall. It is not semantic duplicate detection: a paraphrase with no shared token,
 * source, entity id, or supported relationship can be missed. It stores no persistent semantic copy.
 * Sensitive meanings are excluded
 * from broad lexical/entity matching and are reachable only through exact source provenance or an
 * explicit one-hop correction/refinement/supersession/contradiction relationship. Forgotten
 * meanings and evidence text are never indexed; only an explicit supported relationship may make
 * a forgotten memory id available for narrow canonical comparison.
 */
class TargetedValidationMemoryRetriever internal constructor(
    private val database: RivenDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val limits: TargetedValidationRecallLimits,
    private val reader: ValidationRecallCorpusReader,
    private val observeRoomInvalidations: Boolean = true,
) : ValidationMemoryRetriever, AutoCloseable {
    constructor(
        database: RivenDatabase,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        limits: TargetedValidationRecallLimits = TargetedValidationRecallLimits(),
    ) : this(database, dispatcher, limits, RoomValidationRecallCorpusReader(database.memoryDao()))

    private val fence = database.validationRecallCorpusFence()
    private val buildMutex = Mutex()
    private val stateLock = Any()
    private var publishedIndex: ValidationRecallIndex? = null
    private var lastIncrementalWork: ValidationRecallIncrementalWork? = null
    private var closed = false
    private val pendingMutations = mutableListOf<ValidationRecallCommittedMutation>()
    private var observerSawActiveMutation = false
    private val observerGenerationsToIgnore = mutableSetOf<ValidationRecallGeneration>()
    private val synchronousInvalidationListener: (ValidationRecallCommittedMutation) -> Unit = { mutation ->
        synchronized(stateLock) {
            if (!closed) {
                if (observerSawActiveMutation) {
                    observerSawActiveMutation = false
                } else {
                    observerGenerationsToIgnore += mutation.generation
                }
                when (mutation.change) {
                    is ValidationRecallCorpusChange.MemoryIdsChanged -> {
                        if (publishedIndex != null) pendingMutations += mutation
                    }
                    ValidationRecallCorpusChange.Unknown -> {
                        publishedIndex = null
                        pendingMutations.clear()
                    }
                }
            }
        }
    }

    private val invalidationObserver = RecallInvalidationObserver(
        this,
        "memories",
        "memory_evidence",
        "memory_relationships",
        "memory_entity_links",
    )

    init {
        fence.addInvalidationListener(synchronousInvalidationListener)
        if (observeRoomInvalidations) database.invalidationTracker.addObserver(invalidationObserver)
    }

    override suspend fun retrieve(query: ValidationMemoryQuery): ValidationMemoryRetrieval = withContext(dispatcher) {
        buildMutex.withLock {
            coroutineContext.ensureActive()
            if (isClosed()) return@withLock unavailable(ValidationRecallReadiness.NOT_READY)
            when (val outcome = readyIndexLocked(forceFullRebuild = false)) {
                is IndexBuildOutcome.Ready -> outcome.index.retrieve(query, limits)
                is IndexBuildOutcome.Unavailable -> unavailable(outcome.readiness, outcome.budgetUsage)
            }
        }
    }

    /** Explicit lifecycle/background preparation path for rebuilding an unavailable full index. */
    internal suspend fun prepare(): ValidationRecallReadiness = withContext(dispatcher) {
        buildMutex.withLock {
            coroutineContext.ensureActive()
            if (isClosed()) return@withLock ValidationRecallReadiness.NOT_READY
            when (val outcome = readyIndexLocked(forceFullRebuild = true)) {
                is IndexBuildOutcome.Ready -> ValidationRecallReadiness.READY
                is IndexBuildOutcome.Unavailable -> outcome.readiness
            }
        }
    }

    override fun close() {
        val shouldDetach = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                publishedIndex = null
                pendingMutations.clear()
                observerGenerationsToIgnore.clear()
                lastIncrementalWork = null
                true
            }
        }
        if (!shouldDetach) return
        fence.removeInvalidationListener(synchronousInvalidationListener)
        if (observeRoomInvalidations) database.invalidationTracker.removeObserver(invalidationObserver)
    }

    private suspend fun readyIndexLocked(forceFullRebuild: Boolean): IndexBuildOutcome {
        val generation = fence.snapshotForIndexBuild()
            ?: return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.STALE)
        val state = synchronized(stateLock) {
            if (closed) return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.NOT_READY)
            PublishedState(publishedIndex, pendingMutations.toList())
        }
        val currentIndex = state.index
        if (currentIndex != null && currentIndex.generation == generation && state.pending.isEmpty()) {
            return IndexBuildOutcome.Ready(currentIndex)
        }
        if (!forceFullRebuild && currentIndex != null) {
            val changes = contiguousChanges(currentIndex.generation, generation, state.pending)
            if (changes != null) {
                val changedIds = changes
                    .flatMapTo(sortedSetOf()) { mutation ->
                        (mutation.change as ValidationRecallCorpusChange.MemoryIdsChanged).memoryIds
                    }
                if (changedIds.size > limits.maxIncrementalChangedMemories) {
                    return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.CAPACITY_EXCEEDED)
                }
                val updated = try {
                    applyIncrementalChanges(currentIndex, generation, changedIds)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ValidationRecallCapacityExceeded) {
                    discardPublishedIndex()
                    return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.CAPACITY_EXCEEDED)
                } catch (_: Exception) {
                    discardPublishedIndex()
                    return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.FAILED)
                }
                if (!publishIfCurrent(updated, generation)) {
                    return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.STALE, updated.budgetUsage())
                }
                return IndexBuildOutcome.Ready(updated)
            }
        }

        val built = try {
            buildIndex(generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ValidationRecallCapacityExceeded) {
            return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.CAPACITY_EXCEEDED)
        } catch (_: Exception) {
            return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.FAILED)
        }
        if (!publishIfCurrent(built, generation)) {
            return IndexBuildOutcome.Unavailable(ValidationRecallReadiness.STALE, built.budgetUsage())
        }
        return IndexBuildOutcome.Ready(built)
    }

    private suspend fun buildIndex(generation: ValidationRecallGeneration): ValidationRecallIndex {
        val index = ValidationRecallIndex(generation, limits)
        var afterMemoryId = ""
        var structuralRows = 0

        while (true) {
            coroutineContext.ensureActive()
            val remainingMemories = limits.maxMemories - index.documentCount
            val pageLimit = minOf(limits.pageSize, remainingMemories + 1)
            val page = reader.memoryPage(afterMemoryId, pageLimit, limits.maxMeaningChars + 1)
            if (page.size > remainingMemories) throw ValidationRecallCapacityExceeded()
            if (page.isEmpty()) break
            val pageBuilders = page.associateTo(linkedMapOf()) { row ->
                row.memoryId to RecallDocumentBuilder.create(row, limits)
            }
            val pageIds = page.map { it.memoryId }

            val evidenceRows = boundedStructuralRows(structuralRows) { limit ->
                reader.evidenceRows(pageIds, limit, limits.maxSourceChars + 1)
            }
            structuralRows += evidenceRows.size
            evidenceRows.forEach { row ->
                pageBuilders.getValue(row.memoryId).addEvidence(row, limits)
            }

            val entityRows = boundedStructuralRows(structuralRows) { limit ->
                reader.memoryEntityRows(pageIds, limit)
            }
            structuralRows += entityRows.size
            entityRows.forEach { row ->
                val document = pageBuilders.getValue(row.memoryId)
                if (document.isBroadlyAccessible) document.entityIds += row.entityId
            }

            val outgoing = boundedStructuralRows(structuralRows) { limit ->
                reader.outgoingRelationships(pageIds, limit)
            }
            structuralRows += outgoing.size
            val incoming = boundedStructuralRows(structuralRows) { limit ->
                reader.incomingRelationships(pageIds, limit)
            }
            structuralRows += incoming.size
            (outgoing + incoming)
                .asSequence()
                .filter { it.relationshipType in COMPARISON_RELATIONSHIPS }
                .map { RecallRelationship(it.sourceMemoryId, it.targetMemoryId, it.relationshipType) }
                .forEach(index::addRelationship)

            pageBuilders.values.forEach { builder -> index.addDocument(builder.build()) }

            afterMemoryId = page.last().memoryId
            if (page.size < pageLimit) break
        }
        index.requireStructuralRowsWithinLimit(structuralRows)
        return index
    }

    private suspend fun applyIncrementalChanges(
        index: ValidationRecallIndex,
        generation: ValidationRecallGeneration,
        changedIds: Set<String>,
    ): ValidationRecallIndex {
        if (changedIds.isEmpty()) {
            index.generation = generation
            synchronized(stateLock) {
                lastIncrementalWork = ValidationRecallIncrementalWork(0, 0, 0)
            }
            return index
        }
        coroutineContext.ensureActive()
        val ids = changedIds.sorted()
        val rows = reader.memoryRows(ids, limits.maxMeaningChars + 1)
        val builders = rows.associateTo(linkedMapOf()) { row ->
            row.memoryId to RecallDocumentBuilder.create(row, limits)
        }
        var structuralRows = 0
        val evidenceRows = boundedStructuralRows(structuralRows) { limit ->
            reader.evidenceRows(ids, limit, limits.maxSourceChars + 1)
        }
        structuralRows += evidenceRows.size
        evidenceRows.forEach { row -> builders[row.memoryId]?.addEvidence(row, limits) }
        val entityRows = boundedStructuralRows(structuralRows) { limit ->
            reader.memoryEntityRows(ids, limit)
        }
        structuralRows += entityRows.size
        entityRows.forEach { row ->
            builders[row.memoryId]?.let { builder ->
                if (builder.isBroadlyAccessible) builder.entityIds += row.entityId
            }
        }
        val outgoing = boundedStructuralRows(structuralRows) { limit ->
            reader.outgoingRelationships(ids, limit)
        }
        structuralRows += outgoing.size
        val incoming = boundedStructuralRows(structuralRows) { limit ->
            reader.incomingRelationships(ids, limit)
        }
        structuralRows += incoming.size
        val relationships = (outgoing + incoming)
            .asSequence()
            .filter { it.relationshipType in COMPARISON_RELATIONSHIPS }
            .map { RecallRelationship(it.sourceMemoryId, it.targetMemoryId, it.relationshipType) }
            .toSet()
        val work = index.replaceDocuments(
            changedIds = changedIds,
            replacements = builders.mapValues { (_, builder) -> builder.build() },
            replacementRelationships = relationships,
            nextGeneration = generation,
        )
        synchronized(stateLock) { lastIncrementalWork = work }
        return index
    }

    internal fun incrementalWorkSnapshot(): ValidationRecallIncrementalWork? =
        synchronized(stateLock) { lastIncrementalWork }

    private fun contiguousChanges(
        from: ValidationRecallGeneration,
        to: ValidationRecallGeneration,
        pending: List<ValidationRecallCommittedMutation>,
    ): List<ValidationRecallCommittedMutation>? {
        if (from.databaseSessionId != to.databaseSessionId ||
            from.algorithmVersion != to.algorithmVersion ||
            from.corpusGeneration >= to.corpusGeneration
        ) {
            return null
        }
        val changes = pending
            .filter { it.generation.corpusGeneration > from.corpusGeneration }
            .filter { it.generation.corpusGeneration <= to.corpusGeneration }
            .sortedBy { it.generation.corpusGeneration }
        val expectedCount = to.corpusGeneration - from.corpusGeneration
        if (expectedCount > Int.MAX_VALUE || changes.size != expectedCount.toInt()) return null
        changes.forEachIndexed { index, mutation ->
            if (mutation.generation.databaseSessionId != to.databaseSessionId ||
                mutation.generation.algorithmVersion != to.algorithmVersion ||
                mutation.generation.corpusGeneration != from.corpusGeneration + index + 1L ||
                mutation.change !is ValidationRecallCorpusChange.MemoryIdsChanged
            ) {
                return null
            }
        }
        return changes
    }

    private fun publishIfCurrent(
        index: ValidationRecallIndex,
        generation: ValidationRecallGeneration,
    ): Boolean = synchronized(stateLock) {
        if (closed || !fence.matches(generation) ||
            pendingMutations.any { it.generation.corpusGeneration > generation.corpusGeneration }
        ) {
            return@synchronized false
        }
        pendingMutations.removeAll { it.generation.corpusGeneration <= generation.corpusGeneration }
        publishedIndex = index
        true
    }

    private fun discardPublishedIndex() {
        synchronized(stateLock) {
            publishedIndex = null
            pendingMutations.clear()
        }
    }

    private fun isClosed(): Boolean = synchronized(stateLock) { closed }

    internal fun onCanonicalTablesInvalidated() {
        if (fence.isMutationInFlight()) {
            synchronized(stateLock) { observerSawActiveMutation = true }
            return
        }
        val generation = fence.snapshot()
        synchronized(stateLock) {
            if (closed) return
            if (observerGenerationsToIgnore.remove(generation)) return
            publishedIndex = null
            pendingMutations.clear()
        }
    }

    private fun <T> boundedStructuralRows(
        rowsAlreadyRead: Int,
        load: (Int) -> List<T>,
    ): List<T> {
        val remaining = limits.maxCorpusStructuralRows - rowsAlreadyRead
        val allowed = minOf(limits.maxStructuralRowsPerPage, remaining.coerceAtLeast(0))
        val rows = load(allowed + 1)
        if (rows.size > allowed) throw ValidationRecallCapacityExceeded()
        return rows
    }

    private fun unavailable(
        readiness: ValidationRecallReadiness,
        budgetUsage: ValidationRecallBudgetUsage = ValidationRecallBudgetUsage(),
    ) = ValidationMemoryRetrieval(
        readiness = readiness,
        generation = fence.snapshot(),
        budgetUsage = budgetUsage,
    )

    private sealed interface IndexBuildOutcome {
        data class Ready(val index: ValidationRecallIndex) : IndexBuildOutcome
        data class Unavailable(
            val readiness: ValidationRecallReadiness,
            val budgetUsage: ValidationRecallBudgetUsage = ValidationRecallBudgetUsage(),
        ) : IndexBuildOutcome
    }

    private data class PublishedState(
        val index: ValidationRecallIndex?,
        val pending: List<ValidationRecallCommittedMutation>,
    )

    private companion object {
        val COMPARISON_RELATIONSHIPS = setOf(
            MemoryRelationshipType.REFINES,
            MemoryRelationshipType.SUPERSEDES,
            MemoryRelationshipType.CORRECTS,
            MemoryRelationshipType.CONTRADICTS,
        )
    }
}

private class RecallInvalidationObserver(
    owner: TargetedValidationMemoryRetriever,
    vararg tables: String,
) : InvalidationTracker.Observer(tables) {
    private val owner = WeakReference(owner)

    override fun onInvalidated(tables: Set<String>) {
        owner.get()?.onCanonicalTablesInvalidated()
    }
}

internal interface ValidationRecallCorpusReader {
    fun memoryPage(afterMemoryId: String, limit: Int, maxMeaningCharsPlusOne: Int): List<ValidationRecallMemoryRow>
    fun memoryRows(memoryIds: List<String>, maxMeaningCharsPlusOne: Int): List<ValidationRecallMemoryRow> = emptyList()
    fun evidenceRows(
        memoryIds: List<String>,
        limit: Int,
        maxSourceCharsPlusOne: Int,
    ): List<ValidationRecallEvidenceRow>
    fun memoryEntityRows(memoryIds: List<String>, limit: Int): List<ValidationRecallEntityRow>
    fun outgoingRelationships(memoryIds: List<String>, limit: Int): List<MemoryRelationshipEntity>
    fun incomingRelationships(memoryIds: List<String>, limit: Int): List<MemoryRelationshipEntity>
}

private class RoomValidationRecallCorpusReader(
    private val dao: MemoryDao,
) : ValidationRecallCorpusReader {
    override fun memoryPage(
        afterMemoryId: String,
        limit: Int,
        maxMeaningCharsPlusOne: Int,
    ) = dao.validationRecallMemoryPage(afterMemoryId, limit, maxMeaningCharsPlusOne)

    override fun memoryRows(
        memoryIds: List<String>,
        maxMeaningCharsPlusOne: Int,
    ) = dao.validationRecallMemoryRows(memoryIds, maxMeaningCharsPlusOne)

    override fun evidenceRows(
        memoryIds: List<String>,
        limit: Int,
        maxSourceCharsPlusOne: Int,
    ) = dao.validationRecallEvidenceRows(memoryIds, limit, maxSourceCharsPlusOne)

    override fun memoryEntityRows(memoryIds: List<String>, limit: Int) =
        dao.validationRecallMemoryEntityRows(memoryIds, limit)

    override fun outgoingRelationships(memoryIds: List<String>, limit: Int) =
        dao.validationRecallOutgoingRelationships(memoryIds, limit)

    override fun incomingRelationships(memoryIds: List<String>, limit: Int) =
        dao.validationRecallIncomingRelationships(memoryIds, limit)
}

private class RecallDocumentBuilder private constructor(
    private val memoryId: String,
    private val kind: MemoryKind,
    private val scope: MemoryScope,
    private val retentionState: MemoryRetentionState,
    val isBroadlyAccessible: Boolean,
    private val meaningHash: String,
    private var textChars: Long,
    private val termFrequencies: MutableMap<String, Int>,
    private var tokenCount: Int,
    val sourceExperienceIds: MutableSet<String> = sortedSetOf(),
    val entityIds: MutableSet<String> = sortedSetOf(),
) {
    fun addEvidence(row: ValidationRecallEvidenceRow, limits: TargetedValidationRecallLimits) {
        if (retentionState == MemoryRetentionState.FORGOTTEN ||
            row.sourceAvailability != ExperienceAvailability.AVAILABLE
        ) {
            return
        }
        sourceExperienceIds += row.sourceExperienceId
        if (!isBroadlyAccessible || row.sourceSensitivity != SensitivityLevel.STANDARD) return
        val sourceLength = row.sourceLength ?: return
        val sourceContent = row.sourceContent ?: return
        if (sourceLength > limits.maxSourceChars.toLong() || sourceContent.length > limits.maxSourceChars) {
            throw ValidationRecallCapacityExceeded()
        }
        val nextTextChars = textChars + sourceLength
        if (nextTextChars > limits.maxTextCharsPerDocument.toLong()) throw ValidationRecallCapacityExceeded()
        textChars = nextTextChars
        addTokens(sourceContent, SOURCE_TOKEN_WEIGHT, limits)
    }

    fun build(): RecallDocument = RecallDocument(
        memoryId = memoryId,
        kind = kind,
        scope = scope,
        rowRetentionState = retentionState,
        isBroadlyAccessible = isBroadlyAccessible,
        meaningHash = meaningHash,
        termFrequencies = termFrequencies.toMap(),
        documentLength = tokenCount,
        textChars = textChars,
        sourceExperienceIds = sourceExperienceIds.toSet(),
        entityIds = entityIds.toSet(),
    )

    private fun addTokens(text: String, weight: Int, limits: TargetedValidationRecallLimits) {
        safeIndexTokens(text, limits.maxTokenChars).forEach { token ->
            if (tokenCount > limits.maxTokensPerDocument - weight) throw ValidationRecallCapacityExceeded()
            termFrequencies[token] = (termFrequencies[token] ?: 0) + weight
            tokenCount += weight
        }
    }

    companion object {
        fun create(row: ValidationRecallMemoryRow, limits: TargetedValidationRecallLimits): RecallDocumentBuilder {
            if (row.meaningLength > limits.maxMeaningChars.toLong() || row.meaning.length > limits.maxMeaningChars) {
                throw ValidationRecallCapacityExceeded()
            }
            val broadlyAccessible = row.retentionState != MemoryRetentionState.FORGOTTEN &&
                row.sensitivity == SensitivityLevel.STANDARD
            return RecallDocumentBuilder(
                memoryId = row.memoryId,
                kind = row.kind,
                scope = row.scope,
                retentionState = row.retentionState,
                isBroadlyAccessible = broadlyAccessible,
                meaningHash = if (broadlyAccessible) meaningHash(row.meaning) else "",
                textChars = row.meaningLength,
                termFrequencies = linkedMapOf(),
                tokenCount = 0,
            ).also { builder ->
                if (broadlyAccessible) builder.addTokens(row.meaning, MEANING_TOKEN_WEIGHT, limits)
            }
        }

        private const val MEANING_TOKEN_WEIGHT = 3
        private const val SOURCE_TOKEN_WEIGHT = 1
    }
}

private data class RecallDocument(
    val memoryId: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val rowRetentionState: MemoryRetentionState,
    val isBroadlyAccessible: Boolean,
    val meaningHash: String,
    val termFrequencies: Map<String, Int>,
    val documentLength: Int,
    val textChars: Long,
    val sourceExperienceIds: Set<String>,
    val entityIds: Set<String>,
) {
    val structuralRows: Int
        get() = sourceExperienceIds.size + entityIds.size

    fun toBounded() = BoundedRecallDocument(
        id = memoryId,
        metadata = this,
        exactMeaningHash = meaningHash,
        termFrequencies = termFrequencies,
        documentLength = documentLength,
        textChars = textChars,
        sourceIds = sourceExperienceIds,
        entityIds = entityIds,
        exactSearchable = isBroadlyAccessible,
        lexicallySearchable = isBroadlyAccessible,
        sourceSearchable = rowRetentionState != MemoryRetentionState.FORGOTTEN,
        entitySearchable = isBroadlyAccessible,
    )
}

private data class RecallRelationship(
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val relationshipType: MemoryRelationshipType,
)

private fun RecallRelationship.toBounded() = BoundedRecallRelationship(
    sourceId = sourceMemoryId,
    targetId = targetMemoryId,
    type = relationshipType.name,
)

private class ValidationRecallIndex(
    var generation: ValidationRecallGeneration,
    private val limits: TargetedValidationRecallLimits,
) {
    private val sharedCore = BoundedRecallIndex<RecallDocument>(
        BoundedRecallLimits(
            maxDocuments = limits.maxMemories,
            maxCorpusTextChars = limits.maxCorpusTextChars,
            maxCorpusTokens = limits.maxCorpusTokens,
            maxCorpusPostings = limits.maxCorpusPostings,
            maxCorpusStructuralRows = limits.maxCorpusStructuralRows,
            maxTokenChars = limits.maxTokenChars,
            maxQueryTerms = limits.maxQueryTerms,
            maxPostingVisits = limits.maxPostingVisits,
            maxStructuralVisits = limits.maxStructuralVisits,
            maxGraphSeeds = limits.maxGraphSeeds,
            maxRelationshipVisits = limits.maxRelationshipVisits,
            maxCandidatePool = limits.maxCandidatePool,
        ),
    )
    val documentCount: Int
        get() = sharedCore.documentCount

    fun addDocument(document: RecallDocument) {
        try {
            sharedCore.addDocument(document.toBounded())
        } catch (_: BoundedRecallCapacityExceeded) {
            throw ValidationRecallCapacityExceeded()
        }
    }

    fun addRelationship(relationship: RecallRelationship) {
        try {
            sharedCore.addRelationship(relationship.toBounded())
        } catch (_: BoundedRecallCapacityExceeded) {
            throw ValidationRecallCapacityExceeded()
        }
    }

    fun requireStructuralRowsWithinLimit(rowsRead: Int) {
        if (rowsRead > limits.maxCorpusStructuralRows) throw ValidationRecallCapacityExceeded()
    }

    fun replaceDocuments(
        changedIds: Set<String>,
        replacements: Map<String, RecallDocument>,
        replacementRelationships: Set<RecallRelationship>,
        nextGeneration: ValidationRecallGeneration,
    ): ValidationRecallIncrementalWork {
        val work = try {
            sharedCore.replaceDocuments(
                changedIds = changedIds,
                replacements = replacements.mapValues { it.value.toBounded() },
                replacementRelationships = replacementRelationships.mapTo(linkedSetOf()) { it.toBounded() },
            )
        } catch (_: BoundedRecallCapacityExceeded) {
            throw ValidationRecallCapacityExceeded()
        }
        generation = nextGeneration
        return ValidationRecallIncrementalWork(
            changedMemories = work.changedDocuments,
            incidentRelationshipsRemoved = work.incidentRelationshipsRemoved,
            adjacencyVerticesVisitedForRemoval = work.adjacencyVerticesVisitedForRemoval,
        )
    }

    fun budgetUsage(): ValidationRecallBudgetUsage = sharedCore.usage().let { usage ->
        ValidationRecallBudgetUsage(
            indexedMemories = usage.indexedDocuments,
            indexedTokens = usage.indexedTokens,
            structuralRowsVisited = usage.structuralRows,
        )
    }

    suspend fun retrieve(
        query: ValidationMemoryQuery,
        limits: TargetedValidationRecallLimits,
    ): ValidationMemoryRetrieval {
        coroutineContext.ensureActive()
        if (query.proposedMeaning.length > limits.maxQueryChars ||
            query.sourceExperienceIds.distinct().size > limits.maxQuerySourceIds ||
            query.groundedEntityIds.size > limits.maxQueryEntityIds
        ) {
            return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED)
        }
        val result = sharedCore.query(query, ValidationRecallPolicy)
        val readiness = when (result.status) {
            BoundedRecallQueryStatus.READY -> ValidationRecallReadiness.READY
            BoundedRecallQueryStatus.QUERY_UNSELECTIVE -> ValidationRecallReadiness.QUERY_UNSELECTIVE
            BoundedRecallQueryStatus.BUDGET_EXCEEDED -> ValidationRecallReadiness.BUDGET_EXCEEDED
        }
        return ValidationMemoryRetrieval(
            memoryIds = if (readiness == ValidationRecallReadiness.READY) {
                result.candidates.take(limits.maxFinalIds).map { it.document.id }
            } else {
                emptyList()
            },
            readiness = readiness,
            generation = generation,
            budgetUsage = ValidationRecallBudgetUsage(
                indexedMemories = result.usage.indexedDocuments,
                indexedTokens = result.usage.indexedTokens,
                postingVisits = result.usage.postingVisits,
                structuralRowsVisited = result.usage.structuralRows + result.usage.queryStructuralVisits,
                graphSeedsVisited = result.usage.graphSeeds,
                relationshipRowsVisited = result.usage.relationshipVisits,
                candidatePoolSize = result.usage.candidatePoolSize,
            ),
        )
    }

    private fun unavailable(
        readiness: ValidationRecallReadiness,
        postingVisits: Int = 0,
        structuralVisits: Int = 0,
        graphSeeds: Int = 0,
        relationshipVisits: Int = 0,
    ) = ValidationMemoryRetrieval(
        readiness = readiness,
        generation = generation,
        budgetUsage = budgetUsage().copy(
            postingVisits = postingVisits,
            structuralRowsVisited = budgetUsage().structuralRowsVisited + structuralVisits,
            graphSeedsVisited = graphSeeds,
            relationshipRowsVisited = relationshipVisits,
        ),
    )

    private object ValidationRecallPolicy : BoundedRecallPolicy<ValidationMemoryQuery, RecallDocument> {
        override fun queryText(query: ValidationMemoryQuery) = query.proposedMeaning

        override fun sourceIds(query: ValidationMemoryQuery) = query.sourceExperienceIds

        override fun entityIds(query: ValidationMemoryQuery) = query.groundedEntityIds

        override fun exactMeaningHash(query: ValidationMemoryQuery) = meaningHash(query.proposedMeaning)

        override fun isEligible(
            query: ValidationMemoryQuery,
            document: BoundedRecallDocument<RecallDocument>,
        ) = true

        override fun exactScore(
            query: ValidationMemoryQuery,
            document: BoundedRecallDocument<RecallDocument>,
        ) = EXACT_MEANING_SCORE

        override fun sourceScore(
            query: ValidationMemoryQuery,
            document: BoundedRecallDocument<RecallDocument>,
        ) = SOURCE_MATCH_SCORE

        override fun entityScore(
            query: ValidationMemoryQuery,
            document: BoundedRecallDocument<RecallDocument>,
        ) = ENTITY_MATCH_SCORE

        override fun lexicalBonus(
            query: ValidationMemoryQuery,
            document: BoundedRecallDocument<RecallDocument>,
        ) = (if (document.metadata.kind == query.proposedKind) SAME_KIND_BONUS else 0.0) +
            (if (document.metadata.scope == query.proposedScope) SAME_SCOPE_BONUS else 0.0)

        override fun relationshipScore(
            query: ValidationMemoryQuery,
            seed: BoundedRecallDocument<RecallDocument>,
            related: BoundedRecallDocument<RecallDocument>,
            relationshipType: String,
        ) = RELATION_MATCH_SCORE
    }

    private companion object {
        const val EXACT_MEANING_SCORE = 100.0
        const val SOURCE_MATCH_SCORE = 50.0
        const val ENTITY_MATCH_SCORE = 8.0
        const val RELATION_MATCH_SCORE = 5.0
        const val SAME_KIND_BONUS = 0.35
        const val SAME_SCOPE_BONUS = 0.25
    }
}

private object RecallTokenizer {
    private val tokenPattern = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val unselectiveTerms = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by", "for", "from",
        "had", "has", "have", "he", "her", "hers", "him", "his", "i", "in", "is", "it", "its",
        "me", "my", "of", "on", "or", "our", "ours", "she", "that", "the", "their", "theirs",
        "them", "they", "this", "those", "to", "us", "was", "we", "were", "with", "you", "your",
        "yours",
    )

    fun tokens(text: String, maxTokenChars: Int): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return tokenPattern.findAll(normalized).map { match ->
            match.value.replace('’', '\'').also { token ->
                if (token.length > maxTokenChars) throw RecallTokenLimitExceeded()
            }
        }.filter { it !in unselectiveTerms }.toList()
    }
}

private fun safeIndexTokens(text: String, maxTokenChars: Int): List<String> = try {
    RecallTokenizer.tokens(text, maxTokenChars)
} catch (_: RecallTokenLimitExceeded) {
    throw ValidationRecallCapacityExceeded()
}

private class RecallTokenLimitExceeded : RuntimeException()
private class ValidationRecallCapacityExceeded : RuntimeException()

private fun meaningHash(meaning: String): String = boundedRecallMeaningHash(meaning)
