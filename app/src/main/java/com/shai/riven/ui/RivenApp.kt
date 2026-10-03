package com.shai.riven.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.presence.RivenPresenceSnapshot
import com.shai.riven.data.presence.RivenRoom
import com.shai.riven.data.reminder.ReminderController
import com.shai.riven.data.reminder.platform.ReminderRuntime
import com.shai.riven.data.provider.ProviderProfileSnapshot
import com.shai.riven.data.provider.openrouter.OpenRouterModel
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalogResult
import com.shai.riven.data.runtime.RivenConversationRuntime
import com.shai.riven.data.runtime.RivenMemoryItem
import com.shai.riven.data.runtime.RivenChatMessage
import com.shai.riven.data.runtime.RivenImageAttachment
import com.shai.riven.data.runtime.RivenImagePreviewState
import com.shai.riven.data.runtime.RivenProfileSaveResult
import com.shai.riven.data.runtime.RivenRuntimeController
import com.shai.riven.data.runtime.RivenRuntimeResult
import com.shai.riven.data.runtime.RivenRuntimeSnapshot
import com.shai.riven.ui.arcade.ArcadeApp
import com.shai.riven.ui.reminder.ReminderAlarmScreen
import com.shai.riven.ui.theme.DeepInk
import com.shai.riven.ui.theme.MistBlue
import com.shai.riven.ui.theme.MutedGold
import com.shai.riven.ui.theme.PenthouseNavy
import com.shai.riven.ui.theme.RubyHeart
import com.shai.riven.ui.theme.TableNavy
import com.shai.riven.ui.theme.TableNavyRaised
import com.shai.riven.ui.theme.WarmIvory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RivenDestination(val label: String) {
    CHAT("Chat"),
    ALARMS("Alarms"),
    ARCADE("Arcade"),
    MEMORY("Memory"),
    SETTINGS("Settings"),
}

