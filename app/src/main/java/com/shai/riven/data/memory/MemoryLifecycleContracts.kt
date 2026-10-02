package com.shai.riven.data.memory

import com.shai.riven.data.attention.AttentionAssessment
import com.shai.riven.data.attention.ImmediateAttentionSnapshot
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.TemporalState

data class OpenLoopLifecycleSnapshot(
    val attention: AttentionAssessment,
    val grounding: ImmediateAttentionSnapshot,
    val currentLoops: List<OpenLoopLifecycleItem>,
)

data class OpenLoopLifecycleItem(
    val openLoopId: String,
    val title: String,
    val description: String?,
    val state: OpenLoopState,
    val dueAt: Long?,
    val sensitivity: SensitivityLevel,
    val updatedAt: Long,
)

sealed interface OpenLoopLifecycleProposal {
    data object NoChange : OpenLoopLifecycleProposal
    data class Create(
        val title: String,
        val description: String? = null,
        val state: OpenLoopState = OpenLoopState.PLANNED,
        val dueAt: Long? = null,
        val sensitivity: SensitivityLevel,
    ) : OpenLoopLifecycleProposal
    data class Transition(
        val openLoopId: String,
        val state: OpenLoopState,
        val dueAt: Long? = null,
    ) : OpenLoopLifecycleProposal
}

interface OpenLoopLifecycleDecider {
    suspend fun proposeOpenLoop(snapshot: OpenLoopLifecycleSnapshot): OpenLoopLifecycleProposal =
        OpenLoopLifecycleProposal.NoChange
}

data class ConsolidationSourceMemory(
    val memoryId: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val meaning: String,
    val certainty: MemoryCertainty,
    val sensitivity: SensitivityLevel,
    /** AVAILABLE positive grounding (SUPPORTS/CORRECTS) only; negative/qualifying rows never count. */
    val sourceExperienceIds: List<String>,
    /** Exact role/basis/certainty/lineage revision of all AVAILABLE source evidence. */
    val evidenceFingerprint: String,
    val updatedAt: Long,
)

data class ConsolidationSnapshot(
    val corpusFingerprint: String,
    val sources: List<ConsolidationSourceMemory>,
    val processedSourceSets: List<List<String>> = emptyList(),
)

data class ConsolidationEntityLink(
    val entityId: String,
    val role: EntityLinkRole,
)

sealed interface ConsolidationProposal {
    data object NoConsolidation : ConsolidationProposal
    data class Create(
        val sourceMemoryIds: List<String>,
        val meaning: String,
        val kind: MemoryKind,
        val scope: MemoryScope,
        val certainty: MemoryCertainty,
        val sensitivity: SensitivityLevel,
        val temporalState: TemporalState = TemporalState.CURRENT,
        val significance: IntrinsicSignificanceInput = IntrinsicSignificanceInput(),
        val entityLinks: List<ConsolidationEntityLink> = emptyList(),
    ) : ConsolidationProposal
}

interface MemoryConsolidationDecider {
    suspend fun proposeConsolidation(snapshot: ConsolidationSnapshot): ConsolidationProposal =
        ConsolidationProposal.NoConsolidation
}

sealed interface OpenLoopLifecycleResult {
    data class Applied(val openLoopId: String?) : OpenLoopLifecycleResult
    data class AlreadyProcessed(val experienceId: String) : OpenLoopLifecycleResult
    data class Failure(val errorCode: String, val retryable: Boolean) : OpenLoopLifecycleResult
}

sealed interface MemoryConsolidationResult {
    data class Created(val memoryId: String) : MemoryConsolidationResult
    data class Reused(val memoryId: String?) : MemoryConsolidationResult
    data object NoConsolidation : MemoryConsolidationResult
    data class AlreadyProcessed(val resultMemoryId: String?) : MemoryConsolidationResult
    data class Failure(val errorCode: String, val retryable: Boolean) : MemoryConsolidationResult
}

fun interface MemoryLifecycleIdGenerator {
    fun nextId(): String
}
