package com.shai.riven.data.runtime

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.work.WorkManager
import com.shai.riven.data.attachment.AttachmentBlobStore
import com.shai.riven.data.attachment.AttachmentMetadataResult
import com.shai.riven.data.attachment.AttachmentProviderImageResolver
import com.shai.riven.data.attachment.AttachmentService
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.attachment.ImageAttachmentImportError
import com.shai.riven.data.attachment.ImageAttachmentImportResult
import com.shai.riven.data.attachment.ImageAttachmentImportService
import com.shai.riven.data.attachment.ImageMetadataDecoder
import com.shai.riven.data.attachment.AndroidImageMetadataDecoder
import com.shai.riven.data.attachment.SelectedImageInput
import com.shai.riven.data.attachment.MAX_IMPORTED_IMAGE_BYTES
import com.shai.riven.data.attachment.AttachmentThumbnailStore
import com.shai.riven.data.attachment.FileAttachmentThumbnailStore
import com.shai.riven.data.attachment.ImageThumbnailGenerator
import com.shai.riven.data.attachment.AndroidImageThumbnailGenerator
import com.shai.riven.data.automaticmemory.AutomaticMemoryQueueService
import com.shai.riven.data.automaticmemory.AutomaticMemoryStatusSnapshot
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.background.WorkManagerRivenBackgroundWorkScheduler
import com.shai.riven.data.context.ActiveConversationContextSource
import com.shai.riven.data.context.ConversationInvariantContextSource
import com.shai.riven.data.context.ConversationalContextAssembler
import com.shai.riven.data.context.EphemeralAppStateContextSource
import com.shai.riven.data.context.EphemeralAppStateStore
import com.shai.riven.data.context.OpenLoopContextSource
import com.shai.riven.data.context.RivenContextSourceRegistry
import com.shai.riven.data.context.RivenPresenceContextSource
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.conversation.engine.ConversationEngineErrorCode
import com.shai.riven.data.conversation.engine.ConversationEngineResult
import com.shai.riven.data.conversation.engine.CanonicalConversationImageSelector
import com.shai.riven.data.conversation.engine.ProviderAdapterRegistry
import com.shai.riven.data.conversation.engine.ProviderNeutralConversationEngine
import com.shai.riven.data.conversation.engine.ProviderStateControlHandler
import com.shai.riven.data.conversation.engine.ProviderStateControlResult
import com.shai.riven.data.conversation.engine.StartConversationRunInput
import com.shai.riven.data.conversation.engine.ImageInputAuthorization
import com.shai.riven.data.credential.HasProviderCredentialResult
import com.shai.riven.data.credential.DeleteProviderCredentialResult
import com.shai.riven.data.credential.ProviderCredentialStore
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.credential.PutProviderCredentialResult
import com.shai.riven.data.credential.ReadProviderCredentialResult
import com.shai.riven.data.draft.CommitDraftAsUserMessageInput
import com.shai.riven.data.draft.CommitDraftAsUserMessageResult
import com.shai.riven.data.draft.ConversationDraftService
import com.shai.riven.data.draft.ReadConversationDraftResult
import com.shai.riven.data.draft.SaveConversationDraftInput
import com.shai.riven.data.draft.SaveConversationDraftResult
import com.shai.riven.data.instructions.SaveShaiSystemInstructionsInput
import com.shai.riven.data.instructions.ShaiSystemInstructionsContextSource
import com.shai.riven.data.instructions.ShaiSystemInstructionsReadResult
import com.shai.riven.data.instructions.ShaiSystemInstructionsService
import com.shai.riven.data.instructions.ShaiSystemInstructionsSnapshot
import com.shai.riven.data.instructions.ShaiSystemInstructionsWriteResult
import com.shai.riven.data.memory.intent.ManualCorrectMemoryInput
import com.shai.riven.data.memory.intent.ManualDeleteMemoryInput
import com.shai.riven.data.memory.intent.ManualForgetMemoryInput
import com.shai.riven.data.memory.intent.ManualMemoryIntentResult
import com.shai.riven.data.memory.intent.ManualMemoryIntentService
import com.shai.riven.data.memory.intent.ManualRememberMemoryInput
import com.shai.riven.data.personality.LockedRivenPersonalityContextSource
import com.shai.riven.data.presence.RivenPresenceReadResult
import com.shai.riven.data.presence.RivenPresenceService
import com.shai.riven.data.presence.RivenPresenceSnapshot
import com.shai.riven.data.presence.RivenPresenceWriteResult
import com.shai.riven.data.presence.RivenRoom
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.RivenDatabaseLease
import com.shai.riven.data.persistence.RivenDatabaseProvider
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.persistence.model.ConversationRunTrigger
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.provider.CreateProviderProfileInput
import com.shai.riven.data.provider.CreateProviderProfileResult
import com.shai.riven.data.provider.ProviderCapability
import com.shai.riven.data.provider.ProviderProfileReadResult
import com.shai.riven.data.provider.ProviderProfileService
import com.shai.riven.data.provider.ProviderProfileSnapshot
import com.shai.riven.data.provider.ProviderProfilesReadResult
import com.shai.riven.data.provider.ProviderRuntimeProfileResolver
import com.shai.riven.data.provider.UpdateProviderProfileInput
import com.shai.riven.data.provider.UpdateProviderProfileResult
import com.shai.riven.data.provider.openrouter.OpenRouterConversationAdapter
import com.shai.riven.data.provider.openrouter.OpenRouterContextBudgetPolicy
import com.shai.riven.data.provider.openrouter.OpenRouterHttpClient
import com.shai.riven.data.provider.openrouter.OpenRouterImageInputCapability
import com.shai.riven.data.provider.openrouter.OpenRouterModel
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalog
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalogResult
import com.shai.riven.data.recall.ConversationalMemoryContextSource
import com.shai.riven.data.recall.TargetedConversationalMemoryRetriever
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RivenChatMessage(
    val id: String,
    val role: MessageRole,
    val deliveryState: MessageDeliveryState,
    val content: String,
    val providerModel: String?,
    val images: List<RivenImageAttachment> = emptyList(),
)

data class RivenImageAttachment(
    val attachmentId: String,
    val mimeType: String,
    val byteSize: Long,
    val previewBytes: ByteArray,
    val previewState: RivenImagePreviewState = if (previewBytes.isEmpty()) {
        RivenImagePreviewState.DEFERRED
    } else {
        RivenImagePreviewState.READY
    },
)

enum class RivenImagePreviewState {
    READY,
    DEFERRED,
    UNAVAILABLE,
}

data class RivenMemoryItem(
    val id: String,
    val meaning: String,
    val certainty: MemoryCertainty,
    val isActive: Boolean,
)

data class RivenRuntimeSnapshot(
    val conversationId: String,
    val timelineRevision: Long,
    val messages: List<RivenChatMessage>,
    val draft: String,
    val draftImages: List<RivenImageAttachment> = emptyList(),
    val profiles: List<ProviderProfileSnapshot>,
    val selectedProfileId: String?,
    val selectedProfileHasCredential: Boolean,
    val instructions: ShaiSystemInstructionsSnapshot,
    val memories: List<RivenMemoryItem>,
    val automaticMemoryStatus: AutomaticMemoryStatusSnapshot = AutomaticMemoryStatusSnapshot(
        pending = 0,
        running = 0,
        succeeded = 0,
        excluded = 0,
        failed = 0,
        latestErrorCode = null,
    ),
    val roomState: RivenPresenceSnapshot = RivenPresenceSnapshot(
        actualRoom = RivenRoom.LIVING_ROOM,
        semanticSprite = com.shai.riven.data.presence.RivenSemanticSprite.STANDING_RELAXED,
        browsedRoom = RivenRoom.LIVING_ROOM,
        presenceRevision = 0L,
        browserRevision = 0L,
    ),
)

