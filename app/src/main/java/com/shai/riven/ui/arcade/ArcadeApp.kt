package com.shai.riven.ui.arcade

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shai.riven.ui.theme.AquaHeart
import com.shai.riven.ui.theme.CoralHeart
import com.shai.riven.ui.theme.DeepInk
import com.shai.riven.ui.theme.LimeHeart
import com.shai.riven.ui.theme.MistBlue
import com.shai.riven.ui.theme.MutedGold
import com.shai.riven.ui.theme.PenthouseNavy
import com.shai.riven.ui.theme.RivenTheme
import com.shai.riven.ui.theme.RubyHeart
import com.shai.riven.ui.theme.TableNavy
import com.shai.riven.ui.theme.TableNavyRaised
import com.shai.riven.ui.theme.VioletHeart
import com.shai.riven.ui.theme.WarmIvory
import kotlin.math.min

private val ArcadeUiStateSaver = listSaver<ArcadeUiState, Any>(
    save = { state ->
        listOf(
            state.selectedGameId.orEmpty(),
            state.quietMode,
            state.conversationOpen,
            state.conversationDraft,
            state.demoNotice.orEmpty(),
        )
    },
    restore = { saved ->
        ArcadeUiState(
            selectedGameId = (saved[0] as String).ifBlank { null },
            quietMode = saved[1] as Boolean,
            conversationOpen = saved[2] as Boolean,
            conversationDraft = saved[3] as String,
            demoNotice = (saved[4] as String).ifBlank { null },
        )
    },
)

@Composable
fun ArcadeApp(modifier: Modifier = Modifier) {
    var state by rememberSaveable(stateSaver = ArcadeUiStateSaver) {
        mutableStateOf(ArcadeUiState())
    }
    val dispatch: (ArcadeAction) -> Unit = { action ->
        state = reduceArcadeState(state, action)
    }

    BackHandler(enabled = state.conversationOpen || state.selectedGame != null) {
        dispatch(ArcadeAction.Back)
    }
    ArcadeExperience(
        state = state,
        onAction = dispatch,
        modifier = modifier,
    )
}

@Composable
fun ArcadeExperience(
    state: ArcadeUiState,
    onAction: (ArcadeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lobbyListState = rememberLazyListState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF07101A), PenthouseNavy, Color(0xFF0D2233)),
                ),
            ),
    ) {
        val selectedGame = state.selectedGame
        if (selectedGame == null) {
            ArcadeLobby(state = state, listState = lobbyListState, onAction = onAction)
        } else {
            ArcadeGameScreen(game = selectedGame, state = state, onAction = onAction)
        }

        if (state.conversationOpen) {
            ConversationOverlay(state = state, onAction = onAction)
        }
    }
}

