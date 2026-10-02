package com.shai.riven.data.automaticmemory

import com.shai.riven.data.attention.AttentionContextMessage
import com.shai.riven.data.attention.ImmediateAttentionSnapshot
import com.shai.riven.data.attention.PositiveAttentionSignal
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.provider.ProviderCapability
import com.shai.riven.data.provider.ProviderProfileSnapshot
import com.shai.riven.data.provider.ResolvedProviderRuntimeProfile
import com.shai.riven.data.provider.openrouter.OpenRouterConversationAdapter
import com.shai.riven.data.provider.openrouter.OpenRouterHttpClient
import com.shai.riven.data.provider.openrouter.OpenRouterHttpRequest
import com.shai.riven.data.provider.openrouter.OpenRouterHttpResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterAutomaticMemoryModelTest {
    @Test
    fun analysisUsesSelectedProfileCredentialBoundaryAndOneBoundedJsonCall() = runBlocking {
        val response = JSONObject()
            .put("attention", JSONObject()
                .put("outcome", "FORWARD_FOR_INTERPRETATION")
                .put("positiveSignals", JSONArray().put("PREFERENCE"))
                .put("antiSignals", JSONArray()))
            .put("candidates", JSONArray().put(JSONObject()
                .put("meaning", "Shai loves sardines.")
                .put("kind", "SEMANTIC")
                .put("scope", "SHAI")
                .put("epistemicBasis", "DIRECT_USER_STATEMENT")
                .put("certainty", "CERTAIN")
                .put("state", "READY_FOR_VALIDATION")
                .put("sensitivity", "STANDARD")))
        val http = RecordingHttpClient(response)
        val model = OpenRouterAutomaticMemoryModel(runtimeProfile(), http)

        val attention = model.analyze(snapshot())
        val extraction = model.extract(
            com.shai.riven.data.candidate.CandidateExtractionSnapshot(
                experienceId = "experience-1",
                experienceType = ExperienceType.CONVERSATION_MESSAGE,
                actor = ExperienceActor.SHAI,
                sourceContent = "I love sardines.",
                occurredAt = 1,
                sensitivity = SensitivityLevel.STANDARD,
                sourceMessage = null,
                precedingActiveContext = emptyList(),
                followingActiveContext = listOf(
                    AttentionContextMessage("assistant-1", MessageRole.ASSISTANT, "Noted.", 2),
                ),
                groundedEntityLinks = emptyList(),
                attentionRevision = 0,
                attentionOutcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
                positiveSignals = setOf(PositiveAttentionSignal.PREFERENCE),
                antiSignals = emptySet(),
            ),
        )

        assertEquals(AttentionOutcome.FORWARD_FOR_INTERPRETATION, attention.outcome)
        assertEquals(listOf(PositiveAttentionSignal.PREFERENCE), attention.positiveSignals)
        assertEquals("Shai loves sardines.", extraction.candidates.single().proposedMeaning)
        assertEquals(1, http.requests.size)
        val request = http.requests.single()
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url)
        assertEquals("Bearer test-secret-key", request.headers["Authorization"])
        assertFalse(request.body.orEmpty().contains("test-secret-key"))
        val body = JSONObject(request.body.orEmpty())
        assertEquals(false, body.getBoolean("stream"))
        assertEquals(OpenRouterAutomaticMemoryModel.MAX_COMPLETION_TOKENS, body.getInt("max_completion_tokens"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("Noted."))
    }

    @Test
    fun nonOpenRouterEndpointFailsBeforeCredentialCanReachTransport() = runBlocking {
        val http = RecordingHttpClient(JSONObject())
        val profile = runtimeProfile().copy(
            profile = runtimeProfile().profile.copy(endpointBaseUrl = "https://example.com/api/v1"),
        )
        val model = OpenRouterAutomaticMemoryModel(profile, http)

        val failed = runCatching { model.analyze(snapshot()) }.isFailure

        assertTrue(failed)
        assertTrue(http.requests.isEmpty())
    }

    private fun snapshot() = ImmediateAttentionSnapshot(
        experienceId = "experience-1",
        experienceType = ExperienceType.CONVERSATION_MESSAGE,
        actor = ExperienceActor.SHAI,
        sourceContent = "I love sardines.",
        occurredAt = 1,
        sensitivity = SensitivityLevel.STANDARD,
        sourceMessage = null,
        precedingActiveContext = emptyList(),
        followingActiveContext = listOf(
            AttentionContextMessage("assistant-1", MessageRole.ASSISTANT, "Noted.", 2),
        ),
        groundedEntityLinks = emptyList(),
        timelineRevision = 2,
    )

    private fun runtimeProfile() = ResolvedProviderRuntimeProfile(
        profile = ProviderProfileSnapshot(
            profileId = "selected-profile",
            displayName = "Selected",
            adapterId = OpenRouterConversationAdapter.ADAPTER_ID,
            endpointBaseUrl = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
            modelId = "test/model",
            credentialSlotId = "selected-slot",
            isEnabled = true,
            capabilities = setOf(ProviderCapability.TEXT_CHAT),
            revision = 0,
            createdAt = 1,
            updatedAt = 1,
        ),
        credential = ProviderSecret.fromPlaintext("test-secret-key"),
    )

    private class RecordingHttpClient(
        private val content: JSONObject,
    ) : OpenRouterHttpClient {
        val requests = mutableListOf<OpenRouterHttpRequest>()

        override suspend fun execute(
            request: OpenRouterHttpRequest,
            onLine: suspend (String) -> Boolean,
        ): OpenRouterHttpResponse {
            requests += request
            val envelope = JSONObject()
                .put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", content.toString()))))
            onLine(envelope.toString())
            return OpenRouterHttpResponse(200, emptyMap())
        }
    }
}
