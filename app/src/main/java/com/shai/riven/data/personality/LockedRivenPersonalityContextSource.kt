package com.shai.riven.data.personality

import android.content.Context
import com.shai.riven.data.context.RivenContextBudgetBehavior
import com.shai.riven.data.context.RivenContextContentAuthority
import com.shai.riven.data.context.RivenContextLayer
import com.shai.riven.data.context.RivenContextPayload
import com.shai.riven.data.context.RivenContextProvenanceClass
import com.shai.riven.data.context.RivenContextReadRequest
import com.shai.riven.data.context.RivenContextSource
import com.shai.riven.data.context.RivenContextSourceCriticality
import com.shai.riven.data.context.RivenContextSourceDescriptor
import com.shai.riven.data.context.RivenContextSourceError
import com.shai.riven.data.context.RivenContextSourceResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class LockedRivenPersonalityContextSource(
    private val canonLoader: () -> String,
) : RivenContextSource {
    constructor(context: Context) : this(
        canonLoader = {
            context.applicationContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        },
    )

    override val descriptor = RivenContextSourceDescriptor(
        sourceId = SOURCE_ID,
        layer = RivenContextLayer.LOCKED_RIVEN_PERSONALITY_AND_IDENTITY_CANON,
        provenanceClass = RivenContextProvenanceClass.LOCKED_CANON,
        criticality = RivenContextSourceCriticality.REQUIRED,
        orderWithinLayer = 0,
        maxFragments = 1,
        maxCharsPerFragment = MAX_CANON_CHARS,
        maxAggregateChars = MAX_CANON_CHARS,
        budgetBehavior = RivenContextBudgetBehavior.REQUIRED,
        contentAuthority = RivenContextContentAuthority.INSTRUCTIONS,
    )

    @Volatile
    private var cachedCanon: String? = null

    override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult {
        val canon = try {
            verifiedCanon()
        } catch (failure: Exception) {
            return RivenContextSourceResult.Failure(
                RivenContextSourceError.ReadFailure(
                    errorType = "LockedPersonalityCanonUnavailable",
                    causeType = failure::class.java.simpleName,
                ),
            )
        }
        return RivenContextSourceResult.Success(
            payloads = listOf(
                RivenContextPayload(
                    fragmentId = DOCUMENT_ID,
                    content = canon,
                ),
            ),
        )
    }

    internal fun verifiedCanon(): String =
        cachedCanon ?: synchronized(this) {
            cachedCanon ?: loadAndVerify().also { cachedCanon = it }
        }

    private fun loadAndVerify(): String {
        val normalized = canonLoader()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd('\n')
        require(normalized.isNotBlank())
        require(normalized.length <= MAX_CANON_CHARS)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        require(digest == CANON_SHA256) { "Locked personality canon integrity check failed" }
        return normalized
    }

    companion object {
        const val SOURCE_ID = "LOCKED_RIVEN_PERSONALITY_CANON"
        const val DOCUMENT_ID = "riven_app_personality_canon_2026_09_24"
        const val ASSET_NAME = "Riven_App_Personality_Canon_2026-09-24.json"
        const val CANON_SHA256 = "38c26c29e1f066d1398c4953b86aa1d090c0000e127cfd087e3e5d8ad3e0f804"
        const val MAX_CANON_CHARS = 32_768
    }
}
