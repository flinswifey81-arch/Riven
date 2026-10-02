package com.shai.riven.data.memory

import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.MemoryAccessibilityEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.model.MemoryAccessibilityBand
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.validationRecallCorpusFence
import kotlinx.coroutines.CancellationException

data class MemoryAgingSweepResult(
    val evaluatedCount: Int,
    val dormantCount: Int,
    val hasMore: Boolean,
)

class MemoryAgingService(
    private val database: RivenDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT,
) {
    private val lifecycleDao = database.memoryLifecycleDao()
    private val memoryTransactions = MemoryTransactionService(database)
    private val recallFence = database.validationRecallCorpusFence()

    init {
        require(batchLimit > 0)
    }

    suspend fun sweep(): MemoryAgingSweepResult {
        val now = clock()
        var evaluated = 0
        var dormant = 0
        while (evaluated < batchLimit) {
            val remaining = batchLimit - evaluated
            val page = database.withTransaction {
                lifecycleDao.agingMemories(now, minOf(PAGE_SIZE, remaining))
            }
            if (page.isEmpty()) break
            for (memory in page) {
                val decision = decide(memory, now)
                if (decision.band == MemoryAccessibilityBand.DORMANT &&
                    memory.retentionState == MemoryRetentionState.ACTIVE
                ) {
                    when (memoryTransactions.moveDormant(MemoryStateTransitionInput(memory.id, now))) {
                        is MemoryWriteResult.Success -> dormant += 1
                        is MemoryWriteResult.Failure -> Unit
                    }
                }
                val previousBand = lifecycleDao.accessibility(memory.id)?.band
                    ?: MemoryAccessibilityBand.ORDINARY
                val writeAccessibility: suspend () -> Unit = {
                    database.withTransaction {
                        val current = database.memoryDao().memory(memory.id) ?: return@withTransaction
                        if (current.truthState != MemoryTruthState.SUPPORTED ||
                            current.retentionState == MemoryRetentionState.FORGOTTEN ||
                            current.lifecycleState != MemoryLifecycleState.VALIDATED
                        ) return@withTransaction
                        lifecycleDao.upsertAccessibility(
                            MemoryAccessibilityEntity(
                                memoryId = current.id,
                                band = if (current.retentionState == MemoryRetentionState.DORMANT) {
                                    MemoryAccessibilityBand.DORMANT
                                } else {
                                    decision.band
                                },
                                reasonCode = decision.reasonCode,
                                evaluatedAt = now,
                                sourceUpdatedAt = current.updatedAt,
                            ),
                        )
                    }
                }
                if (decision.band == MemoryAccessibilityBand.LIMITED &&
                    previousBand != MemoryAccessibilityBand.LIMITED
                ) {
                    recallFence.withCanonicalMutation(
                        change = { ValidationRecallCorpusChange.memoryIds(listOf(memory.id)) },
                    ) { writeAccessibility() }
                } else {
                    writeAccessibility()
                }
                evaluated += 1
            }
            if (page.size < minOf(PAGE_SIZE, remaining)) break
        }
        val hasMore = try {
            database.withTransaction { lifecycleDao.agingMemories(now, 1).isNotEmpty() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        return MemoryAgingSweepResult(evaluated, dormant, hasMore)
    }

    internal fun decide(memory: MemoryEntity, now: Long): AgingDecision {
        if (memory.retentionState == MemoryRetentionState.DORMANT) {
            return AgingDecision(MemoryAccessibilityBand.DORMANT, "ALREADY_DORMANT")
        }
        val significance = listOfNotNull(
            memory.autobiographicalSignificance,
            memory.relationshipSignificance,
            memory.emotionalSignificance,
            memory.practicalSignificance,
            memory.identitySignificance,
        ).maxByOrNull(SignificanceLevel::ordinal) ?: SignificanceLevel.NONE
        val reference = memory.lastConfirmedAt ?: memory.learnedAt
        val age = (now - reference).coerceAtLeast(0L)
        return when {
            significance >= SignificanceLevel.HIGH ->
                AgingDecision(MemoryAccessibilityBand.ORDINARY, "HIGH_INTRINSIC_SIGNIFICANCE")
            age >= DORMANCY_AFTER_MS && significance < SignificanceLevel.MODERATE ->
                AgingDecision(MemoryAccessibilityBand.DORMANT, "AGED_LOW_SIGNIFICANCE")
            age >= LIMITED_AFTER_MS && significance < SignificanceLevel.MODERATE ->
                AgingDecision(MemoryAccessibilityBand.LIMITED, "AGED_ACCESS_LIMIT")
            else -> AgingDecision(MemoryAccessibilityBand.ORDINARY, "CURRENT_ACCESS")
        }
    }

    internal data class AgingDecision(
        val band: MemoryAccessibilityBand,
        val reasonCode: String,
    )

    companion object {
        const val DEFAULT_BATCH_LIMIT = 100
        private const val PAGE_SIZE = 25
        const val LIMITED_AFTER_MS = 60L * 24L * 60L * 60L * 1_000L
        const val DORMANCY_AFTER_MS = 180L * 24L * 60L * 60L * 1_000L
    }
}
