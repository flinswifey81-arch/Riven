package com.shai.riven.data.recall

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
import kotlinx.coroutines.ensureActive

internal data class BoundedRecallLimits(
    val maxDocuments: Int,
    val maxCorpusTextChars: Long,
    val maxCorpusTokens: Int,
    val maxCorpusPostings: Int,
    val maxCorpusStructuralRows: Int,
    val maxTokenChars: Int,
    val maxQueryTerms: Int,
    val maxPostingVisits: Int,
    val maxStructuralVisits: Int,
    val maxGraphSeeds: Int,
    val maxRelationshipVisits: Int,
    val maxCandidatePool: Int,
)

internal data class BoundedRecallDocument<M>(
    val id: String,
    val metadata: M,
    val exactMeaningHash: String,
    val termFrequencies: Map<String, Int>,
    val documentLength: Int,
    val textChars: Long,
    val sourceIds: Set<String> = emptySet(),
    val entityIds: Set<String> = emptySet(),
    val exactSearchable: Boolean = true,
    val lexicallySearchable: Boolean = true,
    val sourceSearchable: Boolean = true,
    val entitySearchable: Boolean = true,
) {
    val structuralRows: Int
        get() = sourceIds.size + entityIds.size
}

internal data class BoundedRecallRelationship(
    val sourceId: String,
    val targetId: String,
    val type: String,
)

internal interface BoundedRecallPolicy<Q, M> {
    fun queryText(query: Q): String
    fun directDocumentIds(query: Q): Set<String> = emptySet()
    fun sourceIds(query: Q): Collection<String> = emptyList()
    fun entityIds(query: Q): Collection<String> = emptyList()
    fun exactMeaningHash(query: Q): String? = null
    fun isEligible(query: Q, document: BoundedRecallDocument<M>): Boolean
    fun directScore(query: Q, document: BoundedRecallDocument<M>): Double = 0.0
    fun exactScore(query: Q, document: BoundedRecallDocument<M>): Double = 0.0
    fun sourceScore(query: Q, document: BoundedRecallDocument<M>): Double = 0.0
    fun entityScore(query: Q, document: BoundedRecallDocument<M>): Double = 0.0
    fun lexicalBonus(query: Q, document: BoundedRecallDocument<M>): Double = 0.0
    fun relationshipScore(
        query: Q,
        seed: BoundedRecallDocument<M>,
        related: BoundedRecallDocument<M>,
        relationshipType: String,
    ): Double = 0.0
}

internal enum class BoundedRecallQueryStatus {
    READY,
    QUERY_UNSELECTIVE,
    BUDGET_EXCEEDED,
}

internal data class BoundedRecallUsage(
    val indexedDocuments: Int,
    val indexedTokens: Int,
    val structuralRows: Int,
    val postingVisits: Int = 0,
    val queryStructuralVisits: Int = 0,
    val graphSeeds: Int = 0,
    val relationshipVisits: Int = 0,
    val candidatePoolSize: Int = 0,
)

internal data class BoundedRecallCandidate<M>(
    val document: BoundedRecallDocument<M>,
    val score: Double,
)

internal data class BoundedRecallQueryResult<M>(
    val status: BoundedRecallQueryStatus,
    val candidates: List<BoundedRecallCandidate<M>> = emptyList(),
    val usage: BoundedRecallUsage,
)

internal data class BoundedRecallIncrementalWork(
    val changedDocuments: Int,
    val incidentRelationshipsRemoved: Int,
    val adjacencyVerticesVisitedForRemoval: Int,
)

/**
 * Shared bounded lexical/structural machinery. Each policy owns a separate instance and supplies
 * eligibility and scoring; the index never promotes one policy's cached corpus into another.
 */
