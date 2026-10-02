package com.shai.riven.data.runtime

import android.content.Context
import androidx.core.content.edit
import com.shai.riven.data.provider.openrouter.OpenRouterImageInputCapability
import java.util.concurrent.ConcurrentHashMap

interface ImageInputCapabilityStore {
    fun read(profileId: String, modelId: String): OpenRouterImageInputCapability?
    fun write(profileId: String, modelId: String, capability: OpenRouterImageInputCapability)
}

class InMemoryImageInputCapabilityStore : ImageInputCapabilityStore {
    private val values = ConcurrentHashMap<String, OpenRouterImageInputCapability>()

    override fun read(profileId: String, modelId: String): OpenRouterImageInputCapability? =
        values[key(profileId, modelId)]

    override fun write(
        profileId: String,
        modelId: String,
        capability: OpenRouterImageInputCapability,
    ) {
        values[key(profileId, modelId)] = capability
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

    private fun key(profileId: String, modelId: String) = "${profileId.length}:$profileId$modelId"

    private companion object {
        const val PREFERENCES_NAME = "riven_image_input_capabilities"
    }
}