interface RivenRuntimeController : AutoCloseable {
    suspend fun initialize(): RivenRuntimeResult
    suspend fun snapshot(): RivenRuntimeResult
    suspend fun saveDraft(content: String): RivenRuntimeResult
    suspend fun addDraftImage(uri: Uri, content: String): RivenRuntimeResult =
        RivenRuntimeResult.Failure("Image input is unavailable.")
    suspend fun removeDraftImage(attachmentId: String, content: String): RivenRuntimeResult =
        RivenRuntimeResult.Failure("Image input is unavailable.")
    suspend fun loadImagePreview(attachmentId: String): RivenRuntimeResult = snapshot()
    suspend fun send(content: String, onDelta: suspend (String) -> Unit = {}): RivenRuntimeResult
    suspend fun sendWithUnknownImageCapabilityConfirmation(
        content: String,
        onDelta: suspend (String) -> Unit = {},
    ): RivenRuntimeResult = send(content, onDelta)
    suspend fun retry(onDelta: suspend (String) -> Unit = {}): RivenRuntimeResult
    suspend fun regenerate(onDelta: suspend (String) -> Unit = {}): RivenRuntimeResult
    suspend fun continueConversation(onDelta: suspend (String) -> Unit = {}): RivenRuntimeResult
    suspend fun cancel(): RivenRuntimeResult
    suspend fun saveProfile(
        profileId: String?,
        displayName: String,
        modelId: String,
        apiKey: String,
    ): RivenProfileSaveResult
    suspend fun selectProfile(profileId: String): RivenRuntimeResult
    suspend fun removeOpenRouterCredential(): RivenRuntimeResult
    suspend fun fetchModels(): OpenRouterModelCatalogResult
    suspend fun saveInstructions(
        content: String,
        enabled: Boolean,
        expectedRevision: Long,
    ): RivenRuntimeResult
    suspend fun remember(meaning: String): RivenRuntimeResult
    suspend fun correct(memoryId: String, replacement: String): RivenRuntimeResult
    suspend fun forget(memoryId: String): RivenRuntimeResult
    suspend fun deleteMemory(memoryId: String): RivenRuntimeResult
    suspend fun browseRoom(room: RivenRoom): RivenRuntimeResult = snapshot()
}

sealed interface RivenRuntimeResult {
    data class Success(
        val snapshot: RivenRuntimeSnapshot,
        val engineResult: ConversationEngineResult? = null,
    ) : RivenRuntimeResult

    data class Failure(
        val message: String,
        val engineCode: ConversationEngineErrorCode? = null,
        val snapshot: RivenRuntimeSnapshot? = null,
        val requiresImageCapabilityConfirmation: Boolean = false,
    ) : RivenRuntimeResult
}

sealed interface RivenProfileSaveResult {
    data class Success(val profile: ProviderProfileSnapshot) : RivenProfileSaveResult
    data class Failure(val message: String) : RivenProfileSaveResult
}

interface SelectedProviderProfileStore {
    fun selectedProfileId(): String?
    fun select(profileId: String?)
}

class AndroidSelectedProviderProfileStore(context: Context) : SelectedProviderProfileStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun selectedProfileId(): String? = preferences.getString(KEY_SELECTED_PROFILE, null)

    override fun select(profileId: String?) {
        preferences.edit {
            if (profileId == null) remove(KEY_SELECTED_PROFILE) else putString(KEY_SELECTED_PROFILE, profileId)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "riven_runtime_preferences"
        const val KEY_SELECTED_PROFILE = "selected_provider_profile_id"
    }
}

