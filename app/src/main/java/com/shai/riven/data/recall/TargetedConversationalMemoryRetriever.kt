package com.shai.riven.data.recall

import androidx.room.InvalidationTracker
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.dao.ConversationalRecallMemoryRow
import com.shai.riven.data.persistence.dao.MemoryDao
import com.shai.riven.data.persistence.dao.ValidationRecallEntityRow
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.validation.ValidationRecallCommittedMutation
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.ValidationRecallGeneration
import com.shai.riven.data.validation.validationRecallCorpusFence
import java.lang.ref.WeakReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ConversationalRecallIncrementalWork(
    val changedMemories: Int,
    val incidentRelationshipsRemoved: Int,
    val adjacencyVerticesVisitedForRemoval: Int,
)

/**
 * Process-local conversational recall. It owns an index separate from validation comparison and
 * never indexes forgotten/corrected-false memories or evidence source text.
 */
class TargetedConversationalMemoryRetriever internal constructor(
    private val database: RivenDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val limits: ConversationalRecallLimits,
    private val reader: ConversationalRecallCorpusReader,
) : ConversationalMemoryRetriever, AutoCloseable {
    constructor(
        database: RivenDatabase,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        limits: ConversationalRecallLimits = ConversationalRecallLimits(),
    ) : this(database, dispatcher, limits, RoomConversationalRecallCorpusReader(database.memoryDao()))

    private val fence = database.validationRecallCorpusFence()
    private val buildMutex = Mutex()
    private val stateLock = Any()
    private var publishedIndex: ConversationalRecallIndex? = null
    private var lastIncrementalWork: ConversationalRecallIncrementalWork? = null
    private var closed = false
    private val pendingMutations = mutableListOf<ValidationRecallCommittedMutation>()
    private var observerSawActiveMutation = false
    private val observerGenerationsToIgnore = mutableSetOf<ValidationRecallGeneration>()
    private val synchronousInvalidationListener: (ValidationRecallCommittedMutation) -> Unit = { mutation ->
        synchronized(stateLock) {
            if (!closed) {
                if (observerSawActiveMutation) observerSawActiveMutation = false
                else observerGenerationsToIgnore += mutation.generation
                when (mutation.change) {
                    is ValidationRecallCorpusChange.MemoryIdsChanged -> if (publishedIndex != null) {
                        pendingMutations += mutation
                    }
                    ValidationRecallCorpusChange.Unknown -> {
                        publishedIndex = null
                        pendingMutations.clear()
                    }
                }
            }
        }
    }
    private val invalidationObserver = ConversationalRecallInvalidationObserver(
        this,
        "memories",
        "memory_relationships",
        "memory_entity_links",
    )

    init {
        fence.addInvalidationListener(synchronousInvalidationListener)
        database.invalidationTracker.addObserver(invalidationObserver)
    }

    override suspend fun retrieve(query: ConversationalMemoryQuery): ConversationalMemoryRetrieval =
        withContext(dispatcher) {
            buildMutex.withLock {
                coroutineContext.ensureActive()
                if (isClosed()) return@withLock unavailable(ConversationalRecallReadiness.NOT_READY)
                if (!query.withinLimits(limits)) {
                    return@withLock unavailable(ConversationalRecallReadiness.BUDGET_EXCEEDED)
                }
                when (val outcome = readyIndexLocked(forceFullRebuild = false)) {
                    is IndexBuildOutcome.Ready -> {
                        val result = outcome.index.retrieve(query)
                        if (!fence.matches(outcome.index.generation)) {
                            unavailable(ConversationalRecallReadiness.STALE, result.budgetUsage)
                        } else {
                            result
                        }
                    }
                    is IndexBuildOutcome.Unavailable -> unavailable(outcome.readiness, outcome.budgetUsage)
                }
            }
        }

    internal suspend fun prepare(): ConversationalRecallReadiness = withContext(dispatcher) {
        buildMutex.withLock {
            coroutineContext.ensureActive()
            if (isClosed()) return@withLock ConversationalRecallReadiness.NOT_READY
            when (val outcome = readyIndexLocked(forceFullRebuild = true)) {
                is IndexBuildOutcome.Ready -> ConversationalRecallReadiness.READY
                is IndexBuildOutcome.Unavailable -> outcome.readiness
            }
        }
    }

    override fun close() {
        val detach = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                publishedIndex = null
                pendingMutations.clear()
                observerGenerationsToIgnore.clear()
                lastIncrementalWork = null
                true
            }
        }
        if (!detach) return
        fence.removeInvalidationListener(synchronousInvalidationListener)
        database.invalidationTracker.removeObserver(invalidationObserver)
    }

    private suspend fun readyIndexLocked(forceFullRebuild: Boolean): IndexBuildOutcome {
        val generation = fence.snapshotForIndexBuild()
            ?: return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.STALE)
        val state = synchronized(stateLock) {
            if (closed) return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.NOT_READY)
            PublishedState(publishedIndex, pendingMutations.toList())
        }
        val current = state.index
        if (current != null && current.generation == generation && state.pending.isEmpty()) {
            return IndexBuildOutcome.Ready(current)
        }
        if (!forceFullRebuild && current != null) {
            val changes = contiguousChanges(current.generation, generation, state.pending)
            if (changes != null) {
                val changedIds = changes.flatMapTo(sortedSetOf()) { mutation ->
                    (mutation.change as ValidationRecallCorpusChange.MemoryIdsChanged).memoryIds
                }
                if (changedIds.size > limits.maxIncrementalChangedMemories) {
                    return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.CAPACITY_EXCEEDED)
                }
                val updated = try {
                    applyIncrementalChanges(current, generation, changedIds)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: BoundedRecallCapacityExceeded) {
                    discardPublishedIndex()
                    return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.CAPACITY_EXCEEDED)
                } catch (_: Exception) {
                    discardPublishedIndex()
                    return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.FAILED)
                }
                if (!publishIfCurrent(updated, generation)) {
                    return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.STALE, updated.budgetUsage())
                }
                return IndexBuildOutcome.Ready(updated)
            }
        }
        val built = try {
            buildIndex(generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: BoundedRecallCapacityExceeded) {
            return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.CAPACITY_EXCEEDED)
        } catch (_: Exception) {
            return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.FAILED)
        }
        if (!publishIfCurrent(built, generation)) {
            return IndexBuildOutcome.Unavailable(ConversationalRecallReadiness.STALE, built.budgetUsage())
        }
        return IndexBuildOutcome.Ready(built)
    }

    private suspend fun buildIndex(generation: ValidationRecallGeneration): ConversationalRecallIndex {
        val index = ConversationalRecallIndex(generation, limits)
        var afterMemoryId = ""
        var structuralRows = 0
        while (true) {
            coroutineContext.ensureActive()
            val remaining = limits.maxMemories - index.documentCount
            val pageLimit = minOf(limits.pageSize, remaining + 1)
            val page = reader.memoryPage(afterMemoryId, pageLimit, limits.maxMeaningChars + 1)
            if (page.size > remaining) throw BoundedRecallCapacityExceeded()
            if (page.isEmpty()) break
            val builders = page.mapNotNull { row ->
                ConversationalDocumentBuilder.create(row, limits)?.let { row.memoryId to it }
            }.toMap(linkedMapOf())
            val ids = page.map { it.memoryId }
            val entities = boundedStructuralRows(structuralRows) { reader.memoryEntityRows(ids, it) }
            structuralRows += entities.size
            entities.forEach { row -> builders[row.memoryId]?.entityIds?.add(row.entityId) }
            val outgoing = boundedStructuralRows(structuralRows) { reader.outgoingRelationships(ids, it) }
            structuralRows += outgoing.size
            val incoming = boundedStructuralRows(structuralRows) { reader.incomingRelationships(ids, it) }
            structuralRows += incoming.size
            (outgoing + incoming).asSequence()
                .filter { it.relationshipType in CONVERSATIONAL_RELATIONSHIPS }
                .map(MemoryRelationshipEntity::toBounded)
                .forEach(index::addRelationship)
            builders.values.forEach { index.addDocument(it.build()) }
            afterMemoryId = page.last().memoryId
            if (page.size < pageLimit) break
        }
        if (structuralRows > limits.maxCorpusStructuralRows) throw BoundedRecallCapacityExceeded()
        return index
    }

    private suspend fun applyIncrementalChanges(
        index: ConversationalRecallIndex,
        generation: ValidationRecallGeneration,
        changedIds: Set<String>,
    ): ConversationalRecallIndex {
        if (changedIds.isEmpty()) {
            index.generation = generation
            synchronized(stateLock) { lastIncrementalWork = ConversationalRecallIncrementalWork(0, 0, 0) }
            return index
        }
        coroutineContext.ensureActive()
        val ids = changedIds.sorted()
        val builders = reader.memoryRows(ids, limits.maxMeaningChars + 1).mapNotNull { row ->
            ConversationalDocumentBuilder.create(row, limits)?.let { row.memoryId to it }
        }.toMap(linkedMapOf())
        var structuralRows = 0
        val entities = boundedStructuralRows(structuralRows) { reader.memoryEntityRows(ids, it) }
        structuralRows += entities.size
        entities.forEach { row -> builders[row.memoryId]?.entityIds?.add(row.entityId) }
        val outgoing = boundedStructuralRows(structuralRows) { reader.outgoingRelationships(ids, it) }
        structuralRows += outgoing.size
        val incoming = boundedStructuralRows(structuralRows) { reader.incomingRelationships(ids, it) }
        val relationships = (outgoing + incoming).asSequence()
            .filter { it.relationshipType in CONVERSATIONAL_RELATIONSHIPS }
            .map(MemoryRelationshipEntity::toBounded)
            .toSet()
        val work = index.replaceDocuments(
            changedIds,
            builders.mapValues { it.value.build() },
            relationships,
            generation,
        )
        synchronized(stateLock) {
            lastIncrementalWork = ConversationalRecallIncrementalWork(
                work.changedDocuments,
                work.incidentRelationshipsRemoved,
                work.adjacencyVerticesVisitedForRemoval,
            )
        }
        return index
    }

    internal fun incrementalWorkSnapshot(): ConversationalRecallIncrementalWork? =
        synchronized(stateLock) { lastIncrementalWork }

    private fun contiguousChanges(
        from: ValidationRecallGeneration,
        to: ValidationRecallGeneration,
        pending: List<ValidationRecallCommittedMutation>,
    ): List<ValidationRecallCommittedMutation>? {
        if (from.databaseSessionId != to.databaseSessionId || from.corpusGeneration >= to.corpusGeneration) return null
        val changes = pending.filter { it.generation.corpusGeneration in (from.corpusGeneration + 1)..to.corpusGeneration }
            .sortedBy { it.generation.corpusGeneration }
        val expected = to.corpusGeneration - from.corpusGeneration
        if (expected > Int.MAX_VALUE || changes.size != expected.toInt()) return null
        changes.forEachIndexed { index, mutation ->
            if (mutation.generation.databaseSessionId != to.databaseSessionId ||
                mutation.generation.corpusGeneration != from.corpusGeneration + index + 1L ||
                mutation.change !is ValidationRecallCorpusChange.MemoryIdsChanged
            ) return null
        }
        return changes
    }

    private fun publishIfCurrent(index: ConversationalRecallIndex, generation: ValidationRecallGeneration) =
        synchronized(stateLock) {
            if (closed || !fence.matches(generation) ||
                pendingMutations.any { it.generation.corpusGeneration > generation.corpusGeneration }
            ) return@synchronized false
            pendingMutations.removeAll { it.generation.corpusGeneration <= generation.corpusGeneration }
            publishedIndex = index
            true
        }

    private fun discardPublishedIndex() = synchronized(stateLock) {
        publishedIndex = null
        pendingMutations.clear()
    }

    private fun isClosed() = synchronized(stateLock) { closed }

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

    private fun <T> boundedStructuralRows(rowsRead: Int, load: (Int) -> List<T>): List<T> {
        val remaining = limits.maxCorpusStructuralRows - rowsRead
        val allowed = minOf(limits.maxStructuralRowsPerPage, remaining.coerceAtLeast(0))
        val rows = load(allowed + 1)
        if (rows.size > allowed) throw BoundedRecallCapacityExceeded()
        return rows
    }

    private fun unavailable(
        readiness: ConversationalRecallReadiness,
        budgetUsage: ConversationalRecallBudgetUsage = ConversationalRecallBudgetUsage(),
    ) = ConversationalMemoryRetrieval(
        readiness = readiness,
        generation = fence.snapshot().toConversationalGeneration(),
        budgetUsage = budgetUsage,
    )

    private sealed interface IndexBuildOutcome {
        data class Ready(val index: ConversationalRecallIndex) : IndexBuildOutcome
        data class Unavailable(
            val readiness: ConversationalRecallReadiness,
            val budgetUsage: ConversationalRecallBudgetUsage = ConversationalRecallBudgetUsage(),
        ) : IndexBuildOutcome
    }

    private data class PublishedState(
        val index: ConversationalRecallIndex?,
        val pending: List<ValidationRecallCommittedMutation>,
    )

    private companion object {
        val CONVERSATIONAL_RELATIONSHIPS = setOf(
            MemoryRelationshipType.REINFORCES,
            MemoryRelationshipType.REFINES,
            MemoryRelationshipType.SUPERSEDES,
            MemoryRelationshipType.CORRECTS,
            MemoryRelationshipType.CONTEXTUAL_VARIANT_OF,
            MemoryRelationshipType.PART_OF,
            MemoryRelationshipType.SHARED_CULTURE,
            MemoryRelationshipType.RELATED_TO,
        )
    }
}