internal class BoundedRecallIndex<M>(
    private val limits: BoundedRecallLimits,
) {
    private val documents = linkedMapOf<String, BoundedRecallDocument<M>>()
    private val postings = linkedMapOf<String, MutableMap<String, Int>>()
    private val exactMeanings = linkedMapOf<String, MutableSet<String>>()
    private val sourceIndex = linkedMapOf<String, MutableSet<String>>()
    private val entityIndex = linkedMapOf<String, MutableSet<String>>()
    private val relationships = linkedSetOf<BoundedRecallRelationship>()
    private val relationshipsByDocument = linkedMapOf<String, MutableSet<BoundedRecallRelationship>>()
    private val adjacency = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
    private var indexedTextChars = 0L
    private var indexedTokens = 0
    private var postingCount = 0
    private var structuralRows = 0
    private var lexicalDocumentCount = 0
    private var positiveLexicalDocumentCount = 0
    private var lexicalDocumentTokens = 0L

    val documentCount: Int
        get() = documents.size

    fun document(id: String): BoundedRecallDocument<M>? = documents[id]

    fun addDocument(document: BoundedRecallDocument<M>) {
        if (document.id in documents ||
            documents.size >= limits.maxDocuments ||
            indexedTextChars > limits.maxCorpusTextChars - document.textChars ||
            indexedTokens > limits.maxCorpusTokens - document.documentLength ||
            postingCount > limits.maxCorpusPostings - document.termFrequencies.size ||
            structuralRows > limits.maxCorpusStructuralRows - document.structuralRows
        ) {
            throw BoundedRecallCapacityExceeded()
        }
        documents[document.id] = document
        indexedTextChars += document.textChars
        indexedTokens += document.documentLength
        postingCount += document.termFrequencies.size
        structuralRows += document.structuralRows
        if (document.lexicallySearchable) {
            lexicalDocumentCount++
            if (document.documentLength > 0) {
                positiveLexicalDocumentCount++
                lexicalDocumentTokens += document.documentLength.toLong()
            }
            document.termFrequencies.forEach { (term, frequency) ->
                postings.getOrPut(term) { linkedMapOf() }[document.id] = frequency
            }
        }
        if (document.exactSearchable) exactMeanings.getOrPut(document.exactMeaningHash) { sortedSetOf() } += document.id
        if (document.sourceSearchable) document.sourceIds.forEach { sourceIndex.getOrPut(it) { sortedSetOf() } += document.id }
        if (document.entitySearchable) document.entityIds.forEach { entityIndex.getOrPut(it) { sortedSetOf() } += document.id }
        relationshipsByDocument[document.id].orEmpty().forEach(::activateRelationship)
    }

    fun addRelationship(relationship: BoundedRecallRelationship) {
        if (!relationships.add(relationship)) return
        if (structuralRows >= limits.maxCorpusStructuralRows) throw BoundedRecallCapacityExceeded()
        structuralRows++
        relationshipsByDocument.getOrPut(relationship.sourceId) { linkedSetOf() } += relationship
        relationshipsByDocument.getOrPut(relationship.targetId) { linkedSetOf() } += relationship
        activateRelationship(relationship)
    }

    fun replaceDocuments(
        changedIds: Set<String>,
        replacements: Map<String, BoundedRecallDocument<M>>,
        replacementRelationships: Set<BoundedRecallRelationship>,
    ): BoundedRecallIncrementalWork {
        val relationshipsToRemove = changedIds.flatMapTo(linkedSetOf()) { relationshipsByDocument[it].orEmpty() }
        var adjacencyVisits = 0
        relationshipsToRemove.forEach { adjacencyVisits += removeRelationship(it) }
        changedIds.forEach { id ->
            if (removeDocument(id)) adjacencyVisits++
        }
        replacements.toSortedMap().values.forEach(::addDocument)
        replacementRelationships.sortedWith(
            compareBy<BoundedRecallRelationship>(BoundedRecallRelationship::sourceId)
                .thenBy(BoundedRecallRelationship::targetId)
                .thenBy(BoundedRecallRelationship::type),
        ).forEach(::addRelationship)
        return BoundedRecallIncrementalWork(changedIds.size, relationshipsToRemove.size, adjacencyVisits)
    }

    fun neighbors(id: String, allowedTypes: Set<String>): Set<String> =
        adjacency[id].orEmpty().filterKeys(allowedTypes::contains).values.flatten().toSortedSet()

    fun usage() = BoundedRecallUsage(documentCount, indexedTokens, structuralRows)

    suspend fun <Q> query(query: Q, policy: BoundedRecallPolicy<Q, M>): BoundedRecallQueryResult<M> {
        coroutineContext.ensureActive()
        val queryTerms = try {
            BoundedRecallTokenizer.tokens(policy.queryText(query), limits.maxTokenChars).distinct()
        } catch (_: BoundedRecallTokenLimitExceeded) {
            return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED)
        }
        if (queryTerms.size > limits.maxQueryTerms) return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED)

        val scores = linkedMapOf<String, Double>()
        var postingVisits = 0
        var queryStructuralVisits = 0
        var relationshipVisits = 0

        fun add(id: String, score: Double): Boolean {
            val document = documents[id] ?: return true
            if (!policy.isEligible(query, document) || score <= 0.0) return true
            scores[id] = (scores[id] ?: 0.0) + score
            return scores.size <= limits.maxCandidatePool
        }

        policy.directDocumentIds(query).toSortedSet().forEach { id ->
            val document = documents[id] ?: return@forEach
            if (!add(id, policy.directScore(query, document))) return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED)
        }
        policy.exactMeaningHash(query)?.let { hash ->
            exactMeanings[hash].orEmpty().forEach { id ->
                val document = documents.getValue(id)
                if (!add(id, policy.exactScore(query, document))) return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED)
            }
        }
        policy.sourceIds(query).distinct().sorted().forEach { sourceId ->
            sourceIndex[sourceId].orEmpty().forEach { id ->
                queryStructuralVisits++
                if (queryStructuralVisits > limits.maxStructuralVisits ||
                    !add(id, policy.sourceScore(query, documents.getValue(id)))
                ) return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED, postingVisits, queryStructuralVisits)
            }
        }
        policy.entityIds(query).distinct().sorted().forEach { entityId ->
            entityIndex[entityId].orEmpty().forEach { id ->
                queryStructuralVisits++
                if (queryStructuralVisits > limits.maxStructuralVisits ||
                    !add(id, policy.entityScore(query, documents.getValue(id)))
                ) return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED, postingVisits, queryStructuralVisits)
            }
        }
        if (queryTerms.isEmpty() && scores.isEmpty()) {
            return result(BoundedRecallQueryStatus.QUERY_UNSELECTIVE, postingVisits, queryStructuralVisits)
        }
        val orderedTerms = queryTerms.sortedWith(compareBy<String> { postings[it]?.size ?: 0 }.thenBy { it })
        if (orderedTerms.sumOf { postings[it]?.size ?: 0 } > limits.maxPostingVisits) {
            return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED, postingVisits, queryStructuralVisits)
        }
        orderedTerms.forEach { term ->
            coroutineContext.ensureActive()
            val termPostings = postings[term].orEmpty()
            val averageLength = if (positiveLexicalDocumentCount == 0) 1.0
            else lexicalDocumentTokens.toDouble() / positiveLexicalDocumentCount
            val inverseDocumentFrequency = ln(
                1.0 + (lexicalDocumentCount - termPostings.size + 0.5) / (termPostings.size + 0.5),
            )
            termPostings.forEach { (id, frequency) ->
                postingVisits++
                val document = documents.getValue(id)
                val normalizedLength = document.documentLength / averageLength
                val termScore = frequency * (BM25_K + 1.0) /
                    (frequency + BM25_K * (1.0 - BM25_B + BM25_B * normalizedLength))
                if (!add(id, inverseDocumentFrequency * termScore + policy.lexicalBonus(query, document))) {
                    return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED, postingVisits, queryStructuralVisits)
                }
            }
        }

        val graphSeeds = scores.entries.sortedWith(SCORE_ORDER).take(limits.maxGraphSeeds)
        graphSeeds.forEach { seed ->
            coroutineContext.ensureActive()
            val seedDocument = documents.getValue(seed.key)
            relationshipsByDocument[seed.key].orEmpty().forEach { relationship ->
                relationshipVisits++
                if (relationshipVisits > limits.maxRelationshipVisits) {
                    return result(
                        BoundedRecallQueryStatus.BUDGET_EXCEEDED,
                        postingVisits,
                        queryStructuralVisits,
                        graphSeeds.size,
                        relationshipVisits,
                    )
                }
                val relatedId = if (relationship.sourceId == seed.key) relationship.targetId else relationship.sourceId
                val related = documents[relatedId] ?: return@forEach
                if (!add(relatedId, policy.relationshipScore(query, seedDocument, related, relationship.type))) {
                    return result(BoundedRecallQueryStatus.BUDGET_EXCEEDED, postingVisits, queryStructuralVisits)
                }
            }
        }
        val candidates = scores.entries.sortedWith(SCORE_ORDER).map { entry ->
            BoundedRecallCandidate(documents.getValue(entry.key), entry.value)
        }
        return result(
            BoundedRecallQueryStatus.READY,
            postingVisits,
            queryStructuralVisits,
            graphSeeds.size,
            relationshipVisits,
            candidates,
        )
    }

    private fun activateRelationship(relationship: BoundedRecallRelationship) {
        if (relationship.sourceId !in documents || relationship.targetId !in documents) return
        adjacency.getOrPut(relationship.sourceId) { linkedMapOf() }
            .getOrPut(relationship.type) { sortedSetOf() } += relationship.targetId
        adjacency.getOrPut(relationship.targetId) { linkedMapOf() }
            .getOrPut(relationship.type) { sortedSetOf() } += relationship.sourceId
    }

    private fun removeDocument(id: String): Boolean {
        val document = documents.remove(id) ?: return false
        indexedTextChars -= document.textChars
        indexedTokens -= document.documentLength
        postingCount -= document.termFrequencies.size
        structuralRows -= document.structuralRows
        if (document.lexicallySearchable) {
            lexicalDocumentCount--
            if (document.documentLength > 0) {
                positiveLexicalDocumentCount--
                lexicalDocumentTokens -= document.documentLength.toLong()
            }
            document.termFrequencies.keys.forEach { postings.removePosting(it, id) }
        }
        if (document.exactSearchable) exactMeanings.removeMember(document.exactMeaningHash, id)
        if (document.sourceSearchable) document.sourceIds.forEach { sourceIndex.removeMember(it, id) }
        if (document.entitySearchable) document.entityIds.forEach { entityIndex.removeMember(it, id) }
        adjacency.remove(id)
        return true
    }

    private fun removeRelationship(relationship: BoundedRecallRelationship): Int {
        if (!relationships.remove(relationship)) return 0
        structuralRows--
        relationshipsByDocument.removeMember(relationship.sourceId, relationship)
        relationshipsByDocument.removeMember(relationship.targetId, relationship)
        var visits = 0
        visits += adjacency.removeNeighbor(relationship.sourceId, relationship.type, relationship.targetId)
        visits += adjacency.removeNeighbor(relationship.targetId, relationship.type, relationship.sourceId)
        return visits
    }

    private fun result(
        status: BoundedRecallQueryStatus,
        postingVisits: Int = 0,
        structuralVisits: Int = 0,
        graphSeeds: Int = 0,
        relationshipVisits: Int = 0,
        candidates: List<BoundedRecallCandidate<M>> = emptyList(),
    ) = BoundedRecallQueryResult(
        status = status,
        candidates = candidates,
        usage = usage().copy(
            postingVisits = postingVisits,
            queryStructuralVisits = structuralVisits,
            graphSeeds = graphSeeds,
            relationshipVisits = relationshipVisits,
            candidatePoolSize = candidates.size,
        ),
    )

    private companion object {
        const val BM25_K = 1.2
        const val BM25_B = 0.75
        val SCORE_ORDER = compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }
    }
}

