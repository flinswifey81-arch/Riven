package com.shai.riven.ui.arcade.cosmic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shai.riven.R
import com.shai.riven.ui.theme.AquaHeart
import com.shai.riven.ui.theme.DeepInk
import com.shai.riven.ui.theme.LimeHeart
import com.shai.riven.ui.theme.MistBlue
import com.shai.riven.ui.theme.MutedGold
import com.shai.riven.ui.theme.RubyHeart
import com.shai.riven.ui.theme.TableNavy
import com.shai.riven.ui.theme.TableNavyRaised
import com.shai.riven.ui.theme.VioletHeart
import com.shai.riven.ui.theme.WarmIvory

private val CosmicHunterGreen = Color(0xFF173F35)
private val CosmicNavy = Color(0xFF081925)

@Composable
fun CosmicMischiefGame(
    externallyWaiting: Boolean,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    storeOverride: CosmicMischiefStore? = null,
    opponentOverride: CosmicOpponentAgent? = null,
    opponentExecutionOverride: CosmicOpponentExecution? = null,
    opponentTurnGateOverride: CosmicOpponentTurnGate? = null,
    initialSeed: Long? = null,
) {
    val context = LocalContext.current
    val store = storeOverride ?: remember(context) { SharedPreferencesCosmicMischiefStore(context) }
    val opponent = opponentOverride ?: remember { DeterministicCosmicOpponentAgent() }
    val localOpponentExecution = remember { CosmicOpponentExecution() }
    val opponentExecution = opponentExecutionOverride ?: localOpponentExecution
    val opponentRunner = remember(opponentExecution) { CosmicOpponentTurnRunner(opponentExecution) }
    val localOpponentTurnGate = remember { CosmicOpponentTurnGate() }
    val opponentTurnGate = opponentTurnGateOverride ?: localOpponentTurnGate
    DisposableEffect(opponentExecution) {
        onDispose { opponentExecution.close() }
    }
    val sessionHolder = remember(store, initialSeed) {
        mutableStateOf(
            store.loadSession() ?: CosmicMischiefSession(
                CosmicMischiefEngine.newGame(initialSeed ?: System.currentTimeMillis()),
            ),
        )
    }
    var session by sessionHolder
    var settings by remember(store) { mutableStateOf(store.loadSettings()) }
    var selectedCardId by rememberSaveable { mutableStateOf<Int?>(null) }
    var cheatPrimaryId by rememberSaveable { mutableStateOf<Int?>(null) }
    var chosenColor by rememberSaveable { mutableStateOf(CosmicColor.RUBY) }
    var notice by rememberSaveable {
        mutableStateOf("Offline table ready. This fallback is not live Riven.")
    }
    var confirmFreshGame by rememberSaveable { mutableStateOf(false) }
    val state = session.game
    val public = CosmicMischiefEngine.publicObservation(state)
    val userHand = state.hand(CosmicPlayer.SHAI)
    val selectedCard = selectedCardId?.let { id -> userHand.firstOrNull { it.id == id } }
    val cheatPrimary = cheatPrimaryId?.let { id -> userHand.firstOrNull { it.id == id } }
    val userDecision = !externallyWaiting &&
        state.status == CosmicStatus.PLAYING &&
        CosmicMischiefEngine.decisionPlayer(state) == CosmicPlayer.SHAI

    fun applyAction(actor: CosmicPlayer, action: CosmicAction) {
        val before = sessionHolder.value
        val result = CosmicMischiefEngine.apply(
            before,
            CosmicCommand(actor, before.game.revision, action),
        )
        if (!result.changed) {
            notice = result.message
            return
        }
        session = result.session
        store.saveSession(result.session)
        selectedCardId = null
        cheatPrimaryId = null
        notice = result.message
    }

    LaunchedEffect(session, store) { store.saveSession(session) }
    LaunchedEffect(settings, store) { store.saveSettings(settings) }
    LaunchedEffect(
        state.revision,
        state.gameNumber,
        state.dealSeed,
        state.status,
        externallyWaiting,
        opponent,
    ) {
        opponentTurnGate.updatePaused(externallyWaiting)
        if (
            !externallyWaiting &&
            state.status == CosmicStatus.PLAYING &&
            CosmicMischiefEngine.decisionPlayer(state) == CosmicPlayer.RIVEN
        ) {
            val turnPermit = opponentTurnGate.acquirePermit() ?: return@LaunchedEffect
            val capturedSession = sessionHolder.value
            val outcome = opponentRunner.run(capturedSession, opponent) ?: return@LaunchedEffect
            val committedSession = opponentTurnGate.commitIfPermitted(turnPermit) {
                if (
                    sessionHolder.value !== capturedSession ||
                    CosmicMischiefEngine.decisionPlayer(sessionHolder.value.game) != CosmicPlayer.RIVEN
                ) {
                    null
                } else {
                    session = outcome.actionResult.session
                    selectedCardId = null
                    cheatPrimaryId = null
                    notice = outcome.notice
                    outcome.actionResult.session
                }
            } ?: return@LaunchedEffect
            store.saveSession(committedSession)
        }
    }

    CosmicMischiefLayout(
        state = state,
        settings = settings,
        selectedCardId = selectedCardId,
        cheatPrimaryId = cheatPrimaryId,
        chosenColor = chosenColor,
        notice = notice,
        externallyWaiting = externallyWaiting,
        userDecision = userDecision,
        compactLayout = compactLayout,
        opponentName = "Offline rival",
        onSelectCard = { cardId ->
            if (userDecision) {
                selectedCardId = if (selectedCardId == cardId) null else cardId
                notice = when {
                    cheatPrimaryId != null -> "Extra card selected. Sneak it or choose another."
                    else -> "Card selected. Play it, offer it, or begin a two-card cheat."
                }
            }
        },
        onChooseColor = { chosenColor = it },
        onPlay = {
            selectedCard?.let { card ->
                applyAction(
                    CosmicPlayer.SHAI,
                    CosmicAction.PlayCard(
                        cardId = card.id,
                        chosenColor = chosenColor.takeIf { card.kind == CosmicCardKind.RewriteTheStars },
                    ),
                )
            }
        },
        onDraw = { applyAction(CosmicPlayer.SHAI, CosmicAction.DrawCard) },
        onCheat = {
            val primary = cheatPrimary
            val extra = selectedCard
            when {
                primary == null && extra != null -> {
                    cheatPrimaryId = extra.id
                    selectedCardId = null
                    notice = "Primary card set. Choose a different card to sneak as the extra discard."
                }
                primary != null && extra != null && primary.id != extra.id -> {
                    applyAction(
                        CosmicPlayer.SHAI,
                        CosmicAction.SneakExtraDiscard(
                            primaryCardId = primary.id,
                            extraCardId = extra.id,
                            primaryChosenColor = chosenColor.takeIf {
                                primary.kind == CosmicCardKind.RewriteTheStars
                            },
                            extraChosenColor = chosenColor.takeIf {
                                extra.kind == CosmicCardKind.RewriteTheStars
                            },
                        ),
                    )
                }
                else -> notice = "Select a primary card, then choose a different extra card."
            }
        },
        onCancelCheat = {
            cheatPrimaryId = null
            selectedCardId = null
            notice = "Cheat cancelled. No card moved."
        },
        onCallOut = { applyAction(CosmicPlayer.SHAI, CosmicAction.CallOut) },
        onDeclineCallout = { applyAction(CosmicPlayer.SHAI, CosmicAction.DeclineCallout) },
        onOfferTrade = {
            selectedCard?.let { card ->
                applyAction(CosmicPlayer.SHAI, CosmicAction.OfferTrade(card.id))
            }
        },
        onTradeResponse = { decision ->
            applyAction(
                CosmicPlayer.SHAI,
                CosmicAction.RespondToTrade(
                    decision = decision,
                    returnedCardId = selectedCardId.takeIf { decision == CosmicTradeDecision.HONOR },
                ),
            )
        },
        onToggleLargeText = {
            settings = settings.copy(largeCardText = !settings.largeCardText)
            store.saveSettings(settings)
        },
        onFreshGame = { confirmFreshGame = true },
        modifier = modifier,
    )

    if (confirmFreshGame) {
        AlertDialog(
            onDismissRequest = { confirmFreshGame = false },
            title = { Text("Start a fresh Cosmic Mischief game?") },
            text = {
                Text(
                    "This replaces the current table after confirmation. Playful grudge totals carry forward; " +
                        "hidden cards from this game will not remain available.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fresh = CosmicMischiefEngine.freshGame(
                            sessionHolder.value,
                            System.currentTimeMillis(),
                        )
                        session = fresh
                        store.saveSession(fresh)
                        selectedCardId = null
                        cheatPrimaryId = null
                        notice = "Fresh game confirmed. Seven cards each; Shai acts first."
                        confirmFreshGame = false
                    },
                ) {
                    Text("START FRESH")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFreshGame = false }) { Text("KEEP THIS GAME") }
            },
        )
    }
}

