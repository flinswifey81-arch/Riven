package com.shai.riven.data.background

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepairJobRunnerTest {
    private lateinit var database: RivenDatabase
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pendingJobIsClaimedBeforeHandlerRuns() = runBlocking {
        insertJob("claim", RepairJobState.PENDING, attemptCount = 0, updatedAt = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<RepairJobHandlerResult>()
        val runner = runnerWith { _, _ ->
            started.complete(Unit)
            release.await()
        }

        val execution = async { runner.run("claim") }
        started.await()
        val claimed = database.maintenanceDao().repairJob("claim")
        assertEquals(RepairJobState.RUNNING, claimed?.state)
        assertEquals(1, claimed?.attemptCount)
        assertEquals(now, claimed?.updatedAt)
        assertEquals(null, claimed?.lastErrorCode)
        release.complete(RepairJobHandlerResult.Success)
        assertTrue(execution.await() is RepairJobRunResult.Succeeded)
    }

    @Test
    fun handlerSuccessTransitionsRunningJobToSucceeded() = runBlocking {
        insertJob("success", RepairJobState.PENDING, attemptCount = 0, updatedAt = 1)

        val result = runnerWith { _, _ -> RepairJobHandlerResult.Success }.run("success")

        assertEquals(RepairJobRunResult.Succeeded("success"), result)
        val stored = database.maintenanceDao().repairJob("success")
        assertEquals(RepairJobState.SUCCEEDED, stored?.state)
        assertEquals(1, stored?.attemptCount)
        assertEquals(now, stored?.updatedAt)
    }

    @Test
    fun retryableFailureBelowMaximumReturnsJobToPending() = runBlocking {
        insertJob("retry", RepairJobState.PENDING, attemptCount = 0, updatedAt = 1)

        val result = runnerWith { _, _ ->
            RepairJobHandlerResult.RetryableFailure("TRANSIENT_STORAGE")
        }.run("retry")

        assertEquals(RepairJobRunResult.RetryableFailure("retry", "TRANSIENT_STORAGE"), result)
        val stored = database.maintenanceDao().repairJob("retry")
        assertEquals(RepairJobState.PENDING, stored?.state)
        assertEquals(1, stored?.attemptCount)
        assertEquals("TRANSIENT_STORAGE", stored?.lastErrorCode)
    }

    @Test
    fun retryableFailureAtMaximumAttemptsTransitionsToFailed() = runBlocking {
        insertJob("max", RepairJobState.PENDING, attemptCount = 4, updatedAt = 1)

        val result = runnerWith { _, _ ->
            RepairJobHandlerResult.RetryableFailure("TRANSIENT_STORAGE")
        }.run("max")

        assertEquals(RepairJobRunResult.PermanentlyFailed("max", "TRANSIENT_STORAGE"), result)
        val stored = database.maintenanceDao().repairJob("max")
        assertEquals(RepairJobState.FAILED, stored?.state)
        assertEquals(MAX_REPAIR_ATTEMPTS, stored?.attemptCount)
    }

    @Test
    fun permanentFailureTransitionsToFailedImmediately() = runBlocking {
        insertJob("permanent", RepairJobState.PENDING, attemptCount = 0, updatedAt = 1)

        val result = runnerWith { _, _ ->
            RepairJobHandlerResult.PermanentFailure("INVALID_CANONICAL_TARGET")
        }.run("permanent")

        assertEquals(
            RepairJobRunResult.PermanentlyFailed("permanent", "INVALID_CANONICAL_TARGET"),
            result,
        )
        assertEquals(RepairJobState.FAILED, database.maintenanceDao().repairJob("permanent")?.state)
    }

    @Test
    fun handlerExceptionPersistsClassOnlyWithoutRawMessage() = runBlocking {
        insertJob("exception", RepairJobState.PENDING, attemptCount = 0, updatedAt = 1)

        val result = runnerWith { _, _ ->
            throw IllegalStateException("private user content must never persist")
        }.run("exception")

        assertTrue(result is RepairJobRunResult.RetryableFailure)
        val stored = database.maintenanceDao().repairJob("exception")
        assertEquals("HANDLER_EXCEPTION_IllegalStateException", stored?.lastErrorCode)
        assertFalse(stored?.lastErrorCode.orEmpty().contains("private user content"))
        assertEquals(RepairJobState.PENDING, stored?.state)
    }

    @Test
    fun cancellationPropagatesAndLeavesClaimRunning() = runBlocking {
        insertJob("cancel", RepairJobState.PENDING, attemptCount = 0, updatedAt = 1)
        var cancellationPropagated = false

        try {
            runnerWith { _, _ -> throw CancellationException("cancel") }.run("cancel")
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        val stored = database.maintenanceDao().repairJob("cancel")
        assertEquals(RepairJobState.RUNNING, stored?.state)
        assertEquals(1, stored?.attemptCount)
    }

    @Test
    fun staleRunningJobIsReclaimedAndAttemptIncrements() = runBlocking {
        insertJob(
            "stale",
            RepairJobState.RUNNING,
            attemptCount = 2,
            updatedAt = now - REPAIR_RUNNING_LEASE_MS,
        )

        val result = runnerWith { _, _ -> RepairJobHandlerResult.Success }.run("stale")

        assertEquals(RepairJobRunResult.Succeeded("stale"), result)
        val stored = database.maintenanceDao().repairJob("stale")
        assertEquals(RepairJobState.SUCCEEDED, stored?.state)
        assertEquals(3, stored?.attemptCount)
    }

    @Test
    fun freshRunningJobRefusesConcurrentExecution() = runBlocking {
        insertJob("fresh", RepairJobState.RUNNING, attemptCount = 2, updatedAt = now - 1)
        var handlerCalls = 0

        val result = runnerWith { _, _ ->
            handlerCalls += 1
            RepairJobHandlerResult.Success
        }.run("fresh")

        assertEquals(RepairJobRunResult.AlreadyRunning("fresh"), result)
        assertEquals(0, handlerCalls)
        assertEquals(2, database.maintenanceDao().repairJob("fresh")?.attemptCount)
    }

    @Test
    fun succeededJobIsIdempotentNoOp() = runBlocking {
        insertJob("done", RepairJobState.SUCCEEDED, attemptCount = 1, updatedAt = 1)
        var handlerCalls = 0

        val result = runnerWith { _, _ ->
            handlerCalls += 1
            RepairJobHandlerResult.Success
        }.run("done")

        assertEquals(
            RepairJobRunResult.NoOp("done", RepairJobNoOpReason.ALREADY_SUCCEEDED),
            result,
        )
        assertEquals(0, handlerCalls)
    }

    @Test
    fun missingRepairJobReturnsTypedNoOp() = runBlocking {
        val result = runnerWith { _, _ -> RepairJobHandlerResult.Success }.run("missing")

        assertEquals(
            RepairJobRunResult.NoOp("missing", RepairJobNoOpReason.MISSING),
            result,
        )
    }

    private fun runnerWith(
        execute: suspend (targetType: String, targetId: String) -> RepairJobHandlerResult,
    ): RepairJobRunner = RepairJobRunner(
        database = database,
        handlerRegistry = RepairJobHandlerRegistry(
            listOf(
                FakeRepairJobHandler(
                    supportedType = RepairJobType.REASSESS_PROVENANCE,
                    execute = execute,
                ),
            ),
        ),
        clock = RivenBackgroundClock { now },
    )

    private fun insertJob(
        id: String,
        state: RepairJobState,
        attemptCount: Int,
        updatedAt: Long,
    ) {
        database.maintenanceDao().insertRepairJob(
            RepairJobEntity(
                id = id,
                jobType = RepairJobType.REASSESS_PROVENANCE,
                state = state,
                targetType = "MEMORY",
                targetId = "opaque-target-$id",
                attemptCount = attemptCount,
                createdAt = 1,
                updatedAt = updatedAt,
            ),
        )
    }

    private class FakeRepairJobHandler(
        override val supportedType: RepairJobType,
        private val execute: suspend (String, String) -> RepairJobHandlerResult,
    ) : RepairJobHandler {
        override suspend fun execute(targetType: String, targetId: String): RepairJobHandlerResult =
            execute.invoke(targetType, targetId)
    }
}
