package com.shai.riven.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.provider.ProviderProfileSnapshot
import com.shai.riven.data.provider.openrouter.OpenRouterModel
import com.shai.riven.data.provider.openrouter.OpenRouterModelCatalogResult
import com.shai.riven.data.runtime.RivenConversationRuntime
import com.shai.riven.data.runtime.RivenMemoryItem
import com.shai.riven.data.runtime.RivenProfileSaveResult
import com.shai.riven.data.runtime.RivenRuntimeController
import com.shai.riven.data.runtime.RivenRuntimeResult
import com.shai.riven.data.runtime.RivenRuntimeSnapshot
import com.shai.riven.ui.arcade.ArcadeApp
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
    SETTINGS("Settings"),
    ARCADE("Arcade"),
}

@Composable
fun RivenApp(
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
    var pendingSubmission by remember { mutableStateOf<PendingDraftSubmission?>(null) }
    var nextSubmissionId by remember { mutableStateOf(0L) }
    var activeConversationJob by remember { mutableStateOf<Job?>(null) }

    fun beginSubmission(content: String, baselineUserMessageIds: Set<String>): Long {
        val submissionId = ++nextSubmissionId
        pendingSubmission = PendingDraftSubmission(submissionId, content, baselineUserMessageIds)
        return submissionId
    }

    fun settleSubmission(submissionId: Long?, restored: RivenRuntimeSnapshot?) {
        val pending = pendingSubmission ?: return
        if (submissionId != null && pending.submissionId != submissionId) return
        var committed = false
        restored?.let {
            snapshot = it
            persistedDraft = it.draft
            committed = it.messages.any { message ->
                message.role == MessageRole.USER && message.id !in pending.baselineUserMessageIds
            }
        }
        draft = if (committed) restored?.draft.orEmpty() else pending.content
        pendingSubmission = null
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
                if (pendingSubmission != null) settleSubmission(null, result.snapshot)
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

    LaunchedEffect(runtime, draft, persistedDraft, draftInitialized, pendingSubmission) {
        if (draftInitialized && pendingSubmission == null && draft != persistedDraft) {
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
                    if (pendingSubmission != null) settleSubmission(null, result.snapshot)
                    else if (!draftInitialized) draft = result.snapshot.draft
                }
                is RivenRuntimeResult.Failure -> notice = result.message
            }
        }
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
            destination == RivenDestination.ARCADE -> ArcadeApp(modifier = Modifier.padding(padding))
            loading -> LoadingScreen(padding)
            destination == RivenDestination.SETTINGS -> SettingsScreen(
                runtime = runtime,
                snapshot = snapshot,
                onSnapshot = { snapshot = it },
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
    onDraftSubmitted: (String, Set<String>) -> Long,
    onSubmissionSettled: (Long, RivenRuntimeSnapshot?) -> Unit,
    onRuntimeDraft: (String) -> Unit,
    externalNotice: String?,
    onSnapshot: (RivenRuntimeSnapshot) -> Unit,
    onConversationJobChanged: (Job?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var snapshot by remember(initialSnapshot) { mutableStateOf(initialSnapshot) }
    var sending by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var streamedReply by remember { mutableStateOf("") }
    var notice by remember(externalNotice) { mutableStateOf(externalNotice) }
    var conversationJob by remember { mutableStateOf<Job?>(null) }
    var activeSubmissionId by remember { mutableStateOf<Long?>(null) }
    val latestConversationJob by rememberUpdatedState(conversationJob)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    DisposableEffect(runtime) {
        onDispose { latestConversationJob?.cancel(CancellationException("Chat screen left")) }
    }

    fun apply(result: RivenRuntimeResult, submissionId: Long? = null) {
        when (result) {
            is RivenRuntimeResult.Success -> {
                snapshot = result.snapshot
                if (submissionId != null) onSubmissionSettled(submissionId, result.snapshot)
                else onRuntimeDraft(result.snapshot.draft)
                notice = null
                onSnapshot(result.snapshot)
            }
            is RivenRuntimeResult.Failure -> {
                result.snapshot?.let {
                    snapshot = it
                    if (submissionId == null) onRuntimeDraft(it.draft)
                    onSnapshot(it)
                }
                if (submissionId != null) onSubmissionSettled(submissionId, result.snapshot)
                notice = result.message
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
        Column(
            modifier = Modifier.fillMaxSize().imePadding().testTag(if (compact) "chat_compact" else "chat_normal"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("RIVEN", color = MutedGold, style = MaterialTheme.typography.labelMedium)
                    Text("Conversation", color = WarmIvory, style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    snapshot?.profiles?.singleOrNull { it.profileId == snapshot?.selectedProfileId }?.displayName
                        ?: "Not configured",
                    color = if (snapshot?.selectedProfileHasCredential == true) MistBlue else RubyHeart,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("chat_profile_status"),
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
                items(messages, key = { it.id }) { message -> MessageBubble(message.role, message.content) }
                if (streamedReply.isNotBlank()) {
                    item(key = "streaming") { MessageBubble(MessageRole.ASSISTANT, streamedReply, true) }
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
                                    scope.launch {
                                        try {
                                            apply(runtimeIo { runtime.cancel() }, submissionId)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            submissionId?.let { onSubmissionSettled(it, null) }
                                            notice = "Riven could not confirm cancellation. Your draft is still saved."
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
                Button(
                    onClick = {
                        val submissionId = onDraftSubmitted(
                            draft,
                            snapshot?.messages.orEmpty()
                                .filter { it.role == MessageRole.USER }
                                .mapTo(linkedSetOf()) { it.id },
                        )
                        runConversation(false, submissionId) { runtime.send(draft, it) }
                    },
                    enabled = !sending && !cancelling && draft.isNotBlank(),
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
private fun MessageBubble(role: MessageRole, content: String, streaming: Boolean = false) {
    val isRiven = role == MessageRole.ASSISTANT
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isRiven) Arrangement.Start else Arrangement.End,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            colors = CardDefaults.cardColors(
                containerColor = if (isRiven) DeepInk else ShaiHunterGreen,
            ),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                Modifier.artDecoBubbleFiligree().padding(horizontal = 32.dp, vertical = 24.dp),
            ) {
                Text(if (isRiven) "Riven" else "Shai", color = MutedGold, fontWeight = FontWeight.Bold)
                Text(
                    content + if (streaming) " …" else "",
                    color = WarmIvory,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
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
        modifier = modifier.fillMaxSize().background(PenthouseNavy).testTag("settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Settings", color = WarmIvory, style = MaterialTheme.typography.headlineMedium)
            Text("Provider secrets stay in Android Keystore-backed app storage.", color = MistBlue)
        }
        notice?.let { item { StatusBanner(it) } }
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
        item { SectionTitle("Manual memory controls") }
        item {
            Text(
                "These explicit controls use the canonical Remember / Correct / Forget / Delete services. Automatic extraction is not enabled yet.",
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

private data class PendingDraftSubmission(
    val submissionId: Long,
    val content: String,
    val baselineUserMessageIds: Set<String>,
)

private const val DRAFT_SAVE_DELAY_MILLIS = 350L
private const val MAX_VISIBLE_MODEL_CHOICES = 24
private val ShaiHunterGreen = Color(0xFF123629)

private suspend fun <T> runtimeIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
