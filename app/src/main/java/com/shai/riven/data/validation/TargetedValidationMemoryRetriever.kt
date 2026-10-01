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
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
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
        database.invalidationTracker.addObserver(invalidationObserver)
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
        database.invalidationTracker.removeObserver(invalidationObserver)
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
}

private data class RecallRelationship(
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val relationshipType: MemoryRelationshipType,
)

private class ValidationRecallIndex(
    var generation: ValidationRecallGeneration,
    private val limits: TargetedValidationRecallLimits,
) {
    private val documents = linkedMapOf<String, RecallDocument>()
    private val postings = linkedMapOf<String, MutableMap<String, Int>>()
    private val exactMeanings = linkedMapOf<String, MutableSet<String>>()
    private val sourceIndex = linkedMapOf<String, MutableSet<String>>()
    private val entityIndex = linkedMapOf<String, MutableSet<String>>()
    private val relationships = linkedSetOf<RecallRelationship>()
    private val relationshipsByMemory = linkedMapOf<String, MutableSet<RecallRelationship>>()
    private val adjacency = linkedMapOf<String, MutableSet<String>>()
    private var indexedTextChars = 0L
    private var indexedTokens = 0
    private var postingCount = 0
    private var structuralRows = 0
    private var broadDocumentCount = 0
    private var positiveBroadDocumentCount = 0
    private var broadDocumentTokens = 0L

    val documentCount: Int
        get() = documents.size

    fun addDocument(document: RecallDocument) {
        if (document.memoryId in documents) throw ValidationRecallCapacityExceeded()
        if (documents.size >= limits.maxMemories ||
            indexedTextChars > limits.maxCorpusTextChars - document.textChars ||
            indexedTokens > limits.maxCorpusTokens - document.documentLength ||
            postingCount > limits.maxCorpusPostings - document.termFrequencies.size ||
            structuralRows > limits.maxCorpusStructuralRows - document.structuralRows
        ) {
            throw ValidationRecallCapacityExceeded()
        }
        documents[document.memoryId] = document
        indexedTextChars += document.textChars
        indexedTokens += document.documentLength
        postingCount += document.termFrequencies.size
        structuralRows += document.structuralRows
        if (document.isBroadlyAccessible) {
            broadDocumentCount++
            if (document.documentLength > 0) {
                positiveBroadDocumentCount++
                broadDocumentTokens += document.documentLength.toLong()
            }
            exactMeanings.getOrPut(document.meaningHash) { sortedSetOf() } += document.memoryId
            document.termFrequencies.forEach { (term, frequency) ->
                postings.getOrPut(term) { linkedMapOf() }[document.memoryId] = frequency
            }
            document.entityIds.forEach { entityId ->
                entityIndex.getOrPut(entityId) { sortedSetOf() } += document.memoryId
            }
        }
        if (document.rowRetentionState != MemoryRetentionState.FORGOTTEN) {
            document.sourceExperienceIds.forEach { sourceId ->
                sourceIndex.getOrPut(sourceId) { sortedSetOf() } += document.memoryId
            }
        }
        relationshipsByMemory[document.memoryId].orEmpty().forEach(::activateRelationship)
    }

    fun addRelationship(relationship: RecallRelationship) {
        if (!relationships.add(relationship)) return
        if (structuralRows >= limits.maxCorpusStructuralRows) throw ValidationRecallCapacityExceeded()
        structuralRows++
        relationshipsByMemory.getOrPut(relationship.sourceMemoryId) { linkedSetOf() } += relationship
        relationshipsByMemory.getOrPut(relationship.targetMemoryId) { linkedSetOf() } += relationship
        activateRelationship(relationship)
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
        val relationshipsToRemove = changedIds
            .flatMapTo(linkedSetOf()) { relationshipsByMemory[it].orEmpty() }
        val work = MutableIncrementalWork(
            changedMemories = changedIds.size,
            incidentRelationshipsRemoved = relationshipsToRemove.size,
        )
        relationshipsToRemove.forEach { relationship -> removeRelationship(relationship, work) }
        changedIds.forEach { memoryId -> removeDocument(memoryId, work) }
        replacements.toSortedMap().values.forEach(::addDocument)
        replacementRelationships.sortedWith(
            compareBy<RecallRelationship>(RecallRelationship::sourceMemoryId)
                .thenBy(RecallRelationship::targetMemoryId)
                .thenBy { it.relationshipType.name },
        ).forEach(::addRelationship)
        generation = nextGeneration
        return work.snapshot()
    }

    fun budgetUsage(): ValidationRecallBudgetUsage = ValidationRecallBudgetUsage(
        indexedMemories = documents.size,
        indexedTokens = indexedTokens,
        structuralRowsVisited = structuralRows,
    )

    private fun removeDocument(memoryId: String, work: MutableIncrementalWork) {
        val document = documents.remove(memoryId) ?: return
        indexedTextChars -= document.textChars
        indexedTokens -= document.documentLength
        postingCount -= document.termFrequencies.size
        structuralRows -= document.structuralRows
        if (document.isBroadlyAccessible) {
            broadDocumentCount--
            if (document.documentLength > 0) {
                positiveBroadDocumentCount--
                broadDocumentTokens -= document.documentLength.toLong()
            }
            exactMeanings.removeMember(document.meaningHash, memoryId)
            document.termFrequencies.keys.forEach { term -> postings.removePosting(term, memoryId) }
            document.entityIds.forEach { entityId -> entityIndex.removeMember(entityId, memoryId) }
        }
        if (document.rowRetentionState != MemoryRetentionState.FORGOTTEN) {
            document.sourceExperienceIds.forEach { sourceId -> sourceIndex.removeMember(sourceId, memoryId) }
        }
        work.adjacencyVerticesVisitedForRemoval++
        adjacency.remove(memoryId)
    }

    private fun removeRelationship(
        relationship: RecallRelationship,
        work: MutableIncrementalWork,
    ) {
        if (!relationships.remove(relationship)) return
        structuralRows--
        relationshipsByMemory.removeMember(relationship.sourceMemoryId, relationship)
        relationshipsByMemory.removeMember(relationship.targetMemoryId, relationship)
        work.adjacencyVerticesVisitedForRemoval++
        adjacency[relationship.sourceMemoryId]?.let { neighbors ->
            neighbors.remove(relationship.targetMemoryId)
            if (neighbors.isEmpty()) adjacency.remove(relationship.sourceMemoryId)
        }
        work.adjacencyVerticesVisitedForRemoval++
        adjacency[relationship.targetMemoryId]?.let { neighbors ->
            neighbors.remove(relationship.sourceMemoryId)
            if (neighbors.isEmpty()) adjacency.remove(relationship.targetMemoryId)
        }
    }

    private fun activateRelationship(relationship: RecallRelationship) {
        if (relationship.sourceMemoryId !in documents || relationship.targetMemoryId !in documents) return
        adjacency.getOrPut(relationship.sourceMemoryId) { sortedSetOf() } += relationship.targetMemoryId
        adjacency.getOrPut(relationship.targetMemoryId) { sortedSetOf() } += relationship.sourceMemoryId
    }

    suspend fun retrieve(
        query: ValidationMemoryQuery,
        limits: TargetedValidationRecallLimits,
    ): ValidationMemoryRetrieval {
        coroutineContext.ensureActive()
        if (query.proposedMeaning.length > limits.maxQueryChars) {
            return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED)
        }
        val sourceIds = query.sourceExperienceIds.distinct().sorted()
        val entityIds = query.groundedEntityIds.toSortedSet().toList()
        if (sourceIds.size > limits.maxQuerySourceIds || entityIds.size > limits.maxQueryEntityIds) {
            return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED)
        }
        val queryTerms = try {
            RecallTokenizer.tokens(query.proposedMeaning, limits.maxTokenChars).distinct()
        } catch (_: RecallTokenLimitExceeded) {
            return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED)
        }
        if (queryTerms.size > limits.maxQueryTerms) {
            return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED)
        }

        val scores = linkedMapOf<String, Double>()
        var postingVisits = 0
        var structuralVisits = 0
        var relationshipVisits = 0

        fun add(memoryId: String, score: Double): Boolean {
            if (memoryId !in documents) return true
            scores[memoryId] = (scores[memoryId] ?: 0.0) + score
            return scores.size <= limits.maxCandidatePool
        }

        exactMeanings[meaningHash(query.proposedMeaning)].orEmpty().forEach { memoryId ->
            if (!add(memoryId, EXACT_MEANING_SCORE)) return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED)
        }
        sourceIds.forEach { sourceId ->
            sourceIndex[sourceId].orEmpty().forEach { memoryId ->
                structuralVisits++
                if (structuralVisits > limits.maxStructuralVisits || !add(memoryId, SOURCE_MATCH_SCORE)) {
                    return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED, postingVisits, structuralVisits)
                }
            }
        }
        entityIds.forEach { entityId ->
            entityIndex[entityId].orEmpty().forEach { memoryId ->
                structuralVisits++
                if (structuralVisits > limits.maxStructuralVisits || !add(memoryId, ENTITY_MATCH_SCORE)) {
                    return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED, postingVisits, structuralVisits)
                }
            }
        }

        if (queryTerms.isEmpty() && scores.isEmpty()) {
            return unavailable(ValidationRecallReadiness.QUERY_UNSELECTIVE, postingVisits, structuralVisits)
        }
        val orderedTerms = queryTerms.sortedWith(compareBy<String> { postings[it]?.size ?: 0 }.thenBy { it })
        val plannedPostingVisits = orderedTerms.sumOf { postings[it]?.size ?: 0 }
        if (plannedPostingVisits > limits.maxPostingVisits) {
            return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED, postingVisits, structuralVisits)
        }
        orderedTerms.forEach { term ->
            coroutineContext.ensureActive()
            val termPostings = postings[term].orEmpty()
            val averageDocumentLength = if (positiveBroadDocumentCount == 0) {
                1.0
            } else {
                broadDocumentTokens.toDouble() / positiveBroadDocumentCount
            }
            val inverseDocumentFrequency = ln(
                1.0 + (broadDocumentCount - termPostings.size + 0.5) / (termPostings.size + 0.5),
            )
            termPostings.forEach { (memoryId, termFrequency) ->
                postingVisits++
                val document = documents.getValue(memoryId)
                val normalizedLength = document.documentLength / averageDocumentLength
                val termFrequencyScore = termFrequency * (BM25_K + 1.0) /
                    (termFrequency + BM25_K * (1.0 - BM25_B + BM25_B * normalizedLength))
                val structuralBonus =
                    (if (document.kind == query.proposedKind) SAME_KIND_BONUS else 0.0) +
                        (if (document.scope == query.proposedScope) SAME_SCOPE_BONUS else 0.0)
                if (!add(memoryId, inverseDocumentFrequency * termFrequencyScore + structuralBonus)) {
                    return unavailable(ValidationRecallReadiness.BUDGET_EXCEEDED, postingVisits, structuralVisits)
                }
            }
        }

        val graphSeeds = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limits.maxGraphSeeds)
        graphSeeds.forEach { seed ->
            coroutineContext.ensureActive()
            adjacency[seed.key].orEmpty().forEach { relatedMemoryId ->
                relationshipVisits++
                if (relationshipVisits > limits.maxRelationshipVisits ||
                    !add(relatedMemoryId, RELATION_MATCH_SCORE)
                ) {
                    return unavailable(
                        ValidationRecallReadiness.BUDGET_EXCEEDED,
                        postingVisits,
                        structuralVisits,
                        graphSeeds.size,
                        relationshipVisits,
                    )
                }
            }
        }

        val memoryIds = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limits.maxFinalIds)
            .map { it.key }
        return ValidationMemoryRetrieval(
            memoryIds = memoryIds,
            readiness = ValidationRecallReadiness.READY,
            generation = generation,
            budgetUsage = budgetUsage().copy(
                postingVisits = postingVisits,
                structuralRowsVisited = budgetUsage().structuralRowsVisited + structuralVisits,
                graphSeedsVisited = graphSeeds.size,
                relationshipRowsVisited = relationshipVisits,
                candidatePoolSize = scores.size,
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

    private companion object {
        const val EXACT_MEANING_SCORE = 100.0
        const val SOURCE_MATCH_SCORE = 50.0
        const val ENTITY_MATCH_SCORE = 8.0
        const val RELATION_MATCH_SCORE = 5.0
        const val SAME_KIND_BONUS = 0.35
        const val SAME_SCOPE_BONUS = 0.25
        const val BM25_K = 1.2
        const val BM25_B = 0.75
    }

    private data class MutableIncrementalWork(
        val changedMemories: Int,
        val incidentRelationshipsRemoved: Int,
        var adjacencyVerticesVisitedForRemoval: Int = 0,
    ) {
        fun snapshot() = ValidationRecallIncrementalWork(
            changedMemories = changedMemories,
            incidentRelationshipsRemoved = incidentRelationshipsRemoved,
            adjacencyVerticesVisitedForRemoval = adjacencyVerticesVisitedForRemoval,
        )
    }

    private fun <K, V> MutableMap<K, MutableSet<V>>.removeMember(key: K, value: V) {
        this[key]?.let { values ->
            values.remove(value)
            if (values.isEmpty()) remove(key)
        }
    }

    private fun <K, V> MutableMap<K, MutableMap<V, Int>>.removePosting(key: K, value: V) {
        this[key]?.let { values ->
            values.remove(value)
            if (values.isEmpty()) remove(key)
        }
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

private fun meaningHash(meaning: String): String = MessageDigest.getInstance("SHA-256")
    .digest(Normalizer.normalize(meaning, Normalizer.Form.NFKC).lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
