package com.shai.riven.data.context

import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.recall.BoundedRecallTokenLimitExceeded
import com.shai.riven.data.recall.BoundedRecallTokenizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only, bounded projection of already-canonical open loops. It never creates or updates loops. */
class OpenLoopContextSource(
    private val database: RivenDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val limits: OpenLoopContextLimits = OpenLoopContextLimits(),
) : RivenContextSource {
    override val descriptor = RivenContextSourceDescriptor(
        sourceId = SOURCE_ID,
        layer = RivenContextLayer.RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
        provenanceClass = RivenContextProvenanceClass.OPEN_LOOP,
        criticality = RivenContextSourceCriticality.OPTIONAL,
        orderWithinLayer = 20,
        maxFragments = limits.maxFinalLoops,
        maxCharsPerFragment = limits.maxCharsPerFragment,
        maxAggregateChars = limits.maxAggregateChars,
        budgetBehavior = RivenContextBudgetBehavior.DROP_IF_NEEDED,
        contentAuthority = RivenContextContentAuthority.UNTRUSTED_DATA,
    )

    override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult {
        val conversation = request.conversation ?: return failure("MissingConversationRequest")
        val directIds = conversation.recallCues.directlyRelevantOpenLoopIds.filter { it.isNotBlank() }.toSortedSet()
        val entityIds = conversation.recallCues.groundedEntityIds
        if (conversation.currentInteraction.content.length > limits.maxQueryChars ||
            directIds.size > limits.maxDirectIds ||
            entityIds.size > limits.maxGroundedEntityIds ||
            directIds.any { it.length > limits.maxGroundedIdChars } ||
            entityIds.any { it.length > limits.maxGroundedIdChars }
        ) {
            return failure("OpenLoopRecall.BUDGET_EXCEEDED")
        }
        val queryTokens = try {
            BoundedRecallTokenizer.tokens(conversation.currentInteraction.content, limits.maxTokenChars).toSet()
        } catch (_: BoundedRecallTokenLimitExceeded) {
            return failure("OpenLoopRecall.BUDGET_EXCEEDED")
        }
        if (queryTokens.size > limits.maxQueryTerms) return failure("OpenLoopRecall.BUDGET_EXCEEDED")
        if (queryTokens.isEmpty() && directIds.isEmpty()) return RivenContextSourceResult.Success(emptyList())

        val snapshot = try {
            withContext(dispatcher) {
                database.withTransaction {
                    val dao = database.openLoopDao()
                    val activeStates = ACTIVE_STATES.toList()
                    val direct = if (directIds.isEmpty()) emptyList()
                    else dao.conversationalContextByIds(directIds.toList(), activeStates)
                    val candidates = dao.conversationalContextCandidates(activeStates, limits.maxCandidates + 1)
                    val candidatesOverflowed = candidates.size > limits.maxCandidates
                    if (candidatesOverflowed) throw OpenLoopContextCapacityExceeded()
                    val loops = (direct + candidates).associateBy(OpenLoopEntity::id)
                    if (loops.isEmpty()) return@withTransaction OpenLoopReadSnapshot(emptyList(), emptyMap())
                    val links = dao.conversationalContextEntityLinks(
                        loops.keys.sorted(),
                        limits.maxStructuralRows + 1,
                    )
                    if (links.size > limits.maxStructuralRows) throw OpenLoopContextCapacityExceeded()
                    OpenLoopReadSnapshot(
                        loops = loops.values.sortedBy { it.id },
                        entityIds = links.groupBy { it.openLoopId }
                            .mapValues { (_, values) -> values.mapTo(sortedSetOf()) { it.entityId } },
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OpenLoopContextCapacityExceeded) {
            return failure("OpenLoopRecall.CAPACITY_EXCEEDED")
        } catch (failure: Exception) {
            return failure("OpenLoopRecall.FAILED", failure::class.java.simpleName)
        }

        val selected = snapshot.loops.mapNotNull { loop ->
            val isDirect = loop.id in directIds
            if (loop.sensitivity != SensitivityLevel.STANDARD && !isDirect) return@mapNotNull null
            val loopTokens = try {
                BoundedRecallTokenizer.tokens(
                    listOfNotNull(loop.title, loop.description).joinToString(" "),
                    limits.maxTokenChars,
                ).toSet()
            } catch (_: BoundedRecallTokenLimitExceeded) {
                return failure("OpenLoopRecall.CAPACITY_EXCEEDED")
            }
            val overlap = loopTokens.intersect(queryTokens).size
            if (!isDirect && overlap == 0) return@mapNotNull null
            val entityMatches = snapshot.entityIds[loop.id].orEmpty()
                .count(conversation.recallCues.groundedEntityIds::contains)
            RankedOpenLoop(
                loop = loop,
                score = (if (isDirect) DIRECT_SCORE else 0) +
                    overlap * TOKEN_SCORE +
                    entityMatches * ENTITY_SCORE +
                    loop.dueScore(request.now) +
                    loop.stateScore(),
            )
        }.sortedWith(compareByDescending<RankedOpenLoop> { it.score }.thenBy { it.loop.id })
            .take(limits.maxFinalLoops)

        return RivenContextSourceResult.Success(selected.map { ranked -> ranked.loop.toPayload(request.now) })
    }

    private fun OpenLoopEntity.toPayload(now: Long) = RivenContextPayload(
        fragmentId = id,
        content = buildString {
            append("type=OPEN_LOOP\n")
            append("state=").append(state.name).append('\n')
            dueAt?.let { append("due_at=").append(it).append('\n') }
            append("title=").append(title.replace('\u0000', ' '))
            description?.takeIf(String::isNotBlank)?.let {
                append("\ndescription=").append(it.replace('\u0000', ' '))
            }
        },
        revision = updatedAt,
        observedAt = now,
    )

    private fun OpenLoopEntity.dueScore(now: Long): Int = when {
        dueAt == null -> 0
        dueAt <= now -> 4
        dueAt - now <= DUE_SOON_WINDOW_MILLIS -> 2
        else -> 0
    }

    private fun OpenLoopEntity.stateScore(): Int = when (state) {
        OpenLoopState.BLOCKED -> 3
        OpenLoopState.ACTIVE -> 2
        OpenLoopState.WAITING -> 1
        OpenLoopState.PLANNED -> 0
        OpenLoopState.COMPLETED,
        OpenLoopState.ABANDONED,
        OpenLoopState.EXPIRED,
        -> Int.MIN_VALUE
    }

    private fun failure(errorType: String, causeType: String? = null) = RivenContextSourceResult.Failure(
        RivenContextSourceError.ReadFailure(errorType, causeType),
    )

    private data class OpenLoopReadSnapshot(
        val loops: List<OpenLoopEntity>,
        val entityIds: Map<String, Set<String>>,
    )

    private data class RankedOpenLoop(val loop: OpenLoopEntity, val score: Int)

    private class OpenLoopContextCapacityExceeded : RuntimeException()

    companion object {
        const val SOURCE_ID = "OPEN_LOOPS"
        private const val DIRECT_SCORE = 100
        private const val TOKEN_SCORE = 10
        private const val ENTITY_SCORE = 2
        private const val DUE_SOON_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        private val ACTIVE_STATES = sortedSetOf(
            OpenLoopState.PLANNED,
            OpenLoopState.ACTIVE,
            OpenLoopState.WAITING,
            OpenLoopState.BLOCKED,
        )
    }
}

data class OpenLoopContextLimits(
    val maxCandidates: Int = 128,
    val maxDirectIds: Int = 16,
    val maxGroundedEntityIds: Int = 32,
    val maxGroundedIdChars: Int = 128,
    val maxStructuralRows: Int = 1_024,
    val maxQueryChars: Int = 4_096,
    val maxQueryTerms: Int = 32,
    val maxTokenChars: Int = 64,
    val maxFinalLoops: Int = 4,
    val maxCharsPerFragment: Int = 4_608,
    val maxAggregateChars: Int = 18_432,
) {
    init {
        require(
            listOf(
                maxCandidates,
                maxDirectIds,
                maxGroundedEntityIds,
                maxGroundedIdChars,
                maxStructuralRows,
                maxQueryChars,
                maxQueryTerms,
                maxTokenChars,
                maxFinalLoops,
                maxCharsPerFragment,
                maxAggregateChars,
            ).all { it > 0 },
        )
    }
}