@Composable
private fun CosmicMischiefLayout(
    state: CosmicMischiefState,
    settings: CosmicMischiefSettings,
    selectedCardId: Int?,
    cheatPrimaryId: Int?,
    chosenColor: CosmicColor,
    notice: String,
    externallyWaiting: Boolean,
    userDecision: Boolean,
    compactLayout: Boolean,
    opponentName: String,
    onSelectCard: (Int) -> Unit,
    onChooseColor: (CosmicColor) -> Unit,
    onPlay: () -> Unit,
    onDraw: () -> Unit,
    onCheat: () -> Unit,
    onCancelCheat: () -> Unit,
    onCallOut: () -> Unit,
    onDeclineCallout: () -> Unit,
    onOfferTrade: () -> Unit,
    onTradeResponse: (CosmicTradeDecision) -> Unit,
    onToggleLargeText: () -> Unit,
    onFreshGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val public = CosmicMischiefEngine.publicObservation(state)
    val selected = selectedCardId?.let { id -> state.hand(CosmicPlayer.SHAI).firstOrNull { it.id == id } }
    val statusText = when (state.status) {
        CosmicStatus.PLAYING -> when {
            externallyWaiting -> "WAITING FOR CHAT"
            public.decisionPlayer == CosmicPlayer.SHAI -> "YOUR DECISION"
            else -> "OFFLINE RIVAL DECIDING"
        }
        CosmicStatus.SHAI_WON -> "SHAI WON"
        CosmicStatus.RIVEN_WON -> "OFFLINE RIVAL WON"
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(CosmicNavy, CosmicHunterGreen, Color(0xFF0B2027))),
            )
            .testTag("cosmic_mischief_board")
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactLayout) 5.dp else 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableNavyRaised.copy(alpha = 0.92f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("COSMIC MISCHIEF", color = MutedGold, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    "Game ${state.gameNumber} • ${state.moves} moves • Active ${state.activeColor.label}",
                    color = MistBlue,
                    fontSize = if (settings.largeCardText) 13.sp else 11.sp,
                )
            }
            Text(statusText, color = WarmIvory, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "$opponentName • NOT LIVE RIVEN • ${state.hand(CosmicPlayer.RIVEN).size} CARDS",
                color = MutedGold,
                fontSize = if (settings.largeCardText) 13.sp else 10.sp,
                fontWeight = FontWeight.Bold,
            )
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (settings.largeCardText) 72.dp else 58.dp)
                    .testTag("cosmic_opponent_hand"),
                horizontalArrangement = Arrangement.spacedBy((-16).dp),
            ) {
                items(state.hand(CosmicPlayer.RIVEN).size) { index ->
                    CosmicCardBack(
                        width = if (settings.largeCardText) 48.dp else 40.dp,
                        tag = "cosmic_opponent_card_$index",
                        contentDescription = "Face-down opponent card",
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CosmicCardBack(
                    width = if (settings.largeCardText) 72.dp else 62.dp,
                    tag = "cosmic_draw_pile",
                    contentDescription = "Face-down draw pile",
                )
                Text("DRAW ${state.drawPile.size}", color = MistBlue, fontSize = 10.sp)
            }
            Spacer(Modifier.width(18.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CosmicCardFace(
                    card = state.topDiscard,
                    selected = false,
                    enabled = false,
                    largeText = settings.largeCardText,
                    tag = "cosmic_discard",
                    onClick = {},
                )
                Text("DISCARD", color = MistBlue, fontSize = 10.sp)
            }
        }

        public.visibleTell?.let { tell ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .testTag("cosmic_cheat_tell")
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
                color = RubyHeart.copy(alpha = 0.24f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RubyHeart),
            ) {
                Text(
                    tell.message,
                    modifier = Modifier.padding(9.dp),
                    color = WarmIvory,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        public.pendingTrade?.let { trade ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .testTag("cosmic_trade_offer"),
                color = TableNavyRaised.copy(alpha = 0.96f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MutedGold),
            ) {
                Text(
                    "${trade.proposer.displayName} makes a face-up offer of ${trade.offeredCard.spokenName}; " +
                        "the responder chooses the return card privately.",
                    modifier = Modifier.padding(9.dp),
                    color = WarmIvory,
                    textAlign = TextAlign.Center,
                )
            }
        }

        CosmicActionControls(
            state = state,
            selected = selected,
            cheatPrimaryId = cheatPrimaryId,
            chosenColor = chosenColor,
            userDecision = userDecision,
            onChooseColor = onChooseColor,
            onPlay = onPlay,
            onDraw = onDraw,
            onCheat = onCheat,
            onCancelCheat = onCancelCheat,
            onCallOut = onCallOut,
            onDeclineCallout = onDeclineCallout,
            onOfferTrade = onOfferTrade,
            onTradeResponse = onTradeResponse,
        )

        Text(
            "YOUR HAND • ${state.hand(CosmicPlayer.SHAI).size} CARDS",
            modifier = Modifier.padding(horizontal = 10.dp),
            color = MutedGold,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (settings.largeCardText) 160.dp else 128.dp)
                .testTag("cosmic_user_hand"),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(
                items = state.hand(CosmicPlayer.SHAI),
                key = CosmicCard::id,
            ) { card ->
                CosmicCardFace(
                    card = card,
                    selected = selectedCardId == card.id || cheatPrimaryId == card.id,
                    enabled = userDecision,
                    largeText = settings.largeCardText,
                    tag = "cosmic_user_card_${card.id}",
                    onClick = { onSelectCard(card.id) },
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cosmic_notice")
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            color = TableNavy.copy(alpha = 0.96f),
        ) {
            Text(
                notice,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                color = WarmIvory,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cosmic_table_settings")
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = onToggleLargeText) {
                Text(if (settings.largeCardText) "TEXT: LARGE" else "TEXT: STANDARD")
            }
            TextButton(onClick = onFreshGame) { Text("FRESH GAME") }
            Text(
                "NO UNDO: SHARED HIDDEN INFORMATION",
                modifier = Modifier.align(Alignment.CenterVertically),
                color = MistBlue,
                fontSize = 9.sp,
            )
        }
        Text(
            COSMIC_MISCHIEF_PROVISIONAL_RULES,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            color = MistBlue.copy(alpha = 0.82f),
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            "GRUDGES • Shai ${state.grudges[CosmicPlayer.SHAI.ordinal]} • " +
                "$opponentName ${state.grudges[CosmicPlayer.RIVEN.ordinal]}",
            modifier = Modifier.fillMaxWidth(),
            color = MutedGold,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CosmicActionControls(
    state: CosmicMischiefState,
    selected: CosmicCard?,
    cheatPrimaryId: Int?,
    chosenColor: CosmicColor,
    userDecision: Boolean,
    onChooseColor: (CosmicColor) -> Unit,
    onPlay: () -> Unit,
    onDraw: () -> Unit,
    onCheat: () -> Unit,
    onCancelCheat: () -> Unit,
    onCallOut: () -> Unit,
    onDeclineCallout: () -> Unit,
    onOfferTrade: () -> Unit,
    onTradeResponse: (CosmicTradeDecision) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CosmicColor.entries.forEach { color ->
                val selectedColor = color == chosenColor
                TextButton(
                    onClick = { onChooseColor(color) },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            this.selected = selectedColor
                            stateDescription = if (selectedColor) "Chosen wild color" else "Available wild color"
                        },
                ) {
                    Text(color.label.uppercase(), color = cosmicColor(color), fontSize = 10.sp)
                }
            }
        }
        when {
            state.pendingCheat != null && userDecision -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CosmicButton(
                    text = "CALL OUT",
                    description = "Call out the observable extra-card tell",
                    enabled = true,
                    emphasized = true,
                    onClick = onCallOut,
                    modifier = Modifier.weight(1f),
                )
                CosmicButton(
                    text = "LET IT SLIDE",
                    description = "Decline the callout with no countdown",
                    enabled = true,
                    onClick = onDeclineCallout,
                    modifier = Modifier.weight(1f),
                )
            }

            state.pendingTrade?.responder == CosmicPlayer.SHAI && userDecision -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CosmicButton(
                    text = "HONOR",
                    description = "Honor the bargain using the selected return card",
                    enabled = selected != null,
                    onClick = { onTradeResponse(CosmicTradeDecision.HONOR) },
                )
                CosmicButton(
                    text = "BETRAY",
                    description = "Betray the bargain and keep the offered card",
                    enabled = true,
                    emphasized = true,
                    onClick = { onTradeResponse(CosmicTradeDecision.BETRAY) },
                )
                CosmicButton(
                    text = "REFUSE",
                    description = "Refuse the bargain",
                    enabled = true,
                    onClick = { onTradeResponse(CosmicTradeDecision.REFUSE) },
                )
            }

            else -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CosmicButton(
                    text = "PLAY",
                    description = "Play selected Cosmic Mischief card",
                    enabled = userDecision && selected != null && cheatPrimaryId == null,
                    onClick = onPlay,
                )
                CosmicButton(
                    text = "DRAW",
                    description = "Draw one card and yield the turn",
                    enabled = userDecision && cheatPrimaryId == null,
                    onClick = onDraw,
                )
                CosmicButton(
                    text = if (cheatPrimaryId == null) "CHEAT" else "SNEAK EXTRA",
                    description = if (cheatPrimaryId == null) {
                        "Set selected card as the primary play for a two-card cheat"
                    } else {
                        "Sneak selected card as an extra discard"
                    },
                    enabled = userDecision && selected != null,
                    emphasized = true,
                    onClick = onCheat,
                )
                if (cheatPrimaryId != null) {
                    CosmicButton(
                        text = "CANCEL",
                        description = "Cancel the uncommitted cheat",
                        enabled = true,
                        onClick = onCancelCheat,
                    )
                }
                CosmicButton(
                    text = "TRADE",
                    description = "Make a face-up offer; the responder privately chooses any return card",
                    enabled = userDecision && selected != null && cheatPrimaryId == null && !state.tradeUsedThisTurn,
                    onClick = onOfferTrade,
                )
                CosmicButton(
                    text = "CALL BLUFF",
                    description = "Call out without a tell; a false callout draws one and yields",
                    enabled = userDecision && cheatPrimaryId == null,
                    onClick = onCallOut,
                )
            }
        }
    }
}

