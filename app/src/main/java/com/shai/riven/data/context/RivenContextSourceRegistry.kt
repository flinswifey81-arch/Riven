package com.shai.riven.data.context

import kotlinx.coroutines.CancellationException

class RivenContextSourceRegistry(
    sources: Collection<RivenContextSource>,
) {
    private val sources = sources.toList().also(::validateConfiguration)

    suspend fun collect(now: Long): RivenContextCollectionResult {
        val fragments = mutableListOf<RivenContextFragment>()
        val optionalFailures = mutableListOf<RivenContextSourceFailure>()
        val requiredFailures = mutableListOf<RivenContextSourceFailure>()
        val request = RivenContextReadRequest(now)

        sources.sortedWith(SOURCE_ORDER).forEach { source ->
            val result = try {
                source.read(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RivenContextSourceResult.Failure(
                    RivenContextSourceError.UnexpectedFailure(failure::class.java.simpleName),
                )
            }

            when (result) {
                is RivenContextSourceResult.Failure -> {
                    recordFailure(
                        source = source,
                        cause = RivenContextFailureCause.SourceFailure(result.error),
                        optionalFailures = optionalFailures,
                        requiredFailures = requiredFailures,
                    )
                }

                is RivenContextSourceResult.Success -> {
                    val violation = validatePayloads(source.descriptor, result.payloads)
                    if (violation != null) {
                        recordFailure(
                            source = source,
                            cause = RivenContextFailureCause.ContractViolation(violation),
                            optionalFailures = optionalFailures,
                            requiredFailures = requiredFailures,
                        )
                    } else {
                        result.payloads
                            .asSequence()
                            .filterNot { payload ->
                                payload.validUntil?.let { validUntil -> validUntil <= now } == true
                            }
                            .map { payload -> source.descriptor.stamp(payload) }
                            .forEach(fragments::add)
                    }
                }
            }
        }

        val snapshot = RivenContextSnapshot(
            fragments = fragments.sortedWith(FRAGMENT_ORDER),
            optionalFailures = optionalFailures.toList(),
        )
        return if (requiredFailures.isEmpty()) {
            RivenContextCollectionResult.Success(snapshot)
        } else {
            RivenContextCollectionResult.Failure(
                snapshot = snapshot,
                requiredFailures = requiredFailures.toList(),
            )
        }
    }

    private fun recordFailure(
        source: RivenContextSource,
        cause: RivenContextFailureCause,
        optionalFailures: MutableList<RivenContextSourceFailure>,
        requiredFailures: MutableList<RivenContextSourceFailure>,
    ) {
        val failure = RivenContextSourceFailure(
            sourceId = source.descriptor.sourceId,
            criticality = source.descriptor.criticality,
            cause = cause,
        )
        when (source.descriptor.criticality) {
            RivenContextSourceCriticality.REQUIRED -> requiredFailures += failure
            RivenContextSourceCriticality.OPTIONAL -> optionalFailures += failure
        }
    }

    private fun validatePayloads(
        descriptor: RivenContextSourceDescriptor,
        payloads: List<RivenContextPayload>,
    ): RivenContextContractViolation? {
        if (payloads.size > descriptor.maxFragments) {
            return RivenContextContractViolation.MaximumFragmentsExceeded(
                maximum = descriptor.maxFragments,
                actual = payloads.size,
            )
        }

        val seenFragmentIds = mutableSetOf<String>()
        payloads.forEachIndexed { index, payload ->
            if (payload.fragmentId.isBlank()) {
                return RivenContextContractViolation.BlankFragmentId(index)
            }
            if (!seenFragmentIds.add(payload.fragmentId)) {
                return RivenContextContractViolation.DuplicateFragmentId(payload.fragmentId)
            }
            if (payload.content.isBlank()) {
                return RivenContextContractViolation.BlankContent(payload.fragmentId)
            }
            if (payload.content.length > descriptor.maxCharsPerFragment) {
                return RivenContextContractViolation.FragmentTooLarge(
                    fragmentId = payload.fragmentId,
                    maximumChars = descriptor.maxCharsPerFragment,
                    actualChars = payload.content.length,
                )
            }
            val observedAt = payload.observedAt
            val validUntil = payload.validUntil
            if (observedAt != null && validUntil != null && validUntil <= observedAt) {
                return RivenContextContractViolation.InvalidFreshnessWindow(
                    fragmentId = payload.fragmentId,
                    observedAt = observedAt,
                    validUntil = validUntil,
                )
            }
        }

        val aggregateChars = payloads.sumOf { payload -> payload.content.length.toLong() }
        if (aggregateChars > descriptor.maxAggregateChars) {
            return RivenContextContractViolation.AggregateTooLarge(
                maximumChars = descriptor.maxAggregateChars,
                actualChars = aggregateChars,
            )
        }
        return null
    }

    private fun RivenContextSourceDescriptor.stamp(payload: RivenContextPayload) = RivenContextFragment(
        sourceId = sourceId,
        fragmentId = payload.fragmentId,
        content = payload.content,
        layer = layer,
        provenanceClass = provenanceClass,
        criticality = criticality,
        orderWithinLayer = orderWithinLayer,
        budgetBehavior = budgetBehavior,
        revision = payload.revision,
        observedAt = payload.observedAt,
        validUntil = payload.validUntil,
    )

    private fun validateConfiguration(sources: List<RivenContextSource>) {
        val seenSourceIds = mutableSetOf<String>()
        sources.forEach { source ->
            val descriptor = source.descriptor
            if (descriptor.sourceId.isBlank()) {
                throw RivenContextRegistryConfigurationException(
                    RivenContextRegistryConfigurationError.BlankSourceId,
                )
            }
            if (!seenSourceIds.add(descriptor.sourceId)) {
                throw RivenContextRegistryConfigurationException(
                    RivenContextRegistryConfigurationError.DuplicateSourceId(descriptor.sourceId),
                )
            }
            if (descriptor.maxFragments <= 0) {
                throw RivenContextRegistryConfigurationException(
                    RivenContextRegistryConfigurationError.InvalidMaxFragments(
                        descriptor.sourceId,
                        descriptor.maxFragments,
                    ),
                )
            }
            if (descriptor.maxCharsPerFragment <= 0) {
                throw RivenContextRegistryConfigurationException(
                    RivenContextRegistryConfigurationError.InvalidMaxCharsPerFragment(
                        descriptor.sourceId,
                        descriptor.maxCharsPerFragment,
                    ),
                )
            }
            if (descriptor.maxAggregateChars <= 0) {
                throw RivenContextRegistryConfigurationException(
                    RivenContextRegistryConfigurationError.InvalidMaxAggregateChars(
                        descriptor.sourceId,
                        descriptor.maxAggregateChars,
                    ),
                )
            }
        }
    }

    private companion object {
        val SOURCE_ORDER = compareBy<RivenContextSource>(
            { source -> source.descriptor.layer.ordinal },
            { source -> source.descriptor.orderWithinLayer },
            { source -> source.descriptor.sourceId },
        )

        val FRAGMENT_ORDER = compareBy<RivenContextFragment>(
            { fragment -> fragment.layer.ordinal },
            { fragment -> fragment.orderWithinLayer },
            { fragment -> fragment.sourceId },
            { fragment -> fragment.fragmentId },
        )
    }
}
