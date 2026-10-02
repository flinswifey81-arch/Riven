package com.shai.riven.data.memory

import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryTruthState

/** User intent and completed negative assessments must never be reopened by provenance repair. */
internal fun MemoryEntity.hasTerminalLifecycleIntent(): Boolean =
    truthState == MemoryTruthState.CORRECTED_FALSE ||
        truthState == MemoryTruthState.UNSUPPORTED ||
        retentionState == MemoryRetentionState.FORGOTTEN ||
        lifecycleState == MemoryLifecycleState.SUPERSEDED ||
        lifecycleState == MemoryLifecycleState.RESOLVED
