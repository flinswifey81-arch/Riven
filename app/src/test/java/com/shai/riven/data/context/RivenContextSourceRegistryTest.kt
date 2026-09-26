package com.shai.riven.data.context

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RivenContextSourceRegistryTest {
    @Test
    fun deterministicOrderingIgnoresRegistrationAndPayloadOrder() = runBlocking {
        val sources = listOf(
            source(
                descriptor(
                    sourceId = "dynamic-b",
                    layer = RivenContextLayer.RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
                    orderWithinLayer = 0,
                ),
                payloads = listOf(payload("z"), payload("a")),
            ),
            source(
                descriptor(
                    sourceId = "system-z",
                    layer = RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS,
                    orderWithinLayer = 4,
                ),
                payloads = listOf(payload("only")),
            ),
            source(
                descriptor(
                    sourceId = "dynamic-a",
                    layer = RivenContextLayer.RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
                    orderWithinLayer = 0,
                ),
                payloads = listOf(payload("only")),
            ),
            source(
                descriptor(
                    sourceId = "system-a",
                    layer = RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS,
                    orderWithinLayer = -1,
                ),
                payloads = listOf(payload("only")),
            ),
            source(
                descriptor(
                    sourceId = "app",
                    layer = RivenContextLayer.APP_INVARIANTS_SAFETY_AND_TOOL_TRUTH,
                    orderWithinLayer = 99,
                ),
                payloads = listOf(payload("only")),
            ),
        )

        val first = assertSuccess(RivenContextSourceRegistry(sources).collect(now = 10))
        val second = assertSuccess(RivenContextSourceRegistry(sources.reversed()).collect(now = 10))
        val expected = listOf(
            "app:only",
            "system-a:only",
            "system-z:only",
            "dynamic-a:only",
            "dynamic-b:a",
            "dynamic-b:z",
        )

        assertEquals(expected, first.fragments.map { "${it.sourceId}:${it.fragmentId}" })
        assertEquals(first.fragments, second.fragments)
    }

    @Test
    fun duplicateSourceIdIsRejectedDeterministically() {
        val error = configurationError(
            listOf(
                source(descriptor("duplicate")),
                source(descriptor("duplicate")),
            ),
        )

        assertEquals(RivenContextRegistryConfigurationError.DuplicateSourceId("duplicate"), error)
    }

    @Test
    fun blankSourceIdIsRejected() {
        assertEquals(
            RivenContextRegistryConfigurationError.BlankSourceId,
            configurationError(listOf(source(descriptor(" \n\t")))),
        )
    }

    @Test
    fun zeroAndNegativeDescriptorBoundsAreRejected() {
        val descriptors = listOf(
            descriptor("zero-fragments", maxFragments = 0),
            descriptor("negative-fragments", maxFragments = -1),
            descriptor("zero-fragment-chars", maxCharsPerFragment = 0),
            descriptor("negative-fragment-chars", maxCharsPerFragment = -1),
            descriptor("zero-aggregate", maxAggregateChars = 0),
            descriptor("negative-aggregate", maxAggregateChars = -1),
        )

        val errors = descriptors.map { value -> configurationError(listOf(source(value))) }

        assertTrue(errors[0] is RivenContextRegistryConfigurationError.InvalidMaxFragments)
        assertTrue(errors[1] is RivenContextRegistryConfigurationError.InvalidMaxFragments)
        assertTrue(errors[2] is RivenContextRegistryConfigurationError.InvalidMaxCharsPerFragment)
        assertTrue(errors[3] is RivenContextRegistryConfigurationError.InvalidMaxCharsPerFragment)
        assertTrue(errors[4] is RivenContextRegistryConfigurationError.InvalidMaxAggregateChars)
        assertTrue(errors[5] is RivenContextRegistryConfigurationError.InvalidMaxAggregateChars)
    }

    @Test
    fun maximumFragmentsViolationIsExplicit() = runBlocking {
        val source = source(
            descriptor("bounded", maxFragments = 1),
            payloads = listOf(payload("a"), payload("b")),
        )

        val failure = assertFailure(RivenContextSourceRegistry(listOf(source)).collect(now = 1))

        assertTrue(contractViolation(failure) is RivenContextContractViolation.MaximumFragmentsExceeded)
        assertTrue(failure.snapshot.fragments.isEmpty())
    }

    @Test
    fun perFragmentSizeViolationDoesNotTruncateContent() = runBlocking {
        val original = "123456"
        val source = source(
            descriptor("bounded", maxCharsPerFragment = 5),
            payloads = listOf(payload("large", original)),
        )

        val failure = assertFailure(RivenContextSourceRegistry(listOf(source)).collect(now = 1))
        val violation = contractViolation(failure)

        assertEquals(
            RivenContextContractViolation.FragmentTooLarge("large", maximumChars = 5, actualChars = 6),
            violation,
        )
        assertEquals(original, source.payloads.single().content)
        assertTrue(failure.snapshot.fragments.isEmpty())
    }

    @Test
    fun aggregateSizeViolationIsExplicit() = runBlocking {
        val source = source(
            descriptor("aggregate", maxCharsPerFragment = 10, maxAggregateChars = 5),
            payloads = listOf(payload("a", "abc"), payload("b", "def")),
        )

        val failure = assertFailure(RivenContextSourceRegistry(listOf(source)).collect(now = 1))

        assertEquals(
            RivenContextContractViolation.AggregateTooLarge(maximumChars = 5, actualChars = 6),
            contractViolation(failure),
        )
    }

    @Test
    fun duplicateFragmentIdIsAContractViolation() = runBlocking {
        val source = source(
            descriptor("duplicate-fragments"),
            payloads = listOf(payload("same", "one"), payload("same", "two")),
        )

        val failure = assertFailure(RivenContextSourceRegistry(listOf(source)).collect(now = 1))

        assertEquals(
            RivenContextContractViolation.DuplicateFragmentId("same"),
            contractViolation(failure),
        )
    }

    @Test
    fun blankFragmentIdAndContentAreContractViolations() = runBlocking {
        val blankId = assertFailure(
            RivenContextSourceRegistry(
                listOf(source(descriptor("blank-id"), listOf(payload(" ", "content")))),
            ).collect(now = 1),
        )
        val blankContent = assertFailure(
            RivenContextSourceRegistry(
                listOf(source(descriptor("blank-content"), listOf(payload("id", " \n")))),
            ).collect(now = 1),
        )

        assertTrue(contractViolation(blankId) is RivenContextContractViolation.BlankFragmentId)
        assertEquals(
            RivenContextContractViolation.BlankContent("id"),
            contractViolation(blankContent),
        )
    }

    @Test
    fun invalidFreshnessWindowIsAContractViolation() = runBlocking {
        val equal = source(
            descriptor("equal-window"),
            listOf(payload("equal", observedAt = 10, validUntil = 10)),
        )
        val reversed = source(
            descriptor("reversed-window"),
            listOf(payload("reversed", observedAt = 10, validUntil = 9)),
        )

        listOf(equal, reversed).forEach { invalid ->
            val failure = assertFailure(RivenContextSourceRegistry(listOf(invalid)).collect(now = 1))
            assertTrue(contractViolation(failure) is RivenContextContractViolation.InvalidFreshnessWindow)
        }
    }

    @Test
    fun expiredPayloadIsOmittedWithoutFailure() = runBlocking {
        val source = source(
            descriptor("freshness"),
            listOf(
                payload("expired", observedAt = 1, validUntil = 10),
                payload("fresh", observedAt = 2, validUntil = 11),
                payload("unbounded", observedAt = 3),
            ),
        )

        val snapshot = assertSuccess(RivenContextSourceRegistry(listOf(source)).collect(now = 10))

        assertEquals(listOf("fresh", "unbounded"), snapshot.fragments.map { it.fragmentId })
        assertTrue(snapshot.optionalFailures.isEmpty())
    }

    @Test
    fun optionalSourceFailureKeepsSuccessfulFragmentsAndUsableResult() = runBlocking {
        val optional = failingSource(
            descriptor(
                sourceId = "optional",
                criticality = RivenContextSourceCriticality.OPTIONAL,
            ),
        )
        val healthy = source(descriptor("healthy"), listOf(payload("fragment", "kept")))

        val snapshot = assertSuccess(RivenContextSourceRegistry(listOf(optional, healthy)).collect(now = 1))

        assertEquals(listOf("kept"), snapshot.fragments.map { it.content })
        assertEquals(listOf("optional"), snapshot.optionalFailures.map { it.sourceId })
    }

    @Test
    fun requiredSourceFailureReturnsFailureWithDiagnosticFragments() = runBlocking {
        val required = failingSource(descriptor("required"))
        val healthy = source(descriptor("healthy"), listOf(payload("fragment", "diagnostic")))

        val failure = assertFailure(RivenContextSourceRegistry(listOf(required, healthy)).collect(now = 1))

        assertEquals(listOf("required"), failure.requiredFailures.map { it.sourceId })
        assertEquals(listOf("diagnostic"), failure.snapshot.fragments.map { it.content })
    }

    @Test
    fun cancellationPropagates() = runBlocking {
        val cancelling = FakeSource(descriptor("cancel")) { throw CancellationException("cancelled") }

        try {
            RivenContextSourceRegistry(listOf(cancelling)).collect(now = 1)
            error("Expected cancellation")
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled", cancelled.message)
        }
    }

    private fun configurationError(
        sources: List<RivenContextSource>,
    ): RivenContextRegistryConfigurationError = try {
        RivenContextSourceRegistry(sources)
        error("Expected configuration failure")
    } catch (failure: RivenContextRegistryConfigurationException) {
        failure.error
    }

    private fun contractViolation(
        result: RivenContextCollectionResult.Failure,
    ): RivenContextContractViolation {
        val cause = result.requiredFailures.single().cause
        assertTrue(cause is RivenContextFailureCause.ContractViolation)
        return (cause as RivenContextFailureCause.ContractViolation).violation
    }

    private fun assertSuccess(result: RivenContextCollectionResult): RivenContextSnapshot {
        assertTrue("Expected success, got $result", result is RivenContextCollectionResult.Success)
        return (result as RivenContextCollectionResult.Success).snapshot
    }

    private fun assertFailure(result: RivenContextCollectionResult): RivenContextCollectionResult.Failure {
        assertTrue("Expected failure, got $result", result is RivenContextCollectionResult.Failure)
        return result as RivenContextCollectionResult.Failure
    }

    private fun source(
        descriptor: RivenContextSourceDescriptor,
        payloads: List<RivenContextPayload> = emptyList(),
    ) = FakeSource(descriptor) { RivenContextSourceResult.Success(payloads) }

    private fun failingSource(descriptor: RivenContextSourceDescriptor) = FakeSource(descriptor) {
        RivenContextSourceResult.Failure(
            RivenContextSourceError.ReadFailure(errorType = "ControlledFailure"),
        )
    }

    private fun descriptor(
        sourceId: String,
        layer: RivenContextLayer = RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS,
        provenanceClass: RivenContextProvenanceClass = RivenContextProvenanceClass.OTHER_GROUNDED,
        criticality: RivenContextSourceCriticality = RivenContextSourceCriticality.REQUIRED,
        orderWithinLayer: Int = 0,
        maxFragments: Int = 10,
        maxCharsPerFragment: Int = 100,
        maxAggregateChars: Int = 1_000,
    ) = RivenContextSourceDescriptor(
        sourceId = sourceId,
        layer = layer,
        provenanceClass = provenanceClass,
        criticality = criticality,
        orderWithinLayer = orderWithinLayer,
        maxFragments = maxFragments,
        maxCharsPerFragment = maxCharsPerFragment,
        maxAggregateChars = maxAggregateChars,
        budgetBehavior = RivenContextBudgetBehavior.REQUIRED,
    )

    private fun payload(
        fragmentId: String,
        content: String = fragmentId,
        observedAt: Long? = null,
        validUntil: Long? = null,
    ) = RivenContextPayload(
        fragmentId = fragmentId,
        content = content,
        observedAt = observedAt,
        validUntil = validUntil,
    )

    private class FakeSource(
        override val descriptor: RivenContextSourceDescriptor,
        private val readResult: suspend () -> RivenContextSourceResult,
    ) : RivenContextSource {
        val payloads: List<RivenContextPayload>
            get() = (runBlocking { readResult() } as RivenContextSourceResult.Success).payloads

        override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult = readResult()
    }
}
