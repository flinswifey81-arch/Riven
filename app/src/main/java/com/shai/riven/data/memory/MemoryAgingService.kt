package com.shai.riven.data.memory

import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.MemoryAccessibilityEntity
import com.shai.riven.data.persistence.entity.MemoryAgingSweepCheckpointEntity
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

/** Bounded, keyset-paged aging whose durable cursor survives worker/process restarts. */
class MemoryAgingService(
    private val database: RivenDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    private val beforeEvaluate: suspend (String) -> Unit = {},
) {
    private val lifecycleDao = database.memoryLifecycleDao()
    private val memoryDao = database.memoryDao()
    private val memoryTransactions = MemoryTransactionService(database)
    private val recallFence = database.validationRecallCorpusFence()

    init {
        require(batchLimit > 0)
    }

    suspend fun sweep(): MemoryAgingSweepResult {
        val checkpoint = database.withTransaction {
            val now = clock()
            lifecycleDao.insertAgingSweepCheckpoint(
                MemoryAgingSweepCheckpointEntity(
                    id = CHECKPOINT_ID,
                    sweepStartedAt = now,
                    afterMemoryId = "",
                    updatedAt = now,
                ),
            )
            checkNotNull(lifecycleDao.agingSweepCheckpoint(CHECKPOINT_ID))
        }
        val ids = database.withTransaction {
            lifecycleDao.agingMemoryIds(checkpoint.afterMemoryId, batchLimit + 1)
        }
        val selected = ids.take(batchLimit)
        var dormant = 0
        selected.forEach { memoryId ->
            beforeEvaluate(memoryId)
            if (ageOne(memoryId, checkpoint.sweepStartedAt).becameDormant) dormant += 1
        }

        val hasMore = ids.size > batchLimit
        val checkpointChanged = database.withTransaction {
            if (hasMore) {
                lifecycleDao.advanceAgingSweepCheckpoint(
                    checkpointId = CHECKPOINT_ID,
                    sweepStartedAt = checkpoint.sweepStartedAt,
                    expectedAfterMemoryId = checkpoint.afterMemoryId,
                    nextAfterMemoryId = selected.last(),
                    updatedAt = clock(),
                )
            } else {
                lifecycleDao.finishAgingSweepCheckpoint(
                    checkpointId = CHECKPOINT_ID,
                    sweepStartedAt = checkpoint.sweepStartedAt,
                    expectedAfterMemoryId = checkpoint.afterMemoryId,
                )
            }
        }
        return MemoryAgingSweepResult(
            evaluatedCount = selected.size,
            dormantCount = dormant,
            // A concurrent runner that won the cursor CAS owns the continuation.
            hasMore = hasMore || checkpointChanged != 1,
        )
    }

    private suspend fun ageOne(memoryId: String, sweepStartedAt: Long): AgingMutation = try {
        recallFence.withCanonicalMutation(
            change = { result ->
                if (result.recallChanged) ValidationRecallCorpusChange.memoryIds(listOf(memoryId))
                else ValidationRecallCorpusChange.memoryIds(emptyList())
            },
        ) { mutation ->
            database.withTransaction {
                val current = memoryDao.memory(memoryId) ?: return@withTransaction AgingMutation()
                if (current.truthState != MemoryTruthState.SUPPORTED ||
                    current.retentionState == MemoryRetentionState.FORGOTTEN ||
                    current.lifecycleState != MemoryLifecycleState.VALIDATED ||
                    lifecycleDao.availableEvidence(listOf(memoryId), 1).isEmpty()
                ) return@withTransaction AgingMutation()

                val decision = decide(current, sweepStartedAt)
                val previousBand = lifecycleDao.accessibility(memoryId)?.band
                    ?: MemoryAccessibilityBand.ORDINARY
                if (decision.band == MemoryAccessibilityBand.DORMANT &&
                    current.retentionState == MemoryRetentionState.ACTIVE
                ) {
                    val moved = memoryTransactions.moveDormantInCurrentTransaction(
                        MemoryStateTransitionInput(memoryId, occurredAt = sweepStartedAt),
                        mutation,
                    )
                    check(moved is MemoryWriteResult.Success)
                    AgingMutation(becameDormant = true, recallChanged = true)
                } else {
                    val effectiveBand = if (current.retentionState == MemoryRetentionState.DORMANT) {
                        MemoryAccessibilityBand.DORMANT
                    } else {
                        decision.band
                    }
                    lifecycleDao.upsertAccessibility(
                        MemoryAccessibilityEntity(
                            memoryId = current.id,
                            band = effectiveBand,
                            reasonCode = decision.reasonCode,
                            evaluatedAt = sweepStartedAt,
                            sourceUpdatedAt = current.updatedAt,
                        ),
                    )
                    AgingMutation(recallChanged = previousBand != effectiveBand)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
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

    private data class AgingMutation(
        val becameDormant: Boolean = false,
        val recallChanged: Boolean = false,
    )

    companion object {
        const val DEFAULT_BATCH_LIMIT = 100
        internal const val CHECKPOINT_ID = "memory-aging-v1"
        const val LIMITED_AFTER_MS = 60L * 24L * 60L * 60L * 1_000L
        const val DORMANCY_AFTER_MS = 180L * 24L * 60L * 60L * 1_000L
    }
}