@Composable
private fun ArcadeLobby(
    state: ArcadeUiState,
    listState: LazyListState,
    onAction: (ArcadeAction) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("arcade_catalog")
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 18.dp,
            end = 20.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RIVEN PENTHOUSE",
                        color = MutedGold,
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.8.sp,
                    )
                    Text(
                        text = "Arcade",
                        style = MaterialTheme.typography.displaySmall,
                        color = WarmIvory,
                    )
                }
                QuietControl(
                    quiet = state.quietMode,
                    onToggle = { onAction(ArcadeAction.ToggleQuietMode) },
                )
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = TableNavy.copy(alpha = 0.9f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MutedGold.copy(alpha = 0.34f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Open conversation with Riven" }
                        .clickable { onAction(ArcadeAction.OpenConversation) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TemporaryRivenPortrait()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.quietMode) "Quiet mode is on" else "Choose a table",
                            color = WarmIvory,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (state.quietMode) {
                                "I will stay quiet, but you can tap me whenever you want to chat."
                            } else {
                                "Five tables are being prepared. Tap my portrait to open conversation."
                            },
                            color = MistBlue,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        items(
            count = ArcadeGame.entries.size,
            key = { index -> ArcadeGame.entries[index].gameId },
        ) { index ->
            GameCatalogCard(
                game = ArcadeGame.entries[index],
                onOpen = { onAction(ArcadeAction.OpenGame(ArcadeGame.entries[index].gameId)) },
            )
        }

        item {
            Text(
                text = "Layout preview • Game engines and live Riven replies are not connected in this slice.",
                modifier = Modifier.fillMaxWidth(),
                color = MistBlue.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GameCatalogCard(
    game: ArcadeGame,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = TableNavyRaised.copy(alpha = 0.92f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MutedGold.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = game.eyebrow,
                    color = MutedGold,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp,
                )
                StatusPill(
                    text = if (game.mode == ArcadeGameMode.SOLO) "SOLO" else "WITH RIVEN",
                )
            }
            Text(
                text = game.title,
                color = WarmIvory,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = game.description,
                color = MistBlue,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "OPEN LAYOUT  →",
                color = MutedGold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ArcadeGameScreen(
    game: ArcadeGame,
    state: ArcadeUiState,
    onAction: (ArcadeAction) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val useScrollableLayout = maxHeight < 700.dp || LocalDensity.current.fontScale >= 1.3f
        if (useScrollableLayout) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("arcade_game_scroll"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 14.dp,
                    vertical = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    GameTopBar(
                        game = game,
                        quiet = state.quietMode,
                        onBack = { onAction(ArcadeAction.ExitGame) },
                        onQuietToggle = { onAction(ArcadeAction.ToggleQuietMode) },
                    )
                }
                item {
                    RivenCompanionBar(
                        game = game,
                        quiet = state.quietMode,
                        hold = state.interactionHold,
                        onPortraitTap = { onAction(ArcadeAction.OpenConversation) },
                    )
                }
                item {
                    GameBoardCard(
                        game = game,
                        onAction = onAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(compactBoardHeight(game)),
                    )
                }
                state.demoNotice?.let { notice ->
                    item {
                        PreviewNotice(
                            notice = notice,
                            onDismiss = { onAction(ArcadeAction.DismissDemoNotice) },
                        )
                    }
                }
                item { PendingRulesLabel(game = game, expanded = true) }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GameTopBar(
                    game = game,
                    quiet = state.quietMode,
                    onBack = { onAction(ArcadeAction.ExitGame) },
                    onQuietToggle = { onAction(ArcadeAction.ToggleQuietMode) },
                )
                RivenCompanionBar(
                    game = game,
                    quiet = state.quietMode,
                    hold = state.interactionHold,
                    onPortraitTap = { onAction(ArcadeAction.OpenConversation) },
                )
                GameBoardCard(
                    game = game,
                    onAction = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                state.demoNotice?.let { notice ->
                    PreviewNotice(
                        notice = notice,
                        onDismiss = { onAction(ArcadeAction.DismissDemoNotice) },
                    )
                }
                PendingRulesLabel(game = game, expanded = false)
            }
        }
    }
}

@Composable
private fun GameBoardCard(
    game: ArcadeGame,
    onAction: (ArcadeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2633)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MutedGold.copy(alpha = 0.42f)),
    ) {
        when (game) {
            ArcadeGame.STACKER -> StackerPreview(onAction)
            ArcadeGame.KLONDIKE -> SolitairePreview(onAction)
            ArcadeGame.HEART_MATCH -> HeartMatchPreview(onAction)
            ArcadeGame.WRAPPING_SNAKE -> SnakePreview(onAction)
            ArcadeGame.RIVEN_CARD_TABLE -> SharedCardTablePreview(onAction)
        }
    }
}

@Composable
private fun PendingRulesLabel(
    game: ArcadeGame,
    expanded: Boolean,
) {
    Text(
        text = pendingRulesText(game),
        modifier = Modifier.fillMaxWidth(),
        color = MistBlue.copy(alpha = 0.8f),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun compactBoardHeight(game: ArcadeGame): Dp = when (game) {
    ArcadeGame.STACKER -> 460.dp
    ArcadeGame.KLONDIKE -> 520.dp
    ArcadeGame.HEART_MATCH -> 520.dp
    ArcadeGame.WRAPPING_SNAKE -> 500.dp
    ArcadeGame.RIVEN_CARD_TABLE -> 640.dp
}

@Composable
private fun GameTopBar(
    game: ArcadeGame,
    quiet: Boolean,
    onBack: () -> Unit,
    onQuietToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("‹ Arcade")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = game.title,
                color = WarmIvory,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (game.mode == ArcadeGameMode.SOLO) "Solo layout" else "Shared table layout",
                color = MutedGold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        QuietControl(quiet = quiet, onToggle = onQuietToggle, compact = true)
    }
}

@Composable
private fun RivenCompanionBar(
    game: ArcadeGame,
    quiet: Boolean,
    hold: ArcadeInteractionHold,
    onPortraitTap: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = TableNavy.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MutedGold.copy(alpha = 0.32f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Open conversation with Riven" }
                .clickable(onClick = onPortraitTap)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TemporaryRivenPortrait(compact = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (hold) {
                        ArcadeInteractionHold.SOLO_PAUSED_FOR_CHAT -> "GAME PAUSED FOR CHAT"
                        ArcadeInteractionHold.SHARED_GAME_WAITING_FOR_CHAT -> "RIVEN IS WAITING"
                        ArcadeInteractionHold.NONE -> if (quiet) "QUIET MODE" else "DEMO COMMENTARY"
                    },
                    color = MutedGold,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    text = if (quiet) {
                        "Tap me when you want company."
                    } else {
                        game.commentary
                    },
                    color = WarmIvory,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "CHAT",
                color = MutedGold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun TemporaryRivenPortrait(compact: Boolean = false) {
    val size = if (compact) 54.dp else 68.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF34546A), TableNavy, DeepInk),
                ),
            )
            .border(2.dp, MutedGold, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "R",
            color = WarmIvory,
            fontSize = if (compact) 25.sp else 31.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "TEMP",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(MutedGold, RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            color = DeepInk,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun QuietControl(
    quiet: Boolean,
    onToggle: () -> Unit,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                contentDescription = "Quiet commentary"
                stateDescription = if (quiet) "On" else "Off"
            }
            .toggleable(
                value = quiet,
                role = Role.Switch,
                onValueChange = { onToggle() },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
    ) {
        Text(
            text = "Quiet",
            color = MistBlue,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        )
        Switch(
            checked = quiet,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .background(MutedGold.copy(alpha = 0.14f), RoundedCornerShape(99.dp))
            .border(1.dp, MutedGold.copy(alpha = 0.42f), RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        color = MutedGold,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun ConversationOverlay(
    state: ArcadeUiState,
    onAction: (ArcadeAction) -> Unit,
) {
    Dialog(
        onDismissRequest = { onAction(ArcadeAction.DismissConversation) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepInk.copy(alpha = 0.72f))
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxHeight * 0.94f)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(28.dp),
                color = TableNavyRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, MutedGold.copy(alpha = 0.6f)),
                shadowElevation = 18.dp,
            ) {
                Column(
                    modifier = Modifier
                        .testTag("arcade_conversation_scroll")
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TemporaryRivenPortrait(compact = true)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Conversation",
                                color = WarmIvory,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = holdDescription(state),
                                color = MutedGold,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        TextButton(onClick = { onAction(ArcadeAction.DismissConversation) }) {
                            Text("Dismiss")
                        }
                    }
                    HorizontalDivider(color = MutedGold.copy(alpha = 0.22f))
                    Text(
                        text = "Your draft stays here across dismissal and activity recreation.",
                        color = MistBlue,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.conversationDraft,
                        onValueChange = { onAction(ArcadeAction.UpdateConversationDraft(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        label = { Text("Say something to Riven") },
                        supportingText = {
                            Text("${state.conversationDraft.length}/$MAX_ARCADE_CONVERSATION_DRAFT_CHARS")
                        },
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = PenthouseNavy.copy(alpha = 0.76f),
                    ) {
                        Text(
                            text = "Layout only: sending and live provider replies are not connected yet.",
                            modifier = Modifier.padding(12.dp),
                            color = MistBlue,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { onAction(ArcadeAction.DismissConversation) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Keep draft & return to table")
                    }
                }
            }
        }
    }
}

private fun holdDescription(state: ArcadeUiState): String = when (state.interactionHold) {
    ArcadeInteractionHold.SOLO_PAUSED_FOR_CHAT -> "Solo table paused"
    ArcadeInteractionHold.SHARED_GAME_WAITING_FOR_CHAT -> "Shared table waiting"
    ArcadeInteractionHold.NONE -> "Arcade position preserved"
}

@Composable
private fun PreviewNotice(notice: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MutedGold.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MutedGold.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = notice,
                modifier = Modifier.weight(1f),
                color = WarmIvory,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    }
}

@Composable
private fun StackerPreview(onAction: (ArcadeAction) -> Unit) {
    PreviewBoard(title = "BLOCK BOARD", status = "Ruleset awaiting confirmation") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.72f)
                    .background(PenthouseNavy, RoundedCornerShape(16.dp))
                    .border(1.dp, MutedGold.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
            ) {
                val columns = 8
                val rows = 12
                val cell = min(size.width / columns, size.height / rows)
                val left = (size.width - columns * cell) / 2f
                val top = (size.height - rows * cell) / 2f
                for (column in 1 until columns) {
                    drawLine(
                        color = MistBlue.copy(alpha = 0.08f),
                        start = Offset(left + column * cell, top),
                        end = Offset(left + column * cell, top + rows * cell),
                    )
                }
                for (row in 1 until rows) {
                    drawLine(
                        color = MistBlue.copy(alpha = 0.08f),
                        start = Offset(left, top + row * cell),
                        end = Offset(left + columns * cell, top + row * cell),
                    )
                }
                val blocks = listOf(
                    Triple(1, 9, RubyHeart),
                    Triple(2, 9, RubyHeart),
                    Triple(2, 10, AquaHeart),
                    Triple(3, 10, AquaHeart),
                    Triple(4, 8, VioletHeart),
                    Triple(4, 9, VioletHeart),
                    Triple(5, 9, MutedGold),
                    Triple(6, 10, CoralHeart),
                    Triple(6, 7, LimeHeart),
                    Triple(7, 7, LimeHeart),
                )
                blocks.forEach { (column, row, color) ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left + column * cell + 2f, top + row * cell + 2f),
                        size = androidx.compose.ui.geometry.Size(cell - 4f, cell - 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.18f),
                    )
                }
            }
            Button(
                onClick = { onAction(ArcadeAction.PreviewControl("Stacker board control")) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MutedGold),
            ) {
                Text("CONTROL LAYOUT PREVIEW", color = DeepInk)
            }
        }
    }
}

@Composable
private fun SolitairePreview(onAction: (ArcadeAction) -> Unit) {
    PreviewBoard(title = "DRAW-ONE TABLE", status = "Deal shown for layout") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PlayingCard(back = true, compact = true)
                    PlayingCard(label = "Q", suit = "♥", color = RubyHeart, compact = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("♠", "♥", "♣", "♦").forEachIndexed { index, suit ->
                        PlayingCard(
                            label = if (index == 1) "A" else "",
                            suit = suit,
                            color = if (index % 2 == 0) DeepInk else RubyHeart,
                            compact = true,
                            empty = index != 1,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                repeat(7) { column ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy((-28).dp),
                    ) {
                        repeat(min(column + 1, 4)) { card ->
                            PlayingCard(
                                back = card < min(column, 3),
                                label = if (card >= min(column, 3)) "${column + 3}" else "",
                                suit = if (column % 2 == 0) "♣" else "♦",
                                color = if (column % 2 == 0) DeepInk else RubyHeart,
                                compact = true,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { onAction(ArcadeAction.PreviewControl("Draw one")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("DRAW ONE")
            }
        }
    }
}

@Composable
private fun HeartMatchPreview(onAction: (ArcadeAction) -> Unit) {
    val heartColors = listOf(RubyHeart, AquaHeart, VioletHeart, CoralHeart, LimeHeart)
    PreviewBoard(title = "HEART GRID", status = "Swap & cascade preview") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(6) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(6) { column ->
                        val color = heartColors[(row * 2 + column + if (row == 3) 1 else 0) % heartColors.size]
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(TableNavyRaised, RoundedCornerShape(10.dp))
                                .clickable {
                                    onAction(ArcadeAction.PreviewControl("Heart swap"))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("♥", color = color, fontSize = 28.sp)
                        }
                    }
                }
            }
            Text(
                text = "Cascades, sound, and power-up effects follow in the engine slice.",
                modifier = Modifier.fillMaxWidth(),
                color = MistBlue,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SnakePreview(onAction: (ArcadeAction) -> Unit) {
    PreviewBoard(title = "WRAPPING GRID", status = "Mindful endless preview") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(PenthouseNavy, RoundedCornerShape(18.dp))
                    .border(1.dp, MutedGold.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
            ) {
                val cell = size.minDimension / 12f
                for (index in 1 until 12) {
                    drawLine(
                        color = MistBlue.copy(alpha = 0.08f),
                        start = Offset(index * cell, 0f),
                        end = Offset(index * cell, size.height),
                    )
                    drawLine(
                        color = MistBlue.copy(alpha = 0.08f),
                        start = Offset(0f, index * cell),
                        end = Offset(size.width, index * cell),
                    )
                }
                val snake = listOf(Offset(2f, 6f), Offset(3f, 6f), Offset(4f, 6f), Offset(4f, 5f), Offset(5f, 5f))
                snake.forEachIndexed { index, point ->
                    drawRoundRect(
                        color = if (index == snake.lastIndex) MutedGold else AquaHeart,
                        topLeft = Offset(point.x * cell + 2f, point.y * cell + 2f),
                        size = androidx.compose.ui.geometry.Size(cell - 4f, cell - 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.25f),
                    )
                }
                drawCircle(
                    color = RubyHeart,
                    radius = cell * 0.32f,
                    center = Offset(9.5f * cell, 3.5f * cell),
                )
                drawCircle(
                    color = MutedGold.copy(alpha = 0.38f),
                    radius = size.minDimension / 2f - 2f,
                    center = center,
                    style = Stroke(width = 2f),
                )
            }
            Button(
                onClick = { onAction(ArcadeAction.PreviewControl("Snake direction pad")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("DIRECTION PAD PREVIEW")
            }
        }
    }
}

private data class DemoCard(
    val label: String,
    val colorName: String,
    val color: Color,
)

@Composable
private fun SharedCardTablePreview(onAction: (ArcadeAction) -> Unit) {
    val hand = listOf(
        DemoCard("7", "RED", RubyHeart),
        DemoCard("3", "AQUA", AquaHeart),
        DemoCard("SKIP", "GOLD", MutedGold),
        DemoCard("7", "VIOLET", VioletHeart),
        DemoCard("+2", "RED", RubyHeart),
        DemoCard("5", "LIME", LimeHeart),
        DemoCard("WILD", "ANY", VioletHeart),
    )
    PreviewBoard(title = "SHARED TABLE", status = "Demo hand • no engine") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("RIVEN • 7 CARDS", color = MistBlue, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                repeat(7) { PlayingCard(back = true, compact = true) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayingCard(back = true)
                    Text("DRAW", color = MistBlue, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(18.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayingCard(label = "7", suit = "RED", color = RubyHeart)
                    Text("DISCARD", color = MistBlue, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("Trade", "Cheat", "Call Out").forEach { label ->
                    Button(
                        onClick = { onAction(ArcadeAction.PreviewControl(label)) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (label == "Call Out") RubyHeart.copy(alpha = 0.9f) else TableNavyRaised,
                            contentColor = WarmIvory,
                        ),
                    ) {
                        Text(label.uppercase(), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Text("YOUR HAND", color = MistBlue, style = MaterialTheme.typography.labelSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                hand.forEach { card ->
                    PlayingCard(
                        label = card.label,
                        suit = card.colorName,
                        color = card.color,
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewBoard(
    title: String,
    status: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableNavyRaised.copy(alpha = 0.72f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = MutedGold, style = MaterialTheme.typography.labelMedium)
            Text(status, color = MistBlue, style = MaterialTheme.typography.labelSmall)
        }
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun PlayingCard(
    label: String = "",
    suit: String = "",
    color: Color = DeepInk,
    back: Boolean = false,
    compact: Boolean = false,
    empty: Boolean = false,
) {
    val width = if (compact) 38.dp else 58.dp
    val height = if (compact) 56.dp else 84.dp
    val shape = RoundedCornerShape(if (compact) 7.dp else 10.dp)
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(
                color = when {
                    empty -> Color.Transparent
                    back -> TableNavyRaised
                    else -> WarmIvory
                },
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = if (empty) MutedGold.copy(alpha = 0.45f) else MutedGold,
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            empty -> Text(suit, color = MistBlue.copy(alpha = 0.55f), fontSize = 16.sp)
            back -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp)
                        .border(1.dp, MutedGold.copy(alpha = 0.6f), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("R", color = MutedGold, fontWeight = FontWeight.Bold)
                }
            }

            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    color = color,
                    fontSize = if (label.length > 2) 10.sp else if (compact) 18.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (suit.isNotBlank()) {
                    Text(
                        text = suit,
                        color = color,
                        fontSize = if (suit.length > 2) 7.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun pendingRulesText(game: ArcadeGame): String = when (game) {
    ArcadeGame.STACKER -> "Preview only • Board actions and scoring await the confirmed Stacker ruleset."
    ArcadeGame.KLONDIKE -> "Preview only • Draw-one Klondike engine and legal move handling are not implemented."
    ArcadeGame.HEART_MATCH -> "Preview only • Cascade, sound, and power-up thresholds/effects remain to be finalized."
    ArcadeGame.WRAPPING_SNAKE -> "Preview only • Edge wrapping is intended; self-collision behavior remains undecided."
    ArcadeGame.RIVEN_CARD_TABLE ->
        "Preview only • Deck, penalties, challenges, trade protocol, and hidden-information engine remain pending."
}

@Preview(name = "Arcade lobby", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun ArcadeLobbyPreview() {
    RivenTheme {
        ArcadeExperience(state = ArcadeUiState(), onAction = {})
    }
}

@Preview(name = "Shared card table", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SharedCardTablePreview() {
    RivenTheme {
        ArcadeExperience(
            state = ArcadeUiState(selectedGameId = ArcadeGame.RIVEN_CARD_TABLE.gameId),
            onAction = {},
        )
    }
}

@Preview(name = "Heart match", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun HeartMatchLayoutPreview() {
    RivenTheme {
        ArcadeExperience(
            state = ArcadeUiState(selectedGameId = ArcadeGame.HEART_MATCH.gameId),
            onAction = {},
        )
    }
}