internal object BoundedRecallTokenizer {
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
                if (token.length > maxTokenChars) throw BoundedRecallTokenLimitExceeded()
            }
        }.filter { it !in unselectiveTerms }.toList()
    }
}

internal fun boundedRecallMeaningHash(meaning: String): String = MessageDigest.getInstance("SHA-256")
    .digest(Normalizer.normalize(meaning, Normalizer.Form.NFKC).lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun boundedRecallTermFrequencies(
    text: String,
    weight: Int,
    maximumTokenChars: Int,
    maximumTokens: Int,
): Map<String, Int> {
    val frequencies = linkedMapOf<String, Int>()
    var count = 0
    BoundedRecallTokenizer.tokens(text, maximumTokenChars).forEach { token ->
        if (count > maximumTokens - weight) throw BoundedRecallCapacityExceeded()
        frequencies[token] = (frequencies[token] ?: 0) + weight
        count += weight
    }
    return frequencies
}

internal class BoundedRecallCapacityExceeded : RuntimeException()
internal class BoundedRecallTokenLimitExceeded : RuntimeException()

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

private fun MutableMap<String, MutableMap<String, MutableSet<String>>>.removeNeighbor(
    id: String,
    type: String,
    neighbor: String,
): Int {
    val byType = this[id] ?: return 1
    byType[type]?.let { values ->
        values.remove(neighbor)
        if (values.isEmpty()) byType.remove(type)
    }
    if (byType.isEmpty()) remove(id)
    return 1
}