private class ConversationalRecallInvalidationObserver(
    owner: TargetedConversationalMemoryRetriever,
    vararg tables: String,
) : InvalidationTracker.Observer(tables) {
    private val owner = WeakReference(owner)
    override fun onInvalidated(tables: Set<String>) {
        owner.get()?.onCanonicalTablesInvalidated()
    }
}

internal interface ConversationalRecallCorpusReader {
    fun memoryPage(afterMemoryId: String, limit: Int, maxMeaningCharsPlusOne: Int): List<ConversationalRecallMemoryRow>
    fun memoryRows(memoryIds: List<String>, maxMeaningCharsPlusOne: Int): List<ConversationalRecallMemoryRow>
    fun memoryEntityRows(memoryIds: List<String>, limit: Int): List<ValidationRecallEntityRow>
    fun outgoingRelationships(memoryIds: List<String>, limit: Int): List<MemoryRelationshipEntity>
    fun incomingRelationships(memoryIds: List<String>, limit: Int): List<MemoryRelationshipEntity>
}

private class RoomConversationalRecallCorpusReader(
    private val dao: MemoryDao,
) : ConversationalRecallCorpusReader {
    override fun memoryPage(afterMemoryId: String, limit: Int, maxMeaningCharsPlusOne: Int) =
        dao.conversationalRecallMemoryPage(afterMemoryId, limit, maxMeaningCharsPlusOne)
    override fun memoryRows(memoryIds: List<String>, maxMeaningCharsPlusOne: Int) =
        dao.conversationalRecallMemoryRows(memoryIds, maxMeaningCharsPlusOne)
    override fun memoryEntityRows(memoryIds: List<String>, limit: Int) =
        dao.validationRecallMemoryEntityRows(memoryIds, limit)
    override fun outgoingRelationships(memoryIds: List<String>, limit: Int) =
        dao.validationRecallOutgoingRelationships(memoryIds, limit)
    override fun incomingRelationships(memoryIds: List<String>, limit: Int) =
        dao.validationRecallIncomingRelationships(memoryIds, limit)
}

