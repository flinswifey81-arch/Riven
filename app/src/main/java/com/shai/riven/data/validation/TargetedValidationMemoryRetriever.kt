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
    val maxCorpusPostings: Int = 500_000,
    val maxCorpusStructuralRows: Int = 200_000,
    val maxStructuralRowsPerPage: Int = 4_096,
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
                maxCorpusPostings,
                maxCorpusStructuralRows,
                maxStructuralRowsPerPage,
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
        require(maxFinalIds <= MAX_VALIDATION_RELATED_MEMORIES)
        require(maxCandidatePool >= maxFinalIds)
        require(maxTextCharsPerDocument >= maxMeaningChars)
        require(pageSize <= 256)
    }
}

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
    @Volatile
    private var publishedIndex: ValidationRecallIndex? = null
    @Volatile
    private var closed = false
    private val synchronousInvalidationListener: () -> Unit = { publishedIndex = null }

    private val invalidationObserver = object : InvalidationTracker.Observer(
        "memories",
        "memory_evidence",
        "memory_relationships",
        "memory_entity_links",
        "experiences",
        "experience_entity_links",
        "entities",
        "suppression_tombstones",
    ) {
        override fun onInvalidated(tables: Set<String>) {
            publishedIndex = null
        }
    }

    init {
        fence.addInvalidationListener(synchronousInvalidationListener)
        database.invalidationTracker.addObserver(invalidationObserver)
    }

    override suspend fun retrieve(query: ValidationMemoryQuery): ValidationMemoryRetrieval = withContext(dispatcher) {
        coroutineContext.ensureActive()
        if (closed) return@withContext unavailable(ValidationRecallReadiness.NOT_READY)
        when (val outcome = readyIndex()) {
            is IndexBuildOutcome.Ready -> outcome.index.retrieve(query, limits)
            is IndexBuildOutcome.Unavailable -> unavailable(outcome.readiness, outcome.budgetUsage)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        publishedIndex = null
        fence.removeInvalidationListener(synchronousInvalidationListener)
        database.invalidationTracker.removeObserver(invalidationObserver)
    }

    private suspend fun readyIndex(): IndexBuildOutcome {
        val current = fence.snapshot()
        publishedIndex?.takeIf { it.generation == current }?.let { return IndexBuildOutcome.Ready(it) }
        return buildMutex.withLock {
            coroutineContext.ensureActive()
            val generation = fence.snapshot()
            publishedIndex?.takeIf { it.generation == generation }?.let { return@withLock IndexBuildOutcome.Ready(it) }
            val built = try {
                buildIndex(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ValidationRecallCapacityExceeded) {
                return@withLock IndexBuildOutcome.Unavailable(ValidationRecallReadiness.CAPACITY_EXCEEDED)
            } catch (_: Exception) {
                return@withLock IndexBuildOutcome.Unavailable(ValidationRecallReadiness.FAILED)
            }
            if (!fence.matches(generation)) {
                return@withLock IndexBuildOutcome.Unavailable(
                    ValidationRecallReadiness.STALE,
                    built.budgetUsage,
                )
            }
            publishedIndex = built
            IndexBuildOutcome.Ready(built)
        }
    }

    private suspend fun buildIndex(generation: ValidationRecallGeneration): ValidationRecallIndex {
        val documents = linkedMapOf<String, RecallDocumentBuilder>()
        val relationships = linkedSetOf<RecallRelationship>()
        var afterMemoryId = ""
        var structuralRows = 0

        while (true) {
            coroutineContext.ensureActive()
            val remainingMemories = limits.maxMemories - documents.size
            val pageLimit = minOf(limits.pageSize, remainingMemories + 1)
            val page = reader.memoryPage(afterMemoryId, pageLimit, limits.maxMeaningChars + 1)
            if (page.size > remainingMemories) throw ValidationRecallCapacityExceeded()
            if (page.isEmpty()) break
            page.forEach { row ->
                if (row.meaningLength > limits.maxMeaningChars.toLong() || row.meaning.length > limits.maxMeaningChars) {
                    throw ValidationRecallCapacityExceeded()
                }
                documents[row.memoryId] = RecallDocumentBuilder(row)
            }
            val pageIds = page.map { it.memoryId }

            val evidenceRows = boundedStructuralRows(structuralRows) { limit ->
                reader.evidenceRows(pageIds, limit, limits.maxSourceChars + 1)
            }
            structuralRows += evidenceRows.size
            evidenceRows.forEach { row ->
                val document = documents.getValue(row.memoryId)
                if (row.sourceLength != null && row.sourceLength > limits.maxSourceChars.toLong()) {
                    throw ValidationRecallCapacityExceeded()
                }
                if (document.row.retentionState != MemoryRetentionState.FORGOTTEN &&
                    row.sourceAvailability == ExperienceAvailability.AVAILABLE
                ) {
                    document.sourceExperienceIds += row.sourceExperienceId
                    if (document.row.sensitivity == SensitivityLevel.STANDARD &&
                        row.sourceSensitivity == SensitivityLevel.STANDARD
                    ) {
                        row.sourceContent?.let(document.sourceTexts::add)
                    }
                }
            }

            val entityRows = boundedStructuralRows(structuralRows) { limit ->
                reader.memoryEntityRows(pageIds, limit)
            }
            structuralRows += entityRows.size
            entityRows.forEach { row ->
                val document = documents.getValue(row.memoryId)
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
                .mapTo(relationships) {
                    RecallRelationship(it.sourceMemoryId, it.targetMemoryId, it.relationshipType)
                }

            afterMemoryId = page.last().memoryId
            if (page.size < pageLimit) break
        }

        val postings = linkedMapOf<String, MutableList<RecallPosting>>()
        val exactMeanings = linkedMapOf<String, MutableList<String>>()
        val sourceIndex = linkedMapOf<String, MutableList<String>>()
        val entityIndex = linkedMapOf<String, MutableList<String>>()
        val immutableDocuments = linkedMapOf<String, RecallDocument>()
        var indexedTokens = 0
        var postingCount = 0

        documents.values.forEach { builder ->
            coroutineContext.ensureActive()
            val document = builder.build(limits)
            immutableDocuments[document.memoryId] = document
            indexedTokens += document.documentLength
            if (document.isBroadlyAccessible) {
                exactMeanings.getOrPut(document.meaningHash) { mutableListOf() } += document.memoryId
                document.termFrequencies.forEach { (term, frequency) ->
                    postings.getOrPut(term) { mutableListOf() } += RecallPosting(document.memoryId, frequency)
                    postingCount++
                    if (postingCount > limits.maxCorpusPostings) throw ValidationRecallCapacityExceeded()
                }
                document.entityIds.forEach { entityId ->
                    entityIndex.getOrPut(entityId) { mutableListOf() } += document.memoryId
                }
            }
            if (document.rowRetentionState != MemoryRetentionState.FORGOTTEN) {
                document.sourceExperienceIds.forEach { sourceId ->
                    sourceIndex.getOrPut(sourceId) { mutableListOf() } += document.memoryId
                }
            }
        }

        val adjacency = linkedMapOf<String, MutableSet<String>>()
        relationships.forEach { relationship ->
            if (relationship.sourceMemoryId in immutableDocuments && relationship.targetMemoryId in immutableDocuments) {
                adjacency.getOrPut(relationship.sourceMemoryId) { sortedSetOf() } += relationship.targetMemoryId
                adjacency.getOrPut(relationship.targetMemoryId) { sortedSetOf() } += relationship.sourceMemoryId
            }
        }
        return ValidationRecallIndex(
            generation = generation,
            documents = immutableDocuments,
            postings = postings.mapValues { (_, value) -> value.sortedBy { it.memoryId } },
            exactMeanings = exactMeanings.mapValues { (_, value) -> value.sorted() },
            sourceIndex = sourceIndex.mapValues { (_, value) -> value.distinct().sorted() },
            entityIndex = entityIndex.mapValues { (_, value) -> value.distinct().sorted() },
            adjacency = adjacency.mapValues { (_, value) -> value.toList() },
            broadDocumentCount = immutableDocuments.values.count { it.isBroadlyAccessible },
            averageDocumentLength = immutableDocuments.values
                .filter { it.isBroadlyAccessible && it.documentLength > 0 }
                .map { it.documentLength }
                .average()
                .takeUnless { it.isNaN() } ?: 1.0,
            budgetUsage = ValidationRecallBudgetUsage(
                indexedMemories = immutableDocuments.size,
                indexedTokens = indexedTokens,
                structuralRowsVisited = structuralRows,
            ),
        )
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

    private companion object {
        val COMPARISON_RELATIONSHIPS = setOf(
            MemoryRelationshipType.REFINES,
            MemoryRelationshipType.SUPERSEDES,
            MemoryRelationshipType.CORRECTS,
            MemoryRelationshipType.CONTRADICTS,
        )
    }
}

internal interface ValidationRecallCorpusReader {
    fun memoryPage(afterMemoryId: String, limit: Int, maxMeaningCharsPlusOne: Int): List<ValidationRecallMemoryRow>
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

private data class RecallDocumentBuilder(
    val row: ValidationRecallMemoryRow,
    val sourceTexts: MutableList<String> = mutableListOf(),
    val sourceExperienceIds: MutableSet<String> = sortedSetOf(),
    val entityIds: MutableSet<String> = sortedSetOf(),
) {
    val isBroadlyAccessible: Boolean
        get() = row.retentionState != MemoryRetentionState.FORGOTTEN &&
            row.sensitivity == SensitivityLevel.STANDARD

    fun build(limits: TargetedValidationRecallLimits): RecallDocument {
        val termFrequencies = linkedMapOf<String, Int>()
        var totalChars = row.meaningLength
        var tokenCount = 0
        if (isBroadlyAccessible) {
            safeIndexTokens(row.meaning, limits.maxTokenChars).forEach { token ->
                termFrequencies[token] = (termFrequencies[token] ?: 0) + MEANING_TOKEN_WEIGHT
                tokenCount += MEANING_TOKEN_WEIGHT
            }
            sourceTexts.forEach { text ->
                totalChars += text.length.toLong()
                if (totalChars > limits.maxTextCharsPerDocument.toLong()) throw ValidationRecallCapacityExceeded()
                safeIndexTokens(text, limits.maxTokenChars).forEach { token ->
                    termFrequencies[token] = (termFrequencies[token] ?: 0) + SOURCE_TOKEN_WEIGHT
                    tokenCount += SOURCE_TOKEN_WEIGHT
                }
            }
            if (tokenCount > limits.maxTokensPerDocument) throw ValidationRecallCapacityExceeded()
        }
        return RecallDocument(
            memoryId = row.memoryId,
            kind = row.kind,
            scope = row.scope,
            rowRetentionState = row.retentionState,
            isBroadlyAccessible = isBroadlyAccessible,
            meaningHash = meaningHash(row.meaning),
            termFrequencies = termFrequencies,
            documentLength = tokenCount,
            sourceExperienceIds = sourceExperienceIds,
            entityIds = entityIds,
        )
    }

    private companion object {
        const val MEANING_TOKEN_WEIGHT = 3
        const val SOURCE_TOKEN_WEIGHT = 1
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
    val sourceExperienceIds: Set<String>,
    val entityIds: Set<String>,
)

private data class RecallPosting(val memoryId: String, val termFrequency: Int)

private data class RecallRelationship(
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val relationshipType: MemoryRelationshipType,
)

private data class ValidationRecallIndex(
    val generation: ValidationRecallGeneration,
    val documents: Map<String, RecallDocument>,
    val postings: Map<String, List<RecallPosting>>,
    val exactMeanings: Map<String, List<String>>,
    val sourceIndex: Map<String, List<String>>,
    val entityIndex: Map<String, List<String>>,
    val adjacency: Map<String, List<String>>,
    val broadDocumentCount: Int,
    val averageDocumentLength: Double,
    val budgetUsage: ValidationRecallBudgetUsage,
) {
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
            val inverseDocumentFrequency = ln(
                1.0 + (broadDocumentCount - termPostings.size + 0.5) / (termPostings.size + 0.5),
            )
            termPostings.forEach { posting ->
                postingVisits++
                val document = documents.getValue(posting.memoryId)
                val normalizedLength = document.documentLength / averageDocumentLength
                val termFrequencyScore = posting.termFrequency * (BM25_K + 1.0) /
                    (posting.termFrequency + BM25_K * (1.0 - BM25_B + BM25_B * normalizedLength))
                val structuralBonus =
                    (if (document.kind == query.proposedKind) SAME_KIND_BONUS else 0.0) +
                        (if (document.scope == query.proposedScope) SAME_SCOPE_BONUS else 0.0)
                if (!add(posting.memoryId, inverseDocumentFrequency * termFrequencyScore + structuralBonus)) {
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
            budgetUsage = budgetUsage.copy(
                postingVisits = postingVisits,
                structuralRowsVisited = budgetUsage.structuralRowsVisited + structuralVisits,
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
        budgetUsage = budgetUsage.copy(
            postingVisits = postingVisits,
            structuralRowsVisited = budgetUsage.structuralRowsVisited + structuralVisits,
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
