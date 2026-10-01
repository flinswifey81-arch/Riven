package com.shai.riven.data.conversation.engine

class ProviderAdapterRegistry(
    adapters: Collection<ConversationProviderAdapter>,
) {
    private val adaptersById: Map<String, ConversationProviderAdapter>

    init {
        val registered = linkedMapOf<String, ConversationProviderAdapter>()
        adapters.forEach { adapter ->
            val adapterId = adapter.descriptor.adapterId
            if (adapterId.isBlank()) {
                throw ProviderAdapterRegistryConfigurationException(
                    ProviderAdapterRegistryError.BlankAdapterId,
                )
            }
            if (registered.put(adapterId, adapter) != null) {
                throw ProviderAdapterRegistryConfigurationException(
                    ProviderAdapterRegistryError.DuplicateAdapterId(adapterId),
                )
            }
        }
        adaptersById = registered.toMap()
    }

    fun adapter(adapterId: String): ProviderAdapterLookupResult =
        adaptersById[adapterId]?.let(ProviderAdapterLookupResult::Found)
            ?: ProviderAdapterLookupResult.Failure(ProviderAdapterRegistryError.MissingAdapter(adapterId))
}