private data class ConversationalMemoryMetadata(
    val row: ConversationalRecallMemoryRow,
)

private class ConversationalDocumentBuilder private constructor(
    private val row: ConversationalRecallMemoryRow,
    private val broad: Boolean,
    private val terms: Map<String, Int>,
    val entityIds: MutableSet<String> = sortedSetOf(),
) {
    fun build() = BoundedRecallDocument(
        id = row.memoryId,
        metadata = ConversationalMemoryMetadata(row),
        exactMeaningHash = boundedRecallMeaningHash(row.meaning),
        termFrequencies = terms,
        documentLength = terms.values.sum(),
        textChars = row.meaningLength,
        entityIds = entityIds,
        exactSearchable = broad,
        lexicallySearchable = broad,
        sourceSearchable = false,
        entitySearchable = broad,
    )

    companion object {
        fun create(row: ConversationalRecallMemoryRow, limits: ConversationalRecallLimits): ConversationalDocumentBuilder? {
            if (row.truthState == MemoryTruthState.CORRECTED_FALSE ||
                row.retentionState == MemoryRetentionState.FORGOTTEN
            ) return null
            if (row.meaningLength > limits.maxMeaningChars || row.meaning.length > limits.maxMeaningChars) {
                throw BoundedRecallCapacityExceeded()
            }
            val broad = row.truthState == MemoryTruthState.SUPPORTED &&
                row.certainty != MemoryCertainty.DISPUTED &&
                row.retentionState == MemoryRetentionState.ACTIVE &&
                row.lifecycleState == MemoryLifecycleState.VALIDATED &&
                row.temporalState in BROAD_TEMPORAL_STATES &&
                row.sensitivity == SensitivityLevel.STANDARD
            val terms = if (broad) {
                boundedRecallTermFrequencies(row.meaning, 3, limits.maxTokenChars, limits.maxTokensPerDocument)
            } else {
                emptyMap()
            }
            return ConversationalDocumentBuilder(row, broad, terms)
        }

        private val BROAD_TEMPORAL_STATES = setOf(
            TemporalState.CURRENT,
            TemporalState.ATEMPORAL,
            TemporalState.UNKNOWN,
        )
    }
}

