package com.shai.riven.data.recall

import com.shai.riven.data.context.RivenGroundedRecallCues
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState

const val CONVERSATIONAL_RECALL_ALGORITHM_VERSION = "conversational-recall-lexical-structural-v1"

data class ConversationalRecallLimits(
    val pageSize: Int = 128,
    val maxMemories: Int = 10_000,
    val maxMeaningChars: Int = 4_096,
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
    val maxQueryEntityIds: Int = 32,
    val maxGroundedCueIds: Int = 64,
    val maxPostingVisits: Int = 20_000,
    val maxStructuralVisits: Int = 2_048,
    val maxGraphSeeds: Int = 32,
    val maxRelationshipVisits: Int = 512,
    val maxCandidatePool: Int = 256,
    val maxFinalMemories: Int = 8,
) {
    init {
        require(
            listOf(
                pageSize,
                maxMemories,
                maxMeaningChars,
                maxTokensPerDocument,
                maxTokenChars,
                maxCorpusTokens,
                maxCorpusPostings,
                maxCorpusStructuralRows,
                maxStructuralRowsPerPage,
                maxIncrementalChangedMemories,
                maxQueryChars,
                maxQueryTerms,
                maxQueryEntityIds,
                maxGroundedCueIds,
                maxPostingVisits,
                maxStructuralVisits,
                maxGraphSeeds,
                maxRelationshipVisits,
                maxCandidatePool,
                maxFinalMemories,
            ).all { it > 0 },
        )
        require(maxCorpusTextChars > 0L)
        require(pageSize <= 256)
        require(maxCandidatePool >= maxFinalMemories)
    }
}

data class ConversationalMemoryQuery(
    val currentInteraction: String,
    val now: Long,
    val cues: RivenGroundedRecallCues = RivenGroundedRecallCues(),
)

enum class ConversationalRecallReadiness {
    READY,
    NOT_READY,
    STALE,
    FAILED,
    CAPACITY_EXCEEDED,
    BUDGET_EXCEEDED,
    QUERY_UNSELECTIVE,
}

data class ConversationalRecallGeneration(
    val databaseSessionId: String,
    val corpusGeneration: Long,
    val algorithmVersion: String = CONVERSATIONAL_RECALL_ALGORITHM_VERSION,
)

enum class ConversationalSelectionReason {
    LEXICAL_RELEVANCE,
    GROUNDED_ENTITY,
    DIRECT_GROUNDED_CUE,
    HISTORICAL_CUE,
    ACTIVE_OPEN_LOOP,
    GROUNDED_RELATIONSHIP,
}

data class ConversationalMemoryItem(
    val memoryId: String,
    val meaning: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val epistemicBasis: EpistemicBasis,
    val certainty: MemoryCertainty,
    val truthState: MemoryTruthState,
    val retentionState: MemoryRetentionState,
    val lifecycleState: MemoryLifecycleState,
    val temporalState: TemporalState,
    val validFrom: Long?,
    val validUntil: Long?,
    val sensitivity: SensitivityLevel,
    val autobiographicalSignificance: SignificanceLevel?,
    val relationshipSignificance: SignificanceLevel?,
    val practicalSignificance: SignificanceLevel?,
    val identitySignificance: SignificanceLevel?,
    val selectionReasons: Set<ConversationalSelectionReason>,
)

data class ConversationalRecallBudgetUsage(
    val indexedMemories: Int = 0,
    val indexedTokens: Int = 0,
    val postingVisits: Int = 0,
    val structuralRowsVisited: Int = 0,
    val graphSeedsVisited: Int = 0,
    val relationshipRowsVisited: Int = 0,
    val candidatePoolSize: Int = 0,
)

data class ConversationalMemoryRetrieval(
    val memories: List<ConversationalMemoryItem> = emptyList(),
    val readiness: ConversationalRecallReadiness = ConversationalRecallReadiness.NOT_READY,
    val generation: ConversationalRecallGeneration? = null,
    val budgetUsage: ConversationalRecallBudgetUsage = ConversationalRecallBudgetUsage(),
)

fun interface ConversationalMemoryRetriever {
    suspend fun retrieve(query: ConversationalMemoryQuery): ConversationalMemoryRetrieval
}
