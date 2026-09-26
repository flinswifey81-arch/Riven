package com.shai.riven.data.instructions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.context.RivenContextBudgetBehavior
import com.shai.riven.data.context.RivenContextCollectionResult
import com.shai.riven.data.context.RivenContextContractViolation
import com.shai.riven.data.context.RivenContextFailureCause
import com.shai.riven.data.context.RivenContextLayer
import com.shai.riven.data.context.RivenContextProvenanceClass
import com.shai.riven.data.context.RivenContextSourceCriticality
import com.shai.riven.data.context.RivenContextSourceRegistry
import com.shai.riven.data.persistence.RivenDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShaiSystemInstructionsContextSourceTest {
    private lateinit var database: RivenDatabase
    private lateinit var service: ShaiSystemInstructionsService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = ShaiSystemInstructionsService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun enabledInstructionsCollectWithExactGenericDescriptorAndPayloadMetadata() = runBlocking {
        val content = "  Keep this exact.\nSecond line.  "
        assertTrue(
            service.save(
                SaveShaiSystemInstructionsInput(
                    content = content,
                    isEnabled = true,
                    expectedRevision = 0,
                    occurredAt = 44,
                ),
            ) is ShaiSystemInstructionsWriteResult.Saved,
        )
        val source = ShaiSystemInstructionsContextSource(service)

        val snapshot = assertSuccess(RivenContextSourceRegistry(listOf(source)).collect(now = 50))
        val fragment = snapshot.fragments.single()

        assertEquals(ShaiSystemInstructionsContextSource.SOURCE_ID, fragment.sourceId)
        assertEquals(ShaiSystemInstructionsContextSource.PRIMARY_FRAGMENT_ID, fragment.fragmentId)
        assertEquals(RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS, fragment.layer)
        assertEquals(RivenContextProvenanceClass.SHAI_CONFIGURATION, fragment.provenanceClass)
        assertEquals(RivenContextSourceCriticality.REQUIRED, fragment.criticality)
        assertEquals(RivenContextBudgetBehavior.REQUIRED, fragment.budgetBehavior)
        assertEquals(content, fragment.content)
        assertEquals(1L, fragment.revision)
        assertEquals(44L, fragment.observedAt)
        assertEquals(null, fragment.validUntil)
        assertEquals(ShaiSystemInstructionsContextSource.MAX_CONTEXT_CHARS, source.descriptor.maxCharsPerFragment)
        assertEquals(ShaiSystemInstructionsContextSource.MAX_CONTEXT_CHARS, source.descriptor.maxAggregateChars)
    }

    @Test
    fun noRowDisabledAndBlankInstructionsEmitNoGenericFragment() = runBlocking {
        val source = ShaiSystemInstructionsContextSource(service)
        val registry = RivenContextSourceRegistry(listOf(source))

        assertTrue(assertSuccess(registry.collect(now = 1)).fragments.isEmpty())

        assertTrue(
            service.save(
                SaveShaiSystemInstructionsInput("disabled", false, expectedRevision = 0, occurredAt = 1),
            ) is ShaiSystemInstructionsWriteResult.Saved,
        )
        assertTrue(assertSuccess(registry.collect(now = 2)).fragments.isEmpty())

        assertTrue(
            service.save(
                SaveShaiSystemInstructionsInput(" \n\t", true, expectedRevision = 1, occurredAt = 2),
            ) is ShaiSystemInstructionsWriteResult.Saved,
        )
        assertTrue(assertSuccess(registry.collect(now = 3)).fragments.isEmpty())
    }

    @Test
    fun readFailureIsRequiredSourceFailureRatherThanSilentOmission() = runBlocking {
        val storageError = ShaiSystemInstructionsError.StorageFailure(
            operation = ShaiSystemInstructionsOperation.READ,
            causeType = "ControlledReadFailure",
        )
        val source = ShaiSystemInstructionsContextSource {
            ShaiSystemInstructionsReadResult.Failure(storageError)
        }

        val result = RivenContextSourceRegistry(listOf(source)).collect(now = 1)

        assertTrue(result is RivenContextCollectionResult.Failure)
        result as RivenContextCollectionResult.Failure
        assertTrue(result.snapshot.fragments.isEmpty())
        assertEquals(listOf(ShaiSystemInstructionsContextSource.SOURCE_ID), result.requiredFailures.map { it.sourceId })
        assertTrue(result.requiredFailures.single().cause is RivenContextFailureCause.SourceFailure)
    }

    @Test
    fun oversizedInstructionsFailRegistryContractWithoutTruncation() = runBlocking {
        val oversized = "x".repeat(ShaiSystemInstructionsContextSource.MAX_CONTEXT_CHARS + 1)
        assertTrue(
            service.save(
                SaveShaiSystemInstructionsInput(
                    content = oversized,
                    isEnabled = true,
                    expectedRevision = 0,
                    occurredAt = 1,
                ),
            ) is ShaiSystemInstructionsWriteResult.Saved,
        )

        val result = RivenContextSourceRegistry(
            listOf(ShaiSystemInstructionsContextSource(service)),
        ).collect(now = 2)

        assertTrue(result is RivenContextCollectionResult.Failure)
        result as RivenContextCollectionResult.Failure
        val cause = result.requiredFailures.single().cause
        assertTrue(cause is RivenContextFailureCause.ContractViolation)
        val violation = (cause as RivenContextFailureCause.ContractViolation).violation
        assertEquals(
            RivenContextContractViolation.FragmentTooLarge(
                fragmentId = ShaiSystemInstructionsContextSource.PRIMARY_FRAGMENT_ID,
                maximumChars = ShaiSystemInstructionsContextSource.MAX_CONTEXT_CHARS,
                actualChars = oversized.length,
            ),
            violation,
        )
        assertTrue(result.snapshot.fragments.isEmpty())
        val stored = database.shaiSystemInstructionsDao()
            .instructions(ShaiSystemInstructionsService.PRIMARY_INSTRUCTION_ID)
        assertEquals(oversized, stored?.content)
    }

    private fun assertSuccess(result: RivenContextCollectionResult) =
        when (result) {
            is RivenContextCollectionResult.Success -> result.snapshot
            is RivenContextCollectionResult.Failure -> error("Unexpected required failure: ${result.requiredFailures}")
        }
}
