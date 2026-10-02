package com.shai.riven.data.runtime

import android.content.Context
import androidx.core.content.edit
import com.shai.riven.data.provider.openrouter.OpenRouterImageInputCapability
import java.util.concurrent.ConcurrentHashMap

interface ImageInputCapabilityStore {
    fun read(profileId: String, modelId: String): OpenRouterImageInputCapability?
    fun write(profileId: String, modelId: String, capability: OpenRouterImageInputCapability)
    fun readModel(modelId: String): StoredOpenRouterModelMetadata?
    fun writeModel(modelId: String, metadata: StoredOpenRouterModelMetadata)
}

data class StoredOpenRouterModelMetadata(
    val imageInputCapability: OpenRouterImageInputCapability,
    val contextLength: Int?,
)

class InMemoryImageInputCapabilityStore : ImageInputCapabilityStore {
    private val values = ConcurrentHashMap<String, OpenRouterImageInputCapability>()
    private val models = ConcurrentHashMap<String, StoredOpenRouterModelMetadata>()

    override fun read(profileId: String, modelId: String): OpenRouterImageInputCapability? =
        values[key(profileId, modelId)]

    override fun write(
        profileId: String,
        modelId: String,
        capability: OpenRouterImageInputCapability,
    ) {
        values[key(profileId, modelId)] = capability
    }

    override fun readModel(modelId: String): StoredOpenRouterModelMetadata? = models[modelId]

    override fun writeModel(modelId: String, metadata: StoredOpenRouterModelMetadata) {
        models[modelId] = metadata
    }

    private fun key(profileId: String, modelId: String) = "$profileId\u0000$modelId"
}

class AndroidImageInputCapabilityStore(context: Context) : ImageInputCapabilityStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(profileId: String, modelId: String): OpenRouterImageInputCapability? =
        preferences.getString(key(profileId, modelId), null)?.let { encoded ->
            OpenRouterImageInputCapability.entries.firstOrNull { it.name == encoded }
        }

    override fun write(
        profileId: String,
        modelId: String,
        capability: OpenRouterImageInputCapability,
    ) {
        preferences.edit { putString(key(profileId, modelId), capability.name) }
    }

    override fun readModel(modelId: String): StoredOpenRouterModelMetadata? {
        val encoded = preferences.getString(modelKey(modelId), null) ?: return null
        val separator = encoded.indexOf('|')
        if (separator <= 0) return null
        val capability = OpenRouterImageInputCapability.entries.firstOrNull {
            it.name == encoded.substring(0, separator)
        } ?: return null
        val context = encoded.substring(separator + 1).takeIf(String::isNotEmpty)?.toIntOrNull()
        return StoredOpenRouterModelMetadata(capability, context)
    }

    override fun writeModel(modelId: String, metadata: StoredOpenRouterModelMetadata) {
        val encoded = metadata.imageInputCapability.name + "|" +
            metadata.contextLength?.toString().orEmpty()
        preferences.edit { putString(modelKey(modelId), encoded) }
    }

    private fun key(profileId: String, modelId: String) = "${profileId.length}:$profileId$modelId"
    private fun modelKey(modelId: String) = "model:${modelId.length}:$modelId"

    private companion object {
        const val PREFERENCES_NAME = "riven_image_input_capabilities"
    }
}
