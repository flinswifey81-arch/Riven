package com.shai.riven.data.conversation.engine

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.context.EphemeralAppStateStore
import com.shai.riven.data.context.RivenContextFreshnessReceipt
import com.shai.riven.data.context.RivenContextSnapshot
import com.shai.riven.data.experience.ConversationExperienceAbort
import com.shai.riven.data.experience.ConversationExperienceRecordResult
import com.shai.riven.data.experience.ConversationExperienceService
import com.shai.riven.data.instructions.ShaiSystemInstructionsService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConversationRunEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity
import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.persistence.model.ConversationRunTrigger
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.validation.StaleValidationRecallGenerationException
import com.shai.riven.data.validation.TARGETED_VALIDATION_RECALL_ALGORITHM_VERSION
import com.shai.riven.data.validation.ValidationRecallGeneration
import com.shai.riven.data.validation.validationRecallCorpusFence
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal sealed interface ReserveConversationRunResult {
    data class Reserved(
        val run: ConversationRunEntity,
        val userMessage: MessageEntity,
    ) : ReserveConversationRunResult

    data class Existing(val run: ConversationRunEntity) : ReserveConversationRunResult
    data class Failure(val code: ConversationEngineErrorCode) : ReserveConversationRunResult
}

internal sealed interface RunMutationResult {
    data class Updated(val run: ConversationRunEntity) : RunMutationResult
    data class Unchanged(val run: ConversationRunEntity) : RunMutationResult
    data object Missing : RunMutationResult
    data class Failure(val code: ConversationEngineErrorCode) : RunMutationResult
}

internal sealed interface CompleteConversationRunResult {
    data class Committed(
        val run: ConversationRunEntity,
        val timelineRevision: Long,
    ) : CompleteConversationRunResult

    data class AlreadyTerminal(val run: ConversationRunEntity) : CompleteConversationRunResult
    data class Rejected(val code: ConversationEngineErrorCode) : CompleteConversationRunResult
}

