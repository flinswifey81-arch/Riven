package com.shai.riven.data.runtime

import android.content.Context
import androidx.core.content.edit
import androidx.work.WorkManager
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.background.WorkManagerRivenBackgroundWorkScheduler
import com.shai.riven.data.context.ActiveConversationContextSource
import com.shai.riven.data.context.ConversationInvariantContextSource
import com.shai.riven.data.context.ConversationalContextAssembler
import com.shai.riven.data.context.EphemeralAppStateContextSource
import com.shai.riven.data.context.EphemeralAppStateStore
import com.shai.riven.data.context.OpenLoopContextSource
import com.shai.riven.data.context.RivenContextSourceRegistry
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.conversation.engine.ConversationEngineErrorCode
import com.shai.riven.data.conversation.engine.ConversationEngineResult
import com.shai.riven.data.conversation.engine.ProviderAdapterRegistry
import com.shai.riven.data.conversation.engine.ProviderNeutralConversationEngine
import com.shai.riven.data.conversation.engine.StartConversationRunInput
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
import com.shai.riven.data.persistence.RivenDatabase
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
import com.shai.riven.data.provider.openrouter.OpenRouterHttpClient
import com.shai.riven.data.provider.openrouter.OpenRouterModel
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalog
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalogResult
import com.shai.riven.data.recall.ConversationalMemoryContextSource
import com.shai.riven.data.recall.TargetedConversationalMemoryRetriever
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RivenChatMessage(
    val id: String,
    val role: MessageRole,
    val deliveryState: MessageDeliveryState,
    val content: String,
    val providerModel: String?,
)

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
    val profiles: List<ProviderProfileSnapshot>,
    val selectedProfileId: String?,
    val selectedProfileHasCredential: Boolean,
    val instructions: ShaiSystemInstructionsSnapshot,
    val memories: List<RivenMemoryItem>,
)

