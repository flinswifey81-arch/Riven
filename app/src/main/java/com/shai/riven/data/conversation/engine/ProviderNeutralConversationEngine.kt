package com.shai.riven.data.conversation.engine

import com.shai.riven.data.context.ActiveConversationReceiptValidator
import com.shai.riven.data.context.ActiveConversationContextSource
import com.shai.riven.data.context.ConversationalContextAssembler
import com.shai.riven.data.context.ConversationalContextAssemblyInput
import com.shai.riven.data.context.ConversationalContextFreshnessValidator
import com.shai.riven.data.context.DEFAULT_CONVERSATIONAL_CONTEXT_BUDGET
import com.shai.riven.data.context.EphemeralAppStateReceiptValidator
import com.shai.riven.data.context.EphemeralAppStateStore
import com.shai.riven.data.context.ProviderProfileReceiptValidator
import com.shai.riven.data.context.RivenContextCollectionResult
import com.shai.riven.data.context.RivenContextCollectionBudget
import com.shai.riven.data.context.RivenContextContractViolation
import com.shai.riven.data.context.RivenContextFailureCause
import com.shai.riven.data.context.RivenContextContentAuthority
import com.shai.riven.data.context.RivenContextFreshnessReceipt
import com.shai.riven.data.context.RivenContextFreshnessValidation
import com.shai.riven.data.context.RivenContextSnapshot
import com.shai.riven.data.context.RivenConversationContextRequest
import com.shai.riven.data.context.RivenCurrentInteraction
import com.shai.riven.data.context.ShaiSystemInstructionsReceiptValidator
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.instructions.ShaiSystemInstructionsReadResult
import com.shai.riven.data.instructions.ShaiSystemInstructionsService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConversationRunEntity
import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.provider.ProviderCapability
import com.shai.riven.data.provider.ProviderProfileReadResult
import com.shai.riven.data.provider.ProviderProfileService
import com.shai.riven.data.provider.ProviderProfileSnapshot
import com.shai.riven.data.provider.ProviderRuntimeProfileError
import com.shai.riven.data.provider.ProviderRuntimeProfileResolver
import com.shai.riven.data.provider.ResolveProviderRuntimeProfileResult
import com.shai.riven.data.recall.ConversationalRecallReceiptValidator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ProviderNeutralConversationEngine(
    database: RivenDatabase,
    private val contextAssembler: ConversationalContextAssembler,
    recallValidator: ConversationalRecallReceiptValidator,
    private val profileService: ProviderProfileService,
    private val profileResolver: ProviderRuntimeProfileResolver,
    private val instructionsService: ShaiSystemInstructionsService,
    private val ephemeralStateStore: EphemeralAppStateStore,
    private val adapterRegistry: ProviderAdapterRegistry,
    private val contextBudgetResolver: (ProviderProfileSnapshot) -> RivenContextCollectionBudget = {
        DEFAULT_CONVERSATIONAL_CONTEXT_BUDGET
    },
    private val limits: ConversationEngineLimits = ConversationEngineLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val limiter: ConversationRunLimiter = ConversationRunLimiterPool.forMaximum(limits.maxConcurrentRuns),
    profileReceiptValidator: ProviderProfileReceiptValidator? = null,
    beforeFinalRoomTransaction: suspend () -> Unit = {},
    ownerSessionToken: String = ConversationEngineOwnerRegistry.newToken(),
) : AutoCloseable {
    private val ownerSessionToken = ownerSessionToken
    private val persistence = ConversationRunPersistence(
        database = database,
        ephemeralStateStore = ephemeralStateStore,
        beforeFinalRoomTransaction = beforeFinalRoomTransaction,
    )
    private val activeCalls = ConcurrentHashMap<String, ActiveCall>()
    private val freshnessValidator = ConversationalContextFreshnessValidator(
        activeConversationValidator = ActiveConversationReceiptValidator { receipt ->
            val read = ConversationTimelineService(database).activeTimelineTail(receipt.conversationId, 1)
            read is TimelineReadResult.Success && read.timelineRevision == receipt.timelineRevision
        },
        recallValidator = recallValidator,
        instructionsValidator = ShaiSystemInstructionsReceiptValidator { receipt ->
            when (val result = instructionsService.snapshot()) {
                is ShaiSystemInstructionsReadResult.Success ->
                    result.snapshot.revision == receipt.revision &&
                        result.snapshot.isEnabled == receipt.isEnabled
                is ShaiSystemInstructionsReadResult.Failure -> false
            }
        },
        profileValidator = profileReceiptValidator ?: ProviderProfileReceiptValidator { receipt ->
            when (val result = profileService.profile(receipt.profileId)) {
                is ProviderProfileReadResult.Success -> result.profile.let { profile ->
                    profile.isEnabled &&
                        profile.revision == receipt.revision &&
                        profile.adapterId == receipt.adapterId &&
                        profile.endpointBaseUrl == receipt.endpointBaseUrl &&
                        profile.modelId == receipt.modelId &&
                        profile.capabilities == receipt.capabilities
                }
                is ProviderProfileReadResult.Failure -> false
            }
        },
        ephemeralValidator = EphemeralAppStateReceiptValidator(ephemeralStateStore::isCurrent),
    )
    private val closed = AtomicBoolean(false)

    init {
        ConversationEngineOwnerRegistry.register(ownerSessionToken)
    }

    suspend fun execute(
        input: StartConversationRunInput,
        onDelta: suspend (String) -> Unit = {},
    ): ConversationEngineResult {
        check(!closed.get()) { "Conversation engine is closed" }
        var reserved: ConversationRunEntity? = null
        try {
            val reservation = withContext(NonCancellable) {
                persistence.reserve(input, ownerSessionToken)
            }
            if (reservation is ReserveConversationRunResult.Reserved) reserved = reservation.run
            coroutineContext.ensureActive()
            return when (reservation) {
                is ReserveConversationRunResult.Existing -> ConversationEngineResult.Existing(
                    reservation.run.toSnapshot(),
                )
                is ReserveConversationRunResult.Failure -> ConversationEngineResult.Failed(null, reservation.code)
                is ReserveConversationRunResult.Reserved -> {
                    val limited = limiter.withPermitOrNull(limits.concurrencyWaitMillis) {
                        executeReserved(reservation.run, reservation.userMessage.content, onDelta)
                    }
                    limited ?: failRun(
                        reservation.run,
                        ConversationEngineErrorCode.CONCURRENCY_LIMIT,
                        ConversationRunState.FAILED,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            reserved?.let { settleCancellation(it) }
            throw cancelled
        }
    }

    suspend fun cancel(runId: String): ConversationRunControlResult {
        val requested = persistence.requestCancellation(runId, clock())
        val run = when (requested) {
            is RunMutationResult.Updated -> requested.run
            is RunMutationResult.Unchanged -> requested.run
            RunMutationResult.Missing -> return ConversationRunControlResult.Missing
            is RunMutationResult.Failure -> return ConversationRunControlResult.Failure(requested.code)
        }
        if (run.state.isTerminal()) return ConversationRunControlResult.Unchanged(run.toSnapshot())
        val call = activeCalls[runId]
        call?.cancelRequested?.set(true)
        call?.job?.cancel(CancellationException("Conversation run cancelled"))
        val settled = withContext(NonCancellable) {
            persistence.finishWithoutContent(
                runId = runId,
                ownerSessionToken = run.ownerSessionToken,
                state = ConversationRunState.CANCELLED,
                errorCode = ConversationEngineErrorCode.CANCELLED,
                providerRequestId = run.providerRequestId,
                occurredAt = clock(),
            )
        }
        return settled.toControlResult()
    }

    suspend fun recoverInterruptedRuns(): ConversationRunRecoveryResult =
        persistence.recoverInterrupted(clock())

    fun run(runId: String): ConversationRunSnapshot? = persistence.run(runId)?.toSnapshot()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ConversationEngineOwnerRegistry.unregister(ownerSessionToken)
        }
    }

    private suspend fun executeReserved(
        reserved: ConversationRunEntity,
        userContent: String,
        onDelta: suspend (String) -> Unit,
    ): ConversationEngineResult {
        val job = coroutineContext[Job]
            ?: return failRun(reserved, ConversationEngineErrorCode.STORAGE_FAILURE, ConversationRunState.FAILED)
        val call = ActiveCall(job)
        if (activeCalls.putIfAbsent(reserved.runId, call) != null) {
            return ConversationEngineResult.Existing(reserved.toSnapshot())
        }
        try {
            if (persistence.attachmentIdsForMessage(reserved.userMessageId).isNotEmpty()) {
                return failRun(
                    reserved,
                    ConversationEngineErrorCode.ATTACHMENTS_UNSUPPORTED,
                    ConversationRunState.FAILED,
                )
            }
            val requiredCapabilities = setOf(
                ProviderCapability.TEXT_CHAT,
                ProviderCapability.STREAMING,
            )
            val runtime = when (
                val resolved = profileResolver.resolve(reserved.profileId, requiredCapabilities)
            ) {
                is ResolveProviderRuntimeProfileResult.Success -> resolved.runtimeProfile
                is ResolveProviderRuntimeProfileResult.Failure -> {
                    return failRun(
                        reserved,
                        resolved.error.toEngineCode(),
                        ConversationRunState.FAILED,
                    )
                }
            }
            val adapter = when (val result = adapterRegistry.adapter(runtime.profile.adapterId)) {
                is ProviderAdapterLookupResult.Found -> result.adapter
                is ProviderAdapterLookupResult.Failure -> {
                    return failRun(
                        reserved,
                        ConversationEngineErrorCode.ADAPTER_MISSING,
                        ConversationRunState.FAILED,
                    )
                }
            }
            if (!adapter.descriptor.capabilities.containsAll(requiredCapabilities)) {
                return failRun(
                    reserved,
                    ConversationEngineErrorCode.ADAPTER_CAPABILITY_MISSING,
                    ConversationRunState.FAILED,
                )
            }
            val attached = persistence.attachProfile(
                runId = reserved.runId,
                ownerSessionToken = ownerSessionToken,
                profileId = runtime.profile.profileId,
                profileRevision = runtime.profile.revision,
                adapterId = runtime.profile.adapterId,
                endpointBaseUrl = runtime.profile.endpointBaseUrl,
                modelId = runtime.profile.modelId,
                occurredAt = clock(),
            )
            val attachedRun = (attached as? RunMutationResult.Updated)?.run
                ?: return mutationFailure(attached, reserved)
            val assembled = contextAssembler.assemble(
                ConversationalContextAssemblyInput(
                    now = clock(),
                    conversation = RivenConversationContextRequest(
                        conversationId = attachedRun.conversationId,
                        expectedTimelineRevision = attachedRun.reservedTimelineRevision,
                        currentInteraction = RivenCurrentInteraction(
                            messageId = attachedRun.userMessageId,
                            content = userContent,
                        ),
                        contextHeadMessageId = attachedRun.contextHeadMessageId,
                    ),
                    budget = contextBudgetResolver(runtime.profile),
                ),
            )
            val contextSnapshot = when (assembled) {
                is RivenContextCollectionResult.Success -> assembled.snapshot
                is RivenContextCollectionResult.Failure -> {
                    val errorCode = if (assembled.requiredFailures.any { failure ->
                            val cause = failure.cause as? RivenContextFailureCause.ContractViolation
                            cause?.violation is RivenContextContractViolation.CollectionBudgetExceeded ||
                                (failure.sourceId == ActiveConversationContextSource.SOURCE_ID &&
                                    cause?.violation is RivenContextContractViolation.FragmentTooLarge)
                        }
                    ) {
                        ConversationEngineErrorCode.CONTEXT_LIMIT_EXCEEDED
                    } else {
                        ConversationEngineErrorCode.CONTEXT_ASSEMBLY_FAILED
                    }
                    return failRun(
                        attachedRun,
                        errorCode,
                        ConversationRunState.FAILED,
                    )
                }
            }.let { snapshot ->
                snapshot.copy(
                    freshnessReceipts = snapshot.freshnessReceipts +
                        RivenContextFreshnessReceipt.ProviderProfile(
                            profileId = runtime.profile.profileId,
                            revision = runtime.profile.revision,
                            adapterId = runtime.profile.adapterId,
                            endpointBaseUrl = runtime.profile.endpointBaseUrl,
                            modelId = runtime.profile.modelId,
                            capabilities = runtime.profile.capabilities,
                        ),
                )
            }
            if (contextSnapshot.fragments.any {
                    it.contentAuthority == RivenContextContentAuthority.INSTRUCTIONS
                } && adapter.descriptor.systemContextMode == ProviderSystemContextMode.UNSUPPORTED
            ) {
                return failRun(
                    attachedRun,
                    ConversationEngineErrorCode.SYSTEM_CONTEXT_UNSUPPORTED,
                    ConversationRunState.FAILED,
                )
            }
            val request = ProviderConversationRequest(
                runId = attachedRun.runId,
                idempotencyKey = attachedRun.idempotencyKey,
                endpointBaseUrl = runtime.profile.endpointBaseUrl,
                modelId = runtime.profile.modelId,
                credential = runtime.credential,
                context = contextSnapshot.fragments.map { fragment ->
                    ProviderContextFragment(
                        sourceId = fragment.sourceId,
                        fragmentId = fragment.fragmentId,
                        content = fragment.content,
                        layer = fragment.layer,
                        provenanceClass = fragment.provenanceClass,
                        criticality = fragment.criticality,
                        orderWithinLayer = fragment.orderWithinLayer,
                        orderWithinSource = fragment.orderWithinSource,
                        budgetBehavior = fragment.budgetBehavior,
                        revision = fragment.revision,
                        observedAt = fragment.observedAt,
                        validUntil = fragment.validUntil,
                        contentAuthority = fragment.contentAuthority,
                        conversationRole = fragment.conversationRole,
                    )
                },
            )
            val awaiting = persistence.markAwaitingProvider(
                attachedRun.runId,
                ownerSessionToken,
                clock(),
            )
            val awaitingRun = (awaiting as? RunMutationResult.Updated)?.run
                ?: return mutationFailure(awaiting, attachedRun)

            if (!contextIsCurrentAtDispatchBoundary(contextSnapshot)) {
                return failRun(
                    awaitingRun,
                    ConversationEngineErrorCode.CONTEXT_STALE,
                    ConversationRunState.STALE,
                )
            }

            val stream = StreamAccumulator(limits)
            try {
                // This is deliberately the first suspending call after the coherent Room snapshot
                // and live synchronous recall/expiry checks in contextIsCurrentAtDispatchBoundary.
                withTimeout(limits.maxProviderDurationMillis) {
                    adapter.stream(request) { event ->
                        coroutineContext.ensureActive()
                        if (call.cancelRequested.get()) {
                            throw CancellationException("Conversation run cancellation requested")
                        }
                        val firstDelta = stream.accept(event)
                        if (firstDelta) {
                            when (
                                val streaming = persistence.markStreaming(
                                    awaitingRun.runId,
                                    ownerSessionToken,
                                    clock(),
                                )
                            ) {
                                is RunMutationResult.Updated,
                                is RunMutationResult.Unchanged,
                                -> Unit
                                else -> throw ConversationStreamProtocolException(
                                    ConversationEngineErrorCode.CANCELLED,
                                )
                            }
                        }
                        if (event is ProviderStreamEvent.Delta) onDelta(event.content)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                return failRun(
                    awaitingRun,
                    ConversationEngineErrorCode.PROVIDER_TIMEOUT,
                    ConversationRunState.FAILED,
                )
            }
            val terminal = stream.terminal()
            if (terminal is ProviderStreamEvent.Failure) {
                return failRun(
                    awaitingRun,
                    ConversationEngineErrorCode.PROVIDER_FAILURE,
                    ConversationRunState.FAILED,
                    terminal.providerRequestId,
                )
            }
            terminal as ProviderStreamEvent.Completed
            val commitNow = clock()
            if (freshnessValidator.validate(contextSnapshot, commitNow) !is
                RivenContextFreshnessValidation.Current
            ) {
                return failRun(
                    awaitingRun,
                    ConversationEngineErrorCode.CONTEXT_STALE,
                    ConversationRunState.STALE,
                    terminal.providerRequestId,
                )
            }
            return when (
                val completed = withContext(NonCancellable) {
                    persistence.completeSuccess(
                        runId = awaitingRun.runId,
                        ownerSessionToken = ownerSessionToken,
                        contextSnapshot = contextSnapshot,
                        content = stream.content(),
                        providerRequestId = terminal.providerRequestId,
                        occurredAt = commitNow,
                        eligibilityClock = clock,
                    )
                }
            ) {
                is CompleteConversationRunResult.Committed -> ConversationEngineResult.Succeeded(
                    completed.run.toSnapshot(),
                    completed.timelineRevision,
                )
                is CompleteConversationRunResult.AlreadyTerminal -> ConversationEngineResult.Existing(
                    completed.run.toSnapshot(),
                )
                is CompleteConversationRunResult.Rejected -> {
                    val terminalState = if (completed.code in setOf(
                            ConversationEngineErrorCode.CONTEXT_STALE,
                            ConversationEngineErrorCode.STALE_TIMELINE,
                            ConversationEngineErrorCode.CANCELLED,
                        )
                    ) {
                        ConversationRunState.STALE
                    } else {
                        ConversationRunState.FAILED
                    }
                    failRun(awaitingRun, completed.code, terminalState, terminal.providerRequestId)
                }
            }
        } catch (protocol: ConversationStreamProtocolException) {
            return failRun(
                reserved,
                protocol.code,
                ConversationRunState.FAILED,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failRun(
                reserved,
                ConversationEngineErrorCode.PROVIDER_FAILURE,
                ConversationRunState.FAILED,
            )
        } finally {
            activeCalls.remove(reserved.runId, call)
        }
    }

    private suspend fun failRun(
        run: ConversationRunEntity,
        code: ConversationEngineErrorCode,
        state: ConversationRunState,
        providerRequestId: String? = null,
    ): ConversationEngineResult {
        val result = withContext(NonCancellable) {
            persistence.finishWithoutContent(
                runId = run.runId,
                ownerSessionToken = ownerSessionToken,
                state = state,
                errorCode = code,
                providerRequestId = providerRequestId,
                occurredAt = clock(),
            )
        }
        val snapshot = when (result) {
            is RunMutationResult.Updated -> result.run.toSnapshot()
            is RunMutationResult.Unchanged -> result.run.toSnapshot()
            else -> persistence.run(run.runId)?.toSnapshot()
        }
        return ConversationEngineResult.Failed(snapshot, code)
    }

    private suspend fun contextIsCurrentAtDispatchBoundary(
        snapshot: RivenContextSnapshot,
    ): Boolean {
        if (freshnessValidator.validate(snapshot, clock()) !is RivenContextFreshnessValidation.Current) {
            return false
        }
        // This coherent Room transaction is the final suspension. Nothing below may suspend before
        // the adapter call: process-local recall and expiry are sampled against the live clock last.
        if (!persistence.roomReceiptsCurrent(snapshot)) return false
        return freshnessValidator.validateSynchronousReceipts(snapshot, clock()) is
            RivenContextFreshnessValidation.Current
    }

    private suspend fun settleCancellation(run: ConversationRunEntity) {
        withContext(NonCancellable) {
            persistence.requestCancellation(run.runId, clock())
            persistence.finishWithoutContent(
                runId = run.runId,
                ownerSessionToken = ownerSessionToken,
                state = ConversationRunState.CANCELLED,
                errorCode = ConversationEngineErrorCode.CANCELLED,
                providerRequestId = persistence.run(run.runId)?.providerRequestId,
                occurredAt = clock(),
            )
        }
    }

    private suspend fun mutationFailure(
        result: RunMutationResult,
        run: ConversationRunEntity,
    ): ConversationEngineResult = failRun(
        run = run,
        code = (result as? RunMutationResult.Failure)?.code ?: ConversationEngineErrorCode.STALE_TIMELINE,
        state = ConversationRunState.FAILED,
    )

    private fun ProviderRuntimeProfileError.toEngineCode(): ConversationEngineErrorCode = when (this) {
        is ProviderRuntimeProfileError.MissingProfile -> ConversationEngineErrorCode.PROFILE_MISSING
        is ProviderRuntimeProfileError.ProfileDisabled -> ConversationEngineErrorCode.PROFILE_DISABLED
        is ProviderRuntimeProfileError.MissingCapability ->
            ConversationEngineErrorCode.PROFILE_CAPABILITY_MISSING
        is ProviderRuntimeProfileError.MissingCredential -> ConversationEngineErrorCode.CREDENTIAL_MISSING
        is ProviderRuntimeProfileError.CredentialUnreadable ->
            ConversationEngineErrorCode.CREDENTIAL_UNREADABLE
        is ProviderRuntimeProfileError.StorageFailure -> ConversationEngineErrorCode.STORAGE_FAILURE
    }

    private fun RunMutationResult.toControlResult(): ConversationRunControlResult = when (this) {
        is RunMutationResult.Updated -> ConversationRunControlResult.Updated(run.toSnapshot())
        is RunMutationResult.Unchanged -> ConversationRunControlResult.Unchanged(run.toSnapshot())
        RunMutationResult.Missing -> ConversationRunControlResult.Missing
        is RunMutationResult.Failure -> ConversationRunControlResult.Failure(code)
    }

    private fun ConversationRunState.isTerminal(): Boolean = this in setOf(
        ConversationRunState.SUCCEEDED,
        ConversationRunState.FAILED,
        ConversationRunState.CANCELLED,
        ConversationRunState.STALE,
        ConversationRunState.INTERRUPTED,
    )

    private data class ActiveCall(
        val job: Job,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
    )
}

private class StreamAccumulator(
    private val limits: ConversationEngineLimits,
) {
    private val content = StringBuilder()
    private var eventCount = 0
    private var terminal: ProviderStreamEvent? = null
    private var sawDelta = false

    fun accept(event: ProviderStreamEvent): Boolean {
        eventCount += 1
        if (eventCount > limits.maxStreamEvents) {
            throw ConversationStreamProtocolException(ConversationEngineErrorCode.OUTPUT_LIMIT)
        }
        if (terminal != null) {
            throw ConversationStreamProtocolException(ConversationEngineErrorCode.PROVIDER_PROTOCOL)
        }
        return when (event) {
            is ProviderStreamEvent.Delta -> {
                if (event.content.isEmpty() || event.content.length > limits.maxDeltaChars ||
                    content.length.toLong() + event.content.length > limits.maxOutputChars
                ) {
                    throw ConversationStreamProtocolException(ConversationEngineErrorCode.OUTPUT_LIMIT)
                }
                content.append(event.content)
                val first = !sawDelta
                sawDelta = true
                first
            }
            is ProviderStreamEvent.Completed -> {
                validateProviderRequestId(event.providerRequestId)
                if (!sawDelta || content.isBlank()) {
                    throw ConversationStreamProtocolException(ConversationEngineErrorCode.PROVIDER_PROTOCOL)
                }
                terminal = event
                false
            }
            is ProviderStreamEvent.Failure -> {
                validateProviderRequestId(event.providerRequestId)
                terminal = event
                false
            }
        }
    }

    fun terminal(): ProviderStreamEvent = terminal
        ?: throw ConversationStreamProtocolException(ConversationEngineErrorCode.PROVIDER_PROTOCOL)

    fun content(): String = content.toString()

    private fun validateProviderRequestId(value: String?) {
        if (value != null && (value.isBlank() || value.length > limits.maxProviderRequestIdChars)) {
            throw ConversationStreamProtocolException(ConversationEngineErrorCode.PROVIDER_PROTOCOL)
        }
    }
}

private class ConversationStreamProtocolException(
    val code: ConversationEngineErrorCode,
) : IllegalStateException(code.name)
