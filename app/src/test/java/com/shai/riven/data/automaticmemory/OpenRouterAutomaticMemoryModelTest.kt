package com.shai.riven.data.automaticmemory

import com.shai.riven.data.attention.AttentionContextMessage
import com.shai.riven.data.attention.AttentionAssessment
import com.shai.riven.data.attention.ImmediateAttentionSnapshot
import com.shai.riven.data.attention.PositiveAttentionSignal
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.provider.ProviderCapability
import com.shai.riven.data.provider.ProviderProfileSnapshot
import com.shai.riven.data.provider.ResolvedProviderRuntimeProfile
import com.shai.riven.data.provider.openrouter.OpenRouterConversationAdapter
import com.shai.riven.data.provider.openrouter.OpenRouterHttpClient
import com.shai.riven.data.provider.openrouter.OpenRouterHttpRequest
import com.shai.riven.data.provider.openrouter.OpenRouterHttpResponse
import com.shai.riven.data.validation.CandidateValidationOutcome
import com.shai.riven.data.validation.CandidateValidationSnapshot
import com.shai.riven.data.validation.ClassificationChangeReason
import com.shai.riven.data.validation.ValidationAttentionSignals
import com.shai.riven.data.validation.ValidationCandidateSnapshot
import com.shai.riven.data.memory.RefinementDisposition
import com.shai.riven.data.memory.ConsolidationProposal
import com.shai.riven.data.memory.ConsolidationSnapshot
import com.shai.riven.data.memory.ConsolidationSourceMemory
import com.shai.riven.data.memory.OpenLoopLifecycleItem
import com.shai.riven.data.memory.OpenLoopLifecycleProposal
import com.shai.riven.data.memory.OpenLoopLifecycleSnapshot
import com.shai.riven.data.persistence.model.OpenLoopState
import java.math.BigDecimal
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
    fun singleJsonMarkdownFenceIsAcceptedAtProviderBoundary() = runBlocking {
        val response = JSONObject()
            .put("attention", JSONObject()
                .put("outcome", "NO_CANDIDATE")
                .put("positiveSignals", JSONArray())
                .put("antiSignals", JSONArray()))
            .put("candidates", JSONArray())
        val model = OpenRouterAutomaticMemoryModel(
            runtimeProfile(),
            RecordingHttpClient.raw("\uFEFF  ```json\r\n$response\r\n```  "),
        )

        val result = model.analyze(snapshot())

        assertEquals(AttentionOutcome.NO_CANDIDATE, result.outcome)
    }

    @Test
    fun proseOrMultipleFencesRemainMalformedProviderContent() = runBlocking {
        val response = JSONObject()
            .put("attention", JSONObject()
                .put("outcome", "NO_CANDIDATE")
                .put("positiveSignals", JSONArray())
                .put("antiSignals", JSONArray()))
            .put("candidates", JSONArray())
        listOf(
            "Here is the result: $response",
            "```json\n$response\n```\n```json\n$response\n```",
            "$response trailing commentary",
        ).forEach { rawContent ->
            val model = OpenRouterAutomaticMemoryModel(runtimeProfile(), RecordingHttpClient.raw(rawContent))

            val failure = runCatching { model.analyze(snapshot()) }.exceptionOrNull()

            assertTrue(failure is AutomaticMemoryModelFailure)
            assertEquals("MALFORMED_CONTENT", (failure as AutomaticMemoryModelFailure).errorCode)
            assertTrue(failure.retryable)
        }
    }

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
                .put("sensitivity", "STANDARD")
                .put("sourceClaimId", "SOURCE_CLAIM_V1:0")
                .put("sourceAnchor", JSONObject()
                    .put("text", "love sardines")
                    .put("occurrence", 0))))
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
        assertEquals("SOURCE_CLAIM_V1:0", extraction.candidates.single().sourceClaimId)
        assertEquals("love sardines", extraction.candidates.single().sourceAnchor.text)
        assertEquals(1, http.requests.size)
        val request = http.requests.single()
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url)
        assertEquals("Bearer test-secret-key", request.headers["Authorization"])
        assertFalse(request.body.orEmpty().contains("test-secret-key"))
        val body = JSONObject(request.body.orEmpty())
        assertEquals(false, body.getBoolean("stream"))
        assertEquals(OpenRouterAutomaticMemoryModel.MAX_COMPLETION_TOKENS, body.getInt("max_completion_tokens"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val payload = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertTrue(payload.toString().contains("Noted."))
        assertEquals("SOURCE_CLAIM_V1:0", payload.getJSONArray("sourceClaims").getJSONObject(0)
            .getString("sourceClaimId"))
        assertEquals("I love sardines.", payload.getJSONArray("sourceClaims").getJSONObject(0)
            .getString("text"))
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

    @Test
    fun changedGroundingInvalidatesAnalysisCache() = runBlocking {
        val response = JSONObject()
            .put("attention", JSONObject()
                .put("outcome", "NO_CANDIDATE")
                .put("positiveSignals", JSONArray())
                .put("antiSignals", JSONArray()))
            .put("candidates", JSONArray())
        val http = RecordingHttpClient(response)
        val model = OpenRouterAutomaticMemoryModel(runtimeProfile(), http)

        model.analyze(snapshot())
        model.analyze(snapshot().copy(sourceContent = "I no longer love sardines."))

        assertEquals(2, http.requests.size)
        val secondPayload = JSONObject(http.requests.last().body.orEmpty())
            .getJSONArray("messages")
            .getJSONObject(1)
            .getString("content")
        assertTrue(secondPayload.contains("I no longer love sardines."))
    }

    @Test
    fun oversizedGroundingFailsBeforeProviderTransport() = runBlocking {
        val http = RecordingHttpClient(JSONObject())
        val model = OpenRouterAutomaticMemoryModel(
            runtimeProfile = runtimeProfile(),
            httpClient = http,
            maxInputChars = 64,
        )

        val failure = runCatching { model.analyze(snapshot()) }.exceptionOrNull()

        assertTrue(failure is AutomaticMemoryModelFailure)
        assertEquals("INPUT_LIMIT", (failure as AutomaticMemoryModelFailure).errorCode)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun unauthorizedProviderResponseIsClassifiedAsRecoverableCredentialBlock() = runBlocking {
        val http = RecordingHttpClient(JSONObject(), statusCode = 401)
        val model = OpenRouterAutomaticMemoryModel(runtimeProfile(), http)

        val failure = runCatching { model.analyze(snapshot()) }.exceptionOrNull()

        assertTrue(failure is AutomaticMemoryModelFailure)
        failure as AutomaticMemoryModelFailure
        assertEquals("INVALID_CREDENTIAL", failure.errorCode)
        assertTrue(failure.blocked)
        assertFalse(failure.retryable)
    }

    @Test
    fun nonStringAnalysisEnumsAreRejectedAsBoundedProviderFailure() = runBlocking {
        val response = JSONObject()
            .put("attention", JSONObject()
                .put("outcome", "FORWARD_FOR_INTERPRETATION")
                .put("positiveSignals", JSONArray().put(1))
                .put("antiSignals", JSONArray()))
            .put("candidates", JSONArray())
        val http = RecordingHttpClient(response)
        val model = OpenRouterAutomaticMemoryModel(runtimeProfile(), http)

        val failure = runCatching { model.analyze(snapshot()) }.exceptionOrNull()

        assertTrue(failure is AutomaticMemoryModelFailure)
        assertEquals("INVALID_ANALYSIS_RESPONSE", (failure as AutomaticMemoryModelFailure).errorCode)
    }

    @Test
    fun validationReceivesLockedCanonAndReturnsCanonicalDeferredShape() = runBlocking {
        val response = JSONObject()
            .put("outcome", "DEFER")
            .put("targetMemoryIds", JSONArray())
            .put("admission", JSONObject.NULL)
            .put("refinementDisposition", JSONObject.NULL)
            .put("deferState", "PENDING_CONTEXT")
            .put("deferReason", "NEEDS_MORE_EVIDENCE")
            .put("rejectReason", JSONObject.NULL)
            .put("classificationChangeReason", JSONObject.NULL)
        val http = RecordingHttpClient(response)
        val model = OpenRouterAutomaticMemoryModel(
            runtimeProfile = runtimeProfile(),
            httpClient = http,
            lockedPersonalityCanon = "exact locked canon",
        )
        val snapshot = CandidateValidationSnapshot(
            candidate = ValidationCandidateSnapshot(
                candidateId = "candidate-riven",
                proposedKind = MemoryKind.SELF_DEVELOPMENT,
                proposedScope = MemoryScope.RIVEN,
                proposedMeaning = "Riven has permanently changed.",
                proposedEpistemicBasis = EpistemicBasis.DIRECT_RIVEN_EXPERIENCE,
                proposedCertainty = MemoryCertainty.PROBABLE,
                state = CandidateMemoryState.READY_FOR_VALIDATION,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
            evidence = emptyList(),
            seedAttention = ValidationAttentionSignals(
                outcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
                revision = 3,
                positiveSignals = setOf(PositiveAttentionSignal.SELF_DEVELOPMENT),
                antiSignals = emptySet(),
            ),
            relatedMemories = emptyList(),
        )

        val decision = model.decide(snapshot)

        assertEquals(CandidateValidationOutcome.DEFER, decision.outcome)
        val payload = JSONObject(JSONObject(http.requests.single().body.orEmpty())
            .getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("exact locked canon", payload.getString("lockedRivenPersonalityCanon"))
        assertEquals(3, payload.getJSONObject("seedAttention").getLong("revision"))
    }

    @Test
    fun validationParsesTimeBoundsAllSignificanceDimensionsAndEntityLinks() = runBlocking {
        val response = acceptanceResponse(validFrom = 1_000L, validUntil = 2_000L)
        response.getJSONObject("admission")
            .put("significance", JSONObject()
                .put("autobiographical", "CORE")
                .put("relationship", "HIGH")
                .put("emotional", "MODERATE")
                .put("practical", "LOW")
                .put("identity", "NONE"))
            .put("entityLinks", JSONArray().put(JSONObject()
                .put("entityId", "grounded-person")
                .put("role", "ABOUT")))
        val http = RecordingHttpClient(response)
        val model = OpenRouterAutomaticMemoryModel(runtimeProfile(), http)

        val decision = model.decide(validationSnapshot())

        val admission = checkNotNull(decision.admission)
        assertEquals(TemporalState.TIME_BOUNDED, admission.temporalState)
        assertEquals(1_000L, admission.validFrom)
        assertEquals(2_000L, admission.validUntil)
        assertEquals(SignificanceLevel.CORE, admission.significance.autobiographical)
        assertEquals(SignificanceLevel.HIGH, admission.significance.relationship)
        assertEquals(SignificanceLevel.MODERATE, admission.significance.emotional)
        assertEquals(SignificanceLevel.LOW, admission.significance.practical)
        assertEquals(SignificanceLevel.NONE, admission.significance.identity)
        assertEquals("grounded-person", admission.entityLinks.single().entityId)
        val systemPrompt = JSONObject(http.requests.single().body.orEmpty())
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(systemPrompt.contains("validFrom <= validUntil"))
        assertTrue(systemPrompt.contains("classificationChangeReason is required"))
    }

    @Test
    fun validationParsesKindChangeReasonForRefinement() = runBlocking {
        val response = acceptanceResponse().apply {
            put("outcome", "REFINE_EXISTING")
            put("targetMemoryIds", JSONArray().put("target-memory"))
            put("refinementDisposition", "KEEP_BROADER_CURRENT")
            put("classificationChangeReason", "IMPRECISE_CLASSIFICATION")
        }
        val model = OpenRouterAutomaticMemoryModel(runtimeProfile(), RecordingHttpClient(response))

        val decision = model.decide(validationSnapshot())

        assertEquals(CandidateValidationOutcome.REFINE_EXISTING, decision.outcome)
        assertEquals(RefinementDisposition.KEEP_BROADER_CURRENT, decision.refinementDisposition)
        assertEquals(ClassificationChangeReason.IMPRECISE_CLASSIFICATION, decision.classificationChangeReason)
    }

    @Test
    fun validationRejectsFractionalAndOutOfRangeTimestamps() = runBlocking {
        listOf<Number>(1.5, BigDecimal("9223372036854775808")).forEach { invalidTimestamp ->
            val model = OpenRouterAutomaticMemoryModel(
                runtimeProfile(),
                RecordingHttpClient(acceptanceResponse(validFrom = invalidTimestamp, validUntil = 2_000L)),
            )

            val failure = runCatching { model.decide(validationSnapshot()) }.exceptionOrNull()

            assertTrue(failure is AutomaticMemoryModelFailure)
            assertEquals("INVALID_VALIDATION_RESPONSE", (failure as AutomaticMemoryModelFailure).errorCode)
        }
    }

    @Test
    fun openLoopProposalUsesOnlySuppliedLoopIdsAndParsesTerminalTransition() = runBlocking {
        val response = JSONObject()
            .put("action", "TRANSITION")
            .put("openLoopId", "loop-1")
            .put("state", "COMPLETED")
            .put("dueAt", JSONObject.NULL)
        val http = RecordingHttpClient(response)
        val model = OpenRouterAutomaticMemoryModel(runtimeProfile(), http)

        val proposal = model.proposeOpenLoop(
            OpenLoopLifecycleSnapshot(
                attention = AttentionAssessment(
                    experienceId = "experience-1",
                    outcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
                    positiveSignals = setOf(PositiveAttentionSignal.OPEN_LOOP),
                    antiSignals = emptySet(),
                    revision = 2,
                    createdAt = 1,
                    updatedAt = 2,
                ),
                grounding = snapshot(),
                currentLoops = listOf(
                    OpenLoopLifecycleItem(
                        "loop-1",
                        "Send draft",
                        null,
                        OpenLoopState.ACTIVE,
                        null,
                        SensitivityLevel.STANDARD,
                        1,
                    ),
                ),
            ),
        )

        assertEquals(
            OpenLoopLifecycleProposal.Transition("loop-1", OpenLoopState.COMPLETED),
            proposal,
        )
        val payload = JSONObject(JSONObject(http.requests.single().body.orEmpty())
            .getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("loop-1", payload.getJSONArray("currentLoops").getJSONObject(0)
            .getString("openLoopId"))
    }

    @Test
    fun consolidationProposalCarriesLockedCanonAndIndependentSourceIds() = runBlocking {
        val response = JSONObject()
            .put("action", "CREATE")
            .put("sourceMemoryIds", JSONArray().put("m1").put("m2"))
            .put("meaning", "A cautious broader pattern.")
            .put("kind", "SEMANTIC")
            .put("scope", "SHAI")
            .put("certainty", "PROBABLE")
            .put("sensitivity", "STANDARD")
            .put("temporalState", "CURRENT")
            .put("significance", JSONObject())
            .put("entityLinks", JSONArray())
        val http = RecordingHttpClient(response)
        val model = OpenRouterAutomaticMemoryModel(
            runtimeProfile(),
            http,
            lockedPersonalityCanon = "exact locked canon",
        )
        val snapshot = ConsolidationSnapshot(
            corpusFingerprint = "corpus",
            sources = listOf(
                ConsolidationSourceMemory(
                    "m1", MemoryKind.SEMANTIC, MemoryScope.SHAI, "First", MemoryCertainty.CERTAIN,
                    SensitivityLevel.STANDARD, listOf("e1"), "evidence-1", 1,
                ),
                ConsolidationSourceMemory(
                    "m2", MemoryKind.SEMANTIC, MemoryScope.SHAI, "Second", MemoryCertainty.PROBABLE,
                    SensitivityLevel.STANDARD, listOf("e2"), "evidence-2", 2,
                ),
            ),
        )

        val proposal = model.proposeConsolidation(snapshot)

        assertTrue(proposal is ConsolidationProposal.Create)
        assertEquals(listOf("m1", "m2"), (proposal as ConsolidationProposal.Create).sourceMemoryIds)
        val payload = JSONObject(JSONObject(http.requests.single().body.orEmpty())
            .getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("exact locked canon", payload.getString("lockedRivenPersonalityCanon"))
        assertEquals("e2", payload.getJSONArray("sources").getJSONObject(1)
            .getJSONArray("sourceExperienceIds").getString(0))
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

    private fun validationSnapshot() = CandidateValidationSnapshot(
        candidate = ValidationCandidateSnapshot(
            candidateId = "candidate",
            proposedKind = MemoryKind.SEMANTIC,
            proposedScope = MemoryScope.SHAI,
            proposedMeaning = "A grounded candidate.",
            proposedEpistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
            proposedCertainty = MemoryCertainty.CERTAIN,
            state = CandidateMemoryState.READY_FOR_VALIDATION,
            sensitivity = SensitivityLevel.STANDARD,
            createdAt = 1,
            updatedAt = 1,
        ),
        evidence = emptyList(),
        seedAttention = ValidationAttentionSignals(
            outcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
            revision = 1,
            positiveSignals = setOf(PositiveAttentionSignal.PREFERENCE),
            antiSignals = emptySet(),
        ),
        relatedMemories = emptyList(),
    )

    private fun acceptanceResponse(
        validFrom: Any? = null,
        validUntil: Any? = null,
    ) = JSONObject()
        .put("outcome", "ACCEPT_NEW")
        .put("targetMemoryIds", JSONArray())
        .put("admission", JSONObject()
            .put("temporalState", if (validFrom == null && validUntil == null) "CURRENT" else "TIME_BOUNDED")
            .put("validFrom", validFrom ?: JSONObject.NULL)
            .put("validUntil", validUntil ?: JSONObject.NULL)
            .put("sensitivity", JSONObject.NULL)
            .put("significance", JSONObject()
                .put("autobiographical", JSONObject.NULL)
                .put("relationship", JSONObject.NULL)
                .put("emotional", JSONObject.NULL)
                .put("practical", JSONObject.NULL)
                .put("identity", JSONObject.NULL))
            .put("entityLinks", JSONArray()))
        .put("refinementDisposition", JSONObject.NULL)
        .put("deferState", JSONObject.NULL)
        .put("deferReason", JSONObject.NULL)
        .put("rejectReason", JSONObject.NULL)
        .put("classificationChangeReason", JSONObject.NULL)

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

    private class RecordingHttpClient private constructor(
        private val content: String,
        private val statusCode: Int = 200,
    ) : OpenRouterHttpClient {
        constructor(content: JSONObject, statusCode: Int = 200) : this(content.toString(), statusCode)

        val requests = mutableListOf<OpenRouterHttpRequest>()

        override suspend fun execute(
            request: OpenRouterHttpRequest,
            onLine: suspend (String) -> Boolean,
        ): OpenRouterHttpResponse {
            requests += request
            val envelope = JSONObject()
                .put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", content))))
            onLine(envelope.toString())
            return OpenRouterHttpResponse(statusCode, emptyMap())
        }

        companion object {
            fun raw(content: String, statusCode: Int = 200) = RecordingHttpClient(content, statusCode)
        }
    }
}