interface RivenRuntimeController : AutoCloseable {
    suspend fun initialize(): RivenRuntimeResult
    suspend fun snapshot(): RivenRuntimeResult
    suspend fun saveDraft(content: String): RivenRuntimeResult
    suspend fun send(content: String, onDelta: suspend (String) -> Unit = {}): RivenRuntimeResult
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ownsDatabase: Boolean = false,
) : RivenRuntimeController {
    private val timeline = ConversationTimelineService(database)
    private val profileService = ProviderProfileService(database)
    private val instructionsService = ShaiSystemInstructionsService(database)
    private val ephemeralStore = EphemeralAppStateStore()
    private val recall = TargetedConversationalMemoryRetriever(database)
    private val draftService = ConversationDraftService(
        database = database,
        attachmentCleanupScheduler = backgroundScheduler::enqueueAttachmentCleanup,
    )
    private val memoryIntents = ManualMemoryIntentService(database, backgroundScheduler)
    private val adapter = OpenRouterConversationAdapter(httpClient)
    private val modelCatalog = OpenRouterModelCatalog(httpClient)
    private val engine = ProviderNeutralConversationEngine(
        database = database,
        contextAssembler = ConversationalContextAssembler(
            RivenContextSourceRegistry(
                listOf(
                    ConversationInvariantContextSource(),
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
        clock = clock,
    )
    private val actionMutex = Mutex()

    @Volatile
    private var activeRunId: String? = null

    override suspend fun initialize(): RivenRuntimeResult = actionMutex.withLock {
        engine.recoverInterruptedRuns()
        ensureConversation()
        snapshotResult()
    }

    override suspend fun snapshot(): RivenRuntimeResult = actionMutex.withLock { snapshotResult() }

    override suspend fun saveDraft(content: String): RivenRuntimeResult = actionMutex.withLock {
        ensureConversation()
        val current = readDraft()
        if (current.content == content) return@withLock snapshotResult()
        when (
            draftService.saveDraft(
                SaveConversationDraftInput(
                    conversationId = CONVERSATION_ID,
                    content = content,
                    attachmentIds = emptyList(),
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

    override suspend fun send(
        content: String,
        onDelta: suspend (String) -> Unit,
    ): RivenRuntimeResult = actionMutex.withLock {
        ensureConversation()
        val profileId = selectedProfileOrNull()?.profileId
            ?: return@withLock RivenRuntimeResult.Failure(
                message = "Configure and select an enabled OpenRouter profile before sending.",
                snapshot = snapshotOrNull(),
            )
        val saved = saveDraftWithinLock(content)
        if (saved is RivenRuntimeResult.Failure) return@withLock saved
        val timelineSnapshot = activeTimelineOrNull()
            ?: return@withLock RivenRuntimeResult.Failure("Conversation is unavailable.")
        val draft = readDraft()
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
            ?: return@withLock RivenRuntimeResult.Failure(
                message = "The message could not be committed.",
                snapshot = snapshotOrNull(),
            )
        executeRun(
            profileId = profileId,
            userMessageId = userMessageId,
            expectedTimelineRevision = revision,
            trigger = ConversationRunTrigger.INITIAL,
            onDelta = onDelta,
        )
    }

    override suspend fun retry(onDelta: suspend (String) -> Unit): RivenRuntimeResult = actionMutex.withLock {
        val profileId = selectedProfileOrNull()?.profileId
            ?: return@withLock RivenRuntimeResult.Failure("Select an enabled provider profile first.")
        val active = activeTimelineOrNull()
            ?: return@withLock RivenRuntimeResult.Failure("Conversation is unavailable.")
        val selectedHead = active.messages.lastOrNull()?.id
        val failed = withContext(ioDispatcher) {
            database.conversationRunDao().runsForConversation(CONVERSATION_ID).lastOrNull { run ->
                run.selectedHeadMessageId == selectedHead && run.state in RETRYABLE_RUN_STATES
            }
        } ?: return@withLock RivenRuntimeResult.Failure(
            message = "There is no failed or interrupted reply to retry.",
            snapshot = snapshotOrNull(),
        )
        executeRun(
            profileId = profileId,
            userMessageId = failed.userMessageId,
            expectedTimelineRevision = active.timelineRevision,
            trigger = ConversationRunTrigger.RETRY,
            retryOfRunId = failed.runId,
            onDelta = onDelta,
        )
    }

    override suspend fun regenerate(onDelta: suspend (String) -> Unit): RivenRuntimeResult = actionMutex.withLock {
        val profileId = selectedProfileOrNull()?.profileId
            ?: return@withLock RivenRuntimeResult.Failure("Select an enabled provider profile first.")
        val active = activeTimelineOrNull()
            ?: return@withLock RivenRuntimeResult.Failure("Conversation is unavailable.")
        val original = active.messages.lastOrNull()
        val user = active.messages.dropLast(1).lastOrNull()
        if (original?.role != MessageRole.ASSISTANT ||
            original.deliveryState != MessageDeliveryState.SUCCEEDED ||
            user?.role != MessageRole.USER
        ) {
            return@withLock RivenRuntimeResult.Failure(
                message = "Only the current successful Riven reply can be regenerated.",
                snapshot = snapshotOrNull(),
            )
        }
        executeRun(
            profileId = profileId,
            userMessageId = user.id,
            expectedTimelineRevision = active.timelineRevision,
            trigger = ConversationRunTrigger.REGENERATE,
            regenerateOfMessageId = original.id,
            onDelta = onDelta,
        )
    }

    override suspend fun continueConversation(onDelta: suspend (String) -> Unit): RivenRuntimeResult =
        send(CONTINUE_MESSAGE, onDelta)

    override suspend fun cancel(): RivenRuntimeResult {
        val runId = activeRunId
            ?: return RivenRuntimeResult.Failure(
                message = "There is no active reply to cancel.",
                snapshot = snapshotOrNull(),
            )
        engine.cancel(runId)
        return withContext(NonCancellable) { snapshotResult() }
    }

    override suspend fun saveProfile(
        profileId: String?,
        displayName: String,
        modelId: String,
        apiKey: String,
    ): RivenProfileSaveResult = actionMutex.withLock {
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
                    displayName = displayName.trim(),
                    adapterId = OpenRouterConversationAdapter.ADAPTER_ID,
                    endpointBaseUrl = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
                    modelId = modelId.trim(),
                    credentialSlotId = OPENROUTER_CREDENTIAL_SLOT,
                    capabilities = OPENROUTER_CAPABILITIES,
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
            val current = profileService.profile(profileId)
            if (current !is ProviderProfileReadResult.Success ||
                current.profile.adapterId != OpenRouterConversationAdapter.ADAPTER_ID
            ) {
                return@withLock RivenProfileSaveResult.Failure("The selected profile is unavailable.")
            }
            profileService.update(
                UpdateProviderProfileInput(
                    profileId = current.profile.profileId,
                    expectedRevision = current.profile.revision,
                    displayName = displayName.trim(),
                    adapterId = OpenRouterConversationAdapter.ADAPTER_ID,
                    endpointBaseUrl = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
                    modelId = modelId.trim(),
                    credentialSlotId = OPENROUTER_CREDENTIAL_SLOT,
                    isEnabled = true,
                    capabilities = OPENROUTER_CAPABILITIES,
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
        if (result is RivenProfileSaveResult.Success) selectedProfileStore.select(result.profile.profileId)
        result
    }

    override suspend fun selectProfile(profileId: String): RivenRuntimeResult = actionMutex.withLock {
        val profile = profileService.profile(profileId)
        if (profile !is ProviderProfileReadResult.Success || !profile.profile.isEnabled) {
            return@withLock RivenRuntimeResult.Failure("That provider profile is unavailable.")
        }
        selectedProfileStore.select(profileId)
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
            modelCatalog.models(credential.secret)
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
        engine.close()
        recall.close()
        if (ownsDatabase) database.close()
    }

    private suspend fun executeRun(
        profileId: String,
        userMessageId: String,
        expectedTimelineRevision: Long,
        trigger: ConversationRunTrigger,
        retryOfRunId: String? = null,
        regenerateOfMessageId: String? = null,
        onDelta: suspend (String) -> Unit,
    ): RivenRuntimeResult {
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
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
                ),
                onDelta = onDelta,
            )
            val snapshot = snapshotOrNull()
            when (result) {
                is ConversationEngineResult.Succeeded,
                is ConversationEngineResult.Existing,
                -> RivenRuntimeResult.Success(checkNotNull(snapshot), result)
                is ConversationEngineResult.Failed -> RivenRuntimeResult.Failure(
                    message = result.code.userMessage(),
                    engineCode = result.code,
                    snapshot = snapshot,
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
        } finally {
            activeRunId = null
        }
    }

    private suspend fun saveDraftWithinLock(content: String): RivenRuntimeResult {
        val current = readDraft()
        if (current.content == content) return snapshotResult()
        return when (
            draftService.saveDraft(
                SaveConversationDraftInput(
                    conversationId = CONVERSATION_ID,
                    content = content,
                    attachmentIds = emptyList(),
                    expectedRevision = current.revision,
                    occurredAt = clock(),
                ),
            )
        ) {
            is SaveConversationDraftResult.Saved -> snapshotResult()
            is SaveConversationDraftResult.Failure -> RivenRuntimeResult.Failure("Draft could not be saved.")
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
        return RivenRuntimeSnapshot(
            conversationId = CONVERSATION_ID,
            timelineRevision = active.timelineRevision,
            messages = active.messages.map { it.toRuntimeMessage() },
            draft = readDraft().content,
            profiles = profiles.filter { it.adapterId == OpenRouterConversationAdapter.ADAPTER_ID },
            selectedProfileId = selected?.profileId,
            selectedProfileHasCredential = (credential as? HasProviderCredentialResult.Success)?.exists == true,
            instructions = instructions,
            memories = memories,
        )
    }

    private suspend fun activeTimelineOrNull(): TimelineReadResult.Success? =
        timeline.activeTimeline(CONVERSATION_ID) as? TimelineReadResult.Success

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
        is ReadConversationDraftResult.Draft -> DraftValue(result.snapshot.content, result.snapshot.revision)
        is ReadConversationDraftResult.NoDraft -> DraftValue("", 0L)
        is ReadConversationDraftResult.Failure -> DraftValue("", 0L)
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

    private fun MessageEntity.toRuntimeMessage() = RivenChatMessage(
        id = id,
        role = role,
        deliveryState = deliveryState,
        content = content,
        providerModel = providerModel,
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
        else -> "Riven could not complete this reply. Your message is saved and can be retried."
    }

    private data class DraftValue(val content: String, val revision: Long)

    companion object {
        const val CONVERSATION_ID = "riven-primary-conversation"
        const val OPENROUTER_CREDENTIAL_SLOT = "openrouter-account-key"
        const val CONTINUE_MESSAGE = "Continue."
        const val MAX_VISIBLE_MEMORIES = 100
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
            return RivenConversationRuntime(
                database = RivenDatabase.build(appContext),
                credentialStore = ProviderCredentialStore.fromContext(appContext),
                selectedProfileStore = AndroidSelectedProviderProfileStore(appContext),
                backgroundScheduler = WorkManagerRivenBackgroundWorkScheduler(
                    WorkManager.getInstance(appContext),
                ),
                personalitySource = LockedRivenPersonalityContextSource(appContext),
                httpClient = httpClient,
                ownsDatabase = true,
            )
        }
    }
}