private class ConversationalRecallIndex(
    var generation: ValidationRecallGeneration,
    private val limits: ConversationalRecallLimits,
) {
    private val core = BoundedRecallIndex<ConversationalMemoryMetadata>(
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
        get() = core.documentCount

    fun addDocument(document: BoundedRecallDocument<ConversationalMemoryMetadata>) = core.addDocument(document)
    fun addRelationship(relationship: BoundedRecallRelationship) = core.addRelationship(relationship)

    fun replaceDocuments(
        changedIds: Set<String>,
        replacements: Map<String, BoundedRecallDocument<ConversationalMemoryMetadata>>,
        relationships: Set<BoundedRecallRelationship>,
        nextGeneration: ValidationRecallGeneration,
    ): BoundedRecallIncrementalWork = core.replaceDocuments(changedIds, replacements, relationships).also {
        generation = nextGeneration
    }

    fun budgetUsage() = core.usage().toConversationalUsage()

    suspend fun retrieve(query: ConversationalMemoryQuery): ConversationalMemoryRetrieval {
        val result = core.query(query, ConversationalPolicy)
        val readiness = when (result.status) {
            BoundedRecallQueryStatus.READY -> ConversationalRecallReadiness.READY
            BoundedRecallQueryStatus.QUERY_UNSELECTIVE -> ConversationalRecallReadiness.QUERY_UNSELECTIVE
            BoundedRecallQueryStatus.BUDGET_EXCEEDED -> ConversationalRecallReadiness.BUDGET_EXCEEDED
        }
        if (readiness != ConversationalRecallReadiness.READY) {
            return ConversationalMemoryRetrieval(
                readiness = readiness,
                generation = generation.toConversationalGeneration(),
                budgetUsage = result.usage.toConversationalUsage(),
            )
        }
        val selected = diverseSelection(query, result.candidates)
        return ConversationalMemoryRetrieval(
            memories = selected.map { candidate -> candidate.toItem(query) },
            readiness = ConversationalRecallReadiness.READY,
            generation = generation.toConversationalGeneration(),
            budgetUsage = result.usage.toConversationalUsage(),
        )
    }

    private fun diverseSelection(
        query: ConversationalMemoryQuery,
        candidates: List<BoundedRecallCandidate<ConversationalMemoryMetadata>>,
    ): List<BoundedRecallCandidate<ConversationalMemoryMetadata>> {
        val selected = mutableListOf<BoundedRecallCandidate<ConversationalMemoryMetadata>>()
        val exactMeanings = mutableSetOf<String>()
        val kindScopes = mutableMapOf<Pair<String, String>, Int>()
        val lineages = mutableMapOf<String, Int>()
        candidates.forEach { candidate ->
            if (selected.size >= limits.maxFinalMemories) return@forEach
            val row = candidate.document.metadata.row
            val isDirect = row.memoryId in query.cues.allMemoryCueIds()
            if (!isDirect && candidate.score < MINIMUM_RELEVANCE_SCORE) return@forEach
            if (!exactMeanings.add(candidate.document.exactMeaningHash)) return@forEach
            val kindScope = row.kind.name to row.scope.name
            if ((kindScopes[kindScope] ?: 0) >= MAX_PER_KIND_SCOPE) return@forEach
            val lineageKey = lineageKey(row.memoryId)
            val lineageLimit = if (row.memoryId in query.cues.historicalMemoryIds) 2 else 1
            if ((lineages[lineageKey] ?: 0) >= lineageLimit) return@forEach
            val tokens = safeTokens(row.meaning)
            if (selected.any { existing -> tokenSimilarity(tokens, safeTokens(existing.document.metadata.row.meaning)) >= 0.85 }) {
                return@forEach
            }
            selected += candidate
            kindScopes[kindScope] = (kindScopes[kindScope] ?: 0) + 1
            lineages[lineageKey] = (lineages[lineageKey] ?: 0) + 1
        }
        return selected
    }

    private fun lineageKey(memoryId: String): String {
        val visited = sortedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += memoryId
        while (pending.isNotEmpty() && visited.size < limits.maxRelationshipVisits) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            core.neighbors(current, LINEAGE_RELATIONSHIPS)
                .filterNot(visited::contains)
                .forEach(pending::addLast)
        }
        return visited.first()
    }

    private fun BoundedRecallCandidate<ConversationalMemoryMetadata>.toItem(
        query: ConversationalMemoryQuery,
    ): ConversationalMemoryItem {
        val row = document.metadata.row
        val reasons = linkedSetOf<ConversationalSelectionReason>()
        if (row.memoryId in query.cues.allMemoryCueIds()) reasons += ConversationalSelectionReason.DIRECT_GROUNDED_CUE
        if (row.memoryId in query.cues.historicalMemoryIds) reasons += ConversationalSelectionReason.HISTORICAL_CUE
        if (row.memoryId in query.cues.activeOpenLoopMemoryIds) reasons += ConversationalSelectionReason.ACTIVE_OPEN_LOOP
        if (document.entityIds.any(query.cues.groundedEntityIds::contains)) reasons += ConversationalSelectionReason.GROUNDED_ENTITY
        if (score < DIRECT_SCORE) reasons += ConversationalSelectionReason.LEXICAL_RELEVANCE
        if (reasons.isEmpty()) reasons += ConversationalSelectionReason.GROUNDED_RELATIONSHIP
        return ConversationalMemoryItem(
            memoryId = row.memoryId,
            meaning = row.meaning,
            kind = row.kind,
            scope = row.scope,
            epistemicBasis = row.epistemicBasis,
            certainty = row.certainty,
            truthState = row.truthState,
            retentionState = row.retentionState,
            lifecycleState = row.lifecycleState,
            temporalState = row.temporalState,
            validFrom = row.validFrom,
            validUntil = row.validUntil,
            sensitivity = row.sensitivity,
            autobiographicalSignificance = row.autobiographicalSignificance,
            relationshipSignificance = row.relationshipSignificance,
            practicalSignificance = row.practicalSignificance,
            identitySignificance = row.identitySignificance,
            selectionReasons = reasons,
        )
    }

    private object ConversationalPolicy : BoundedRecallPolicy<ConversationalMemoryQuery, ConversationalMemoryMetadata> {
        override fun queryText(query: ConversationalMemoryQuery) = query.currentInteraction
        override fun directDocumentIds(query: ConversationalMemoryQuery) = query.cues.allMemoryCueIds()
        override fun entityIds(query: ConversationalMemoryQuery) = query.cues.groundedEntityIds
        override fun exactMeaningHash(query: ConversationalMemoryQuery) = boundedRecallMeaningHash(query.currentInteraction)

        override fun isEligible(
            query: ConversationalMemoryQuery,
            document: BoundedRecallDocument<ConversationalMemoryMetadata>,
        ): Boolean {
            val row = document.metadata.row
            val cues = query.cues
            if (row.truthState == MemoryTruthState.CORRECTED_FALSE || row.retentionState == MemoryRetentionState.FORGOTTEN) {
                return false
            }
            if ((row.truthState == MemoryTruthState.DISPUTED || row.certainty == MemoryCertainty.DISPUTED) &&
                row.memoryId !in cues.disputedMemoryIds
            ) return false
            if (row.retentionState == MemoryRetentionState.DORMANT && row.memoryId !in cues.dormantMemoryIds) return false
            if (row.lifecycleState in HISTORICAL_LIFECYCLE_STATES && row.memoryId !in cues.historicalMemoryIds) return false
            if (row.temporalState == TemporalState.HISTORICAL && row.memoryId !in cues.historicalMemoryIds) return false
            if (row.temporalState == TemporalState.TIME_BOUNDED &&
                row.memoryId !in cues.timeBoundMemoryIds && row.memoryId !in cues.historicalMemoryIds
            ) return false
            if (row.validFrom != null && query.now < row.validFrom && row.memoryId !in cues.historicalMemoryIds) return false
            if (row.validUntil != null && query.now >= row.validUntil && row.memoryId !in cues.historicalMemoryIds) return false
            if (row.sensitivity != SensitivityLevel.STANDARD &&
                row.memoryId !in cues.sensitiveMemoryIds && row.memoryId !in cues.activeOpenLoopMemoryIds
            ) return false
            return true
        }

        override fun directScore(
            query: ConversationalMemoryQuery,
            document: BoundedRecallDocument<ConversationalMemoryMetadata>,
        ) = if (document.id in query.cues.activeOpenLoopMemoryIds) ACTIVE_LOOP_SCORE else DIRECT_SCORE

        override fun exactScore(
            query: ConversationalMemoryQuery,
            document: BoundedRecallDocument<ConversationalMemoryMetadata>,
        ) = EXACT_SCORE

        override fun entityScore(
            query: ConversationalMemoryQuery,
            document: BoundedRecallDocument<ConversationalMemoryMetadata>,
        ) = ENTITY_SCORE

        override fun lexicalBonus(
            query: ConversationalMemoryQuery,
            document: BoundedRecallDocument<ConversationalMemoryMetadata>,
        ) = document.metadata.row.maxNonEmotionalSignificance().rank * SIGNIFICANCE_TIE_BREAK

        override fun relationshipScore(
            query: ConversationalMemoryQuery,
            seed: BoundedRecallDocument<ConversationalMemoryMetadata>,
            related: BoundedRecallDocument<ConversationalMemoryMetadata>,
            relationshipType: String,
        ) = if (relationshipType in CONTEXT_RELATIONSHIPS) RELATIONSHIP_SCORE else 0.0
    }

    private fun safeTokens(text: String): Set<String> = try {
        BoundedRecallTokenizer.tokens(text, limits.maxTokenChars).toSet()
    } catch (_: BoundedRecallTokenLimitExceeded) {
        emptySet()
    }

    private fun tokenSimilarity(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    private companion object {
        const val MINIMUM_RELEVANCE_SCORE = 0.25
        const val MAX_PER_KIND_SCOPE = 2
        const val DIRECT_SCORE = 100.0
        const val ACTIVE_LOOP_SCORE = 120.0
        const val EXACT_SCORE = 25.0
        const val ENTITY_SCORE = 2.5
        const val RELATIONSHIP_SCORE = 1.5
        const val SIGNIFICANCE_TIE_BREAK = 0.05
        val HISTORICAL_LIFECYCLE_STATES = setOf(MemoryLifecycleState.SUPERSEDED, MemoryLifecycleState.RESOLVED)
        val LINEAGE_RELATIONSHIPS = setOf(
            MemoryRelationshipType.REFINES.name,
            MemoryRelationshipType.SUPERSEDES.name,
            MemoryRelationshipType.CORRECTS.name,
        )
        val CONTEXT_RELATIONSHIPS = setOf(
            MemoryRelationshipType.REINFORCES.name,
            MemoryRelationshipType.CONTEXTUAL_VARIANT_OF.name,
            MemoryRelationshipType.PART_OF.name,
            MemoryRelationshipType.SHARED_CULTURE.name,
            MemoryRelationshipType.RELATED_TO.name,
        )
    }
}

private fun MemoryRelationshipEntity.toBounded() = BoundedRecallRelationship(
    sourceId = sourceMemoryId,
    targetId = targetMemoryId,
    type = relationshipType.name,
)

private fun ConversationalRecallMemoryRow.maxNonEmotionalSignificance(): RankedSignificance {
    val values = listOf(
        autobiographicalSignificance,
        relationshipSignificance,
        practicalSignificance,
        identitySignificance,
    )
    return RankedSignificance(values.maxOfOrNull { it.rank() } ?: 0)
}

private data class RankedSignificance(val rank: Int)

private fun SignificanceLevel?.rank() = when (this) {
    null, SignificanceLevel.NONE -> 0
    SignificanceLevel.LOW -> 1
    SignificanceLevel.MODERATE -> 2
    SignificanceLevel.HIGH -> 3
    SignificanceLevel.CORE -> 4
}

private fun com.shai.riven.data.context.RivenGroundedRecallCues.allMemoryCueIds(): Set<String> = buildSet {
    addAll(directlyRelevantMemoryIds)
    addAll(historicalMemoryIds)
    addAll(dormantMemoryIds)
    addAll(disputedMemoryIds)
    addAll(timeBoundMemoryIds)
    addAll(sensitiveMemoryIds)
    addAll(activeOpenLoopMemoryIds)
}

private fun ConversationalMemoryQuery.withinLimits(limits: ConversationalRecallLimits): Boolean =
    currentInteraction.length <= limits.maxQueryChars &&
        cues.groundedEntityIds.size <= limits.maxQueryEntityIds &&
        cues.allMemoryCueIds().size <= limits.maxGroundedCueIds &&
        cues.groundedEntityIds.all { it.length <= MAX_GROUNDED_ID_CHARS } &&
        cues.allMemoryCueIds().all { it.length <= MAX_GROUNDED_ID_CHARS }

private const val MAX_GROUNDED_ID_CHARS = 128

private fun ValidationRecallGeneration.toConversationalGeneration() = ConversationalRecallGeneration(
    databaseSessionId = databaseSessionId,
    corpusGeneration = corpusGeneration,
)

private fun BoundedRecallUsage.toConversationalUsage() = ConversationalRecallBudgetUsage(
    indexedMemories = indexedDocuments,
    indexedTokens = indexedTokens,
    postingVisits = postingVisits,
    structuralRowsVisited = structuralRows + queryStructuralVisits,
    graphSeedsVisited = graphSeeds,
    relationshipRowsVisited = relationshipVisits,
    candidatePoolSize = candidatePoolSize,
)
