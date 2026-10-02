package com.shai.riven.data.provider.openrouter

import com.shai.riven.data.context.RivenContextBudgetBehavior
import com.shai.riven.data.context.RivenContextContentAuthority
import com.shai.riven.data.context.RivenContextLayer
import com.shai.riven.data.context.RivenContextProvenanceClass
import com.shai.riven.data.context.RivenContextSourceCriticality
import com.shai.riven.data.conversation.engine.ProviderContextFragment
import com.shai.riven.data.conversation.engine.ProviderConversationRequest
import com.shai.riven.data.conversation.engine.ProviderFailureCode
import com.shai.riven.data.conversation.engine.ProviderStreamEvent
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.persistence.model.MessageRole
import kotlinx.coroutines.runBlocking
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
class OpenRouterConversationAdapterTest {
    @Test
    fun streamsGroundedRequestAndKeepsSecretOutOfBody() = runBlocking {
        val http = RecordingHttpClient(
            status = 200,
            lines = listOf(
                ": OPENROUTER PROCESSING",
                "data: {\"id\":\"req-1\",\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}",
                "",
                "data: {\"id\":\"req-1\",\"choices\":[{\"delta\":{\"content\":\"Shai\"}}]}",
                "",
                "data: [DONE]",
                "",
            ),
        )
        val events = mutableListOf<ProviderStreamEvent>()

        OpenRouterConversationAdapter(http).stream(request()) { events += it }

        val captured = checkNotNull(http.request)
        assertEquals("POST", captured.method)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", captured.url)
        assertEquals("Bearer secret-value", captured.headers["Authorization"])
        assertFalse(checkNotNull(captured.body).contains("secret-value"))
        val json = JSONObject(captured.body)
        assertEquals("anthropic/example", json.getString("model"))
        assertTrue(json.getBoolean("stream"))
        val messages = json.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("locked canon", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertTrue(messages.getJSONObject(1).getString("content").startsWith("Grounded context data"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
        assertEquals("What do you remember?", messages.getJSONObject(2).getString("content"))
        assertEquals(
            listOf(
                ProviderStreamEvent.Delta("Hello "),
                ProviderStreamEvent.Delta("Shai"),
                ProviderStreamEvent.Completed("req-1"),
            ),
            events,
        )
    }

    @Test
    fun missingCredentialFailsClosedWithoutHttp() = runBlocking {
        val http = RecordingHttpClient(200, emptyList())
        val events = mutableListOf<ProviderStreamEvent>()

        OpenRouterConversationAdapter(http).stream(request(credential = null)) { events += it }

        assertEquals(null, http.request)
        assertEquals(
            listOf(ProviderStreamEvent.Failure(ProviderFailureCode.AUTHENTICATION)),
            events,
        )
    }

    @Test
    fun mapsHttpAndMidstreamFailures() = runBlocking {
        val unauthorized = RecordingHttpClient(401, listOf("{\"error\":\"bad key\"}"))
        val unauthorizedEvents = mutableListOf<ProviderStreamEvent>()
        OpenRouterConversationAdapter(unauthorized).stream(request()) { unauthorizedEvents += it }
        assertEquals(
            listOf(ProviderStreamEvent.Failure(ProviderFailureCode.AUTHENTICATION)),
            unauthorizedEvents,
        )

        val midstream = RecordingHttpClient(
            200,
            listOf(
                "data: {\"id\":\"req-2\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}",
                "",
                "data: {\"id\":\"req-2\",\"error\":{\"code\":429}}",
                "",
                "data: [DONE]",
                "",
            ),
        )
        val events = mutableListOf<ProviderStreamEvent>()
        OpenRouterConversationAdapter(midstream).stream(request()) { events += it }
        assertEquals(
            listOf(
                ProviderStreamEvent.Delta("partial"),
                ProviderStreamEvent.Failure(ProviderFailureCode.RATE_LIMITED, "req-2"),
            ),
            events,
        )
    }

    @Test
    fun sseDecoderIgnoresCommentsAndJoinsDataLines() {
        val decoder = OpenRouterSseDecoder()
        assertEquals(null, decoder.accept(": keepalive"))
        assertEquals(null, decoder.accept("data: {\"value\":"))
        assertEquals(null, decoder.accept("data: 1}"))
        assertEquals(
            OpenRouterSseEvent.Payload("{\"value\":\n1}"),
            decoder.accept(""),
        )
        assertEquals(null, decoder.accept("data: [DONE]"))
        assertEquals(OpenRouterSseEvent.Done, decoder.accept(""))
        assertEquals(null, decoder.finish())
    }

    private fun request(credential: ProviderSecret? = ProviderSecret.fromPlaintext("secret-value")) =
        ProviderConversationRequest(
            runId = "run-1",
            idempotencyKey = "idem-1",
            endpointBaseUrl = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
            modelId = "anthropic/example",
            credential = credential,
            context = listOf(
                fragment(
                    sourceId = "canon",
                    content = "locked canon",
                    authority = RivenContextContentAuthority.INSTRUCTIONS,
                ),
                fragment(
                    sourceId = "memory",
                    content = "Shai likes rain",
                    authority = RivenContextContentAuthority.UNTRUSTED_DATA,
                ),
                fragment(
                    sourceId = "active",
                    content = "What do you remember?",
                    authority = RivenContextContentAuthority.UNTRUSTED_DATA,
                    role = MessageRole.USER,
                ),
            ),
        )

    private fun fragment(
        sourceId: String,
        content: String,
        authority: RivenContextContentAuthority,
        role: MessageRole? = null,
    ) = ProviderContextFragment(
        sourceId = sourceId,
        fragmentId = "$sourceId-fragment",
        content = content,
        layer = RivenContextLayer.ACTIVE_CANONICAL_CONVERSATION_AND_CURRENT_INTERACTION,
        provenanceClass = RivenContextProvenanceClass.ACTIVE_CONVERSATION,
        criticality = RivenContextSourceCriticality.REQUIRED,
        orderWithinLayer = 0,
        orderWithinSource = 0,
        budgetBehavior = RivenContextBudgetBehavior.REQUIRED,
        revision = null,
        observedAt = null,
        validUntil = null,
        contentAuthority = authority,
        conversationRole = role,
    )

    private class RecordingHttpClient(
        private val status: Int,
        private val lines: List<String>,
    ) : OpenRouterHttpClient {
        var request: OpenRouterHttpRequest? = null

        override suspend fun execute(
            request: OpenRouterHttpRequest,
            onLine: suspend (String) -> Unit,
        ): OpenRouterHttpResponse {
            this.request = request
            lines.forEach { onLine(it) }
            return OpenRouterHttpResponse(status, emptyMap())
        }
    }
}