class RivenConversationRuntime(
    private val database: RivenDatabase,
    private val credentialStore: ProviderCredentialStore,
    private val selectedProfileStore: SelectedProviderProfileStore,
    private val backgroundScheduler: RivenBackgroundWorkScheduler,
    private val personalitySource: LockedRivenPersonalityContextSource,
    httpClient: OpenRouterHttpClient,
    attachmentBlobStore: AttachmentBlobStore? = null,
    private val attachmentThumbnailStore: AttachmentThumbnailStore? = null,
    imageMetadataDecoder: ImageMetadataDecoder = AndroidImageMetadataDecoder,
    private val imageThumbnailGenerator: ImageThumbnailGenerator = AndroidImageThumbnailGenerator,
    private val contentResolver: ContentResolver? = null,
    private val imageCapabilityStore: ImageInputCapabilityStore = InMemoryImageInputCapabilityStore(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val databaseLease: RivenDatabaseLease? = null,
    private val afterImageImportedBeforeDraftLink: suspend (String) -> Unit = {},
    private val beforeDraftContentFlush: suspend () -> Unit = {},
) : RivenRuntimeController {
    private val timeline = ConversationTimelineService(database)
    private val profileService = ProviderProfileService(database)
    private val instructionsService = ShaiSystemInstructionsService(database)
    private val ephemeralStore = EphemeralAppStateStore()
    private val recall = TargetedConversationalMemoryRetriever(database)
    private val presence = RivenPresenceService(database)
    private val draftService = ConversationDraftService(
        database = database,
        attachmentCleanupScheduler = backgroundScheduler::enqueueAttachmentCleanup,
    )
    private val thumbnailCache = BoundedImageThumbnailCache(attachmentThumbnailStore)
    private val unavailableImagePreviewIds = mutableSetOf<String>()
    private val attachmentService = attachmentBlobStore?.let {
        AttachmentService(
            database,
            it,
            afterBlobDeletionBeforeRowDeletion = { attachmentId ->
                attachmentThumbnailStore?.delete(attachmentId)
                thumbnailCache.remove(attachmentId)
            },
        )
    }
    private val imageImporter = attachmentService?.let {
        ImageAttachmentImportService(
            it,
            imageMetadataDecoder,
            attachmentThumbnailStore,
            imageThumbnailGenerator,
        )
    }
    private val imageResolver = attachmentService?.let {
        AttachmentProviderImageResolver(it, imageMetadataDecoder)
    }
    private val memoryIntents = ManualMemoryIntentService(database, backgroundScheduler)
    private val automaticMemoryQueue = AutomaticMemoryQueueService(database, backgroundScheduler)
    private val adapter = OpenRouterConversationAdapter(httpClient)
    private val modelCatalog = OpenRouterModelCatalog(httpClient)
    private val contextBudgetPolicy = OpenRouterContextBudgetPolicy { modelId ->
        imageCapabilityStore.readModel(modelId)?.contextLength
    }
    private val fetchedModels = mutableMapOf<String, OpenRouterModel>()
    private val canonicalImageSelector = CanonicalConversationImageSelector(database)
    private val engine = ProviderNeutralConversationEngine(
        database = database,
        contextAssembler = ConversationalContextAssembler(
            RivenContextSourceRegistry(
                listOf(
                    ConversationInvariantContextSource(),
                    RivenPresenceContextSource(presence),
                    personalitySource,
                    ShaiSystemInstructionsContextSource(instructionsService),
                    EphemeralAppStateContextSource(ephemeralStore),
                    ConversationalMemoryContextSource(recall),
                    OpenLoopContextSource(database),
                    ActiveConversationContextSource(timeline),
                ),
            ),
        ),
        recallValidator = recall,
        profileService = profileService,
        profileResolver = ProviderRuntimeProfileResolver(profileService, credentialStore),
        instructionsService = instructionsService,
        ephemeralStateStore = ephemeralStore,
        adapterRegistry = ProviderAdapterRegistry(listOf(adapter)),
        stateControlHandler = ProviderStateControlHandler { request, expectedRevision, occurredAt ->
            when (
                val result = presence.applyModelControl(
                    roomId = request.roomId,
                    spriteId = request.spriteId,
                    expectedPresenceRevision = expectedRevision,
                    occurredAt = occurredAt,
                )
            ) {
                is RivenPresenceWriteResult.Rejected -> ProviderStateControlResult.Rejected(result.reason)
                is RivenPresenceWriteResult.Unchanged -> result.snapshot.toProviderStateControlResult()
                is RivenPresenceWriteResult.Updated -> result.snapshot.toProviderStateControlResult()
            }
        },
        imageContentResolver = imageResolver ?: com.shai.riven.data.conversation.engine.ProviderImageContentResolver { null },
        contextBudgetResolver = { profile -> contextBudgetPolicy.budgetFor(profile.modelId) },
        clock = clock,
    )
    private val actionMutex = Mutex()

    private val activeConversationOperation = AtomicReference<ActiveConversationOperation?>()

    override suspend fun initialize(): RivenRuntimeResult = actionMutex.withLock {
        if (presence.initialize(clock()) is RivenPresenceReadResult.Failure) {
            return@withLock RivenRuntimeResult.Failure("Riven's room state could not be initialized.")
        }
        engine.recoverInterruptedRuns()
        ensureConversation()
        withContext(ioDispatcher) {
            automaticMemoryQueue.reconcileSucceededRuns(
                limit = AUTOMATIC_MEMORY_RECONCILIATION_LIMIT,
                occurredAt = clock(),
            )
            automaticMemoryQueue.schedulePending(AUTOMATIC_MEMORY_RECONCILIATION_LIMIT)
        }
        snapshotResult()
    }

    override suspend fun snapshot(): RivenRuntimeResult = actionMutex.withLock { snapshotResult() }

    override suspend fun browseRoom(room: RivenRoom): RivenRuntimeResult = actionMutex.withLock {
        when (presence.browse(room, clock())) {
            is RivenPresenceWriteResult.Rejected -> RivenRuntimeResult.Failure(
                message = "That room could not be opened.",
                snapshot = snapshotOrNull(),
            )
            is RivenPresenceWriteResult.Unchanged,
            is RivenPresenceWriteResult.Updated,
            -> snapshotResult()
        }
    }

    override suspend fun saveDraft(content: String): RivenRuntimeResult = actionMutex.withLock {
        ensureConversation()
        val current = readDraft()
        if (current.content == content) return@withLock snapshotResult()
        when (
            draftService.saveDraft(
                SaveConversationDraftInput(
                    conversationId = CONVERSATION_ID,
                    content = content,
                    attachmentIds = current.attachmentIds,
                    expectedRevision = current.revision,
                    occurredAt = clock(),
                ),
            )
        ) {
            is SaveConversationDraftResult.Saved -> snapshotResult()
            is SaveConversationDraftResult.Failure -> RivenRuntimeResult.Failure(
                message = "Draft could not be saved.",
                snapshot = snapshotOrNull(),
            )
        }
    }

    override suspend fun addDraftImage(uri: Uri, content: String): RivenRuntimeResult = actionMutex.withLock {
        ensureConversation()
        if (!flushDraftContentWithinLock(content)) {
            return@withLock RivenRuntimeResult.Failure(
                "Draft could not be saved.",
            )
        }
        val resolver = contentResolver
            ?: return@withLock RivenRuntimeResult.Failure(
                "The image picker is unavailable.",
                snapshot = snapshotOrNull(),
            )
        val selected = selectedImageInput(resolver, uri, clock())
            ?: return@withLock RivenRuntimeResult.Failure(
                "The selected image could not be opened.",
                snapshot = snapshotOrNull(),
            )
        addDraftImageWithinLock(selected, content)
    }

    internal suspend fun addDraftImageSelection(
        selected: SelectedImageInput,
        content: String,
    ): RivenRuntimeResult = actionMutex.withLock {
        ensureConversation()
        if (!flushDraftContentWithinLock(content)) {
            return@withLock RivenRuntimeResult.Failure(
                "Draft could not be saved.",
            )
        }
        addDraftImageWithinLock(selected, content)
    }

    private suspend fun addDraftImageWithinLock(
        selected: SelectedImageInput,
        content: String,
    ): RivenRuntimeResult {
        val importer = imageImporter
            ?: return RivenRuntimeResult.Failure("Image storage is unavailable.")
        val current = readDraft()
        if (current.attachmentIds.isNotEmpty()) {
            return RivenRuntimeResult.Failure(
                "Remove the current image before selecting another.",
                snapshot = snapshotOrNull(),
            )
        }
        val imported = withContext(ioDispatcher) { importer.import(selected) }
        val image = (imported as? ImageAttachmentImportResult.Success)?.image
            ?: return RivenRuntimeResult.Failure(
                message = (imported as ImageAttachmentImportResult.Failure).error.userMessage(),
                snapshot = snapshotOrNull(),
            )
        afterImageImportedBeforeDraftLink(image.metadata.attachmentId)
        val saved = draftService.saveDraft(
            SaveConversationDraftInput(
                conversationId = CONVERSATION_ID,
                content = content,
                attachmentIds = listOf(image.metadata.attachmentId),
                expectedRevision = current.revision,
                occurredAt = clock(),
            ),
        )
        if (saved !is SaveConversationDraftResult.Saved) {
            withContext(ioDispatcher) {
                attachmentService?.discardUnreferencedAvailable(image.metadata.attachmentId, clock())
                runCatching { attachmentThumbnailStore?.delete(image.metadata.attachmentId) }
                thumbnailCache.remove(image.metadata.attachmentId)
                unavailableImagePreviewIds.remove(image.metadata.attachmentId)
            }
            return RivenRuntimeResult.Failure(
                "The selected image could not be attached.",
                snapshot = snapshotOrNull(),
            )
        }
        thumbnailCache.read(image.metadata.attachmentId)
        unavailableImagePreviewIds.remove(image.metadata.attachmentId)
        return snapshotResult()
    }

    override suspend fun removeDraftImage(
        attachmentId: String,
        content: String,
    ): RivenRuntimeResult =
        actionMutex.withLock {
            ensureConversation()
            if (!flushDraftContentWithinLock(content)) {
                return@withLock RivenRuntimeResult.Failure(
                    "Draft could not be saved.",
                )
            }
            val current = readDraft()
            if (attachmentId !in current.attachmentIds) {
                return@withLock RivenRuntimeResult.Failure(
                    "The image could not be removed.",
                    snapshot = snapshotOrNull(),
                )
            }
            when (
                draftService.saveDraft(
                    SaveConversationDraftInput(
                        conversationId = CONVERSATION_ID,
                        content = content,
                        attachmentIds = current.attachmentIds - attachmentId,
                        expectedRevision = current.revision,
                        occurredAt = clock(),
                    ),
                )
            ) {
                is SaveConversationDraftResult.Saved -> {
                    if (database.attachmentDao().messageReferenceCount(attachmentId) == 0) {
                        withContext(ioDispatcher) {
                            runCatching { attachmentThumbnailStore?.delete(attachmentId) }
                            thumbnailCache.remove(attachmentId)
                            unavailableImagePreviewIds.remove(attachmentId)
                        }
                    }
                    snapshotResult()
                }
                is SaveConversationDraftResult.Failure -> RivenRuntimeResult.Failure(
                    "The image could not be removed.",
                    snapshot = snapshotOrNull(),
                )
            }
        }

    override suspend fun loadImagePreview(attachmentId: String): RivenRuntimeResult =
        actionMutex.withLock {
            ensureConversation()
            if (!isAttachmentLinkedToActiveConversation(attachmentId)) {
                return@withLock RivenRuntimeResult.Failure("The image is no longer available.")
            }
            if (thumbnailCache.peek(attachmentId) == null &&
                attachmentId !in unavailableImagePreviewIds
            ) {
                val thumbnail = withContext(ioDispatcher) {
                    imageResolver?.resolve(attachmentId)?.let { original ->
                        imageThumbnailGenerator.create(original.bytes)
                    }
                }
                if (thumbnail == null) {
                    thumbnailCache.remove(attachmentId)
                    unavailableImagePreviewIds += attachmentId
                } else {
                    withContext(ioDispatcher) {
                        runCatching { attachmentThumbnailStore?.write(attachmentId, thumbnail) }
                    }
                    thumbnailCache.put(attachmentId, thumbnail)
                    unavailableImagePreviewIds.remove(attachmentId)
                }
            }
            snapshotResult()
        }

    override suspend fun send(
        content: String,
        onDelta: suspend (String) -> Unit,
    ): RivenRuntimeResult = sendInternal(content, false, onDelta)

    override suspend fun sendWithUnknownImageCapabilityConfirmation(
        content: String,
        onDelta: suspend (String) -> Unit,
    ): RivenRuntimeResult = sendInternal(content, true, onDelta)

    private suspend fun sendInternal(
        content: String,
        confirmUnknownImageCapability: Boolean,
        onDelta: suspend (String) -> Unit,
    ): RivenRuntimeResult = conversationOperation { operation ->
        ensureConversation()
        coroutineContext.ensureActive()
        val saved = saveDraftWithinLock(content)
        if (saved is RivenRuntimeResult.Failure) return@conversationOperation saved
        coroutineContext.ensureActive()
        val profile = selectedProfileOrNull()
            ?: return@conversationOperation RivenRuntimeResult.Failure(
                message = "Configure and select an enabled OpenRouter profile before sending.",
                snapshot = snapshotOrNull(),
            )
        val draft = readDraft()
        val timelineSnapshot = activeTimelineOrNull()
            ?: return@conversationOperation RivenRuntimeResult.Failure("Conversation is unavailable.")
        val requiresImageContext = draft.attachmentIds.isNotEmpty() ||
            timelineSnapshot.immediatelyPreviousUserHasImage()
        val imageAuthorization = if (!requiresImageContext) {
            null
        } else {
            when (imageCapability(profile)) {
                OpenRouterImageInputCapability.SUPPORTED ->
                    ImageInputAuthorization.MODEL_DECLARED_SUPPORTED
                OpenRouterImageInputCapability.UNSUPPORTED ->
                    return@conversationOperation RivenRuntimeResult.Failure(
                        "The selected model does not accept image input. Choose a vision-capable model or remove the image.",
                        snapshot = snapshotOrNull(),
                    )
                OpenRouterImageInputCapability.UNKNOWN -> if (confirmUnknownImageCapability) {
                    imageCapabilityStore.write(
                        profile.profileId,
                        profile.modelId,
                        OpenRouterImageInputCapability.USER_CONFIRMED_UNKNOWN,
                    )
                    ImageInputAuthorization.USER_CONFIRMED_UNKNOWN
                } else {
                    return@conversationOperation RivenRuntimeResult.Failure(
                        "OpenRouter did not declare whether this model accepts images. Confirm Send anyway to try it, or remove the image.",
                        requiresImageCapabilityConfirmation = true,
                        snapshot = snapshotOrNull(),
                    )
                }
                OpenRouterImageInputCapability.USER_CONFIRMED_UNKNOWN ->
                    ImageInputAuthorization.USER_CONFIRMED_UNKNOWN
            }
        }
        val userMessageId = UUID.randomUUID().toString()
        val committed = draftService.commitDraftAsUserMessage(
            CommitDraftAsUserMessageInput(
                conversationId = CONVERSATION_ID,
                messageId = userMessageId,
                expectedDraftRevision = draft.revision,
                expectedTimelineRevision = timelineSnapshot.timelineRevision,
                occurredAt = clock(),
            ),
        )
        val revision = (committed as? CommitDraftAsUserMessageResult.MessageCommitted)?.timelineRevision
            ?: return@conversationOperation RivenRuntimeResult.Failure(
                message = "The message could not be committed.",
                snapshot = snapshotOrNull(),
            )
        executeRun(
            operation = operation,
            profileId = profile.profileId,
            userMessageId = userMessageId,
            expectedTimelineRevision = revision,
            trigger = ConversationRunTrigger.INITIAL,
            imageInputAuthorization = imageAuthorization,
            onDelta = onDelta,
        )
    }

    override suspend fun retry(onDelta: suspend (String) -> Unit): RivenRuntimeResult = conversationOperation { operation ->
        val profile = selectedProfileOrNull()
            ?: return@conversationOperation RivenRuntimeResult.Failure("Select an enabled provider profile first.")
        coroutineContext.ensureActive()
        val active = activeTimelineOrNull()
            ?: return@conversationOperation RivenRuntimeResult.Failure("Conversation is unavailable.")
        val selectedHead = active.messages.lastOrNull()?.id
        val failed = withContext(ioDispatcher) {
            database.conversationRunDao().runsForConversation(CONVERSATION_ID).lastOrNull { run ->
                run.selectedHeadMessageId == selectedHead && run.state in RETRYABLE_RUN_STATES
            }
        } ?: return@conversationOperation RivenRuntimeResult.Failure(
            message = "There is no failed or interrupted reply to retry.",
            snapshot = snapshotOrNull(),
        )
        executeRun(
            operation = operation,
            profileId = profile.profileId,
            userMessageId = failed.userMessageId,
            expectedTimelineRevision = active.timelineRevision,
            trigger = ConversationRunTrigger.RETRY,
            retryOfRunId = failed.runId,
            canonicalImageContextHeadMessageId = failed.contextHeadMessageId,
            onDelta = onDelta,
        )
    }

    override suspend fun regenerate(onDelta: suspend (String) -> Unit): RivenRuntimeResult = conversationOperation { operation ->
        val profile = selectedProfileOrNull()
            ?: return@conversationOperation RivenRuntimeResult.Failure("Select an enabled provider profile first.")
        coroutineContext.ensureActive()
        val active = activeTimelineOrNull()
            ?: return@conversationOperation RivenRuntimeResult.Failure("Conversation is unavailable.")
        val original = active.messages.lastOrNull()
        val user = active.messages.dropLast(1).lastOrNull()
        if (original?.role != MessageRole.ASSISTANT ||
            original.deliveryState != MessageDeliveryState.SUCCEEDED ||
            user?.role != MessageRole.USER
        ) {
            return@conversationOperation RivenRuntimeResult.Failure(
                message = "Only the current successful Riven reply can be regenerated.",
                snapshot = snapshotOrNull(),
            )
        }
        executeRun(
            operation = operation,
            profileId = profile.profileId,
            userMessageId = user.id,
            expectedTimelineRevision = active.timelineRevision,
            trigger = ConversationRunTrigger.REGENERATE,
            regenerateOfMessageId = original.id,
            onDelta = onDelta,
        )
    }

    override suspend fun continueConversation(
        onDelta: suspend (String) -> Unit,
    ): RivenRuntimeResult = conversationOperation { operation ->
        ensureConversation()
        val profile = selectedProfileOrNull()
            ?: return@conversationOperation RivenRuntimeResult.Failure(
                "Select an enabled provider profile first.",
            )
        coroutineContext.ensureActive()
        val active = activeTimelineOrNull()
            ?: return@conversationOperation RivenRuntimeResult.Failure("Conversation is unavailable.")
        val imageAuthorization = if (active.immediatelyPreviousUserHasImage()) {
            when (imageCapability(profile)) {
                OpenRouterImageInputCapability.SUPPORTED ->
                    ImageInputAuthorization.MODEL_DECLARED_SUPPORTED
                OpenRouterImageInputCapability.USER_CONFIRMED_UNKNOWN ->
                    ImageInputAuthorization.USER_CONFIRMED_UNKNOWN
                OpenRouterImageInputCapability.UNSUPPORTED ->
                    return@conversationOperation RivenRuntimeResult.Failure(
                        "The selected model does not accept the preceding image.",
                        snapshot = snapshotOrNull(),
                    )
                OpenRouterImageInputCapability.UNKNOWN ->
                    return@conversationOperation RivenRuntimeResult.Failure(
                        "OpenRouter did not declare whether this model accepts the preceding image. Send a manual Continue message to confirm image input.",
                        snapshot = snapshotOrNull(),
                    )
            }
        } else {
            null
        }
        val userMessageId = UUID.randomUUID().toString()
        val occurredAt = clock()
        val appended = timeline.appendMessage(
            AppendTimelineMessageInput(
                conversationId = CONVERSATION_ID,
                message = NewTimelineMessageInput(
                    messageId = userMessageId,
                    role = MessageRole.USER,
                    deliveryState = MessageDeliveryState.PERSISTED,
                    content = CONTINUE_MESSAGE,
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                ),
                expectedTimelineRevision = active.timelineRevision,
                occurredAt = occurredAt,
            ),
        )
        val revision = (appended as? TimelineWriteResult.MessageAppended)?.timelineRevision
            ?: return@conversationOperation RivenRuntimeResult.Failure(
                message = "Continue could not be added to the conversation.",
                snapshot = snapshotOrNull(),
            )
        executeRun(
            operation = operation,
            profileId = profile.profileId,
            userMessageId = userMessageId,
            expectedTimelineRevision = revision,
            trigger = ConversationRunTrigger.INITIAL,
            imageInputAuthorization = imageAuthorization,
            onDelta = onDelta,
        )
    }

    override suspend fun cancel(): RivenRuntimeResult {
        val operation = activeConversationOperation.get()
            ?: return RivenRuntimeResult.Failure(
                message = "There is no active reply to cancel.",
                snapshot = snapshotOrNull(),
            )
        operation.job.cancel(CancellationException("Conversation operation cancelled"))
        operation.runId.get()?.let { runId ->
            withContext(NonCancellable) { engine.cancel(runId) }
        }
        return withContext(NonCancellable) { snapshotResult() }
    }

    override suspend fun saveProfile(
        profileId: String?,
        displayName: String,
        modelId: String,
        apiKey: String,
    ): RivenProfileSaveResult = actionMutex.withLock {
        val normalizedDisplayName = displayName.trim()
        val normalizedModelId = modelId.trim()
        val imageCapability = resolvedImageCapability(profileId, normalizedModelId)
        val profileCapabilities = buildList {
            addAll(OPENROUTER_CAPABILITIES)
            if (imageCapability == OpenRouterImageInputCapability.SUPPORTED) {
                add(ProviderCapability.IMAGE_INPUT)
            }
        }
        val validation = profileService.validateCandidate(
            displayName = normalizedDisplayName,
            adapterId = OpenRouterConversationAdapter.ADAPTER_ID,
            endpointBaseUrl = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
            modelId = normalizedModelId,
            credentialSlotId = OPENROUTER_CREDENTIAL_SLOT,
            capabilities = profileCapabilities,
        )
        if (validation != null) {
            return@withLock RivenProfileSaveResult.Failure(
                "Profile validation failed: ${validation::class.java.simpleName}",
            )
        }
        val currentProfile = profileId?.let { id ->
            val current = profileService.profile(id)
            if (current !is ProviderProfileReadResult.Success ||
                current.profile.adapterId != OpenRouterConversationAdapter.ADAPTER_ID
            ) {
                return@withLock RivenProfileSaveResult.Failure("The selected profile is unavailable.")
            }
            current.profile
        }
        if (apiKey.isNotBlank()) {
            val credential = withContext(ioDispatcher) {
                credentialStore.putCredential(
                    OPENROUTER_CREDENTIAL_SLOT,
                    ProviderSecret.fromPlaintext(apiKey.trim()),
                )
            }
            if (credential !is PutProviderCredentialResult.Success) {
                return@withLock RivenProfileSaveResult.Failure("The API key could not be stored securely.")
            }
        }
        val occurredAt = clock()
        val result = if (profileId == null) {
            profileService.create(
                CreateProviderProfileInput(
                    profileId = UUID.randomUUID().toString(),
                    displayName = normalizedDisplayName,
                    adapterId = OpenRouterConversationAdapter.ADAPTER_ID,
                    endpointBaseUrl = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
                    modelId = normalizedModelId,
                    credentialSlotId = OPENROUTER_CREDENTIAL_SLOT,
                    capabilities = profileCapabilities,
                    occurredAt = occurredAt,
                ),
            ).let { created ->
                when (created) {
                    is CreateProviderProfileResult.Success -> RivenProfileSaveResult.Success(created.profile)
                    is CreateProviderProfileResult.Failure -> RivenProfileSaveResult.Failure(
                        "Profile validation failed: ${created.error::class.java.simpleName}",
                    )
                }
            }
        } else {
            val current = checkNotNull(currentProfile)
            profileService.update(
                UpdateProviderProfileInput(
                    profileId = current.profileId,
                    expectedRevision = current.revision,
                    displayName = normalizedDisplayName,
                    adapterId = OpenRouterConversationAdapter.ADAPTER_ID,
                    endpointBaseUrl = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
                    modelId = normalizedModelId,
                    credentialSlotId = OPENROUTER_CREDENTIAL_SLOT,
                    isEnabled = true,
                    capabilities = profileCapabilities,
                    occurredAt = occurredAt,
                ),
            ).let { updated ->
                when (updated) {
                    is UpdateProviderProfileResult.Success -> RivenProfileSaveResult.Success(updated.profile)
                    is UpdateProviderProfileResult.Failure -> RivenProfileSaveResult.Failure(
                        "Profile validation failed: ${updated.error::class.java.simpleName}",
                    )
                }
            }
        }
        if (result is RivenProfileSaveResult.Success) {
            selectedProfileStore.select(result.profile.profileId)
            imageCapabilityStore.write(
                result.profile.profileId,
                result.profile.modelId,
                imageCapability,
            )
            backgroundScheduler.enqueueAutomaticMemorySweep()
        }
        result
    }

    override suspend fun selectProfile(profileId: String): RivenRuntimeResult = actionMutex.withLock {
        val profile = profileService.profile(profileId)
        if (profile !is ProviderProfileReadResult.Success || !profile.profile.isEnabled) {
            return@withLock RivenRuntimeResult.Failure("That provider profile is unavailable.")
        }
        selectedProfileStore.select(profileId)
        backgroundScheduler.enqueueAutomaticMemorySweep()
        snapshotResult()
    }

    override suspend fun removeOpenRouterCredential(): RivenRuntimeResult = actionMutex.withLock {
        when (
            withContext(ioDispatcher) {
                credentialStore.deleteCredential(OPENROUTER_CREDENTIAL_SLOT)
            }
        ) {
            DeleteProviderCredentialResult.Success -> snapshotResult()
            is DeleteProviderCredentialResult.Failure -> RivenRuntimeResult.Failure(
                message = "The saved API key could not be removed.",
                snapshot = snapshotOrNull(),
            )
        }
    }

    override suspend fun fetchModels(): OpenRouterModelCatalogResult {
        val credential = withContext(ioDispatcher) {
            credentialStore.readCredential(OPENROUTER_CREDENTIAL_SLOT)
        }
        return if (credential is ReadProviderCredentialResult.Success) {
            modelCatalog.models(credential.secret).also { result ->
                if (result is OpenRouterModelCatalogResult.Success) {
                    contextBudgetPolicy.record(result.models)
                    fetchedModels.clear()
                    fetchedModels.putAll(result.models.associateBy(OpenRouterModel::id))
                    result.models.forEach { model ->
                        val previous = imageCapabilityStore.readModel(model.id)
                        imageCapabilityStore.writeModel(
                            model.id,
                            StoredOpenRouterModelMetadata(
                                imageInputCapability = model.imageInputCapability,
                                contextLength = model.contextLength ?: previous?.contextLength,
                            ),
                        )
                    }
                }
            }
        } else {
            OpenRouterModelCatalogResult.Failure(
                com.shai.riven.data.provider.openrouter.OpenRouterModelCatalogError.AUTHENTICATION,
            )
        }
    }

    override suspend fun saveInstructions(
        content: String,
        enabled: Boolean,
        expectedRevision: Long,
    ): RivenRuntimeResult = actionMutex.withLock {
        when (
            instructionsService.save(
                SaveShaiSystemInstructionsInput(
                    content = content,
                    isEnabled = enabled,
                    expectedRevision = expectedRevision,
                    occurredAt = clock(),
                ),
            )
        ) {
            is ShaiSystemInstructionsWriteResult.Saved -> snapshotResult()
            is ShaiSystemInstructionsWriteResult.Cleared -> snapshotResult()
            is ShaiSystemInstructionsWriteResult.Failure -> RivenRuntimeResult.Failure(
                message = "System Instructions changed elsewhere; reload and try again.",
                snapshot = snapshotOrNull(),
            )
        }
    }

    override suspend fun remember(meaning: String): RivenRuntimeResult = actionMutex.withLock {
        val result = memoryIntents.remember(
            ManualRememberMemoryInput(
                meaning = meaning,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                certainty = MemoryCertainty.CERTAIN,
                sensitivity = SensitivityLevel.STANDARD,
                occurredAt = clock(),
            ),
        )
        manualMemoryResult(result, "Remember")
    }

    override suspend fun correct(memoryId: String, replacement: String): RivenRuntimeResult = actionMutex.withLock {
        manualMemoryResult(
            memoryIntents.correct(
                ManualCorrectMemoryInput(
                    memoryId = memoryId,
                    replacementMeaning = replacement,
                    occurredAt = clock(),
                ),
            ),
            "Correction",
        )
    }

    override suspend fun forget(memoryId: String): RivenRuntimeResult = actionMutex.withLock {
        manualMemoryResult(
            memoryIntents.forget(ManualForgetMemoryInput(memoryId, clock())),
            "Forget",
        )
    }

    override suspend fun deleteMemory(memoryId: String): RivenRuntimeResult = actionMutex.withLock {
        manualMemoryResult(
            memoryIntents.delete(ManualDeleteMemoryInput(memoryId, clock())),
            "Delete",
        )
    }

    override fun close() {
        activeConversationOperation.get()?.job?.cancel(CancellationException("Riven runtime closed"))
        engine.close()
        recall.close()
        databaseLease?.close()
    }

    private suspend fun executeRun(
        operation: ActiveConversationOperation,
        profileId: String,
        userMessageId: String,
        expectedTimelineRevision: Long,
        trigger: ConversationRunTrigger,
        retryOfRunId: String? = null,
        regenerateOfMessageId: String? = null,
        imageInputAuthorization: ImageInputAuthorization? = null,
        canonicalImageContextHeadMessageId: String = userMessageId,
        onDelta: suspend (String) -> Unit,
    ): RivenRuntimeResult {
        val runId = UUID.randomUUID().toString()
        operation.runId.set(runId)
        val canonicalImageIds = canonicalImageSelector.select(
            conversationId = CONVERSATION_ID,
            userMessageId = userMessageId,
            contextHeadMessageId = canonicalImageContextHeadMessageId,
        )
        val resolvedImageAuthorization = imageInputAuthorization
            ?: existingImageAuthorization(canonicalImageIds, profileId)
        if (canonicalImageIds.isNotEmpty() && resolvedImageAuthorization == null) {
            return RivenRuntimeResult.Failure(
                "The selected model does not accept this image. Choose a vision-capable model.",
                snapshot = snapshotOrNull(),
            )
        }
        return try {
            val result = engine.execute(
                StartConversationRunInput(
                    runId = runId,
                    idempotencyKey = UUID.randomUUID().toString(),
                    conversationId = CONVERSATION_ID,
                    userMessageId = userMessageId,
                    assistantMessageId = UUID.randomUUID().toString(),
                    profileId = profileId,
                    trigger = trigger,
                    retryOfRunId = retryOfRunId,
                    regenerateOfMessageId = regenerateOfMessageId,
                    expectedTimelineRevision = expectedTimelineRevision,
                    occurredAt = clock(),
                    imageInputAuthorization = resolvedImageAuthorization,
                ),
                onDelta = onDelta,
            )
            when (result) {
                is ConversationEngineResult.Succeeded -> {
                    withContext(ioDispatcher) {
                        automaticMemoryQueue.ensureForSucceededRun(
                            runId = result.run.runId,
                            sourceTimelineRevision = result.timelineRevision,
                            occurredAt = clock(),
                        )
                    }
                    RivenRuntimeResult.Success(checkNotNull(snapshotOrNull()), result)
                }
                is ConversationEngineResult.Existing -> {
                    if (result.run.state == ConversationRunState.SUCCEEDED) {
                        val currentRevision = activeTimelineOrNull()?.timelineRevision
                        if (currentRevision != null) {
                            withContext(ioDispatcher) {
                                automaticMemoryQueue.ensureForSucceededRun(
                                    runId = result.run.runId,
                                    sourceTimelineRevision = currentRevision,
                                    occurredAt = clock(),
                                )
                            }
                        }
                    }
                    RivenRuntimeResult.Success(checkNotNull(snapshotOrNull()), result)
                }
                is ConversationEngineResult.Failed -> RivenRuntimeResult.Failure(
                    message = result.code.userMessage(),
                    engineCode = result.code,
                    snapshot = snapshotOrNull(),
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                RivenRuntimeResult.Failure(
                    message = "Reply cancelled.",
                    engineCode = ConversationEngineErrorCode.CANCELLED,
                    snapshot = snapshotOrNull(),
                )
            }
        }
    }

    private suspend fun conversationOperation(
        block: suspend (ActiveConversationOperation) -> RivenRuntimeResult,
    ): RivenRuntimeResult {
        val job = coroutineContext[Job]
            ?: return RivenRuntimeResult.Failure("Conversation operation is unavailable.")
        val operation = ActiveConversationOperation(job)
        if (!activeConversationOperation.compareAndSet(null, operation)) {
            return RivenRuntimeResult.Failure("Another reply is already in progress.")
        }
        return try {
            actionMutex.withLock {
                coroutineContext.ensureActive()
                block(operation)
            }
        } finally {
            activeConversationOperation.compareAndSet(operation, null)
        }
    }

    private suspend fun saveDraftWithinLock(content: String): RivenRuntimeResult {
        return if (flushDraftContentWithinLock(content)) {
            snapshotResult()
        } else {
            RivenRuntimeResult.Failure("Draft could not be saved.")
        }
    }

    private suspend fun flushDraftContentWithinLock(content: String): Boolean {
        val current = readDraft()
        if (current.content == content) return true
        return try {
            beforeDraftContentFlush()
            when (
                draftService.saveDraft(
                    SaveConversationDraftInput(
                        conversationId = CONVERSATION_ID,
                        content = content,
                        attachmentIds = current.attachmentIds,
                        expectedRevision = current.revision,
                        occurredAt = clock(),
                    ),
                )
            ) {
                is SaveConversationDraftResult.Saved -> true
                is SaveConversationDraftResult.Failure -> false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun isAttachmentLinkedToActiveConversation(attachmentId: String): Boolean {
        if (attachmentId in readDraft().attachmentIds) return true
        val active = activeTimelineOrNull() ?: return false
        return active.messages.any { message ->
            attachmentId in database.attachmentDao().attachmentIdsForMessage(message.id)
        }
    }

    private suspend fun ensureConversation() {
        if (activeTimelineOrNull() != null) return
        timeline.createConversationWithTimeline(
            CreateTimelineConversationInput(
                conversationId = CONVERSATION_ID,
                createdAt = clock(),
                updatedAt = clock(),
                status = ConversationStatus.ACTIVE,
                title = "Riven",
            ),
        )
    }

    private suspend fun snapshotResult(): RivenRuntimeResult = snapshotOrNull()?.let {
        RivenRuntimeResult.Success(it)
    } ?: RivenRuntimeResult.Failure("Riven's local conversation could not be loaded.")

    private suspend fun snapshotOrNull(): RivenRuntimeSnapshot? {
        val active = activeTimelineOrNull() ?: return null
        val draftValue = readDraft()
        val profiles = when (val result = profileService.allProfiles()) {
            is ProviderProfilesReadResult.Success -> result.profiles
            is ProviderProfilesReadResult.Failure -> emptyList()
        }
        val selected = selectedProfile(profiles)
        val credential = selected?.credentialSlotId?.let { slot ->
            withContext(ioDispatcher) { credentialStore.hasCredential(slot) }
        }
        val instructions = when (val result = instructionsService.snapshot()) {
            is ShaiSystemInstructionsReadResult.Success -> result.snapshot
            is ShaiSystemInstructionsReadResult.Failure -> ShaiSystemInstructionsService.DEFAULT_SNAPSHOT
        }
        val memories = withContext(ioDispatcher) {
            database.memoryDao().recentMemories(MAX_VISIBLE_MEMORIES).map { it.toRuntimeItem() }
        }
        val automaticMemoryStatus = withContext(ioDispatcher) { automaticMemoryQueue.status() }
        val roomState = (presence.snapshot() as? RivenPresenceReadResult.Success)?.snapshot ?: return null
        val imageSnapshot = withContext(ioDispatcher) {
            val draftImages = runtimeImages(draftValue.attachmentIds)
            val byMessage = linkedMapOf<String, List<RivenImageAttachment>>()
            active.messages.forEach { message ->
                byMessage[message.id] = runtimeImagesForMessage(message.id)
            }
            RuntimeImageSnapshot(draftImages, byMessage)
        }
        return RivenRuntimeSnapshot(
            conversationId = CONVERSATION_ID,
            timelineRevision = active.timelineRevision,
            messages = active.messages.map { message ->
                message.toRuntimeMessage(imageSnapshot.byMessage[message.id].orEmpty())
            },
            draft = draftValue.content,
            draftImages = imageSnapshot.draftImages,
            profiles = profiles.filter { it.adapterId == OpenRouterConversationAdapter.ADAPTER_ID },
            selectedProfileId = selected?.profileId,
            selectedProfileHasCredential = (credential as? HasProviderCredentialResult.Success)?.exists == true,
            instructions = instructions,
            memories = memories,
            automaticMemoryStatus = automaticMemoryStatus,
            roomState = roomState,
        )
    }

    private suspend fun activeTimelineOrNull(): TimelineReadResult.Success? =
        timeline.activeTimeline(CONVERSATION_ID) as? TimelineReadResult.Success

    private fun TimelineReadResult.Success.immediatelyPreviousUserHasImage(): Boolean {
        val previousUser = messages.lastOrNull { message ->
            message.role == MessageRole.USER &&
                message.deliveryState == MessageDeliveryState.PERSISTED
        } ?: return false
        return database.attachmentDao().attachmentIdsForMessage(previousUser.id).isNotEmpty()
    }

    private suspend fun selectedProfileOrNull(): ProviderProfileSnapshot? {
        val profiles = when (val result = profileService.allProfiles()) {
            is ProviderProfilesReadResult.Success -> result.profiles
            is ProviderProfilesReadResult.Failure -> return null
        }
        return selectedProfile(profiles)
    }

    private fun selectedProfile(profiles: List<ProviderProfileSnapshot>): ProviderProfileSnapshot? {
        val eligible = profiles.filter {
            it.isEnabled && it.adapterId == OpenRouterConversationAdapter.ADAPTER_ID
        }
        val selected = selectedProfileStore.selectedProfileId()
            ?.let { selectedId -> eligible.singleOrNull { it.profileId == selectedId } }
            ?: eligible.firstOrNull()
        if (selected?.profileId != selectedProfileStore.selectedProfileId()) {
            selectedProfileStore.select(selected?.profileId)
        }
        return selected
    }

    private suspend fun readDraft(): DraftValue = when (val result = draftService.readDraft(CONVERSATION_ID)) {
        is ReadConversationDraftResult.Draft -> DraftValue(
            result.snapshot.content,
            result.snapshot.attachmentIds,
            result.snapshot.revision,
        )
        is ReadConversationDraftResult.NoDraft -> DraftValue("", emptyList(), 0L)
        is ReadConversationDraftResult.Failure -> DraftValue("", emptyList(), 0L)
    }

    private fun imageCapability(profile: ProviderProfileSnapshot): OpenRouterImageInputCapability =
        resolvedImageCapability(
            profileId = profile.profileId,
            modelId = profile.modelId,
            legacyProfileCapabilities = profile.capabilities,
        )

    private fun resolvedImageCapability(
        profileId: String?,
        modelId: String,
        legacyProfileCapabilities: Set<ProviderCapability> = emptySet(),
    ): OpenRouterImageInputCapability {
        val catalogCapability = fetchedModels[modelId]?.imageInputCapability
            ?: imageCapabilityStore.readModel(modelId)?.imageInputCapability
        if (catalogCapability != null &&
            catalogCapability != OpenRouterImageInputCapability.UNKNOWN &&
            catalogCapability != OpenRouterImageInputCapability.USER_CONFIRMED_UNKNOWN
        ) {
            return catalogCapability
        }
        val profileCapability = profileId?.let { imageCapabilityStore.read(it, modelId) }
        if (profileCapability == OpenRouterImageInputCapability.USER_CONFIRMED_UNKNOWN) {
            return profileCapability
        }
        if (ProviderCapability.IMAGE_INPUT in legacyProfileCapabilities) {
            return OpenRouterImageInputCapability.SUPPORTED
        }
        return catalogCapability ?: profileCapability ?: OpenRouterImageInputCapability.UNKNOWN
    }

    private suspend fun existingImageAuthorization(
        canonicalImageIds: Map<String, List<String>>,
        profileId: String,
    ): ImageInputAuthorization? {
        if (canonicalImageIds.isEmpty()) return null
        val profile = (profileService.profile(profileId) as? ProviderProfileReadResult.Success)?.profile
            ?: return null
        return when (imageCapability(profile)) {
            OpenRouterImageInputCapability.SUPPORTED ->
                ImageInputAuthorization.MODEL_DECLARED_SUPPORTED
            OpenRouterImageInputCapability.USER_CONFIRMED_UNKNOWN ->
                ImageInputAuthorization.USER_CONFIRMED_UNKNOWN
            OpenRouterImageInputCapability.UNSUPPORTED,
            OpenRouterImageInputCapability.UNKNOWN,
            -> null
        }
    }

    private fun runtimeImagesForMessage(
        messageId: String,
    ): List<RivenImageAttachment> {
        val service = attachmentService ?: return emptyList()
        val metadata = service.orderedAvailableAttachmentsForMessage(messageId)
        val ids = (metadata as? com.shai.riven.data.attachment.MessageAttachmentsResult.Success)
            ?.attachments
            ?.map { it.attachmentId }
            .orEmpty()
        return runtimeImages(ids)
    }

    private fun runtimeImages(
        attachmentIds: List<String>,
    ): List<RivenImageAttachment> {
        val service = attachmentService ?: return emptyList()
        return attachmentIds.mapNotNull { attachmentId ->
            val metadata = (service.attachmentMetadata(attachmentId) as?
                AttachmentMetadataResult.Success)?.attachment ?: return@mapNotNull null
            if (metadata.kind != AttachmentKind.IMAGE ||
                metadata.state != AttachmentState.AVAILABLE ||
                metadata.byteSize == null || metadata.byteSize !in 1..MAX_IMPORTED_IMAGE_BYTES
            ) {
                return@mapNotNull null
            }
            val preview = thumbnailCache.peek(attachmentId)
            RivenImageAttachment(
                attachmentId = attachmentId,
                mimeType = metadata.mimeType,
                byteSize = metadata.byteSize,
                previewBytes = preview ?: ByteArray(0),
                previewState = when {
                    preview != null -> RivenImagePreviewState.READY
                    attachmentId in unavailableImagePreviewIds -> RivenImagePreviewState.UNAVAILABLE
                    else -> RivenImagePreviewState.DEFERRED
                },
            )
        }
    }

    private suspend fun manualMemoryResult(
        result: ManualMemoryIntentResult,
        operation: String,
    ): RivenRuntimeResult = when (result) {
        is ManualMemoryIntentResult.Remembered,
        is ManualMemoryIntentResult.Corrected,
        is ManualMemoryIntentResult.Forgotten,
        is ManualMemoryIntentResult.Deleted,
        -> snapshotResult()
        is ManualMemoryIntentResult.Failure -> RivenRuntimeResult.Failure(
            message = "$operation was not applied: ${result.error::class.java.simpleName}",
            snapshot = snapshotOrNull(),
        )
    }

    private fun MessageEntity.toRuntimeMessage(images: List<RivenImageAttachment>) = RivenChatMessage(
        id = id,
        role = role,
        deliveryState = deliveryState,
        content = content,
        providerModel = providerModel,
        images = images,
    )

    private fun MemoryEntity.toRuntimeItem() = RivenMemoryItem(
        id = id,
        meaning = meaning,
        certainty = certainty,
        isActive = retentionState.name == "ACTIVE" && truthState.name == "SUPPORTED" &&
            lifecycleState.name == "VALIDATED",
    )

    private fun ConversationEngineErrorCode.userMessage(): String = when (this) {
        ConversationEngineErrorCode.CREDENTIAL_MISSING -> "Add an OpenRouter API key in Settings."
        ConversationEngineErrorCode.CREDENTIAL_UNREADABLE -> "The saved API key cannot be read; replace it in Settings."
        ConversationEngineErrorCode.PROFILE_MISSING,
        ConversationEngineErrorCode.PROFILE_DISABLED,
        -> "Select an enabled OpenRouter profile in Settings."
        ConversationEngineErrorCode.PROVIDER_TIMEOUT -> "OpenRouter timed out. Your message is saved; retry when ready."
        ConversationEngineErrorCode.CANCELLED -> "Reply cancelled. Your message is still saved."
        ConversationEngineErrorCode.CONTEXT_STALE,
        ConversationEngineErrorCode.STALE_TIMELINE,
        -> "The conversation changed while Riven was replying. Nothing partial was saved."
        ConversationEngineErrorCode.CONTEXT_ASSEMBLY_FAILED ->
            "Riven's grounded context could not be assembled. No provider request was made."
        ConversationEngineErrorCode.CONTEXT_LIMIT_EXCEEDED ->
            "This turn cannot fit beside Riven's locked canon within the selected model's context limit. No provider request was made."
        else -> "Riven could not complete this reply. Your message is saved and can be retried."
    }

    private data class DraftValue(
        val content: String,
        val attachmentIds: List<String>,
        val revision: Long,
    )

    private data class RuntimeImageSnapshot(
        val draftImages: List<RivenImageAttachment>,
        val byMessage: Map<String, List<RivenImageAttachment>>,
    )

    private data class ActiveConversationOperation(
        val job: Job,
        val runId: AtomicReference<String?> = AtomicReference(null),
    )

    companion object {
        const val CONVERSATION_ID = "riven-primary-conversation"
        const val OPENROUTER_CREDENTIAL_SLOT = "openrouter-account-key"
        const val CONTINUE_MESSAGE = "Continue."
        const val MAX_VISIBLE_MEMORIES = 100
        const val AUTOMATIC_MEMORY_RECONCILIATION_LIMIT = 25
        val OPENROUTER_CAPABILITIES = listOf(
            ProviderCapability.TEXT_CHAT,
            ProviderCapability.STREAMING,
        )
        val RETRYABLE_RUN_STATES = setOf(
            ConversationRunState.FAILED,
            ConversationRunState.CANCELLED,
            ConversationRunState.STALE,
            ConversationRunState.INTERRUPTED,
        )

        fun fromContext(
            context: Context,
            httpClient: OpenRouterHttpClient =
                com.shai.riven.data.provider.openrouter.HttpUrlConnectionOpenRouterHttpClient(),
        ): RivenConversationRuntime {
            val appContext = context.applicationContext
            val databaseLease = RivenDatabaseProvider.acquire(appContext)
            return try {
                RivenConversationRuntime(
                    database = databaseLease.database,
                    credentialStore = ProviderCredentialStore.fromContext(appContext),
                    selectedProfileStore = AndroidSelectedProviderProfileStore(appContext),
                    backgroundScheduler = WorkManagerRivenBackgroundWorkScheduler(
                        WorkManager.getInstance(appContext),
                    ),
                    personalitySource = LockedRivenPersonalityContextSource(appContext),
                    httpClient = httpClient,
                    attachmentBlobStore = FileAttachmentBlobStore.fromContext(appContext),
                    attachmentThumbnailStore = FileAttachmentThumbnailStore.fromContext(appContext),
                    contentResolver = appContext.contentResolver,
                    imageCapabilityStore = AndroidImageInputCapabilityStore(appContext),
                    databaseLease = databaseLease,
                )
            } catch (failure: Exception) {
                databaseLease.close()
                throw failure
            }
        }
    }
}

private fun selectedImageInput(
    resolver: ContentResolver,
    uri: Uri,
    occurredAt: Long,
): SelectedImageInput? {
    val mimeType = try {
        resolver.getType(uri)
    } catch (_: SecurityException) {
        return null
    }
    val byteSize = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getLong(0).takeIf { it >= 0L }
        }
    } catch (_: Exception) {
        null
    }
    return SelectedImageInput(
        declaredMimeType = mimeType,
        declaredByteSize = byteSize,
        openStream = {
            resolver.openInputStream(uri)
                ?: throw IllegalStateException("Selected content has no readable stream")
        },
        occurredAt = occurredAt,
    )
}

private fun RivenPresenceSnapshot.toProviderStateControlResult() = ProviderStateControlResult.Applied(
    actualRoomId = actualRoom.stableId,
    semanticSpriteId = semanticSprite.stableId,
    presenceRevision = presenceRevision,
)

private fun ImageAttachmentImportError.userMessage(): String = when (this) {
    ImageAttachmentImportError.UnsupportedMimeType ->
        "Choose a JPEG, PNG, or WebP image."
    ImageAttachmentImportError.Oversized ->
        "That image is too large. Choose one smaller than 8 MB."
    ImageAttachmentImportError.CorruptOrUndecodable ->
        "That image is corrupt or could not be decoded."
    ImageAttachmentImportError.UnsafeDimensions ->
        "That image's dimensions are too large to process safely."
    is ImageAttachmentImportError.ReadFailure ->
        "The selected image could not be read."
    is ImageAttachmentImportError.StoreFailure ->
        "The selected image could not be stored privately."
}
