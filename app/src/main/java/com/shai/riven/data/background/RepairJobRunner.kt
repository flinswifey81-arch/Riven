package com.shai.riven.data.background

import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import kotlinx.coroutines.CancellationException

interface RepairJobHandler {
    val supportedType: RepairJobType

    suspend fun execute(targetType: String, targetId: String): RepairJobHandlerResult
}

sealed interface RepairJobHandlerResult {
    data object Success : RepairJobHandlerResult
    data class RetryableFailure(val errorCode: String) : RepairJobHandlerResult
    data class PermanentFailure(val errorCode: String) : RepairJobHandlerResult
}

class RepairJobHandlerRegistry(
    handlers: Iterable<RepairJobHandler>,
) {
    private val handlersByType: Map<RepairJobType, RepairJobHandler> = buildMap {
        handlers.sortedBy { it.supportedType.name }.forEach { handler ->
            require(put(handler.supportedType, handler) == null) {
                "Duplicate repair handler for ${handler.supportedType.name}"
            }
        }
    }

    val supportedTypes: Set<RepairJobType>
        get() = handlersByType.keys

    fun supports(type: RepairJobType): Boolean = handlersByType.containsKey(type)

    fun handlerFor(type: RepairJobType): RepairJobHandler? = handlersByType[type]
}

enum class RepairJobNoOpReason {
    MISSING,
    UNSUPPORTED_HANDLER,
    ALREADY_SUCCEEDED,
    ALREADY_FAILED,
}

sealed interface RepairJobRunResult {
    data class Succeeded(val repairJobId: String) : RepairJobRunResult

    data class RetryableFailure(
        val repairJobId: String,
        val errorCode: String,
    ) : RepairJobRunResult

    data class PermanentlyFailed(
        val repairJobId: String,
        val errorCode: String,
    ) : RepairJobRunResult

    data class AlreadyRunning(val repairJobId: String) : RepairJobRunResult
    data class LeaseLost(val repairJobId: String) : RepairJobRunResult

    data class NoOp(
        val repairJobId: String,
        val reason: RepairJobNoOpReason,
    ) : RepairJobRunResult

    data class StorageFailure(
        val repairJobId: String,
        val errorCode: String,
    ) : RepairJobRunResult
}

fun interface RepairJobRunOperations {
    suspend fun run(repairJobId: String): RepairJobRunResult
}

