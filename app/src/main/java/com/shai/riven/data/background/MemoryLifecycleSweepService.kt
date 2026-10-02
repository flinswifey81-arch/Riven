package com.shai.riven.data.background

import com.shai.riven.data.memory.MemoryAgingService
import kotlinx.coroutines.CancellationException

sealed interface MemoryLifecycleSweepResult {
    data class Completed(
        val evaluatedCount: Int,
        val dormantCount: Int,
        val moreWorkRemaining: Boolean,
    ) : MemoryLifecycleSweepResult
    data class RetryableFailure(val errorCode: String) : MemoryLifecycleSweepResult
}

fun interface MemoryLifecycleSweepOperations {
    suspend fun runSweep(): MemoryLifecycleSweepResult
}

class MemoryLifecycleSweepService(
    private val aging: MemoryAgingService,
) : MemoryLifecycleSweepOperations {
    override suspend fun runSweep(): MemoryLifecycleSweepResult = try {
        val result = aging.sweep()
        MemoryLifecycleSweepResult.Completed(
            evaluatedCount = result.evaluatedCount,
            dormantCount = result.dormantCount,
            moreWorkRemaining = result.hasMore,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        MemoryLifecycleSweepResult.RetryableFailure(
            "MEMORY_LIFECYCLE_${failure::class.java.simpleName.safeCodeFragment()}",
        )
    }

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "Exception" }.take(40)
}