internal class ConversationRunPersistence(
    private val database: RivenDatabase,
    private val ephemeralStateStore: EphemeralAppStateStore,
    private val beforeFinalRoomTransaction: suspend () -> Unit = {},
) {
    private val runDao = database.conversationRunDao()
    private val timelineDao = database.conversationTimelineDao()
    private val attachmentDao = database.attachmentDao()
    private val profileDao = database.providerProfileDao()
    private val instructionsDao = database.shaiSystemInstructionsDao()
    private val experiences = ConversationExperienceService(database)

    suspend fun reserve(
        input: StartConversationRunInput,
        ownerSessionToken: String,
    ): ReserveConversationRunResult {
        val fingerprint = input.fingerprintOrNull()
            ?: return ReserveConversationRunResult.Failure(ConversationEngineErrorCode.INVALID_INPUT)
        return try {
            database.withTransaction {
                runDao.runByIdempotencyKey(input.idempotencyKey)?.let { existing ->
                    return@withTransaction existing.matchExisting(fingerprint)
                }
                runDao.run(input.runId)?.let { existing ->
                    return@withTransaction existing.matchExisting(fingerprint)
                }
                if (runDao.activeRun(input.conversationId) != null) {
                    return@withTransaction ReserveConversationRunResult.Failure(
                        ConversationEngineErrorCode.ACTIVE_RUN_EXISTS,
                    )
                }
                val conversation = timelineDao.conversation(input.conversationId)
                    ?: return@withTransaction ReserveConversationRunResult.Failure(
                        ConversationEngineErrorCode.MISSING_CONVERSATION,
                    )
                val head = timelineDao.timelineHead(input.conversationId)
                    ?: return@withTransaction ReserveConversationRunResult.Failure(
                        ConversationEngineErrorCode.MISSING_CONVERSATION,
                    )
                if (head.timelineRevision != input.expectedTimelineRevision ||
                    head.timelineRevision == Long.MAX_VALUE
                ) {
                    return@withTransaction ReserveConversationRunResult.Failure(
                        ConversationEngineErrorCode.STALE_TIMELINE,
                    )
                }
                val userMessage = timelineDao.message(input.userMessageId)
                    ?: return@withTransaction ReserveConversationRunResult.Failure(
                        ConversationEngineErrorCode.MISSING_MESSAGE,
                    )
                if (userMessage.conversationId != input.conversationId ||
                    userMessage.role != MessageRole.USER ||
                    userMessage.deliveryState != MessageDeliveryState.PERSISTED
                ) {
                    return@withTransaction ReserveConversationRunResult.Failure(
                        ConversationEngineErrorCode.INVALID_USER_MESSAGE,
                    )
                }
                if (timelineDao.message(input.assistantMessageId) != null) {
                    return@withTransaction ReserveConversationRunResult.Failure(
                        ConversationEngineErrorCode.IDEMPOTENCY_MISMATCH,
                    )
                }
                val contextHead = when (input.trigger) {
                    ConversationRunTrigger.INITIAL -> {
                        if (input.retryOfRunId != null || input.regenerateOfMessageId != null ||
                            head.activeHeadMessageId != userMessage.id
                        ) {
                            return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_USER_MESSAGE,
                            )
                        }
                        userMessage.id
                    }

                    ConversationRunTrigger.RETRY -> {
                        if (input.regenerateOfMessageId != null) {
                            return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_RETRY,
                            )
                        }
                        val previous = input.retryOfRunId?.let(runDao::run)
                            ?: return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_RETRY,
                            )
                        if (!previous.state.isRetryable() ||
                            previous.conversationId != input.conversationId ||
                            previous.userMessageId != input.userMessageId ||
                            previous.selectedHeadMessageId != head.activeHeadMessageId
                        ) {
                            return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_RETRY,
                            )
                        }
                        previous.contextHeadMessageId
                    }

                    ConversationRunTrigger.REGENERATE -> {
                        if (input.retryOfRunId != null) {
                            return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_REGENERATION,
                            )
                        }
                        val originalId = input.regenerateOfMessageId
                            ?: return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_REGENERATION,
                            )
                        val original = timelineDao.message(originalId)
                            ?: return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_REGENERATION,
                            )
                        val parentId = timelineDao.parentEdge(originalId)?.parentMessageId
                            ?: return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_REGENERATION,
                            )
                        if (head.activeHeadMessageId != original.id ||
                            original.role != MessageRole.ASSISTANT ||
                            original.deliveryState != MessageDeliveryState.SUCCEEDED ||
                            parentId != userMessage.id
                        ) {
                            return@withTransaction ReserveConversationRunResult.Failure(
                                ConversationEngineErrorCode.INVALID_REGENERATION,
                            )
                        }
                        userMessage.id
                    }
                }

                when (experiences.recordMessageInCurrentTransaction(userMessage, input.occurredAt)) {
                    is ConversationExperienceRecordResult.Created,
                    is ConversationExperienceRecordResult.AlreadyRecorded,
                    -> Unit
                    is ConversationExperienceRecordResult.NotEligible ->
                        return@withTransaction ReserveConversationRunResult.Failure(
                            ConversationEngineErrorCode.INVALID_USER_MESSAGE,
                        )
                }

                val nextSequence = timelineDao.maximumSequenceNumber(input.conversationId)
                    ?.takeIf { it != Long.MAX_VALUE }
                    ?.plus(1L)
                    ?: if (timelineDao.maximumSequenceNumber(input.conversationId) == null) 1L else {
                        return@withTransaction ReserveConversationRunResult.Failure(
                            ConversationEngineErrorCode.STORAGE_FAILURE,
                        )
                    }
                val pendingAssistant = MessageEntity(
                    id = input.assistantMessageId,
                    conversationId = input.conversationId,
                    sequenceNumber = nextSequence,
                    role = MessageRole.ASSISTANT,
                    deliveryState = MessageDeliveryState.PENDING,
                    content = "",
                    createdAt = input.occurredAt,
                    updatedAt = input.occurredAt,
                )
                timelineDao.insertMessage(pendingAssistant)
                timelineDao.insertParentEdge(
                    MessageParentEdgeEntity(
                        childMessageId = pendingAssistant.id,
                        parentMessageId = userMessage.id,
                        createdAt = input.occurredAt,
                    ),
                )
                val reservedRevision = head.timelineRevision + 1L
                timelineDao.updateTimelineHead(
                    head.copy(
                        timelineRevision = reservedRevision,
                        updatedAt = input.occurredAt,
                    ),
                )
                timelineDao.updateConversation(conversation.copy(updatedAt = input.occurredAt))
                val run = ConversationRunEntity(
                    runId = input.runId,
                    conversationId = input.conversationId,
                    userMessageId = input.userMessageId,
                    assistantMessageId = input.assistantMessageId,
                    trigger = input.trigger,
                    retryOfRunId = input.retryOfRunId,
                    regenerateOfMessageId = input.regenerateOfMessageId,
                    state = ConversationRunState.PREPARING,
                    activeConversationId = input.conversationId,
                    idempotencyKey = input.idempotencyKey,
                    inputFingerprint = fingerprint,
                    ownerSessionToken = ownerSessionToken,
                    profileId = input.profileId,
                    selectedHeadMessageId = head.activeHeadMessageId,
                    contextHeadMessageId = contextHead,
                    reservedTimelineRevision = reservedRevision,
                    createdAt = input.occurredAt,
                    updatedAt = input.occurredAt,
                )
                runDao.insert(run)
                ReserveConversationRunResult.Reserved(run, userMessage)
            }
        } catch (abort: ConversationExperienceAbort) {
            ReserveConversationRunResult.Failure(ConversationEngineErrorCode.STORAGE_FAILURE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteConstraintException) {
            resolveReservationConstraint(input, fingerprint)
        } catch (_: Exception) {
            ReserveConversationRunResult.Failure(ConversationEngineErrorCode.STORAGE_FAILURE)
        }
    }

    suspend fun attachProfile(
        runId: String,
        ownerSessionToken: String,
        profileId: String,
        profileRevision: Long,
        adapterId: String,
        endpointBaseUrl: String,
        modelId: String,
        occurredAt: Long,
    ): RunMutationResult = mutate(runId) { run ->
        if (run.ownerSessionToken != ownerSessionToken || run.profileId != profileId ||
            run.state != ConversationRunState.PREPARING
        ) {
            return@mutate null
        }
        run.copy(
            profileRevision = profileRevision,
            adapterId = adapterId,
            endpointBaseUrl = endpointBaseUrl,
            modelId = modelId,
            updatedAt = occurredAt,
        )
    }

    suspend fun markAwaitingProvider(
        runId: String,
        ownerSessionToken: String,
        occurredAt: Long,
    ): RunMutationResult = mutate(runId) { run ->
        if (run.ownerSessionToken != ownerSessionToken || run.state != ConversationRunState.PREPARING) {
            return@mutate null
        }
        run.copy(
            state = ConversationRunState.AWAITING_PROVIDER,
            startedAt = occurredAt,
            updatedAt = occurredAt,
        )
    }

    suspend fun markStreaming(
        runId: String,
        ownerSessionToken: String,
        occurredAt: Long,
    ): RunMutationResult = mutate(runId) { run ->
        if (run.ownerSessionToken != ownerSessionToken ||
            run.state !in setOf(ConversationRunState.AWAITING_PROVIDER, ConversationRunState.STREAMING)
        ) {
            return@mutate null
        }
        if (run.state == ConversationRunState.STREAMING) run else run.copy(
            state = ConversationRunState.STREAMING,
            updatedAt = occurredAt,
        )
    }

    suspend fun requestCancellation(runId: String, occurredAt: Long): RunMutationResult =
        mutate(runId) { run ->
            if (run.state.isTerminal()) return@mutate run
            if (run.state == ConversationRunState.CANCEL_REQUESTED) return@mutate run
            run.copy(
                state = ConversationRunState.CANCEL_REQUESTED,
                updatedAt = occurredAt,
            )
        }

    suspend fun finishWithoutContent(
        runId: String,
        ownerSessionToken: String?,
        state: ConversationRunState,
        errorCode: ConversationEngineErrorCode,
        providerRequestId: String?,
        occurredAt: Long,
    ): RunMutationResult {
        require(state in setOf(
            ConversationRunState.FAILED,
            ConversationRunState.CANCELLED,
            ConversationRunState.STALE,
            ConversationRunState.INTERRUPTED,
        ))
        if (providerRequestId != null && providerRequestId.length > MAX_PROVIDER_REQUEST_ID_CHARS) {
            return RunMutationResult.Failure(ConversationEngineErrorCode.PROVIDER_PROTOCOL)
        }
        return try {
            database.withTransaction {
                val run = runDao.run(runId) ?: return@withTransaction RunMutationResult.Missing
                if (ownerSessionToken != null && run.ownerSessionToken != ownerSessionToken) {
                    return@withTransaction RunMutationResult.Failure(ConversationEngineErrorCode.STALE_TIMELINE)
                }
                if (run.state.isTerminal()) return@withTransaction RunMutationResult.Unchanged(run)
                val message = timelineDao.message(run.assistantMessageId)
                    ?: return@withTransaction RunMutationResult.Missing
                if (message.deliveryState != MessageDeliveryState.PENDING) {
                    return@withTransaction RunMutationResult.Failure(ConversationEngineErrorCode.STALE_TIMELINE)
                }
                val head = timelineDao.timelineHead(run.conversationId)
                    ?: return@withTransaction RunMutationResult.Failure(
                        ConversationEngineErrorCode.MISSING_CONVERSATION,
                    )
                val conversation = timelineDao.conversation(run.conversationId)
                    ?: return@withTransaction RunMutationResult.Failure(
                        ConversationEngineErrorCode.MISSING_CONVERSATION,
                    )
                if (head.timelineRevision == Long.MAX_VALUE) {
                    return@withTransaction RunMutationResult.Failure(ConversationEngineErrorCode.STALE_TIMELINE)
                }
                val delivery = if (state == ConversationRunState.FAILED) {
                    MessageDeliveryState.FAILED
                } else {
                    MessageDeliveryState.CANCELLED
                }
                check(
                    timelineDao.updateMessage(
                        message.copy(
                            deliveryState = delivery,
                            updatedAt = occurredAt,
                            providerName = run.adapterId,
                            providerModel = run.modelId,
                            providerRequestId = providerRequestId,
                            errorCode = errorCode.name,
                        ),
                    ) == 1,
                )
                val nextRevision = head.timelineRevision + 1L
                timelineDao.updateTimelineHead(
                    head.copy(timelineRevision = nextRevision, updatedAt = occurredAt),
                )
                timelineDao.updateConversation(conversation.copy(updatedAt = occurredAt))
                val terminal = run.copy(
                    state = state,
                    activeConversationId = null,
                    providerRequestId = providerRequestId,
                    errorCode = errorCode.name,
                    updatedAt = occurredAt,
                    finishedAt = occurredAt,
                )
                check(runDao.update(terminal) == 1)
                RunMutationResult.Updated(terminal)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RunMutationResult.Failure(ConversationEngineErrorCode.STORAGE_FAILURE)
        }
    }

    suspend fun completeSuccess(
        runId: String,
        ownerSessionToken: String,
        contextSnapshot: RivenContextSnapshot,
        content: String,
        providerRequestId: String?,
        occurredAt: Long,
        eligibilityClock: () -> Long,
    ): CompleteConversationRunResult {
        if (content.isBlank() || providerRequestId?.length ?: 0 > MAX_PROVIDER_REQUEST_ID_CHARS) {
            return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.PROVIDER_PROTOCOL)
        }
        val recallReceipts = contextSnapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.ConversationalRecall>()
        val ephemeralReceipts = contextSnapshot.freshnessReceipts
            .filterIsInstance<RivenContextFreshnessReceipt.EphemeralAppState>()
        if (recallReceipts.size > 1 || ephemeralReceipts.size > 1) {
            return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.CONTEXT_STALE)
        }
        val commit: suspend () -> CompleteConversationRunResult = {
            beforeFinalRoomTransaction()
            database.withTransaction {
                ephemeralStateStore.withFreshnessGuard(
                    receipt = ephemeralReceipts.singleOrNull(),
                    now = eligibilityClock(),
                ) {
                    completeSuccessInCurrentTransaction(
                        runId = runId,
                        ownerSessionToken = ownerSessionToken,
                        contextSnapshot = contextSnapshot,
                        content = content,
                        providerRequestId = providerRequestId,
                        occurredAt = occurredAt,
                    )
                } ?: CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.CONTEXT_STALE)
            }
        }
        return try {
            val recall = recallReceipts.singleOrNull()
            if (recall == null) {
                commit()
            } else {
                database.validationRecallCorpusFence().withStableGeneration(
                    ValidationRecallGeneration(
                        databaseSessionId = recall.databaseSessionId,
                        corpusGeneration = recall.corpusGeneration,
                        algorithmVersion = TARGETED_VALIDATION_RECALL_ALGORITHM_VERSION,
                    ),
                ) { commit() }
            }
        } catch (_: StaleValidationRecallGenerationException) {
            CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.CONTEXT_STALE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.STORAGE_FAILURE)
        }
    }

    suspend fun recoverInterrupted(occurredAt: Long): ConversationRunRecoveryResult {
        val interrupted = mutableListOf<String>()
        runDao.activeRuns().forEach { run ->
            if (!ConversationEngineOwnerRegistry.isLive(run.ownerSessionToken)) {
                when (
                    finishWithoutContent(
                        runId = run.runId,
                        ownerSessionToken = null,
                        state = ConversationRunState.INTERRUPTED,
                        errorCode = ConversationEngineErrorCode.INTERRUPTED,
                        providerRequestId = run.providerRequestId,
                        occurredAt = occurredAt,
                    )
                ) {
                    is RunMutationResult.Updated -> interrupted += run.runId
                    else -> Unit
                }
            }
        }
        return ConversationRunRecoveryResult(interrupted.sorted())
    }

    fun run(runId: String): ConversationRunEntity? = runDao.run(runId)

    fun attachmentIdsForMessage(messageId: String): List<String> =
        attachmentDao.attachmentIdsForMessage(messageId)

    /** Coherent Room snapshot used as the final suspending pre-dispatch read. */
    suspend fun roomReceiptsCurrent(snapshot: RivenContextSnapshot): Boolean = try {
        database.withTransaction { roomReceiptsCurrentInCurrentTransaction(snapshot) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun completeSuccessInCurrentTransaction(
        runId: String,
        ownerSessionToken: String,
        contextSnapshot: RivenContextSnapshot,
        content: String,
        providerRequestId: String?,
        occurredAt: Long,
    ): CompleteConversationRunResult {
        val run = runDao.run(runId)
            ?: return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.STALE_TIMELINE)
        if (run.state == ConversationRunState.SUCCEEDED) {
            return CompleteConversationRunResult.AlreadyTerminal(run)
        }
        if (run.ownerSessionToken != ownerSessionToken ||
            run.state !in setOf(ConversationRunState.AWAITING_PROVIDER, ConversationRunState.STREAMING)
        ) {
            return CompleteConversationRunResult.Rejected(
                if (run.state == ConversationRunState.CANCEL_REQUESTED) {
                    ConversationEngineErrorCode.CANCELLED
                } else {
                    ConversationEngineErrorCode.STALE_TIMELINE
                },
            )
        }
        if (!roomReceiptsCurrentInCurrentTransaction(contextSnapshot)) {
            return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.CONTEXT_STALE)
        }
        val head = timelineDao.timelineHead(run.conversationId)
            ?: return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.MISSING_CONVERSATION)
        if (head.timelineRevision != run.reservedTimelineRevision ||
            head.activeHeadMessageId != run.selectedHeadMessageId ||
            head.timelineRevision == Long.MAX_VALUE
        ) {
            return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.STALE_TIMELINE)
        }
        val conversation = timelineDao.conversation(run.conversationId)
            ?: return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.MISSING_CONVERSATION)
        val message = timelineDao.message(run.assistantMessageId)
            ?: return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.STALE_TIMELINE)
        if (message.deliveryState != MessageDeliveryState.PENDING || message.content.isNotEmpty()) {
            return CompleteConversationRunResult.Rejected(ConversationEngineErrorCode.STALE_TIMELINE)
        }
        val succeededMessage = message.copy(
            deliveryState = MessageDeliveryState.SUCCEEDED,
            content = content,
            updatedAt = occurredAt,
            providerName = run.adapterId,
            providerModel = run.modelId,
            providerRequestId = providerRequestId,
            errorCode = null,
        )
        check(timelineDao.updateMessage(succeededMessage) == 1)
        val nextRevision = head.timelineRevision + 1L
        timelineDao.updateTimelineHead(
            head.copy(
                activeHeadMessageId = succeededMessage.id,
                timelineRevision = nextRevision,
                updatedAt = occurredAt,
            ),
        )
        timelineDao.updateConversation(conversation.copy(updatedAt = occurredAt))
        when (experiences.recordMessageInCurrentTransaction(succeededMessage, occurredAt)) {
            is ConversationExperienceRecordResult.Created -> Unit
            is ConversationExperienceRecordResult.AlreadyRecorded -> Unit
            is ConversationExperienceRecordResult.NotEligible ->
                error("Succeeded assistant message was not eligible for canonical experience")
        }
        val succeededRun = run.copy(
            state = ConversationRunState.SUCCEEDED,
            activeConversationId = null,
            providerRequestId = providerRequestId,
            errorCode = null,
            updatedAt = occurredAt,
            finishedAt = occurredAt,
        )
        check(runDao.update(succeededRun) == 1)
        return CompleteConversationRunResult.Committed(succeededRun, nextRevision)
    }

    private fun roomReceiptsCurrentInCurrentTransaction(snapshot: RivenContextSnapshot): Boolean {
        for (receipt in snapshot.freshnessReceipts) {
            when (receipt) {
                is RivenContextFreshnessReceipt.ActiveConversation -> {
                    val head = timelineDao.timelineHead(receipt.conversationId) ?: return false
                    if (head.timelineRevision != receipt.timelineRevision) return false
                }

                is RivenContextFreshnessReceipt.ShaiSystemInstructions -> {
                    val stored = instructionsDao.instructions(ShaiSystemInstructionsService.PRIMARY_INSTRUCTION_ID)
                    val revision = stored?.revision ?: 0L
                    val enabled = stored?.isEnabled ?: false
                    if (revision != receipt.revision || enabled != receipt.isEnabled) return false
                }

                is RivenContextFreshnessReceipt.ProviderProfile -> {
                    val profile = profileDao.profile(receipt.profileId) ?: return false
                    val capabilities = profileDao.capabilities(receipt.profileId)
                        .sortedBy { it.name }
                        .toCollection(linkedSetOf())
                    if (!profile.isEnabled || profile.revision != receipt.revision ||
                        profile.adapterId != receipt.adapterId ||
                        profile.endpointBaseUrl != receipt.endpointBaseUrl ||
                        profile.modelId != receipt.modelId || capabilities != receipt.capabilities
                    ) {
                        return false
                    }
                }

                is RivenContextFreshnessReceipt.ConversationalRecall,
                is RivenContextFreshnessReceipt.EphemeralAppState,
                -> Unit
            }
        }
        return true
    }

    private suspend fun mutate(
        runId: String,
        transform: (ConversationRunEntity) -> ConversationRunEntity?,
    ): RunMutationResult = try {
        database.withTransaction {
            val current = runDao.run(runId) ?: return@withTransaction RunMutationResult.Missing
            val replacement = transform(current)
                ?: return@withTransaction RunMutationResult.Failure(ConversationEngineErrorCode.STALE_TIMELINE)
            if (replacement == current) return@withTransaction RunMutationResult.Unchanged(current)
            check(runDao.update(replacement) == 1)
            RunMutationResult.Updated(replacement)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        RunMutationResult.Failure(ConversationEngineErrorCode.STORAGE_FAILURE)
    }

    private fun ConversationRunEntity.matchExisting(fingerprint: String): ReserveConversationRunResult =
        if (inputFingerprint == fingerprint) {
            ReserveConversationRunResult.Existing(this)
        } else {
            ReserveConversationRunResult.Failure(ConversationEngineErrorCode.IDEMPOTENCY_MISMATCH)
        }

    private fun resolveReservationConstraint(
        input: StartConversationRunInput,
        fingerprint: String,
    ): ReserveConversationRunResult {
        val existing = runDao.runByIdempotencyKey(input.idempotencyKey) ?: runDao.run(input.runId)
        return existing?.matchExisting(fingerprint)
            ?: ReserveConversationRunResult.Failure(ConversationEngineErrorCode.ACTIVE_RUN_EXISTS)
    }

    private fun StartConversationRunInput.fingerprintOrNull(): String? {
        if (listOf(runId, idempotencyKey, conversationId, userMessageId, assistantMessageId, profileId)
                .any { it.isBlank() || it.length > MAX_IDENTIFIER_CHARS } ||
            expectedTimelineRevision < 0L ||
            (trigger == ConversationRunTrigger.RETRY) != (retryOfRunId != null) ||
            (trigger == ConversationRunTrigger.REGENERATE) != (regenerateOfMessageId != null)
        ) {
            return null
        }
        val material = listOf(
            runId,
            idempotencyKey,
            conversationId,
            userMessageId,
            assistantMessageId,
            profileId,
            trigger.name,
            retryOfRunId.orEmpty(),
            regenerateOfMessageId.orEmpty(),
            expectedTimelineRevision.toString(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun ConversationRunState.isRetryable(): Boolean = this in setOf(
        ConversationRunState.FAILED,
        ConversationRunState.CANCELLED,
        ConversationRunState.STALE,
        ConversationRunState.INTERRUPTED,
    )

    private fun ConversationRunState.isTerminal(): Boolean = this in setOf(
        ConversationRunState.SUCCEEDED,
        ConversationRunState.FAILED,
        ConversationRunState.CANCELLED,
        ConversationRunState.STALE,
        ConversationRunState.INTERRUPTED,
    )

    private companion object {
        const val MAX_IDENTIFIER_CHARS = 200
        const val MAX_PROVIDER_REQUEST_ID_CHARS = 1_024
    }
}

internal fun ConversationRunEntity.toSnapshot() = ConversationRunSnapshot(
    runId = runId,
    conversationId = conversationId,
    userMessageId = userMessageId,
    assistantMessageId = assistantMessageId,
    trigger = trigger,
    retryOfRunId = retryOfRunId,
    regenerateOfMessageId = regenerateOfMessageId,
    state = state,
    profileId = profileId,
    profileRevision = profileRevision,
    adapterId = adapterId,
    modelId = modelId,
    selectedHeadMessageId = selectedHeadMessageId,
    contextHeadMessageId = contextHeadMessageId,
    reservedTimelineRevision = reservedTimelineRevision,
    providerRequestId = providerRequestId,
    errorCode = errorCode,
    createdAt = createdAt,
    updatedAt = updatedAt,
    finishedAt = finishedAt,
)