class RepairJobRunner(
    private val database: RivenDatabase,
    private val handlerRegistry: RepairJobHandlerRegistry,
    private val clock: RivenBackgroundClock = SystemRivenBackgroundClock,
    private val maxAttempts: Int = MAX_REPAIR_ATTEMPTS,
    private val runningLeaseMs: Long = REPAIR_RUNNING_LEASE_MS,
) : RepairJobRunOperations {
    private val dao = database.maintenanceDao()

    init {
        require(maxAttempts > 0) { "Maximum repair attempts must be positive" }
        require(runningLeaseMs > 0) { "Repair running lease must be positive" }
    }

    override suspend fun run(repairJobId: String): RepairJobRunResult {
        val initial = try {
            dao.repairJob(repairJobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RepairJobRunResult.StorageFailure(
                repairJobId,
                failure.safeOperationalCode(READ_JOB_PREFIX),
            )
        } ?: return RepairJobRunResult.NoOp(repairJobId, RepairJobNoOpReason.MISSING)

        val handler = handlerRegistry.handlerFor(initial.jobType)
            ?: return RepairJobRunResult.NoOp(repairJobId, RepairJobNoOpReason.UNSUPPORTED_HANDLER)

        val now = clock.now()
        val claim = try {
            claim(repairJobId, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RepairJobRunResult.StorageFailure(
                repairJobId,
                failure.safeOperationalCode(CLAIM_JOB_PREFIX),
            )
        }
        when (claim) {
            is ClaimResult.NoOp -> return claim.result
            is ClaimResult.Claimed -> Unit
        }

        val claimed = (claim as ClaimResult.Claimed).job
        val handlerResult = try {
            handler.execute(claimed.targetType, claimed.targetId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RepairJobHandlerResult.RetryableFailure(
                HANDLER_EXCEPTION_PREFIX + failure::class.java.simpleName.safeCodeFragment(),
            )
        }

        val updatedAt = clock.now()
        return when (handlerResult) {
            RepairJobHandlerResult.Success -> finish(
                claimed = claimed,
                newState = RepairJobState.SUCCEEDED,
                updatedAt = updatedAt,
                errorCode = null,
                completed = RepairJobRunResult.Succeeded(repairJobId),
            )
            is RepairJobHandlerResult.PermanentFailure -> {
                val code = handlerResult.errorCode.safeHandlerErrorCode()
                finish(
                    claimed = claimed,
                    newState = RepairJobState.FAILED,
                    updatedAt = updatedAt,
                    errorCode = code,
                    completed = RepairJobRunResult.PermanentlyFailed(repairJobId, code),
                )
            }
            is RepairJobHandlerResult.RetryableFailure -> {
                val code = handlerResult.errorCode.safeHandlerErrorCode()
                if (claimed.attemptCount < maxAttempts) {
                    finish(
                        claimed = claimed,
                        newState = RepairJobState.PENDING,
                        updatedAt = updatedAt,
                        errorCode = code,
                        completed = RepairJobRunResult.RetryableFailure(repairJobId, code),
                    )
                } else {
                    finish(
                        claimed = claimed,
                        newState = RepairJobState.FAILED,
                        updatedAt = updatedAt,
                        errorCode = code,
                        completed = RepairJobRunResult.PermanentlyFailed(repairJobId, code),
                    )
                }
            }
        }
    }

    private suspend fun claim(repairJobId: String, now: Long): ClaimResult = database.withTransaction {
        val current = dao.repairJob(repairJobId)
            ?: return@withTransaction ClaimResult.NoOp(
                RepairJobRunResult.NoOp(repairJobId, RepairJobNoOpReason.MISSING),
            )
        when (current.state) {
            RepairJobState.PENDING -> {
                val changed = dao.claimPendingRepairJob(
                    repairJobId = repairJobId,
                    pendingState = RepairJobState.PENDING,
                    runningState = RepairJobState.RUNNING,
                    expectedAttemptCount = current.attemptCount,
                    claimedAt = now,
                )
                if (changed == 1) {
                    ClaimResult.Claimed(
                        current.copy(
                            state = RepairJobState.RUNNING,
                            attemptCount = current.attemptCount + 1,
                            updatedAt = now,
                            lastErrorCode = null,
                        ),
                    )
                } else {
                    ClaimResult.NoOp(RepairJobRunResult.AlreadyRunning(repairJobId))
                }
            }
            RepairJobState.RUNNING -> {
                val staleBefore = now - runningLeaseMs
                if (current.updatedAt > staleBefore) {
                    ClaimResult.NoOp(RepairJobRunResult.AlreadyRunning(repairJobId))
                } else {
                    val changed = dao.reclaimStaleRunningRepairJob(
                        repairJobId = repairJobId,
                        runningState = RepairJobState.RUNNING,
                        expectedAttemptCount = current.attemptCount,
                        expectedUpdatedAt = current.updatedAt,
                        staleBefore = staleBefore,
                        claimedAt = now,
                    )
                    if (changed == 1) {
                        ClaimResult.Claimed(
                            current.copy(
                                attemptCount = current.attemptCount + 1,
                                updatedAt = now,
                                lastErrorCode = null,
                            ),
                        )
                    } else {
                        ClaimResult.NoOp(RepairJobRunResult.AlreadyRunning(repairJobId))
                    }
                }
            }
            RepairJobState.SUCCEEDED -> ClaimResult.NoOp(
                RepairJobRunResult.NoOp(repairJobId, RepairJobNoOpReason.ALREADY_SUCCEEDED),
            )
            RepairJobState.FAILED -> ClaimResult.NoOp(
                RepairJobRunResult.NoOp(repairJobId, RepairJobNoOpReason.ALREADY_FAILED),
            )
        }
    }

    private fun finish(
        claimed: RepairJobEntity,
        newState: RepairJobState,
        updatedAt: Long,
        errorCode: String?,
        completed: RepairJobRunResult,
    ): RepairJobRunResult = try {
        val changed = dao.finishClaimedRepairJob(
            repairJobId = claimed.id,
            runningState = RepairJobState.RUNNING,
            newState = newState,
            expectedAttemptCount = claimed.attemptCount,
            claimedAt = claimed.updatedAt,
            updatedAt = updatedAt,
            lastErrorCode = errorCode,
        )
        if (changed == 1) completed else RepairJobRunResult.LeaseLost(claimed.id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RepairJobRunResult.StorageFailure(
            claimed.id,
            failure.safeOperationalCode(FINISH_JOB_PREFIX),
        )
    }

    private fun String.safeHandlerErrorCode(): String =
        if (length in 1..MAX_ERROR_CODE_LENGTH && all { it.isLetterOrDigit() || it == '_' }) {
            this
        } else {
            INVALID_HANDLER_ERROR_CODE
        }

    private fun Exception.safeOperationalCode(prefix: String): String =
        prefix + this::class.java.simpleName.safeCodeFragment()

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }
            .ifBlank { UNKNOWN_EXCEPTION }
            .take(MAX_EXCEPTION_CODE_LENGTH)

    private sealed interface ClaimResult {
        data class Claimed(val job: RepairJobEntity) : ClaimResult
        data class NoOp(val result: RepairJobRunResult) : ClaimResult
    }

    private companion object {
        const val READ_JOB_PREFIX = "READ_REPAIR_JOB_"
        const val CLAIM_JOB_PREFIX = "CLAIM_REPAIR_JOB_"
        const val FINISH_JOB_PREFIX = "FINISH_REPAIR_JOB_"
        const val HANDLER_EXCEPTION_PREFIX = "HANDLER_EXCEPTION_"
        const val INVALID_HANDLER_ERROR_CODE = "INVALID_HANDLER_ERROR_CODE"
        const val UNKNOWN_EXCEPTION = "Exception"
        const val MAX_ERROR_CODE_LENGTH = 64
        const val MAX_EXCEPTION_CODE_LENGTH = 40
    }
}