@Composable
private fun CosmicButton(
    text: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minWidth = 72.dp, minHeight = 48.dp)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (emphasized) RubyHeart else TableNavyRaised,
            contentColor = WarmIvory,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Text(text, maxLines = 1, fontSize = 10.sp)
    }
}

@Composable
private fun CosmicCardFace(
    card: CosmicCard,
    selected: Boolean,
    enabled: Boolean,
    largeText: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val width = if (largeText) 92.dp else 72.dp
    val shape = RoundedCornerShape(10.dp)
    val accent = card.color?.let(::cosmicColor) ?: MutedGold
    Card(
        modifier = Modifier
            .width(width)
            .aspectRatio(0.68f)
            .defaultMinSize(minWidth = 64.dp, minHeight = 96.dp)
            .testTag(tag)
            .semantics {
                contentDescription = cosmicCardDescription(card)
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (selected) Modifier.border(4.dp, MutedGold, shape) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = WarmIvory),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(WarmIvory, accent.copy(alpha = 0.15f), CosmicNavy.copy(alpha = 0.14f)),
                    ),
                )
                .padding(6.dp),
        ) {
            Text(
                card.shortLabel,
                modifier = Modifier.align(Alignment.TopStart),
                color = accent,
                fontWeight = FontWeight.Black,
                fontSize = if (largeText) 18.sp else 14.sp,
            )
            Text(
                when (card.kind) {
                    is CosmicCardKind.Number -> "✦ ${card.kind.value} ✦"
                    CosmicCardKind.Eclipse -> "◐"
                    CosmicCardKind.DoubleTrouble -> "✦✦"
                    CosmicCardKind.RewriteTheStars -> "✧✦✧"
                },
                modifier = Modifier.align(Alignment.Center),
                color = accent,
                fontSize = if (largeText) 24.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when (card.kind) {
                    is CosmicCardKind.Number -> requireNotNull(card.color).label.uppercase()
                    CosmicCardKind.Eclipse -> "ECLIPSE"
                    CosmicCardKind.DoubleTrouble -> "DOUBLE\nTROUBLE"
                    CosmicCardKind.RewriteTheStars -> "REWRITE\nSTARS"
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = DeepInk,
                fontSize = if (largeText) 11.sp else 9.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun CosmicCardBack(width: Dp, tag: String, contentDescription: String) {
    Image(
        painter = painterResource(R.drawable.riven_card_back),
        contentDescription = contentDescription,
        modifier = Modifier
            .width(width)
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MutedGold, RoundedCornerShape(8.dp))
            .testTag(tag),
        contentScale = ContentScale.Crop,
    )
}

private fun cosmicColor(color: CosmicColor): Color = when (color) {
    CosmicColor.RUBY -> RubyHeart
    CosmicColor.AQUA -> AquaHeart
    CosmicColor.VIOLET -> VioletHeart
    CosmicColor.LIME -> LimeHeart
}

private fun cosmicCardDescription(card: CosmicCard): String = when (card.kind) {
    is CosmicCardKind.Number -> "${card.spokenName}. Match color or number."
    CosmicCardKind.Eclipse -> "${card.spokenName}. Skip the other player."
    CosmicCardKind.DoubleTrouble -> "${card.spokenName}. The other player draws two and loses the turn."
    CosmicCardKind.RewriteTheStars -> "Rewrite the Stars. Choose the active color."
}
