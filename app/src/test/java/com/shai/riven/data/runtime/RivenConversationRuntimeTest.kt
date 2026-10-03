package com.shai.riven.data.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.arcade.ARCADE_TRANSIENT_MEMORY_EXCLUSION
import com.shai.riven.data.arcade.ArcadeGameObservation
import com.shai.riven.data.arcade.ArcadeObservationFact
import com.shai.riven.data.arcade.ArcadeObservationView
import com.shai.riven.data.arcade.ArcadeObservationWriteResult
import com.shai.riven.data.attachment.AttachmentByteSource
import com.shai.riven.data.attachment.AttachmentBlobStore
import com.shai.riven.data.attachment.AttachmentBlobWriteResult
import com.shai.riven.data.attachment.AttachmentCreateResult
import com.shai.riven.data.attachment.AttachmentService
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.attachment.ImportedAttachmentInput
import com.shai.riven.data.attachment.DecodedImageMetadata
import com.shai.riven.data.attachment.ImageMetadataDecoder
import com.shai.riven.data.attachment.FileAttachmentThumbnailStore
import com.shai.riven.data.attachment.ImageThumbnailGenerator
import com.shai.riven.data.attachment.SelectedImageInput
import java.io.ByteArrayInputStream
import com.shai.riven.data.conversation.engine.ConversationEngineErrorCode
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.draft.ConversationDraftService
import com.shai.riven.data.draft.SaveConversationDraftInput
import com.shai.riven.data.draft.SaveConversationDraftResult
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
import com.shai.riven.data.persistence.model.AutomaticMemoryJobState
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.provider.openrouter.OpenRouterImageInputCapability
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalogResult
import com.shai.riven.data.provider.openrouter.OpenRouterHttpClient
import com.shai.riven.data.provider.openrouter.OpenRouterHttpRequest
import com.shai.riven.data.provider.openrouter.OpenRouterHttpResponse
import java.util.concurrent.atomic.AtomicLong
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64
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
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private lateinit var attachmentRoot: java.io.File
    private lateinit var attachmentBlobStore: CountingAttachmentBlobStore
    private lateinit var imageCapabilities: InMemoryImageInputCapabilityStore
    private lateinit var thumbnailStore: FileAttachmentThumbnailStore
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
        attachmentRoot = Files.createTempDirectory("riven-runtime-images").toFile()
        attachmentBlobStore = CountingAttachmentBlobStore(FileAttachmentBlobStore(attachmentRoot))
        imageCapabilities = InMemoryImageInputCapabilityStore()
        thumbnailStore = FileAttachmentThumbnailStore(java.io.File(attachmentRoot, "thumbs"))
    }

    @After
    fun tearDown() {
        runtimes.forEach(RivenConversationRuntime::close)
        database.close()
        attachmentRoot.deleteRecursively()
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
        assertEquals(3, scheduler.totalCalls)
        assertEquals(1, scheduler.automaticMemorySweepCalls)
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
    fun failedArcadeOriginSurvivesClearAndRestartThenExcludesChatRetryFromMemory() = runBlocking {
        val first = runtime(QueueHttpClient(providerFailure()))
        assertTrue(first.initialize() is RivenRuntimeResult.Success)
        assertTrue(first.saveProfile(null, "Primary", "anthropic/example", "key") is RivenProfileSaveResult.Success)
        assertTrue(
            first.publishArcadeObservation(
                ArcadeGameObservation(
                    gameId = "stacker",
                    gameTitle = "Celestial Spire",
                    sessionId = "arcade-origin",
                    sequence = 1,
                    view = ArcadeObservationView.SOLO_PUBLIC,
                    phase = "playing",
                    facts = listOf(ArcadeObservationFact("Lines", "0")),
                    observedAt = clock.incrementAndGet(),
                ),
            ) is ArcadeObservationWriteResult.Published,
        )

        val failed = first.sendFromArcade("Keep this game turn transient")
        assertTrue(failed is RivenRuntimeResult.Failure)
        assertTrue(first.clearArcadeObservation() is ArcadeObservationWriteResult.Cleared)
        first.close()
        runtimes.remove(first)

        val second = runtime(QueueHttpClient(success("Recovered outside Arcade.")))
        assertTrue(second.initialize() is RivenRuntimeResult.Success)
        val retried = second.retry()

        assertTrue("Expected retry success, got $retried", retried is RivenRuntimeResult.Success)
        val messages = (retried as RivenRuntimeResult.Success).snapshot.messages
        assertEquals(
            listOf("Keep this game turn transient", "Recovered outside Arcade."),
            messages.map { it.content },
        )
        messages.forEach { message ->
            val job = checkNotNull(database.automaticMemoryDao().jobForMessage(message.id))
            assertEquals(AutomaticMemoryJobState.EXCLUDED, job.state)
            assertEquals(ARCADE_TRANSIENT_MEMORY_EXCLUSION, job.lastErrorCode)
        }
        assertEquals(0, database.memoryDao().candidateMemoryCount())
        assertEquals(0, database.memoryDao().memoryCount())
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
                messages.getJSONObject(it).optString("content") == "b".repeat(3_000)
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

        val next = runtime.send("Next message after cancellation")
        assertTrue(next is RivenRuntimeResult.Success)
        assertEquals(
            listOf("Cancel this reply", "Next message after cancellation", "Recovered reply."),
            (next as RivenRuntimeResult.Success).snapshot.messages.map { it.content },
        )
    }

    @Test
    fun imageAddAndRemovePersistLatestCaptionWithoutWaitingForDebounce() = runBlocking {
        val runtime = runtime(QueueHttpClient())
        runtime.initialize()
        runtime.saveDraft("older persisted caption")

        val added = runtime.addDraftImageSelection(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = pngBytes().size.toLong(),
                openStream = { ByteArrayInputStream(pngBytes()) },
                occurredAt = clock.incrementAndGet(),
            ),
            content = "fresh caption before debounce",
        ) as RivenRuntimeResult.Success

        assertEquals("fresh caption before debounce", added.snapshot.draft)
        val attachmentId = added.snapshot.draftImages.single().attachmentId
        val removed = runtime.removeDraftImage(
            attachmentId,
            content = "newest caption before remove debounce",
        ) as RivenRuntimeResult.Success
        assertEquals("newest caption before remove debounce", removed.snapshot.draft)
        assertTrue(removed.snapshot.draftImages.isEmpty())
    }

    @Test
    fun imageMutationFailuresStillPersistTheLatestSameFrameCaption() = runBlocking {
        val runtime = runtime(QueueHttpClient())
        runtime.initialize()
        runtime.saveDraft("older persisted caption")

        val failedImport = runtime.addDraftImageSelection(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = 3,
                openStream = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                occurredAt = clock.incrementAndGet(),
            ),
            content = "fresh caption despite failed import",
        ) as RivenRuntimeResult.Failure
        assertEquals("fresh caption despite failed import", checkNotNull(failedImport.snapshot).draft)

        val added = runtime.addDraftImageSelection(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = pngBytes().size.toLong(),
                openStream = { ByteArrayInputStream(pngBytes()) },
                occurredAt = clock.incrementAndGet(),
            ),
            content = "caption with image",
        ) as RivenRuntimeResult.Success
        val attachmentId = added.snapshot.draftImages.single().attachmentId

        val duplicate = runtime.addDraftImageSelection(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = pngBytes().size.toLong(),
                openStream = { ByteArrayInputStream(pngBytes()) },
                occurredAt = clock.incrementAndGet(),
            ),
            content = "fresh caption despite duplicate",
        ) as RivenRuntimeResult.Failure
        assertEquals("fresh caption despite duplicate", checkNotNull(duplicate.snapshot).draft)

        val failedRemove = runtime.removeDraftImage(
            attachmentId = "not-the-current-image",
            content = "fresh caption despite failed remove",
        ) as RivenRuntimeResult.Failure
        assertEquals("fresh caption despite failed remove", checkNotNull(failedRemove.snapshot).draft)
        assertEquals(attachmentId, failedRemove.snapshot.draftImages.single().attachmentId)
    }

    @Test
    fun imageDraftLinkSaveFailureRetainsTheCaptionFlushedBeforeImport() = runBlocking {
        val runtime = runtime(
            QueueHttpClient(),
            afterImageImportedBeforeDraftLink = { attachmentId ->
                AttachmentService(database, attachmentBlobStore).discardUnreferencedAvailable(
                    attachmentId,
                    clock.incrementAndGet(),
                )
            },
        )
        runtime.initialize()
        runtime.saveDraft("older persisted caption")

        val failedSave = runtime.addDraftImageSelection(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = pngBytes().size.toLong(),
                openStream = { ByteArrayInputStream(pngBytes()) },
                occurredAt = clock.incrementAndGet(),
            ),
            content = "fresh caption despite link failure",
        ) as RivenRuntimeResult.Failure

        assertEquals("fresh caption despite link failure", checkNotNull(failedSave.snapshot).draft)
        assertTrue(failedSave.snapshot.draftImages.isEmpty())
    }

    @Test
    fun imagePreflightFlushFailuresNeverReturnAnOlderDraftSnapshot() = runBlocking {
        val first = runtime(QueueHttpClient())
        first.initialize()
        first.saveDraft("older persisted caption")
        val oversizedCaption = "x".repeat(com.shai.riven.data.draft.MAX_DRAFT_CONTENT_CHARS + 1)

        val oversized = first.addDraftImageSelection(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = pngBytes().size.toLong(),
                openStream = { ByteArrayInputStream(pngBytes()) },
                occurredAt = clock.incrementAndGet(),
            ),
            content = oversizedCaption,
        ) as RivenRuntimeResult.Failure

        assertNull(oversized.snapshot)
        assertEquals(
            "older persisted caption",
            (first.snapshot() as RivenRuntimeResult.Success).snapshot.draft,
        )

        val attached = first.addDraftImageSelection(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = pngBytes().size.toLong(),
                openStream = { ByteArrayInputStream(pngBytes()) },
                occurredAt = clock.incrementAndGet(),
            ),
            content = "caption with image",
        ) as RivenRuntimeResult.Success
        val attachmentId = attached.snapshot.draftImages.single().attachmentId
        first.close()
        runtimes.remove(first)

        val failing = runtime(
            QueueHttpClient(),
            beforeDraftContentFlush = { error("injected storage failure") },
        )
        failing.initialize()
        val remove = failing.removeDraftImage(attachmentId, "live caption not yet persisted")
            as RivenRuntimeResult.Failure

        assertNull(remove.snapshot)
        val persisted = (failing.snapshot() as RivenRuntimeResult.Success).snapshot
        assertEquals("caption with image", persisted.draft)
        assertEquals(attachmentId, persisted.draftImages.single().attachmentId)
    }

    @Test
    fun imageDraftRelaunchFollowupRegenerateAndContinueUseOnlyBoundedCanonicalImage() = runBlocking {
        val http = QueueHttpClient(
            success("I can see it."),
            success("I can still see it."),
            success("Continuing without resending it."),
            success("Now the old image is out of scope."),
        )
        val first = runtime(http)
        first.initialize()
        val profile = first.saveProfile(null, "Primary", "manual/unknown-vision", "fake-key")
            as RivenProfileSaveResult.Success
        attachImageDraft("A small caption")
        val beforeRelaunch = (first.snapshot() as RivenRuntimeResult.Success).snapshot
        assertEquals(1, beforeRelaunch.draftImages.size)
        assertEquals("A small caption", beforeRelaunch.draft)
        first.close()
        runtimes.remove(first)

        val reopened = runtime(http)
        val restored = (reopened.initialize() as RivenRuntimeResult.Success).snapshot
        assertEquals(1, restored.draftImages.size)
        val blocked = reopened.send("A small caption")
        assertTrue(blocked is RivenRuntimeResult.Failure)
        assertTrue((blocked as RivenRuntimeResult.Failure).requiresImageCapabilityConfirmation)
        assertEquals(1, checkNotNull(blocked.snapshot).draftImages.size)
        assertTrue(http.requests.isEmpty())

        val sent = reopened.sendWithUnknownImageCapabilityConfirmation("A small caption")
        assertTrue("Expected image send success, got $sent", sent is RivenRuntimeResult.Success)
        assertEquals(
            OpenRouterImageInputCapability.USER_CONFIRMED_UNKNOWN,
            imageCapabilities.read(profile.profile.profileId, profile.profile.modelId),
        )
        val sentSnapshot = (sent as RivenRuntimeResult.Success).snapshot
        assertTrue(sentSnapshot.draftImages.isEmpty())
        assertEquals(1, sentSnapshot.messages.single { it.role == MessageRole.USER }.images.size)
        assertEquals(1, imagePartCount(checkNotNull(http.requests[0].body)))
        assertEquals(listOf("A small caption"), imageTextParts(checkNotNull(http.requests[0].body)))

        assertTrue(reopened.regenerate() is RivenRuntimeResult.Success)
        assertEquals(1, imagePartCount(checkNotNull(http.requests[1].body)))
        assertTrue(reopened.continueConversation() is RivenRuntimeResult.Success)
        assertEquals(1, imagePartCount(checkNotNull(http.requests[2].body)))
        assertTrue(reopened.send("Now unrelated") is RivenRuntimeResult.Success)
        assertEquals(0, imagePartCount(checkNotNull(http.requests[3].body)))
    }

    @Test
    fun failedTextFollowupRetryReusesTheSameBoundedCanonicalImage() = runBlocking {
        val http = QueueHttpClient(
            success("Initial image answer."),
            providerFailure(),
            success("Recovered follow-up."),
        )
        val runtime = runtime(http)
        runtime.initialize()
        val profile = runtime.saveProfile(null, "Vision", "vision/model", "fake-key")
            as RivenProfileSaveResult.Success
        imageCapabilities.write(
            profile.profile.profileId,
            profile.profile.modelId,
            OpenRouterImageInputCapability.SUPPORTED,
        )
        attachImageDraft("What is here?")

        assertTrue(runtime.send("What is here?") is RivenRuntimeResult.Success)
        assertTrue(runtime.send("Look more closely") is RivenRuntimeResult.Failure)
        val retried = runtime.retry()

        assertTrue("Expected image follow-up retry success, got $retried", retried is RivenRuntimeResult.Success)
        assertEquals(1, imagePartCount(checkNotNull(http.requests[1].body)))
        assertEquals(1, imagePartCount(checkNotNull(http.requests[2].body)))
    }

    @Test
    fun regeneratedTextFollowupReusesTheSameBoundedCanonicalImage() = runBlocking {
        val http = QueueHttpClient(
            success("Initial image answer."),
            success("First follow-up."),
            success("Regenerated follow-up."),
        )
        val runtime = runtime(http)
        runtime.initialize()
        val profile = runtime.saveProfile(null, "Vision", "vision/model", "fake-key")
            as RivenProfileSaveResult.Success
        imageCapabilities.write(
            profile.profile.profileId,
            profile.profile.modelId,
            OpenRouterImageInputCapability.SUPPORTED,
        )
        attachImageDraft("What is here?")

        assertTrue(runtime.send("What is here?") is RivenRuntimeResult.Success)
        assertTrue(runtime.send("Describe the background") is RivenRuntimeResult.Success)
        val regenerated = runtime.regenerate()

        assertTrue("Expected regenerated follow-up success, got $regenerated", regenerated is RivenRuntimeResult.Success)
        assertEquals(1, imagePartCount(checkNotNull(http.requests[1].body)))
        assertEquals(1, imagePartCount(checkNotNull(http.requests[2].body)))
    }

    @Test
    fun declaredUnsupportedModelBlocksImageAndPreservesDraftWithoutHttp() = runBlocking {
        val http = QueueHttpClient(success("must not run"))
        val runtime = runtime(http)
        runtime.initialize()
        val profile = runtime.saveProfile(null, "Text only", "text/model", "fake-key")
            as RivenProfileSaveResult.Success
        imageCapabilities.write(
            profile.profile.profileId,
            profile.profile.modelId,
            OpenRouterImageInputCapability.UNSUPPORTED,
        )
        attachImageDraft("")

        val result = runtime.send("")

        assertTrue(result is RivenRuntimeResult.Failure)
        assertFalse((result as RivenRuntimeResult.Failure).requiresImageCapabilityConfirmation)
        assertEquals(1, checkNotNull(result.snapshot).draftImages.size)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun catalogRefreshDowngradeOverridesPreviouslySupportedOrConfirmedImageState() = runBlocking {
        val http = QueueHttpClient(
            listOf(
                """{"data":[{"id":"changing/model","name":"Changing","context_length":8192,"architecture":{"input_modalities":["text"]}}]}""",
            ),
        )
        val runtime = runtime(http)
        runtime.initialize()
        val profile = runtime.saveProfile(null, "Changing", "changing/model", "fake-key")
            as RivenProfileSaveResult.Success
        imageCapabilities.write(
            profile.profile.profileId,
            profile.profile.modelId,
            OpenRouterImageInputCapability.USER_CONFIRMED_UNKNOWN,
        )
        attachImageDraft("Do not send")

        assertTrue(runtime.fetchModels() is OpenRouterModelCatalogResult.Success)
        val result = runtime.send("Do not send")

        assertTrue(result is RivenRuntimeResult.Failure)
        assertFalse((result as RivenRuntimeResult.Failure).requiresImageCapabilityConfirmation)
        assertEquals(1, checkNotNull(result.snapshot).draftImages.size)
        assertEquals(1, http.requests.size)
        assertEquals("GET", http.requests.single().method)
    }

    @Test
    fun coldRelaunchUsesPersistedSmallCatalogContextInsteadOfUnknownFallback() = runBlocking {
        val catalogHttp = QueueHttpClient(
            listOf(
                """{"data":[{"id":"tiny/model","name":"Tiny","context_length":4096,"architecture":{"input_modalities":["text"]}}]}""",
            ),
        )
        val first = runtime(catalogHttp)
        first.initialize()
        val profile = first.saveProfile(null, "Tiny", "tiny/model", "fake-key")
            as RivenProfileSaveResult.Success
        assertTrue(first.fetchModels() is OpenRouterModelCatalogResult.Success)
        assertTrue(
            first.saveProfile(
                profile.profile.profileId,
                "Tiny",
                "tiny/model",
                "",
            ) is RivenProfileSaveResult.Success,
        )
        first.close()
        runtimes.remove(first)

        val providerHttp = QueueHttpClient(success("must not run"))
        val reopened = runtime(providerHttp)
        reopened.initialize()
        val result = reopened.send("x".repeat(6_000))

        assertTrue(result is RivenRuntimeResult.Failure)
        assertEquals(
            ConversationEngineErrorCode.CONTEXT_LIMIT_EXCEEDED,
            (result as RivenRuntimeResult.Failure).engineCode,
        )
        assertTrue(providerHttp.requests.isEmpty())
    }

    @Test
    fun oldImageHistoryLoadsPerVisibleImageWithoutFalseUnavailableState() = runBlocking {
        val fullThumbnail = ByteArray(com.shai.riven.data.attachment.MAX_ATTACHMENT_THUMBNAIL_BYTES) { 7 }
        val runtime = runtime(
            QueueHttpClient(),
            thumbnailGenerator = ImageThumbnailGenerator { fullThumbnail },
        )
        runtime.initialize()
        val timeline = ConversationTimelineService(database)
        val attachments = AttachmentService(database, attachmentBlobStore)
        var revision = 0L
        val attachmentIds = mutableListOf<String>()
        repeat(20) { index ->
            val created = attachments.createImportedAttachment(
                ImportedAttachmentInput(
                    kind = AttachmentKind.IMAGE,
                    mimeType = "image/png",
                    occurredAt = clock.incrementAndGet(),
                    bytes = AttachmentByteSource.fromBytes(pngBytes()),
                ),
            ) as AttachmentCreateResult.Success
            attachmentIds += created.attachment.attachmentId
            val appended = timeline.appendMessage(
                AppendTimelineMessageInput(
                    conversationId = RivenConversationRuntime.CONVERSATION_ID,
                    message = NewTimelineMessageInput(
                        messageId = "history-image-$index",
                        role = MessageRole.USER,
                        deliveryState = MessageDeliveryState.PERSISTED,
                        content = "image $index",
                        createdAt = clock.incrementAndGet(),
                        updatedAt = clock.incrementAndGet(),
                        attachmentIds = listOf(created.attachment.attachmentId),
                    ),
                    expectedTimelineRevision = revision,
                    occurredAt = clock.incrementAndGet(),
                ),
            ) as TimelineWriteResult.MessageAppended
            revision = appended.timelineRevision
        }
        attachmentBlobStore.openCount = 0

        val initial = (runtime.snapshot() as RivenRuntimeResult.Success).snapshot
        assertEquals(20, initial.messages.flatMap(RivenChatMessage::images).size)
        assertTrue(
            initial.messages.flatMap(RivenChatMessage::images)
                .all { it.previewState == RivenImagePreviewState.DEFERRED },
        )
        assertEquals(0, attachmentBlobStore.openCount)

        attachmentIds.forEach { attachmentId ->
            assertTrue(runtime.loadImagePreview(attachmentId) is RivenRuntimeResult.Success)
        }
        val loaded = (runtime.snapshot() as RivenRuntimeResult.Success).snapshot
        val images = loaded.messages.flatMap(RivenChatMessage::images)

        assertEquals(20, images.size)
        assertEquals(16, images.count { it.previewState == RivenImagePreviewState.READY })
        assertEquals(4, images.count { it.previewState == RivenImagePreviewState.DEFERRED })
        assertFalse(images.any { it.previewState == RivenImagePreviewState.UNAVAILABLE })
        assertTrue(images.sumOf { it.previewBytes.size } <= MAX_RUNTIME_THUMBNAIL_CACHE_BYTES)
        assertEquals(20, attachmentBlobStore.openCount)

        val reloadedOldest = runtime.loadImagePreview(attachmentIds.first()) as RivenRuntimeResult.Success
        val oldest = reloadedOldest.snapshot.messages.flatMap(RivenChatMessage::images)
            .single { it.attachmentId == attachmentIds.first() }
        assertEquals(RivenImagePreviewState.READY, oldest.previewState)
        assertEquals(21, attachmentBlobStore.openCount)
    }

    @Test
    fun restoredOriginalRegeneratesThumbnailAndMissingOriginalIsExplicitlyUnavailable() = runBlocking {
        val first = runtime(QueueHttpClient())
        first.initialize()
        val attachmentId = attachImageDraft("Restored image")
        first.close()
        runtimes.remove(first)
        thumbnailStore.delete(attachmentId)

        val restored = runtime(QueueHttpClient())
        val beforeLoad = (restored.initialize() as RivenRuntimeResult.Success).snapshot
        assertEquals(RivenImagePreviewState.DEFERRED, beforeLoad.draftImages.single().previewState)
        attachmentBlobStore.openCount = 0
        val regenerated = restored.loadImagePreview(attachmentId) as RivenRuntimeResult.Success

        assertEquals(RivenImagePreviewState.READY, regenerated.snapshot.draftImages.single().previewState)
        assertTrue(regenerated.snapshot.draftImages.single().previewBytes.isNotEmpty())
        assertTrue(thumbnailStore.read(attachmentId)?.isNotEmpty() == true)
        assertEquals(1, attachmentBlobStore.openCount)
        restored.close()
        runtimes.remove(restored)

        thumbnailStore.delete(attachmentId)
        val storageKey = (
            AttachmentService(database, attachmentBlobStore).attachmentMetadata(attachmentId) as
                com.shai.riven.data.attachment.AttachmentMetadataResult.Success
            ).attachment.storageKey
        attachmentBlobStore.delete(storageKey)
        assertFalse(attachmentBlobStore.exists(storageKey))
        val missing = runtime(QueueHttpClient())
        val missingInitial = (missing.initialize() as RivenRuntimeResult.Success).snapshot
        assertEquals(RivenImagePreviewState.DEFERRED, missingInitial.draftImages.single().previewState)
        val unavailable = missing.loadImagePreview(attachmentId) as RivenRuntimeResult.Success

        assertEquals(RivenImagePreviewState.UNAVAILABLE, unavailable.snapshot.draftImages.single().previewState)
        assertTrue(unavailable.snapshot.draftImages.single().previewBytes.isEmpty())
    }

    @Test
    fun validatedRoomControlCommitsBeforeNarrativeAndRestoresWithoutMovingBrowser() = runBlocking {
        val controlledReply = "RIVEN_STATE_CONTROL {\"room\":\"study\",\"sprite\":\"standing_teasing\"}\nNow I'm in the study."
        val http = QueueHttpClient(successJson(controlledReply))
        val first = runtime(http)
        first.initialize()
        assertTrue(first.saveProfile(null, "Primary", "anthropic/example", "private-key") is RivenProfileSaveResult.Success)
        var stateObservedAtNarrative: com.shai.riven.data.persistence.entity.RivenPresenceEntity? = null

        val sent = first.send("Go to the study") {
            stateObservedAtNarrative = database.rivenPresenceDao().state(
                com.shai.riven.data.presence.RivenPresenceService.PRIMARY_STATE_ID,
            )
        }

        assertTrue("Expected success, got $sent", sent is RivenRuntimeResult.Success)
        assertEquals("study", checkNotNull(stateObservedAtNarrative).actualRoomId)
        val snapshot = (sent as RivenRuntimeResult.Success).snapshot
        assertEquals("Now I'm in the study.", snapshot.messages.last().content)
        assertEquals(com.shai.riven.data.presence.RivenRoom.STUDY, snapshot.roomState.actualRoom)
        assertEquals(com.shai.riven.data.presence.RivenRoom.LIVING_ROOM, snapshot.roomState.browsedRoom)
        assertFalse(snapshot.roomState.isRivenVisibleInBrowsedRoom)
        assertTrue(checkNotNull(http.requests.single().body).contains("RIVEN_ACTUAL_ROOM=living_room"))

        first.close()
        runtimes.remove(first)
        val restored = (runtime(QueueHttpClient()).initialize() as RivenRuntimeResult.Success).snapshot.roomState
        assertEquals(com.shai.riven.data.presence.RivenRoom.STUDY, restored.actualRoom)
        assertEquals(com.shai.riven.data.presence.RivenSemanticSprite.STANDING_TEASING, restored.semanticSprite)
        assertEquals(com.shai.riven.data.presence.RivenRoom.LIVING_ROOM, restored.browsedRoom)
    }

    @Test
    fun providerReceivesViewedAndActualRoomsWithoutBrowsingTeleportingRiven() = runBlocking {
        val http = QueueHttpClient(successJson("I know you're viewing the study while I'm elsewhere."))
        val first = runtime(http)
        first.initialize()
        assertTrue(first.saveProfile(null, "Primary", "anthropic/example", "private-key") is RivenProfileSaveResult.Success)

        val browsed = first.browseRoom(com.shai.riven.data.presence.RivenRoom.STUDY)
        val sent = first.send("Can you see which room I opened?")

        assertTrue(browsed is RivenRuntimeResult.Success)
        assertTrue(sent is RivenRuntimeResult.Success)
        val body = checkNotNull(http.requests.single().body)
        assertTrue(body.contains("CURRENT_USER_VIEWED_ROOM=study"))
        assertTrue(body.contains("RIVEN_ACTUAL_ROOM=living_room"))
        val roomState = (sent as RivenRuntimeResult.Success).snapshot.roomState
        assertEquals(com.shai.riven.data.presence.RivenRoom.STUDY, roomState.browsedRoom)
        assertEquals(com.shai.riven.data.presence.RivenRoom.LIVING_ROOM, roomState.actualRoom)
        assertFalse(roomState.isRivenVisibleInBrowsedRoom)
    }

    @Test
    fun unsupportedModelRoomFailsBeforeNarrativeIsExposed() = runBlocking {
        val reply = "RIVEN_STATE_CONTROL {\"room\":\"attic\",\"sprite\":\"standing_relaxed\"}\nI moved."
        val first = runtime(QueueHttpClient(successJson(reply)))
        first.initialize()
        assertTrue(first.saveProfile(null, "Primary", "anthropic/example", "private-key") is RivenProfileSaveResult.Success)
        val deltas = mutableListOf<String>()

        val sent = first.send("Go upstairs") { deltas += it }

        assertTrue(sent is RivenRuntimeResult.Failure)
        assertEquals(ConversationEngineErrorCode.STATE_CONTROL_REJECTED, (sent as RivenRuntimeResult.Failure).engineCode)
        assertTrue(deltas.isEmpty())
        assertEquals(com.shai.riven.data.presence.RivenRoom.LIVING_ROOM, checkNotNull(sent.snapshot).roomState.actualRoom)
        assertFalse(sent.snapshot.messages.any { it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun retryRejectsNoOpModelControlBeforeNarrativeIsExposed() = runBlocking {
        val noOpReply =
            "RIVEN_STATE_CONTROL {\"room\":\"living_room\",\"sprite\":\"standing_relaxed\"}\nI stayed put."
        val first = runtime(QueueHttpClient(providerFailure(), successJson(noOpReply)))
        first.initialize()
        assertTrue(first.saveProfile(null, "Primary", "anthropic/example", "private-key") is RivenProfileSaveResult.Success)
        assertTrue(first.send("Try this") is RivenRuntimeResult.Failure)
        val deltas = mutableListOf<String>()

        val retried = first.retry { deltas += it }

        assertNoOpStateControlRejected(retried, deltas)
        assertFalse(checkNotNull((retried as RivenRuntimeResult.Failure).snapshot).messages.any {
            it.role == MessageRole.ASSISTANT
        })
    }

    @Test
    fun regenerateRejectsNoOpModelControlBeforeNarrativeIsExposed() = runBlocking {
        val noOpReply =
            "RIVEN_STATE_CONTROL {\"room\":\"living_room\",\"sprite\":\"standing_relaxed\"}\nI stayed put."
        val first = runtime(QueueHttpClient(success("Original reply."), successJson(noOpReply)))
        first.initialize()
        assertTrue(first.saveProfile(null, "Primary", "anthropic/example", "private-key") is RivenProfileSaveResult.Success)
        assertTrue(first.send("Start here") is RivenRuntimeResult.Success)
        val deltas = mutableListOf<String>()

        val regenerated = first.regenerate { deltas += it }

        assertNoOpStateControlRejected(regenerated, deltas)
        assertEquals(
            listOf("Original reply."),
            checkNotNull((regenerated as RivenRuntimeResult.Failure).snapshot).messages
                .filter { it.role == MessageRole.ASSISTANT }
                .map { it.content },
        )
    }

    @Test
    fun continueRejectsNoOpModelControlBeforeNarrativeIsExposed() = runBlocking {
        val noOpReply =
            "RIVEN_STATE_CONTROL {\"room\":\"living_room\",\"sprite\":\"standing_relaxed\"}\nI stayed put."
        val first = runtime(QueueHttpClient(success("Original reply."), successJson(noOpReply)))
        first.initialize()
        assertTrue(first.saveProfile(null, "Primary", "anthropic/example", "private-key") is RivenProfileSaveResult.Success)
        assertTrue(first.send("Start here") is RivenRuntimeResult.Success)
        val deltas = mutableListOf<String>()

        val continued = first.continueConversation { deltas += it }

        assertNoOpStateControlRejected(continued, deltas)
        assertEquals(
            listOf("Original reply."),
            checkNotNull((continued as RivenRuntimeResult.Failure).snapshot).messages
                .filter { it.role == MessageRole.ASSISTANT }
                .map { it.content },
        )
    }

    private fun assertNoOpStateControlRejected(
        result: RivenRuntimeResult,
        deltas: List<String>,
    ) {
        assertTrue(result is RivenRuntimeResult.Failure)
        result as RivenRuntimeResult.Failure
        assertEquals(ConversationEngineErrorCode.STATE_CONTROL_REJECTED, result.engineCode)
        assertTrue(deltas.isEmpty())
        assertEquals(
            com.shai.riven.data.presence.RivenRoom.LIVING_ROOM,
            checkNotNull(result.snapshot).roomState.actualRoom,
        )
    }

    private fun runtime(
        http: OpenRouterHttpClient,
        thumbnailGenerator: ImageThumbnailGenerator = ImageThumbnailGenerator { bytes ->
            bytes.take(64).toByteArray()
        },
        afterImageImportedBeforeDraftLink: suspend (String) -> Unit = {},
        beforeDraftContentFlush: suspend () -> Unit = {},
    ) = RivenConversationRuntime(
        database = database,
        credentialStore = credentials,
        selectedProfileStore = selection,
        backgroundScheduler = scheduler,
        personalitySource = LockedRivenPersonalityContextSource(context),
        httpClient = http,
        attachmentBlobStore = attachmentBlobStore,
        attachmentThumbnailStore = thumbnailStore,
        imageMetadataDecoder = TEST_IMAGE_DECODER,
        imageThumbnailGenerator = thumbnailGenerator,
        imageCapabilityStore = imageCapabilities,
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        clock = { clock.incrementAndGet() },
        afterImageImportedBeforeDraftLink = afterImageImportedBeforeDraftLink,
        beforeDraftContentFlush = beforeDraftContentFlush,
    ).also(runtimes::add)

    private suspend fun attachImageDraft(caption: String): String {
        val attachment = AttachmentService(database, attachmentBlobStore).createImportedAttachment(
            ImportedAttachmentInput(
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                occurredAt = clock.incrementAndGet(),
                bytes = AttachmentByteSource.fromBytes(pngBytes()),
            ),
        ) as AttachmentCreateResult.Success
        val saved = ConversationDraftService(database, scheduler::enqueueAttachmentCleanup).saveDraft(
            SaveConversationDraftInput(
                conversationId = RivenConversationRuntime.CONVERSATION_ID,
                content = caption,
                attachmentIds = listOf(attachment.attachment.attachmentId),
                expectedRevision = 0,
                occurredAt = clock.incrementAndGet(),
            ),
        )
        assertTrue(saved is SaveConversationDraftResult.Saved)
        thumbnailStore.write(attachment.attachment.attachmentId, pngBytes())
        return attachment.attachment.attachmentId
    }

    private fun pngBytes(): ByteArray = Base64.getDecoder().decode(VALID_ONE_PIXEL_PNG)

    private fun imagePartCount(body: String): Int {
        val messages = JSONObject(body).getJSONArray("messages")
        var count = 0
        for (messageIndex in 0 until messages.length()) {
            val content = messages.getJSONObject(messageIndex).optJSONArray("content") ?: continue
            for (partIndex in 0 until content.length()) {
                if (content.getJSONObject(partIndex).optString("type") == "image_url") count++
            }
        }
        return count
    }

    private fun imageTextParts(body: String): List<String> {
        val messages = JSONObject(body).getJSONArray("messages")
        return buildList {
            for (messageIndex in 0 until messages.length()) {
                val content = messages.getJSONObject(messageIndex).optJSONArray("content") ?: continue
                for (partIndex in 0 until content.length()) {
                    val part = content.getJSONObject(partIndex)
                    if (part.optString("type") == "text") add(part.getString("text"))
                }
            }
        }
    }

    private companion object {
        val TEST_IMAGE_DECODER = ImageMetadataDecoder { bytes ->
            if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
                DecodedImageMetadata("image/png", 2, 2)
            } else {
                null
            }
        }
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        const val VALID_ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAALSURBVBhXY2BABwAAEgABp3qZbgAAAABJRU5ErkJggg=="
    }

    private fun success(content: String) = listOf(
        "data: {\"id\":\"request-ok\",\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}",
        "",
        "data: [DONE]",
        "",
    )

    private fun successJson(content: String): List<String> {
        val payload = JSONObject()
            .put("id", "request-ok")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "delta",
                        JSONObject().put("content", content),
                    ),
                ),
            )
        return listOf("data: $payload", "", "data: [DONE]", "")
    }

    private fun providerFailure() = listOf(
        "data: {\"id\":\"request-failed\",\"error\":{\"code\":429}}",
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

    private class CountingAttachmentBlobStore(
        private val delegate: AttachmentBlobStore,
    ) : AttachmentBlobStore {
        var openCount: Int = 0

        override fun write(
            storageKey: String,
            source: AttachmentByteSource,
        ): AttachmentBlobWriteResult = delegate.write(storageKey, source)

        override fun exists(storageKey: String): Boolean = delegate.exists(storageKey)

        override fun open(storageKey: String): InputStream {
            openCount += 1
            return delegate.open(storageKey)
        }

        override fun delete(storageKey: String) = delegate.delete(storageKey)
    }

    private class HoldingHttpClient : OpenRouterHttpClient {
        val entered = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun execute(
            request: OpenRouterHttpRequest,
            onLine: suspend (String) -> Boolean,
        ): OpenRouterHttpResponse {
            calls += 1
            if (calls == 1) {
                entered.complete(Unit)
                awaitCancellation()
            }
            listOf(
                "data: {\"id\":\"request-recovered\",\"choices\":[{\"delta\":{\"content\":\"Recovered reply.\"}}]}",
                "",
                "data: [DONE]",
                "",
            ).forEach { line -> onLine(line) }
            return OpenRouterHttpResponse(200, emptyMap())
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
        var automaticMemorySweepCalls = 0

        override fun enqueueAttachmentCleanup(attachmentId: String) = enqueued()
        override fun enqueueAttachmentMaintenanceSweep() = enqueued()
        override fun enqueueRepairJob(repairJobId: String) = enqueued()
        override fun enqueueRepairSweep() = enqueued()
        override fun enqueueAutomaticMemoryJob(automaticMemoryJobId: String) = enqueued()
        override fun enqueueAutomaticMemorySweep(): RivenBackgroundScheduleResult {
            automaticMemorySweepCalls += 1
            return enqueued()
        }
        override fun ensurePeriodicMaintenance() = enqueued()

        private fun enqueued(): RivenBackgroundScheduleResult {
            totalCalls += 1
            return RivenBackgroundScheduleResult.Enqueued(listOf("recorded"))
        }
    }
}