@Composable
fun RivenApp(
    reminderControllerFactory: (android.content.Context) -> ReminderController = {
        ReminderRuntime.from(it).repository
    },
    uiAssets: RivenUiAssets = RivenUiAssets.Empty,
    runtimeFactory: (android.content.Context) -> RivenRuntimeController = {
        RivenConversationRuntime.fromContext(it)
    },
) {
    val context = LocalContext.current
    val runtime = remember(context, runtimeFactory) { runtimeFactory(context.applicationContext) }
    var destination by rememberSaveable { mutableStateOf(RivenDestination.CHAT) }
    var snapshot by remember { mutableStateOf<RivenRuntimeSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var draftInitialized by rememberSaveable { mutableStateOf(false) }
    var persistedDraft by remember { mutableStateOf("") }
    var pendingSubmissionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingBaselineUserMessageIds by rememberSaveable {
        mutableStateOf<List<String>?>(null)
    }
    var nextSubmissionId by rememberSaveable { mutableStateOf(0L) }
    var activeConversationJob by remember { mutableStateOf<Job?>(null) }

    fun beginSubmission(baselineUserMessageIds: Set<String>): Long {
        val submissionId = ++nextSubmissionId
        pendingSubmissionId = submissionId
        pendingBaselineUserMessageIds = baselineUserMessageIds.toList()
        return submissionId
    }

    fun settleSubmission(submissionId: Long?, restored: RivenRuntimeSnapshot?) {
        val pendingId = pendingSubmissionId ?: return
        if (submissionId != null && pendingId != submissionId) return
        val baselineUserMessageIds = pendingBaselineUserMessageIds.orEmpty()
        var committed = false
        restored?.let {
            snapshot = it
            persistedDraft = it.draft
            committed = it.messages.any { message ->
                message.role == MessageRole.USER && message.id !in baselineUserMessageIds
            }
        }
        if (committed) draft = restored?.draft.orEmpty()
        pendingSubmissionId = null
        pendingBaselineUserMessageIds = null
        draftInitialized = true
    }

    fun navigateTo(target: RivenDestination) {
        if (destination == RivenDestination.CHAT && target != RivenDestination.CHAT) {
            activeConversationJob?.cancel(CancellationException("Chat screen left"))
        }
        destination = target
    }

    DisposableEffect(runtime) { onDispose(runtime::close) }
    LaunchedEffect(runtime) {
        when (val result = runtimeIo { runtime.initialize() }) {
            is RivenRuntimeResult.Success -> {
                snapshot = result.snapshot
                persistedDraft = result.snapshot.draft
                if (pendingSubmissionId != null) settleSubmission(null, result.snapshot)
                else if (!draftInitialized) draft = result.snapshot.draft
                draftInitialized = true
            }
            is RivenRuntimeResult.Failure -> {
                snapshot = result.snapshot
                notice = result.message
                result.snapshot?.let { restored ->
                    persistedDraft = restored.draft
                    if (!draftInitialized) draft = restored.draft
                    draftInitialized = true
                }
            }
        }
        loading = false
    }

    LaunchedEffect(runtime, draft, persistedDraft, draftInitialized, pendingSubmissionId) {
        if (draftInitialized && pendingSubmissionId == null && draft != persistedDraft) {
            delay(DRAFT_SAVE_DELAY_MILLIS)
            when (val result = runtimeIo { runtime.saveDraft(draft) }) {
                is RivenRuntimeResult.Success -> {
                    snapshot = result.snapshot
                    persistedDraft = result.snapshot.draft
                }
                is RivenRuntimeResult.Failure -> notice = result.message
            }
        }
    }

    LaunchedEffect(runtime, destination) {
        if (!loading) {
            when (val result = runtimeIo { runtime.snapshot() }) {
                is RivenRuntimeResult.Success -> {
                    snapshot = result.snapshot
                    persistedDraft = result.snapshot.draft
                    if (pendingSubmissionId != null) settleSubmission(null, result.snapshot)
                    else if (!draftInitialized) draft = result.snapshot.draft
                }
                is RivenRuntimeResult.Failure -> notice = result.message
            }
        }
    }

    BackHandler(enabled = destination == RivenDestination.ALARMS) {
        navigateTo(RivenDestination.CHAT)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("riven_app"),
        containerColor = PenthouseNavy,
        bottomBar = {
            NavigationBar(containerColor = TableNavyRaised) {
                RivenDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { navigateTo(item) },
                        icon = { DestinationIcon(item) },
                        label = { Text(item.label) },
                        modifier = Modifier.testTag("nav_${item.name.lowercase()}"),
                    )
                }
            }
        },
    ) { padding ->
        when {
            destination == RivenDestination.ALARMS -> ReminderAlarmScreen(
                modifier = Modifier.padding(padding),
                controllerFactory = reminderControllerFactory,
            )
            destination == RivenDestination.ARCADE -> ArcadeApp(
                modifier = Modifier.padding(padding),
                portraitResourceId = uiAssets.arcadePortraitResourceId,
            )
            loading -> LoadingScreen(padding)
            destination == RivenDestination.SETTINGS || destination == RivenDestination.MEMORY -> SettingsScreen(
                runtime = runtime,
                snapshot = snapshot,
                onSnapshot = { snapshot = it },
                memoryOnly = destination == RivenDestination.MEMORY,
                modifier = Modifier.padding(padding),
            )
            else -> ChatScreen(
                runtime = runtime,
                initialSnapshot = snapshot,
                draft = draft,
                onDraftChange = { draft = it },
                onDraftSubmitted = ::beginSubmission,
                onSubmissionSettled = ::settleSubmission,
                onRuntimeDraft = { storedDraft ->
                    draft = storedDraft
                    persistedDraft = storedDraft
                },
                externalNotice = notice,
                onSnapshot = { snapshot = it },
                onConversationJobChanged = { activeConversationJob = it },
                onOpenSettings = { navigateTo(RivenDestination.SETTINGS) },
                uiAssets = uiAssets,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DestinationIcon(destination: RivenDestination) {
    Canvas(
        modifier = Modifier.size(28.dp).semantics {
            contentDescription = "${destination.label} destination"
        },
    ) {
        val gold = MutedGold
        val stroke = 1.6.dp.toPx()
        val thin = 1.2.dp.toPx()
        when (destination) {
            RivenDestination.CHAT -> {
                val left = 3.dp.toPx()
                val top = 6.dp.toPx()
                val width = size.width - 2 * left
                val height = 16.dp.toPx()
                drawRoundRect(
                    color = gold,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawLine(gold, Offset(left, top), Offset(size.width / 2, top + 9.dp.toPx()), thin)
                drawLine(gold, Offset(size.width - left, top), Offset(size.width / 2, top + 9.dp.toPx()), thin)
            }
            RivenDestination.ARCADE -> {
                val left = 6.dp.toPx()
                val top = 2.dp.toPx()
                drawRoundRect(
                    color = gold,
                    topLeft = Offset(left, top),
                    size = Size(16.dp.toPx(), 24.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawCircle(gold, radius = 3.dp.toPx(), center = center, style = Stroke(thin))
                drawLine(gold, Offset(center.x, center.y - 6.dp.toPx()), Offset(center.x, center.y + 6.dp.toPx()), thin)
                drawLine(gold, Offset(center.x - 6.dp.toPx(), center.y), Offset(center.x + 6.dp.toPx(), center.y), thin)
            }
            RivenDestination.ALARMS -> {
                val radius = 9.dp.toPx()
                drawCircle(gold, radius = radius, center = center, style = Stroke(stroke))
                drawCircle(gold, radius = 1.25.dp.toPx(), center = center)
                drawLine(
                    gold,
                    Offset(center.x, center.y),
                    Offset(center.x, center.y - 5.dp.toPx()),
                    stroke,
                )
                drawLine(
                    gold,
                    Offset(center.x, center.y),
                    Offset(center.x + 4.dp.toPx(), center.y + 2.dp.toPx()),
                    stroke,
                )
                drawLine(
                    gold,
                    Offset(center.x - 7.dp.toPx(), 3.dp.toPx()),
                    Offset(4.dp.toPx(), 7.dp.toPx()),
                    thin,
                )
                drawLine(
                    gold,
                    Offset(center.x + 7.dp.toPx(), 3.dp.toPx()),
                    Offset(24.dp.toPx(), 7.dp.toPx()),
                    thin,
                )
                drawLine(gold, Offset(8.dp.toPx(), 22.dp.toPx()), Offset(6.dp.toPx(), 25.dp.toPx()), thin)
                drawLine(gold, Offset(20.dp.toPx(), 22.dp.toPx()), Offset(22.dp.toPx(), 25.dp.toPx()), thin)
            }
            RivenDestination.MEMORY -> {
                val left = 4.dp.toPx()
                val top = 4.dp.toPx()
                val pageWidth = 9.dp.toPx()
                val pageHeight = 20.dp.toPx()
                drawRoundRect(
                    color = gold,
                    topLeft = Offset(left, top),
                    size = Size(pageWidth, pageHeight),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawRoundRect(
                    color = gold,
                    topLeft = Offset(size.width - left - pageWidth, top),
                    size = Size(pageWidth, pageHeight),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawLine(gold, Offset(center.x, top + 1.dp.toPx()), Offset(center.x, top + pageHeight), thin)
                drawLine(gold, Offset(left + 2.dp.toPx(), 10.dp.toPx()), Offset(left + pageWidth - 2.dp.toPx(), 10.dp.toPx()), thin)
                drawLine(gold, Offset(size.width - left - pageWidth + 2.dp.toPx(), 10.dp.toPx()), Offset(size.width - left - 2.dp.toPx(), 10.dp.toPx()), thin)
            }
            RivenDestination.SETTINGS -> {
                val left = 3.dp.toPx()
                val top = 4.dp.toPx()
                drawRoundRect(
                    color = gold,
                    topLeft = Offset(left, top),
                    size = Size(size.width - 2 * left, 20.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(stroke),
                )
                listOf(9.dp to 10.dp, 14.dp to 18.dp, 19.dp to 12.dp).forEach { (x, knobY) ->
                    val xPx = x.toPx()
                    drawLine(gold, Offset(xPx, top + 3.dp.toPx()), Offset(xPx, top + 17.dp.toPx()), thin)
                    drawCircle(gold, radius = 2.dp.toPx(), center = Offset(xPx, knobY.toPx()), style = Stroke(stroke))
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).background(PenthouseNavy),
        contentAlignment = Alignment.Center,
    ) {
        Text("Opening Riven…", color = WarmIvory)
    }
}

@Composable
private fun ChatScreen(
    runtime: RivenRuntimeController,
    initialSnapshot: RivenRuntimeSnapshot?,
    draft: String,
    onDraftChange: (String) -> Unit,
    onDraftSubmitted: (Set<String>) -> Long,
    onSubmissionSettled: (Long, RivenRuntimeSnapshot?) -> Unit,
    onRuntimeDraft: (String) -> Unit,
    externalNotice: String?,
    onSnapshot: (RivenRuntimeSnapshot) -> Unit,
    onConversationJobChanged: (Job?) -> Unit,
    onOpenSettings: () -> Unit,
    uiAssets: RivenUiAssets,
    modifier: Modifier = Modifier,
) {
    var snapshot by remember(initialSnapshot) { mutableStateOf(initialSnapshot) }
    var sending by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var streamedReply by remember { mutableStateOf("") }
    var notice by remember(externalNotice) { mutableStateOf(externalNotice) }
    var conversationJob by remember { mutableStateOf<Job?>(null) }
    var activeSubmissionId by remember { mutableStateOf<Long?>(null) }
    var awaitingUnknownImageConfirmation by remember { mutableStateOf(false) }
    var floorPlanOpen by rememberSaveable { mutableStateOf(false) }
    val latestConversationJob by rememberUpdatedState(conversationJob)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    DisposableEffect(runtime) {
        onDispose { latestConversationJob?.cancel(CancellationException("Chat screen left")) }
    }

    fun apply(
        result: RivenRuntimeResult,
        submissionId: Long? = null,
        preserveComposerDraft: Boolean = false,
    ) {
        when (result) {
            is RivenRuntimeResult.Success -> {
                snapshot = result.snapshot
                if (submissionId != null) onSubmissionSettled(submissionId, result.snapshot)
                else if (!preserveComposerDraft) onRuntimeDraft(result.snapshot.draft)
                notice = null
                awaitingUnknownImageConfirmation = false
                onSnapshot(result.snapshot)
            }
            is RivenRuntimeResult.Failure -> {
                result.snapshot?.let {
                    snapshot = it
                    if (submissionId == null && !preserveComposerDraft) onRuntimeDraft(it.draft)
                    onSnapshot(it)
                }
                if (submissionId != null) onSubmissionSettled(submissionId, result.snapshot)
                notice = result.message
                awaitingUnknownImageConfirmation = result.requiresImageCapabilityConfirmation
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                apply(runtimeIo { runtime.addDraftImage(uri, draft) })
            }
        }
    }
    fun loadImagePreview(attachmentId: String) {
        scope.launch {
            when (val result = runtimeIo { runtime.loadImagePreview(attachmentId) }) {
                is RivenRuntimeResult.Success -> {
                    snapshot = result.snapshot
                    onSnapshot(result.snapshot)
                }
                is RivenRuntimeResult.Failure -> result.snapshot?.let {
                    snapshot = it
                    onSnapshot(it)
                }
            }
        }
    }

    fun runConversation(
        preserveDraft: Boolean,
        submissionId: Long? = null,
        block: suspend (suspend (String) -> Unit) -> RivenRuntimeResult,
    ) {
        if (sending || cancelling) return
        sending = true
        activeSubmissionId = submissionId
        streamedReply = ""
        notice = null
        awaitingUnknownImageConfirmation = false
        conversationJob = scope.launch {
            try {
                if (preserveDraft) {
                    when (val saved = runtimeIo { runtime.saveDraft(draft) }) {
                        is RivenRuntimeResult.Success -> onSnapshot(saved.snapshot)
                        is RivenRuntimeResult.Failure -> {
                            saved.snapshot?.let {
                                snapshot = it
                                onSnapshot(it)
                            }
                            notice = saved.message
                            return@launch
                        }
                    }
                }
                val result = runtimeIo {
                    block { delta ->
                        withContext(Dispatchers.Main.immediate) { streamedReply += delta }
                    }
                }
                apply(result, submissionId)
            } catch (_: CancellationException) {
                notice = "Reply cancelled. Your message is still saved."
            } catch (_: Exception) {
                submissionId?.let { onSubmissionSettled(it, null) }
                notice = "Riven could not complete this reply. Your message is still saved."
            } finally {
                streamedReply = ""
                sending = false
                conversationJob = null
                onConversationJobChanged(null)
                if (!cancelling) activeSubmissionId = null
            }
        }
        onConversationJobChanged(conversationJob)
    }

    LaunchedEffect(snapshot?.messages?.size, streamedReply) {
        val total = (snapshot?.messages?.size ?: 0) + if (streamedReply.isBlank()) 0 else 1
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF07101A), PenthouseNavy, Color(0xFF0D2233))),
        ),
    ) {
        val compact = maxHeight < 640.dp || maxWidth < 360.dp
        RoomBackdrop(snapshot?.roomState, uiAssets)
        Column(
            modifier = Modifier.fillMaxSize().imePadding().testTag(if (compact) "chat_compact" else "chat_normal"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    uiAssets.brandIconResourceId?.let { resourceId ->
                        Image(
                            painter = painterResource(resourceId),
                            contentDescription = "Riven brand icon",
                            modifier = Modifier.size(36.dp).testTag("riven_brand_icon"),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Column(modifier = Modifier.padding(start = if (uiAssets.brandIconResourceId == null) 0.dp else 8.dp)) {
                        Text("RIVEN", color = MutedGold, style = MaterialTheme.typography.labelMedium)
                        Text("Conversation", color = WarmIvory, style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        snapshot?.profiles?.singleOrNull { it.profileId == snapshot?.selectedProfileId }?.displayName
                            ?: "Not configured",
                        color = if (snapshot?.selectedProfileHasCredential == true) MistBlue else RubyHeart,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("chat_profile_status"),
                    )
                    OutlinedButton(
                        onClick = { floorPlanOpen = !floorPlanOpen },
                        modifier = Modifier.padding(start = 8.dp).testTag("room_key"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        BrassKeyIcon()
                        Text("Rooms", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (floorPlanOpen) {
                FloorPlan(
                    browsedRoom = snapshot?.roomState?.browsedRoom ?: RivenRoom.LIVING_ROOM,
                    onBrowse = { room ->
                        scope.launch { apply(runtimeIo { runtime.browseRoom(room) }, preserveComposerDraft = true) }
                    },
                )
            }
            if (snapshot?.selectedProfileId == null || snapshot?.selectedProfileHasCredential != true) {
                ConfigurationBanner(onOpenSettings)
            }
            notice?.let { StatusBanner(it) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_transcript"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val messages = snapshot?.messages.orEmpty()
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "This is the persistent conversation. Configure OpenRouter, then say something to Riven.",
                            color = MistBlue,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message, compact = compact, onLoadImagePreview = ::loadImagePreview)
                }
                if (streamedReply.isNotBlank()) {
                    item(key = "streaming") {
                        MessageBubble(
                            RivenChatMessage(
                                id = "streaming",
                                role = MessageRole.ASSISTANT,
                                deliveryState = com.shai.riven.data.persistence.model.MessageDeliveryState.PENDING,
                                content = streamedReply,
                                providerModel = null,
                            ),
                            streaming = true,
                            compact = compact,
                            onLoadImagePreview = {},
                        )
                    }
                }
            }
            val actions = @Composable {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().testTag("chat_actions"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        TextButton(
                            onClick = { runConversation(true) { runtime.retry(it) } },
                            enabled = !sending && !cancelling,
                        ) {
                            Text("Retry")
                        }
                    }
                    item {
                        TextButton(
                            onClick = { runConversation(true) { runtime.regenerate(it) } },
                            enabled = !sending && !cancelling,
                        ) {
                            Text("Regenerate")
                        }
                    }
                    item {
                        TextButton(
                            onClick = { runConversation(true) { runtime.continueConversation(it) } },
                            enabled = !sending && !cancelling,
                        ) {
                            Text("Continue")
                        }
                    }
                    if (sending || cancelling) {
                        item {
                            TextButton(
                                onClick = {
                                    if (cancelling) return@TextButton
                                    cancelling = true
                                    val submissionId = activeSubmissionId
                                    val jobToCancel = conversationJob
                                    jobToCancel?.cancel(CancellationException("Conversation cancelled by user"))
                                    scope.launch {
                                        try {
                                            val cancellationResult = try {
                                                runtimeIo { runtime.cancel() }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                null
                                            }
                                            jobToCancel?.join()
                                            val reconciled = try {
                                                runtimeIo { runtime.snapshot() }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                cancellationResult
                                            }
                                            if (reconciled != null) {
                                                apply(
                                                    result = reconciled,
                                                    submissionId = submissionId,
                                                    preserveComposerDraft = submissionId == null,
                                                )
                                            } else {
                                                submissionId?.let { onSubmissionSettled(it, null) }
                                                notice = "Riven could not confirm cancellation. Your draft is still saved."
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } finally {
                                            activeSubmissionId = null
                                            cancelling = false
                                        }
                                    }
                                },
                                enabled = !cancelling,
                            ) { Text(if (cancelling) "Cancelling" else "Cancel") }
                        }
                    }
                }
            }
            actions()
            snapshot?.draftImages?.singleOrNull()?.let { image ->
                DraftImagePreview(
                    image = image,
                    pending = sending || cancelling,
                    onLoadImagePreview = { loadImagePreview(image.attachmentId) },
                    onRemove = {
                        if (!sending && !cancelling) {
                            scope.launch {
                                apply(runtimeIo { runtime.removeDraftImage(image.attachmentId, draft) })
                            }
                        }
                    },
                )
            }
            if (awaitingUnknownImageConfirmation) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = {
                            val submissionId = onDraftSubmitted(
                                snapshot?.messages.orEmpty()
                                    .filter { it.role == MessageRole.USER }
                                    .mapTo(linkedSetOf()) { it.id },
                            )
                            runConversation(false, submissionId) {
                                runtime.sendWithUnknownImageCapabilityConfirmation(draft, it)
                            }
                        },
                        enabled = !sending && !cancelling,
                        modifier = Modifier.testTag("chat_confirm_unknown_image"),
                    ) {
                        Text("Send anyway")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f).testTag("chat_input"),
                    enabled = !sending && !cancelling,
                    label = { Text("Message Riven") },
                    minLines = 1,
                    maxLines = if (compact) 3 else 5,
                )
                OutlinedButton(
                    onClick = { imagePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                    enabled = !sending && !cancelling && snapshot?.draftImages.orEmpty().isEmpty(),
                    modifier = Modifier.testTag("chat_add_image").semantics {
                        contentDescription = "Choose an image to send"
                    },
                ) {
                    Text("Photo")
                }
                Button(
                    onClick = {
                        val submissionId = onDraftSubmitted(
                            snapshot?.messages.orEmpty()
                                .filter { it.role == MessageRole.USER }
                                .mapTo(linkedSetOf()) { it.id },
                        )
                        runConversation(false, submissionId) { runtime.send(draft, it) }
                    },
                    enabled = !sending && !cancelling &&
                        (draft.isNotBlank() || snapshot?.draftImages.orEmpty().isNotEmpty()),
                    modifier = Modifier.testTag("chat_send"),
                    colors = ButtonDefaults.buttonColors(containerColor = RubyHeart),
                ) {
                    Text(if (sending || cancelling) "…" else "Send")
                }
            }
        }
    }
}

@Composable
private fun ConfigurationBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).testTag("chat_configuration_banner"),
        colors = CardDefaults.cardColors(containerColor = TableNavyRaised),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OpenRouter profile or key is missing.", color = WarmIvory, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun StatusBanner(message: String) {
    Text(
        text = message,
        color = WarmIvory,
        modifier = Modifier.fillMaxWidth().background(RubyHeart.copy(alpha = 0.72f)).padding(10.dp)
            .testTag("runtime_notice"),
    )
}

@Composable
private fun MessageBubble(
    message: RivenChatMessage,
    streaming: Boolean = false,
    compact: Boolean = false,
    onLoadImagePreview: (String) -> Unit,
) {
    val isRiven = message.role == MessageRole.ASSISTANT
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isRiven) Arrangement.Start else Arrangement.End,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (compact) 0.94f else 0.86f),
            colors = CardDefaults.cardColors(
                containerColor = if (isRiven) DeepInk.copy(alpha = 0.94f) else ShaiHunterGreen.copy(alpha = 0.96f),
            ),
            border = BorderStroke(1.dp, MutedGold.copy(alpha = if (isRiven) 0.34f else 0.46f)),
            shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
        ) {
            Column(
                Modifier.artDecoBubbleFiligree().padding(
                    horizontal = if (compact) 20.dp else 28.dp,
                    vertical = if (compact) 15.dp else 20.dp,
                ),
            ) {
                Text(if (isRiven) "Riven" else "Shai", color = MutedGold, fontWeight = FontWeight.Bold)
                message.images.forEach { image ->
                    AttachmentThumbnail(
                        image = image,
                        description = if (isRiven) "Image from Riven" else "Image sent by Shai",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                            .testTag("message_image_${image.attachmentId}"),
                        onLoadPreview = { onLoadImagePreview(image.attachmentId) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (message.content.isNotBlank() || streaming) {
                    Text(
                        message.content + if (streaming) " …" else "",
                        color = WarmIvory,
                        style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomBackdrop(state: RivenPresenceSnapshot?, uiAssets: RivenUiAssets) {
    val room = state?.browsedRoom ?: RivenRoom.LIVING_ROOM
    val backgroundResourceId = uiAssets.roomBackgroundResourceId(room)
    val spriteResourceId = uiAssets.visibleSpriteResourceId(state)
    Box(
        modifier = Modifier.fillMaxSize().testTag("room_backdrop_${room.stableId}"),
    ) {
        if (backgroundResourceId != null) {
            Image(
                painter = painterResource(backgroundResourceId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("approved_room_art_${room.stableId}"),
                contentScale = ContentScale.Crop,
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val gold = MutedGold.copy(alpha = 0.20f)
                val green = ShaiHunterGreen.copy(alpha = 0.22f)
                drawRect(green, topLeft = Offset(0f, size.height * 0.64f), size = Size(size.width, size.height * 0.36f))
                drawLine(gold, Offset(size.width * 0.08f, size.height * 0.64f), Offset(size.width * 0.92f, size.height * 0.64f), 2.dp.toPx())
                drawRoundRect(
                    color = gold,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.18f),
                    size = Size(size.width * 0.76f, size.height * 0.32f),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(1.2.dp.toPx()),
                )
                drawCircle(gold, 2.dp.toPx(), Offset(size.width * 0.18f, size.height * 0.12f))
                drawCircle(gold, 1.5.dp.toPx(), Offset(size.width * 0.78f, size.height * 0.09f))
                drawCircle(gold, 1.dp.toPx(), Offset(size.width * 0.88f, size.height * 0.28f))
            }
        }
        if (spriteResourceId != null && state != null) {
            val placement = state.semanticSprite.uiPlacement()
            val alignment = when (placement.anchor) {
                RivenSpriteAnchor.START -> Alignment.BottomStart
                RivenSpriteAnchor.CENTER -> Alignment.BottomCenter
                RivenSpriteAnchor.END -> Alignment.BottomEnd
            }
            Image(
                painter = painterResource(spriteResourceId),
                contentDescription = "Riven ${state.semanticSprite.stableId.replace('_', ' ')}",
                modifier = Modifier
                    .align(alignment)
                    .fillMaxHeight(placement.heightFraction)
                    .padding(bottom = placement.bottomPaddingDp.dp)
                    .testTag("approved_riven_sprite_${state.semanticSprite.stableId}"),
                contentScale = ContentScale.Fit,
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                DeepInk.copy(alpha = if (backgroundResourceId == null) 0.08f else 0.30f),
            ),
        )
        Text(
            room.displayName.uppercase(),
            color = MutedGold.copy(alpha = 0.62f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp)
                .testTag("browsed_room_label"),
        )
    }
}

@Composable
private fun BrassKeyIcon() {
    Canvas(
        modifier = Modifier.size(18.dp).semantics { contentDescription = "Open room floor plan" },
    ) {
        val stroke = 1.6.dp.toPx()
        drawCircle(MutedGold, radius = 4.dp.toPx(), center = Offset(5.dp.toPx(), 7.dp.toPx()), style = Stroke(stroke))
        drawLine(MutedGold, Offset(8.dp.toPx(), 10.dp.toPx()), Offset(16.dp.toPx(), 16.dp.toPx()), stroke)
        drawLine(MutedGold, Offset(12.dp.toPx(), 13.dp.toPx()), Offset(14.dp.toPx(), 11.dp.toPx()), stroke)
    }
}

@Composable
private fun FloorPlan(
    browsedRoom: RivenRoom,
    onBrowse: (RivenRoom) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("room_floor_plan"),
        colors = CardDefaults.cardColors(containerColor = TableNavyRaised.copy(alpha = 0.96f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("PENTHOUSE FLOOR PLAN", color = MutedGold, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoomPlanButton(RivenRoom.STUDY, browsedRoom, onBrowse, Modifier.weight(1f))
                RoomPlanButton(RivenRoom.LIVING_ROOM, browsedRoom, onBrowse, Modifier.weight(1.35f))
                RoomPlanButton(RivenRoom.TERRACE, browsedRoom, onBrowse, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.weight(0.35f))
                RoomPlanButton(RivenRoom.KITCHEN, browsedRoom, onBrowse, Modifier.weight(1f))
                RoomPlanButton(RivenRoom.BEDROOM, browsedRoom, onBrowse, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RoomPlanButton(
    room: RivenRoom,
    browsedRoom: RivenRoom,
    onBrowse: (RivenRoom) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = room == browsedRoom
    OutlinedButton(
        onClick = { onBrowse(room) },
        modifier = modifier.testTag("room_${room.stableId}"),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MutedGold.copy(alpha = 0.24f) else Color.Transparent,
            contentColor = if (selected) WarmIvory else MistBlue,
        ),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 7.dp),
    ) {
        Text(room.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DraftImagePreview(
    image: RivenImageAttachment,
    pending: Boolean,
    onLoadImagePreview: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("draft_image_preview"),
        colors = CardDefaults.cardColors(containerColor = TableNavyRaised),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AttachmentThumbnail(
                image = image,
                description = if (pending) "Image pending send" else "Selected image preview",
                modifier = Modifier.size(72.dp),
                onLoadPreview = onLoadImagePreview,
            )
            Column(Modifier.weight(1f)) {
                Text(if (pending) "Sending image" else "Image ready", color = WarmIvory)
                Text(
                    "${image.mimeType} · ${image.byteSize / 1_024} KB",
                    color = MistBlue,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = onRemove,
                enabled = !pending,
                modifier = Modifier.testTag("chat_remove_image").semantics {
                    contentDescription = "Remove selected image"
                },
            ) {
                Text("Remove")
            }
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    image: RivenImageAttachment,
    description: String,
    modifier: Modifier,
    onLoadPreview: () -> Unit,
) {
    LaunchedEffect(image.attachmentId, image.previewState) {
        if (image.previewState == RivenImagePreviewState.DEFERRED) onLoadPreview()
    }
    val bitmap = remember(image.attachmentId, image.previewBytes) {
        decodePreviewBitmap(image.previewBytes)
    }
    if (image.previewState == RivenImagePreviewState.READY && bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(DeepInk, RoundedCornerShape(10.dp)),
        )
    } else if (image.previewState == RivenImagePreviewState.UNAVAILABLE) {
        Box(
            modifier = modifier.background(DeepInk, RoundedCornerShape(10.dp)).semantics {
                contentDescription = "$description unavailable"
            },
            contentAlignment = Alignment.Center,
        ) {
            Text("Image unavailable", color = MistBlue, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        Box(
            modifier = modifier.background(DeepInk, RoundedCornerShape(10.dp)).semantics {
                contentDescription = "$description loading"
            },
            contentAlignment = Alignment.Center,
        ) {
            Text("Loading image", color = MistBlue, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun decodePreviewBitmap(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_PREVIEW_EDGE ||
        bounds.outHeight / sample > MAX_PREVIEW_EDGE
    ) {
        sample *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private fun Modifier.artDecoBubbleFiligree(): Modifier = drawBehind {
    val gold = MutedGold.copy(alpha = 0.46f)
    val inset = 5.dp.toPx()
    val reach = 25.dp.toPx()
    val inner = 10.dp.toPx()
    val stroke = 1.dp.toPx()
    val right = size.width - inset
    val bottom = size.height - inset

    drawLine(gold, Offset(inset, inset), Offset(reach, inset), stroke)
    drawLine(gold, Offset(inset, inset), Offset(inset, reach), stroke)
    drawLine(gold, Offset(inset, inner), Offset(inner, inset), stroke)
    drawLine(gold, Offset(right, bottom), Offset(size.width - reach, bottom), stroke)
    drawLine(gold, Offset(right, bottom), Offset(right, size.height - reach), stroke)
    drawLine(gold, Offset(right, size.height - inner), Offset(size.width - inner, bottom), stroke)
}

@Composable
private fun SettingsScreen(
    runtime: RivenRuntimeController,
    snapshot: RivenRuntimeSnapshot?,
    onSnapshot: (RivenRuntimeSnapshot) -> Unit,
    modifier: Modifier = Modifier,
    memoryOnly: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var modelId by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<OpenRouterModel>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var instructions by remember(snapshot?.instructions?.revision) {
        mutableStateOf(snapshot?.instructions?.content.orEmpty())
    }
    var instructionsEnabled by remember(snapshot?.instructions?.revision) {
        mutableStateOf(snapshot?.instructions?.isEnabled ?: false)
    }
    var memoryMeaning by rememberSaveable { mutableStateOf("") }
    var correctingId by rememberSaveable { mutableStateOf<String?>(null) }
    var correction by rememberSaveable { mutableStateOf("") }
    var deleteConfirmationId by rememberSaveable { mutableStateOf<String?>(null) }

    fun apply(result: RivenRuntimeResult, success: String? = null) {
        when (result) {
            is RivenRuntimeResult.Success -> {
                onSnapshot(result.snapshot)
                notice = success
            }
            is RivenRuntimeResult.Failure -> {
                result.snapshot?.let(onSnapshot)
                notice = result.message
            }
        }
    }

    fun edit(profile: ProviderProfileSnapshot?) {
        editingProfileId = profile?.profileId
        displayName = profile?.displayName.orEmpty()
        modelId = profile?.modelId.orEmpty()
        apiKey = ""
    }

    LaunchedEffect(snapshot?.selectedProfileId) {
        if (editingProfileId == null && displayName.isBlank() && modelId.isBlank()) {
            edit(snapshot?.profiles?.singleOrNull { it.profileId == snapshot.selectedProfileId })
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(PenthouseNavy)
            .testTag(if (memoryOnly) "memory_screen" else "settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(if (memoryOnly) "Memory" else "Settings", color = WarmIvory, style = MaterialTheme.typography.headlineMedium)
            Text(
                if (memoryOnly) "Review what Riven may carry forward."
                else "Provider secrets stay in Android Keystore-backed app storage.",
                color = MistBlue,
            )
        }
        notice?.let { item { StatusBanner(it) } }
        if (!memoryOnly) {
        item { SectionTitle("OpenRouter profiles") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(snapshot?.profiles.orEmpty(), key = { it.profileId }) { profile ->
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                apply(
                                    runtimeIo { runtime.selectProfile(profile.profileId) },
                                    "Selected ${profile.displayName}.",
                                )
                                edit(profile)
                            }
                        },
                    ) {
                        Text(
                            if (profile.profileId == snapshot?.selectedProfileId) "✓ ${profile.displayName}"
                            else profile.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item { TextButton(onClick = { edit(null) }) { Text("New profile") } }
            }
        }
        item {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth().testTag("profile_name"),
            )
        }
        item {
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                label = { Text("Model ID, e.g. anthropic/claude-sonnet-4.6") },
                modifier = Modifier.fillMaxWidth().testTag("profile_model"),
            )
        }
        item {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("OpenRouter API key (leave blank to keep saved key)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("profile_api_key"),
            )
        }
        item {
            Text(
                if (snapshot?.selectedProfileHasCredential == true) "API key stored securely"
                else "No API key stored",
                color = if (snapshot?.selectedProfileHasCredential == true) MistBlue else RubyHeart,
                modifier = Modifier.testTag("credential_status"),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val submittedApiKey = apiKey
                        apiKey = ""
                        scope.launch {
                            when (
                                val result = runtimeIo {
                                    runtime.saveProfile(
                                        editingProfileId,
                                        displayName,
                                        modelId,
                                        submittedApiKey,
                                    )
                                }
                            ) {
                                is RivenProfileSaveResult.Success -> {
                                    editingProfileId = result.profile.profileId
                                    val refreshed = runtimeIo { runtime.snapshot() }
                                    apply(refreshed, "Profile saved.")
                                }
                                is RivenProfileSaveResult.Failure -> notice = result.message
                            }
                        }
                    },
                    enabled = displayName.isNotBlank() && modelId.isNotBlank(),
                    modifier = Modifier.testTag("profile_save"),
                ) { Text("Save profile") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            when (val result = runtimeIo { runtime.fetchModels() }) {
                                is OpenRouterModelCatalogResult.Success -> {
                                    models = result.models
                                    notice = "Loaded ${result.models.size} models."
                                }
                                is OpenRouterModelCatalogResult.Failure ->
                                    notice = "Models could not be loaded: ${result.code.name}."
                            }
                        }
                    },
                    enabled = snapshot?.selectedProfileHasCredential == true,
                    modifier = Modifier.testTag("models_refresh"),
                ) { Text("Load models") }
            }
        }
        if (models.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = TableNavy)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Select a model", color = MutedGold, fontWeight = FontWeight.Bold)
                        models.take(MAX_VISIBLE_MODEL_CHOICES).forEach { model ->
                            TextButton(onClick = { modelId = model.id }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "${model.name} · ${model.id}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        apply(runtimeIo { runtime.removeOpenRouterCredential() }, "API key removed.")
                    }
                },
                enabled = snapshot?.selectedProfileHasCredential == true,
            ) { Text("Remove saved API key") }
        }
        item { HorizontalDivider(color = MutedGold.copy(alpha = 0.3f)) }
        item { SectionTitle("Shai System Instructions") }
        item {
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Instructions for future turns") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("system_instructions"),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = instructionsEnabled, onCheckedChange = { instructionsEnabled = it })
                Spacer(Modifier.width(8.dp))
                Text("Enabled", color = WarmIvory)
            }
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        apply(
                            runtimeIo {
                                runtime.saveInstructions(
                                    instructions,
                                    instructionsEnabled,
                                    snapshot?.instructions?.revision ?: 0L,
                                )
                            },
                            "System Instructions saved.",
                        )
                    }
                },
            ) { Text("Save instructions") }
        }
        item { HorizontalDivider(color = MutedGold.copy(alpha = 0.3f)) }
        }
        item { SectionTitle("Automatic memory") }
        item {
            val status = snapshot?.automaticMemoryStatus
            Card(
                colors = CardDefaults.cardColors(containerColor = TableNavy),
                modifier = Modifier.fillMaxWidth().testTag("automatic_memory_status"),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Pending ${status?.pending ?: 0}  •  Running ${status?.running ?: 0}  •  Failed ${status?.failed ?: 0}",
                        color = WarmIvory,
                    )
                    Text(
                        "Completed ${status?.succeeded ?: 0}  •  Excluded ${status?.excluded ?: 0}",
                        color = MistBlue,
                    )
                    status?.latestErrorCode?.let { code ->
                        Text("Latest processing status: $code", color = RubyHeart)
                    }
                }
            }
        }
        item {
            Text(
                "Completed canonical turns are processed through grounded attention, candidate validation, and provenance checks. Retry and regeneration do not reuse discarded reply evidence.",
                color = MistBlue,
            )
        }
        item { HorizontalDivider(color = MutedGold.copy(alpha = 0.3f)) }
        item { SectionTitle("Manual memory controls") }
        item {
            Text(
                "Remember / Correct / Forget / Delete remain explicit canonical controls. Forget and Delete also suppress automatic resurrection from their old evidence.",
                color = MistBlue,
            )
        }
        item {
            OutlinedTextField(
                value = memoryMeaning,
                onValueChange = { memoryMeaning = it },
                label = { Text("Meaning to remember") },
                modifier = Modifier.fillMaxWidth().testTag("memory_remember_input"),
            )
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        when (val result = runtimeIo { runtime.remember(memoryMeaning) }) {
                            is RivenRuntimeResult.Success -> {
                                apply(result, "Memory saved.")
                                memoryMeaning = ""
                            }
                            is RivenRuntimeResult.Failure -> apply(result)
                        }
                    }
                },
                enabled = memoryMeaning.isNotBlank(),
                modifier = Modifier.testTag("memory_remember"),
            ) { Text("Remember") }
        }
        items(snapshot?.memories.orEmpty(), key = { it.id }) { memory ->
            MemoryControlCard(
                memory = memory,
                correcting = correctingId == memory.id,
                correction = correction,
                confirmingDelete = deleteConfirmationId == memory.id,
                onStartCorrect = {
                    correctingId = memory.id
                    correction = memory.meaning
                },
                onCorrectionChange = { correction = it },
                onCorrect = {
                    scope.launch {
                        when (val result = runtimeIo { runtime.correct(memory.id, correction) }) {
                            is RivenRuntimeResult.Success -> {
                                apply(result, "Memory corrected.")
                                correctingId = null
                                correction = ""
                            }
                            is RivenRuntimeResult.Failure -> apply(result)
                        }
                    }
                },
                onForget = {
                    scope.launch { apply(runtimeIo { runtime.forget(memory.id) }, "Memory forgotten.") }
                },
                onRequestDelete = { deleteConfirmationId = memory.id },
                onCancelDelete = { deleteConfirmationId = null },
                onDelete = {
                    scope.launch {
                        apply(runtimeIo { runtime.deleteMemory(memory.id) }, "Memory deleted.")
                        deleteConfirmationId = null
                    }
                },
            )
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun MemoryControlCard(
    memory: RivenMemoryItem,
    correcting: Boolean,
    correction: String,
    confirmingDelete: Boolean,
    onStartCorrect: () -> Unit,
    onCorrectionChange: (String) -> Unit,
    onCorrect: () -> Unit,
    onForget: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = TableNavyRaised)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(memory.meaning, color = WarmIvory)
            Text(
                "${memory.certainty.name} · ${if (memory.isActive) "active" else "inactive"}",
                color = MistBlue,
                style = MaterialTheme.typography.labelSmall,
            )
            if (correcting) {
                OutlinedTextField(
                    value = correction,
                    onValueChange = onCorrectionChange,
                    label = { Text("Corrected meaning") },
                    modifier = Modifier.fillMaxWidth().testTag("memory_correction_input"),
                )
                Button(onClick = onCorrect, enabled = correction.isNotBlank()) { Text("Apply correction") }
            } else if (confirmingDelete) {
                Text("Delete removes canonical meaning and retains only opaque suppression lineage.", color = RubyHeart)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = RubyHeart)) {
                        Text("Confirm delete")
                    }
                    TextButton(onClick = onCancelDelete) { Text("Cancel") }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { TextButton(onClick = onStartCorrect) { Text("Correct") } }
                    item { TextButton(onClick = onForget, enabled = memory.isActive) { Text("Forget") } }
                    item { TextButton(onClick = onRequestDelete) { Text("Delete") } }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = MutedGold, style = MaterialTheme.typography.titleLarge)
}

private const val DRAFT_SAVE_DELAY_MILLIS = 350L
private const val MAX_VISIBLE_MODEL_CHOICES = 24
private const val MAX_PREVIEW_EDGE = 1_024
private val ShaiHunterGreen = Color(0xFF123629)

private suspend fun <T> runtimeIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
