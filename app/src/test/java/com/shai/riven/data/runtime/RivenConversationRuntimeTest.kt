package com.shai.riven.data.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.conversation.engine.ConversationEngineErrorCode
import com.shai.riven.data.credential.ClearProviderCredentialsResult
import com.shai.riven.data.credential.DeleteProviderCredentialResult
import com.shai.riven.data.credential.HasProviderCredentialResult
import com.shai.riven.data.credential.ProviderCredentialStore
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.credential.PutProviderCredentialResult
import com.shai.riven.data.credential.ReadProviderCredentialResult
import com.shai.riven.data.personality.LockedRivenPersonalityContextSource
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.provider.openrouter.OpenRouterHttpClient
import com.shai.riven.data.provider.openrouter.OpenRouterHttpRequest
import com.shai.riven.data.provider.openrouter.OpenRouterHttpResponse
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RivenConversationRuntimeTest {
    private lateinit var context: Context
    private lateinit var database: RivenDatabase
    private lateinit var credentials: InMemoryCredentialStore
    private lateinit var selection: InMemorySelectedProfileStore
    private lateinit var scheduler: RecordingScheduler
    private val clock = AtomicLong(1_000)
    private val runtimes = mutableListOf<RivenConversationRuntime>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        credentials = InMemoryCredentialStore()
        selection = InMemorySelectedProfileStore()
        scheduler = RecordingScheduler()
    }

    @After
    fun tearDown() {
        runtimes.forEach(RivenConversationRuntime::close)
        database.close()
    }

    @Test
    fun draftTranscriptProfileAndSecureCredentialSurviveRuntimeRelaunch() = runBlocking {
        val http = QueueHttpClient(success("Hello Shai."))
        val first = runtime(http)
        assertTrue(first.initialize() is RivenRuntimeResult.Success)
        val profile = first.saveProfile(null, "Primary", "anthropic/example", "private-key")
        assertTrue(profile is RivenProfileSaveResult.Success)
        assertTrue(first.saveDraft("Draft survives relaunch") is RivenRuntimeResult.Success)
        first.close()
        runtimes.remove(first)

        val second = runtime(http)
        val reopened = second.initialize() as RivenRuntimeResult.Success
        assertEquals("Draft survives relaunch", reopened.snapshot.draft)
        assertTrue(reopened.snapshot.selectedProfileHasCredential)
        val deltas = mutableListOf<String>()
        val sent = second.send("Hello Riven") { deltas += it }
        assertTrue("Expected success, got $sent", sent is RivenRuntimeResult.Success)
        assertEquals(listOf("Hello Shai."), deltas)
        val sentSnapshot = (sent as RivenRuntimeResult.Success).snapshot
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), sentSnapshot.messages.map { it.role })
        assertEquals(listOf("Hello Riven", "Hello Shai."), sentSnapshot.messages.map { it.content })

        val request = http.requests.single()
        assertEquals("Bearer private-key", request.headers["Authorization"])
        assertFalse(checkNotNull(request.body).contains("private-key"))
        assertTrue(request.body.contains(LockedRivenPersonalityContextSource.DOCUMENT_ID))
        assertTrue(request.body.contains("LOCKED PERSONALITY CANON"))
        assertEquals(0, scheduler.totalCalls)
        second.close()
        runtimes.remove(second)

        val third = runtime(QueueHttpClient())
        val finalSnapshot = (third.initialize() as RivenRuntimeResult.Success).snapshot
        assertEquals(listOf("Hello Riven", "Hello Shai."), finalSnapshot.messages.map { it.content })
        assertEquals("", finalSnapshot.draft)
    }

    @Test
    fun fastSendWithoutAProfilePersistsDraftBeforeValidationAndSurvivesRelaunch() = runBlocking {
        val first = runtime(QueueHttpClient())
        assertTrue(first.initialize() is RivenRuntimeResult.Success)

        val rejected = first.send("Keep this unconfigured message")

        assertTrue(rejected is RivenRuntimeResult.Failure)
        assertEquals(
            "Keep this unconfigured message",
            checkNotNull((rejected as RivenRuntimeResult.Failure).snapshot).draft,
        )
        first.close()
        runtimes.remove(first)

        val second = runtime(QueueHttpClient())
        val reopened = second.initialize() as RivenRuntimeResult.Success
        assertEquals("Keep this unconfigured message", reopened.snapshot.draft)
        assertTrue(reopened.snapshot.messages.isEmpty())
    }

    @Test
    fun failedPartialReplyIsNotCanonicalAndRetryCompletesIt() = runBlocking {
        val http = QueueHttpClient(
            listOf(
                listOf(
                    "data: {\"id\":\"failed\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}",
                    "",
                    "data: {\"id\":\"failed\",\"error\":{\"code\":429}}",
                    "",
                ),
                success("Recovered reply."),
            ),
        )
        val runtime = runtime(http)
        runtime.initialize()
        assertTrue(runtime.saveProfile(null, "Primary", "anthropic/example", "key") is RivenProfileSaveResult.Success)

        val failed = runtime.send("Please retry")
        assertTrue(failed is RivenRuntimeResult.Failure)
        assertEquals(ConversationEngineErrorCode.PROVIDER_FAILURE, (failed as RivenRuntimeResult.Failure).engineCode)
        assertEquals(listOf("Please retry"), checkNotNull(failed.snapshot).messages.map { it.content })
        assertFalse(failed.snapshot.messages.any { it.content.contains("partial") })
        assertTrue(runtime.saveDraft("Keep this retry draft") is RivenRuntimeResult.Success)

        val retried = runtime.retry()
        assertTrue("Expected retry success, got $retried", retried is RivenRuntimeResult.Success)
        assertEquals(
            listOf("Please retry", "Recovered reply."),
            (retried as RivenRuntimeResult.Success).snapshot.messages.map { it.content },
        )
        assertEquals("Keep this retry draft", retried.snapshot.draft)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun regenerateBranchesFromUserAndContinueIsAnExplicitPersistentTurn() = runBlocking {
        val http = QueueHttpClient(
            success("First reply."),
            success("Regenerated reply."),
            success("Continued reply."),
        )
        val runtime = runtime(http)
        runtime.initialize()
        assertTrue(runtime.saveProfile(null, "Primary", "anthropic/example", "key") is RivenProfileSaveResult.Success)

        assertTrue(runtime.send("Start here") is RivenRuntimeResult.Success)
        assertTrue(runtime.saveDraft("Keep this unfinished draft") is RivenRuntimeResult.Success)
        val regenerated = runtime.regenerate()
        assertTrue(regenerated is RivenRuntimeResult.Success)
        assertEquals(
            listOf("Start here", "Regenerated reply."),
            (regenerated as RivenRuntimeResult.Success).snapshot.messages.map { it.content },
        )
        assertTrue(
            database.conversationTimelineDao().allMessages(RivenConversationRuntime.CONVERSATION_ID)
                .any { it.content == "First reply." },
        )
        assertEquals(
            "Keep this unfinished draft",
            (regenerated as RivenRuntimeResult.Success).snapshot.draft,
        )

        val continued = runtime.continueConversation()
        assertTrue(continued is RivenRuntimeResult.Success)
        assertEquals(
            listOf("Start here", "Regenerated reply.", "Continue.", "Continued reply."),
            (continued as RivenRuntimeResult.Success).snapshot.messages.map { it.content },
        )
        assertEquals(
            "Keep this unfinished draft",
            (continued as RivenRuntimeResult.Success).snapshot.draft,
        )
        assertEquals(3, http.requests.size)
    }

    @Test
    fun invalidProfileNeverReplacesPreviouslyStoredCredential() = runBlocking {
        val runtime = runtime(QueueHttpClient())
        runtime.initialize()
        val saved = runtime.saveProfile(null, "Primary", "anthropic/example", "fake-old-key")
        assertTrue(saved is RivenProfileSaveResult.Success)

        val rejected = runtime.saveProfile(
            profileId = (saved as RivenProfileSaveResult.Success).profile.profileId,
            displayName = "x".repeat(201),
            modelId = "anthropic/example",
            apiKey = "fake-new-key",
        )

        assertTrue(rejected is RivenProfileSaveResult.Failure)
        val stored = credentials.readCredential(RivenConversationRuntime.OPENROUTER_CREDENTIAL_SLOT)
        assertEquals("fake-old-key", (stored as ReadProviderCredentialResult.Success).secret.reveal())
    }

    @Test
    fun cancelBeforeProviderDispatchCancelsQueuedPreparationAndNeverStartsHttp() = runBlocking {
        val http = QueueHttpClient(success("must not run"))
        val runtime = runtime(http)
        runtime.initialize()
        val saved = runtime.saveProfile(null, "Primary", "anthropic/example", "fake-key")
            as RivenProfileSaveResult.Success
        credentials.blockNextPut()
        val profileSave = async(Dispatchers.Default) {
            runtime.saveProfile(saved.profile.profileId, "Primary", "anthropic/example", "fake-replacement")
        }
        assertTrue(credentials.awaitBlockedPut())
        val sending = async(Dispatchers.Default) { runtime.send("Cancel during preparation") }

        withTimeout(2_000) {
            while (true) {
                val result = runtime.cancel()
                if (result is RivenRuntimeResult.Success) return@withTimeout result
                yield()
            }
            error("unreachable")
        }
        credentials.releaseBlockedPut()
        profileSave.await()
        try {
            sending.await()
        } catch (_: CancellationException) {
            // Expected: cancellation covers the operation before a run id or provider call exists.
        }
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun exactCanonLongFirstTurnMultipleTurnsAndEnabledInstructionsFitBoundedContext() = runBlocking {
        val http = QueueHttpClient(success("First reply."), success("Second reply."))
        val runtime = runtime(http)
        runtime.initialize()
        assertTrue(runtime.saveProfile(null, "Primary", "anthropic/example", "fake-key") is RivenProfileSaveResult.Success)
        assertTrue(runtime.saveInstructions("Follow this grounded preference. ".repeat(50), true, 0) is RivenRuntimeResult.Success)

        assertTrue(runtime.send("a".repeat(8_000)) is RivenRuntimeResult.Success)
        assertTrue(runtime.send("b".repeat(3_000)) is RivenRuntimeResult.Success)

        assertEquals(2, http.requests.size)
        val messages = JSONObject(checkNotNull(http.requests.last().body)).getJSONArray("messages")
        val canon = context.assets.open(LockedRivenPersonalityContextSource.ASSET_NAME)
            .bufferedReader().use { it.readText() }
            .replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        assertTrue((0 until messages.length()).any { messages.getJSONObject(it).optString("content") == canon })
        assertTrue(
            (0 until messages.length()).any {
                messages.getJSONObject(it).optString("content") == "role=USER\n${"b".repeat(3_000)}"
            },
        )
    }

    @Test
    fun singleTurnBeyondExplicitUnknownModelBudgetFailsBeforeHttp() = runBlocking {
        val http = QueueHttpClient(success("must not run"))
        val runtime = runtime(http)
        runtime.initialize()
        assertTrue(runtime.saveProfile(null, "Primary", "anthropic/example", "fake-key") is RivenProfileSaveResult.Success)

        val result = runtime.send("x".repeat(20_000))

        assertTrue(result is RivenRuntimeResult.Failure)
        assertEquals(ConversationEngineErrorCode.CONTEXT_LIMIT_EXCEEDED, (result as RivenRuntimeResult.Failure).engineCode)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun currentTurnBeyondTranscriptSourceLimitReportsExplicitContextLimitBeforeHttp() = runBlocking {
        val http = QueueHttpClient(success("must not run"))
        val runtime = runtime(http)
        runtime.initialize()
        assertTrue(runtime.saveProfile(null, "Primary", "anthropic/example", "fake-key") is RivenProfileSaveResult.Success)

        val result = runtime.send("x".repeat(40_000))

        assertTrue(result is RivenRuntimeResult.Failure)
        assertEquals(
            ConversationEngineErrorCode.CONTEXT_LIMIT_EXCEEDED,
            (result as RivenRuntimeResult.Failure).engineCode,
        )
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun cancelSettlesTheRunAndNeverPersistsPartialAssistantContent() = runBlocking {
        val http = HoldingHttpClient()
        val runtime = runtime(http)
        runtime.initialize()
        assertTrue(runtime.saveProfile(null, "Primary", "anthropic/example", "key") is RivenProfileSaveResult.Success)

        val sending = async { runtime.send("Cancel this reply") }
        withTimeout(5_000) { http.entered.await() }
        val cancelled = runtime.cancel()
        assertTrue(cancelled is RivenRuntimeResult.Success)
        try {
            sending.await()
        } catch (_: CancellationException) {
            // Cancelling the provider call also cancels the caller's in-flight send coroutine.
        }

        val snapshot = (runtime.snapshot() as RivenRuntimeResult.Success).snapshot
        assertEquals(listOf("Cancel this reply"), snapshot.messages.map { it.content })
        assertEquals(
            com.shai.riven.data.persistence.model.ConversationRunState.CANCELLED,
            database.conversationRunDao().runsForConversation(RivenConversationRuntime.CONVERSATION_ID).last().state,
        )
    }

    private fun runtime(http: OpenRouterHttpClient) = RivenConversationRuntime(
        database = database,
        credentialStore = credentials,
        selectedProfileStore = selection,
        backgroundScheduler = scheduler,
        personalitySource = LockedRivenPersonalityContextSource(context),
        httpClient = http,
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        clock = { clock.incrementAndGet() },
    ).also(runtimes::add)

    private fun success(content: String) = listOf(
        "data: {\"id\":\"request-ok\",\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}",
        "",
        "data: [DONE]",
        "",
    )

    private class QueueHttpClient(
        responses: List<List<String>> = emptyList(),
    ) : OpenRouterHttpClient {
        constructor(vararg responses: List<String>) : this(responses.toList())

        private val responses = ArrayDeque(responses)
        val requests = mutableListOf<OpenRouterHttpRequest>()

        override suspend fun execute(
            request: OpenRouterHttpRequest,
            onLine: suspend (String) -> Boolean,
        ): OpenRouterHttpResponse {
            requests += request
            val lines = responses.removeFirstOrNull() ?: error("Unexpected HTTP request")
            for (line in lines) if (!onLine(line)) break
            return OpenRouterHttpResponse(200, emptyMap())
        }
    }

    private class HoldingHttpClient : OpenRouterHttpClient {
        val entered = CompletableDeferred<Unit>()

        override suspend fun execute(
            request: OpenRouterHttpRequest,
            onLine: suspend (String) -> Boolean,
        ): OpenRouterHttpResponse {
            entered.complete(Unit)
            awaitCancellation()
        }
    }

    private class InMemorySelectedProfileStore : SelectedProviderProfileStore {
        private var selected: String? = null
        override fun selectedProfileId(): String? = selected
        override fun select(profileId: String?) {
            selected = profileId
        }
    }

    private class InMemoryCredentialStore : ProviderCredentialStore {
        private val values = mutableMapOf<String, ProviderSecret>()
        @Volatile private var blockNextPut = false
        private var putEntered = CountDownLatch(1)
        private var releasePut = CountDownLatch(1)

        fun blockNextPut() {
            putEntered = CountDownLatch(1)
            releasePut = CountDownLatch(1)
            blockNextPut = true
        }

        fun awaitBlockedPut(): Boolean = putEntered.await(2, TimeUnit.SECONDS)

        fun releaseBlockedPut() = releasePut.countDown()

        override fun putCredential(credentialSlotId: String, secret: ProviderSecret): PutProviderCredentialResult {
            if (blockNextPut) {
                blockNextPut = false
                putEntered.countDown()
                check(releasePut.await(2, TimeUnit.SECONDS)) { "Blocked credential write was not released" }
            }
            values[credentialSlotId] = secret
            return PutProviderCredentialResult.Success
        }

        override fun readCredential(credentialSlotId: String): ReadProviderCredentialResult =
            values[credentialSlotId]?.let(ReadProviderCredentialResult::Success)
                ?: ReadProviderCredentialResult.Failure(
                    com.shai.riven.data.credential.ProviderCredentialError.MissingCredential(credentialSlotId),
                )

        override fun hasCredential(credentialSlotId: String) =
            HasProviderCredentialResult.Success(values.containsKey(credentialSlotId))

        override fun deleteCredential(credentialSlotId: String): DeleteProviderCredentialResult {
            values.remove(credentialSlotId)
            return DeleteProviderCredentialResult.Success
        }

        override fun clearAllCredentials(): ClearProviderCredentialsResult {
            val count = values.size
            values.clear()
            return ClearProviderCredentialsResult.Success(count)
        }
    }

    private class RecordingScheduler : RivenBackgroundWorkScheduler {
        var totalCalls = 0

        override fun enqueueAttachmentCleanup(attachmentId: String) = enqueued()
        override fun enqueueAttachmentMaintenanceSweep() = enqueued()
        override fun enqueueRepairJob(repairJobId: String) = enqueued()
        override fun enqueueRepairSweep() = enqueued()
        override fun ensurePeriodicMaintenance() = enqueued()

        private fun enqueued(): RivenBackgroundScheduleResult {
            totalCalls += 1
            return RivenBackgroundScheduleResult.Enqueued(listOf("recorded"))
        }
    }
}
