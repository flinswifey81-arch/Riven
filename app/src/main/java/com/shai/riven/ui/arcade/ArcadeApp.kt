package com.shai.riven.ui.arcade

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import com.shai.riven.R
import com.shai.riven.ui.arcade.comet.CometTrailGame
import com.shai.riven.ui.arcade.spire.CelestialSpireGame
import com.shai.riven.ui.theme.AquaHeart
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
                text = "Celestial Spire and Comet Trail are playable • Three table previews and live Riven replies remain unconnected.",
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
        if (useScrollableLayout && game.hasPlayableEngine) {
            CompactPlayableGameScreen(
                game = game,
                state = state,
                onAction = onAction,
            )
        } else if (useScrollableLayout) {
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
                        externallyPaused = state.interactionHold == ArcadeInteractionHold.SOLO_PAUSED_FOR_CHAT,
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
                    externallyPaused = state.interactionHold == ArcadeInteractionHold.SOLO_PAUSED_FOR_CHAT,
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
private fun CompactPlayableGameScreen(
    game: ArcadeGame,
    state: ArcadeUiState,
    onAction: (ArcadeAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("compact_playable_game_screen"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = { onAction(ArcadeAction.ExitGame) },
                modifier = Modifier.semantics { contentDescription = "Return to Arcade" },
            ) {
                Text("‹")
            }
            Text(
                text = game.title,
                modifier = Modifier.weight(1f),
                color = WarmIvory,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QuietControl(
                quiet = state.quietMode,
                onToggle = { onAction(ArcadeAction.ToggleQuietMode) },
                compact = true,
            )
            TextButton(
                onClick = { onAction(ArcadeAction.OpenConversation) },
                modifier = Modifier.semantics { contentDescription = "Open conversation with Riven" },
            ) {
                Text("CHAT", fontSize = 10.sp)
            }
        }
        GameBoardCard(
            game = game,
            externallyPaused = state.interactionHold == ArcadeInteractionHold.SOLO_PAUSED_FOR_CHAT,
            onAction = onAction,
            compactGameLayout = true,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun GameBoardCard(
    game: ArcadeGame,
    externallyPaused: Boolean,
    onAction: (ArcadeAction) -> Unit,
    modifier: Modifier = Modifier,
    compactGameLayout: Boolean = false,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2633)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MutedGold.copy(alpha = 0.42f)),
    ) {
        when (game) {
            ArcadeGame.STACKER -> CelestialSpireGame(
                externallyPaused = externallyPaused,
                compactLayout = compactGameLayout,
            )
            ArcadeGame.KLONDIKE -> SolitairePreview(onAction)
            ArcadeGame.HEART_MATCH -> HeartMatchPreview(onAction)
            ArcadeGame.WRAPPING_SNAKE -> CometTrailGame(
                externallyPaused = externallyPaused,
                compactLayout = compactGameLayout,
            )
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
    ArcadeGame.STACKER -> error("Celestial Spire uses the responsive compact layout")
    ArcadeGame.KLONDIKE -> 520.dp
    ArcadeGame.HEART_MATCH -> 520.dp
    ArcadeGame.WRAPPING_SNAKE -> error("Comet Trail uses the responsive compact layout")
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
    val tileKinds = CelestialMatchTileKind.entries
    PreviewBoard(title = "CELESTIAL GRID", status = "Moons • hearts • stars") {
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
                        val tileKind = tileKinds[(row * 2 + column + if (row == 3) 1 else 0) % tileKinds.size]
                        CelestialMatchTile(
                            kind = tileKind,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onClick = { onAction(ArcadeAction.PreviewControl("Celestial tile swap")) },
                        )
                    }
                }
            }
            Text(
                text = "Distinct moon, heart, and star tiles preview the look; cascades and effects remain pending.",
                modifier = Modifier.fillMaxWidth(),
                color = MistBlue,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private enum class CelestialMatchTileKind {
    MOON,
    HEART,
    STAR,
}

@Composable
private fun CelestialMatchTile(
    kind: CelestialMatchTileKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (kind) {
        CelestialMatchTileKind.MOON -> "Aqua moon tile"
        CelestialMatchTileKind.HEART -> "Ruby heart tile"
        CelestialMatchTileKind.STAR -> "Gold star tile"
    }
    Box(
        modifier = modifier
            .background(TableNavyRaised, RoundedCornerShape(10.dp))
            .border(1.dp, MutedGold.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(7.dp),
        ) {
            val tileCenter = center
            val radius = size.minDimension * 0.39f
            when (kind) {
                CelestialMatchTileKind.MOON -> {
                    drawCircle(AquaHeart, radius = radius, center = tileCenter)
                    drawCircle(
                        color = TableNavyRaised,
                        radius = radius * 0.84f,
                        center = tileCenter + Offset(radius * 0.48f, -radius * 0.08f),
                    )
                    drawCircle(
                        color = MutedGold.copy(alpha = 0.55f),
                        radius = radius * 1.08f,
                        center = tileCenter,
                        style = Stroke(width = 0.75.dp.toPx()),
                    )
                }

                CelestialMatchTileKind.HEART -> {
                    val heart = Path().apply {
                        moveTo(tileCenter.x, tileCenter.y + radius)
                        cubicTo(
                            tileCenter.x - radius * 1.18f,
                            tileCenter.y + radius * 0.24f,
                            tileCenter.x - radius * 0.82f,
                            tileCenter.y - radius * 0.88f,
                            tileCenter.x,
                            tileCenter.y - radius * 0.34f,
                        )
                        cubicTo(
                            tileCenter.x + radius * 0.82f,
                            tileCenter.y - radius * 0.88f,
                            tileCenter.x + radius * 1.18f,
                            tileCenter.y + radius * 0.24f,
                            tileCenter.x,
                            tileCenter.y + radius,
                        )
                        close()
                    }
                    drawPath(heart, color = RubyHeart)
                    drawPath(
                        path = heart,
                        color = MutedGold.copy(alpha = 0.52f),
                        style = Stroke(width = 0.75.dp.toPx()),
                    )
                }

                CelestialMatchTileKind.STAR -> {
                    val star = Path().apply {
                        repeat(10) { index ->
                            val pointRadius = if (index % 2 == 0) radius else radius * 0.42f
                            val angle = -PI / 2.0 + index * PI / 5.0
                            val x = tileCenter.x + cos(angle).toFloat() * pointRadius
                            val y = tileCenter.y + sin(angle).toFloat() * pointRadius
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    drawPath(star, color = MutedGold)
                    drawCircle(
                        color = WarmIvory.copy(alpha = 0.72f),
                        radius = radius * 0.13f,
                        center = tileCenter,
                    )
                }
            }
        }
    }
}

private enum class CelestialCardMotif {
    ORBIT,
    ECLIPSE,
    TWIN_STARS,
    CONSTELLATION,
}

private data class DemoCard(
    val id: String,
    val cornerLabel: String,
    val title: String,
    val effect: String,
    val accessibilityLabel: String,
    val color: Color,
    val motif: CelestialCardMotif,
)

@Composable
private fun SharedCardTablePreview(onAction: (ArcadeAction) -> Unit) {
    val hand = listOf(
        DemoCard(
            id = "red-seven",
            cornerLabel = "7",
            title = "RED",
            effect = "Number card",
            accessibilityLabel = "Red seven. Number card.",
            color = RubyHeart,
            motif = CelestialCardMotif.ORBIT,
        ),
        DemoCard(
            id = "aqua-three",
            cornerLabel = "3",
            title = "AQUA",
            effect = "Number card",
            accessibilityLabel = "Aqua three. Number card.",
            color = AquaHeart,
            motif = CelestialCardMotif.ORBIT,
        ),
        DemoCard(
            id = "eclipse",
            cornerLabel = "Ø",
            title = "ECLIPSE",
            effect = "Skip next turn",
            accessibilityLabel = "Eclipse. Skip the next turn.",
            color = MutedGold,
            motif = CelestialCardMotif.ECLIPSE,
        ),
        DemoCard(
            id = "violet-seven",
            cornerLabel = "7",
            title = "VIOLET",
            effect = "Number card",
            accessibilityLabel = "Violet seven. Number card.",
            color = VioletHeart,
            motif = CelestialCardMotif.ORBIT,
        ),
        DemoCard(
            id = "double-trouble",
            cornerLabel = "+2",
            title = "DOUBLE TROUBLE",
            effect = "Next player draws 2",
            accessibilityLabel = "Double Trouble. The next player draws two cards.",
            color = RubyHeart,
            motif = CelestialCardMotif.TWIN_STARS,
        ),
        DemoCard(
            id = "lime-five",
            cornerLabel = "5",
            title = "LIME",
            effect = "Number card",
            accessibilityLabel = "Lime five. Number card.",
            color = LimeHeart,
            motif = CelestialCardMotif.ORBIT,
        ),
        DemoCard(
            id = "rewrite-the-stars",
            cornerLabel = "★",
            title = "REWRITE THE STARS",
            effect = "Choose next color",
            accessibilityLabel = "Rewrite the Stars. Choose the next color.",
            color = VioletHeart,
            motif = CelestialCardMotif.CONSTELLATION,
        ),
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
                    CelestialPlayingCard(card = hand.first(), testTag = "celestial_discard")
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
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("celestial_hand"),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(
                    count = hand.size,
                    key = { index -> hand[index].id },
                ) { index ->
                    val card = hand[index]
                    CelestialPlayingCard(card = card, testTag = "celestial_hand_${card.id}")
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
private fun CelestialPlayingCard(
    card: DemoCard,
    testTag: String,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val width = if (largeText) 116.dp else 92.dp
    val height = if (largeText) 184.dp else 138.dp
    val shape = RoundedCornerShape(if (largeText) 12.dp else 10.dp)
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(WarmIvory, shape)
            .border(1.dp, MutedGold, shape)
            .clearAndSetSemantics { contentDescription = card.accessibilityLabel }
            .testTag(testTag),
    ) {
        CelestialCardArtwork(card = card)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = card.cornerLabel,
                    color = card.color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = card.cornerLabel,
                    color = card.color.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = card.cornerLabel,
                modifier = Modifier.testTag("${testTag}_primary"),
                color = card.color,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = card.title,
                color = DeepInk,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = card.effect,
                modifier = Modifier.testTag("${testTag}_effect"),
                color = DeepInk.copy(alpha = 0.82f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CelestialCardArtwork(card: DemoCard) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(3.dp),
    ) {
        val stroke = 0.75.dp.toPx()
        val innerInset = 2.dp.toPx()
        val corner = 7.dp.toPx()
        val bracket = size.minDimension * 0.16f
        val center = Offset(size.width / 2f, size.height * 0.43f)
        val radius = size.minDimension * 0.18f

        drawRoundRect(
            color = MutedGold.copy(alpha = 0.55f),
            topLeft = Offset(innerInset, innerInset),
            size = androidx.compose.ui.geometry.Size(
                width = size.width - innerInset * 2f,
                height = size.height - innerInset * 2f,
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = Stroke(width = stroke),
        )
        listOf(
            Offset(innerInset * 2f, innerInset * 2f),
            Offset(size.width - innerInset * 2f, innerInset * 2f),
            Offset(innerInset * 2f, size.height - innerInset * 2f),
            Offset(size.width - innerInset * 2f, size.height - innerInset * 2f),
        ).forEachIndexed { index, point ->
            val horizontalDirection = if (index % 2 == 0) 1f else -1f
            val verticalDirection = if (index < 2) 1f else -1f
            drawLine(
                color = MutedGold.copy(alpha = 0.62f),
                start = point,
                end = point + Offset(bracket * horizontalDirection, 0f),
                strokeWidth = stroke,
            )
            drawLine(
                color = MutedGold.copy(alpha = 0.62f),
                start = point,
                end = point + Offset(0f, bracket * verticalDirection),
                strokeWidth = stroke,
            )
        }

        when (card.motif) {
            CelestialCardMotif.ORBIT -> {
                drawCircle(card.color.copy(alpha = 0.12f), radius = radius, center = center)
                drawCircle(
                    color = MutedGold.copy(alpha = 0.72f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = stroke),
                )
                drawCircle(
                    color = card.color.copy(alpha = 0.72f),
                    radius = radius * 0.18f,
                    center = center + Offset(radius * 1.25f, 0f),
                )
                drawLine(
                    color = card.color.copy(alpha = 0.42f),
                    start = center - Offset(radius * 1.45f, 0f),
                    end = center + Offset(radius * 1.45f, 0f),
                    strokeWidth = stroke,
                )
            }

            CelestialCardMotif.ECLIPSE -> {
                drawCircle(card.color.copy(alpha = 0.34f), radius = radius, center = center)
                drawCircle(
                    color = WarmIvory,
                    radius = radius * 0.84f,
                    center = center + Offset(radius * 0.42f, -radius * 0.08f),
                )
                drawCircle(
                    color = MutedGold.copy(alpha = 0.78f),
                    radius = radius * 1.18f,
                    center = center,
                    style = Stroke(width = stroke),
                )
            }

            CelestialCardMotif.TWIN_STARS -> {
                drawCircle(
                    color = card.color.copy(alpha = 0.2f),
                    radius = radius * 0.72f,
                    center = center - Offset(radius * 0.48f, 0f),
                )
                drawCircle(
                    color = MutedGold.copy(alpha = 0.24f),
                    radius = radius * 0.72f,
                    center = center + Offset(radius * 0.48f, 0f),
                )
                drawCircle(
                    color = MutedGold.copy(alpha = 0.72f),
                    radius = radius * 1.35f,
                    center = center,
                    style = Stroke(width = stroke),
                )
            }

            CelestialCardMotif.CONSTELLATION -> {
                val points = listOf(
                    center + Offset(-radius * 1.05f, radius * 0.45f),
                    center + Offset(-radius * 0.42f, -radius * 0.82f),
                    center + Offset(radius * 0.18f, radius * 0.12f),
                    center + Offset(radius * 0.86f, -radius * 0.58f),
                    center + Offset(radius * 1.08f, radius * 0.55f),
                )
                points.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        color = MutedGold.copy(alpha = 0.72f),
                        start = start,
                        end = end,
                        strokeWidth = stroke,
                    )
                }
                val starColors = listOf(RubyHeart, AquaHeart, MutedGold, VioletHeart, LimeHeart)
                points.forEachIndexed { index, point ->
                    drawCircle(
                        color = starColors[index],
                        radius = if (index == 2) stroke * 3.2f else stroke * 2.1f,
                        center = point,
                    )
                }
            }
        }
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
                Image(
                    painter = painterResource(R.drawable.riven_card_back),
                    contentDescription = "Face-down card",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        .testTag("riven_card_back_art"),
                    contentScale = ContentScale.Fit,
                )
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
    ArcadeGame.STACKER ->
        "Playable • Endless line clearing with fixed manual speed; solo play refreshes instead of ending."
    ArcadeGame.KLONDIKE -> "Preview only • Draw-one Klondike engine and legal move handling are not implemented."
    ArcadeGame.HEART_MATCH -> "Preview only • Cascade, sound, and power-up thresholds/effects remain to be finalized."
    ArcadeGame.WRAPPING_SNAKE ->
        "Playable default for review • Predicted self-contact pauses before impact so another safe direction can be chosen without losing progress."
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
