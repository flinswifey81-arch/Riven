package com.shai.riven.ui.arcade.solitaire

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shai.riven.R
import com.shai.riven.ui.theme.DeepInk
import com.shai.riven.ui.theme.MistBlue
import com.shai.riven.ui.theme.MutedGold
import com.shai.riven.ui.theme.TableNavy
import com.shai.riven.ui.theme.TableNavyRaised
import com.shai.riven.ui.theme.WarmIvory

private val HunterGreen = Color(0xFF173F35)
private val CardRed = Color(0xFF9F3345)

private enum class SolitaireSelectionZone {
    WASTE,
    TABLEAU,
    FOUNDATION,
}

private data class SolitaireSelection(
    val zone: SolitaireSelectionZone,
    val column: Int = -1,
    val cardIndex: Int = -1,
    val suit: SolitaireSuit? = null,
)

private enum class SolitaireConfirmation {
    RESTART,
    NEW_DEAL,
}

@Composable
fun MidnightSolitaireGame(
    externallyPaused: Boolean,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    storeOverride: MidnightSolitaireStore? = null,
    initialSeed: Long? = null,
) {
    val context = LocalContext.current
    val store = storeOverride ?: remember(context) { SharedPreferencesMidnightSolitaireStore(context) }
    val sessionHolder = remember(store, initialSeed) {
        mutableStateOf(
            store.loadSession() ?: MidnightSolitaireSession(
                MidnightSolitaireEngine.newGame(initialSeed ?: System.currentTimeMillis()),
            ),
        )
    }
    var session by sessionHolder
    val settingsHolder = remember(store) { mutableStateOf(store.loadSettings()) }
    var settings by settingsHolder
    var manuallyPaused by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<SolitaireSelection?>(null) }
    var notice by rememberSaveable {
        mutableStateOf("Draw one, move by standard Klondike rules, or choose a fresh deal whenever you want.")
    }
    var confirmation by remember { mutableStateOf<SolitaireConfirmation?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner, store) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!lifecycleResumed) {
                store.saveSession(sessionHolder.value)
                store.saveSettings(settingsHolder.value)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            store.saveSession(sessionHolder.value)
            store.saveSettings(settingsHolder.value)
        }
    }
    LaunchedEffect(session, store) { store.saveSession(session) }
    LaunchedEffect(settings, store) { store.saveSettings(settings) }

    val paused = externallyPaused || manuallyPaused || !lifecycleResumed
    val performMove: (SolitaireMove) -> Unit = { move ->
        if (!paused) {
            val result = MidnightSolitaireEngine.apply(sessionHolder.value, move)
            notice = result.message
            if (result.changed) {
                session = result.session
                store.saveSession(result.session)
                selection = null
            }
        }
    }
    val moveSelectionToTableau: (Int) -> Unit = { target ->
        val move = when (val selected = selection) {
            null -> null
            else -> when (selected.zone) {
                SolitaireSelectionZone.WASTE -> SolitaireMove.WasteToTableau(target)
                SolitaireSelectionZone.TABLEAU -> SolitaireMove.TableauToTableau(
                    fromColumn = selected.column,
                    fromIndex = selected.cardIndex,
                    toColumn = target,
                )

                SolitaireSelectionZone.FOUNDATION -> SolitaireMove.FoundationToTableau(
                    suit = requireNotNull(selected.suit),
                    toColumn = target,
                )
            }
        }
        if (move != null) performMove(move)
    }
    val moveSelectionToFoundation: () -> Unit = {
        val move = when (val selected = selection) {
            null -> null
            else -> when (selected.zone) {
                SolitaireSelectionZone.WASTE -> SolitaireMove.WasteToFoundation
                SolitaireSelectionZone.TABLEAU -> SolitaireMove.TableauToFoundation(selected.column)
                SolitaireSelectionZone.FOUNDATION -> null
            }
        }
        if (move != null) performMove(move)
    }

    MidnightSolitaireLayout(
        state = session.game,
        settings = settings,
        selected = selection,
        paused = paused,
        pauseLabel = when {
            externallyPaused -> "PAUSED FOR CHAT"
            manuallyPaused -> "PAUSED"
            !lifecycleResumed -> "PAUSED WHILE AWAY"
            else -> "PLAYING"
        },
        notice = notice,
        canUndo = session.undoStack.isNotEmpty(),
        compactLayout = compactLayout,
        onDrawOrRecycle = { performMove(SolitaireMove.DrawOrRecycle) },
        onWasteTap = {
            if (!paused && session.game.waste.isNotEmpty()) {
                selection = if (selection?.zone == SolitaireSelectionZone.WASTE) {
                    null
                } else {
                    SolitaireSelection(SolitaireSelectionZone.WASTE)
                }
            }
        },
        onFoundationTap = { suit ->
            if (!paused) {
                if (selection == null) {
                    if (session.game.foundations[suit.ordinal].isNotEmpty()) {
                        selection = SolitaireSelection(SolitaireSelectionZone.FOUNDATION, suit = suit)
                    }
                } else if (selection?.zone == SolitaireSelectionZone.FOUNDATION && selection?.suit == suit) {
                    selection = null
                } else {
                    moveSelectionToFoundation()
                }
            }
        },
        onTableauCardTap = { column, index ->
            if (!paused) {
                val card = session.game.tableau[column][index]
                val selected = selection
                when {
                    selected == null && card.faceUp -> {
                        selection = SolitaireSelection(
                            zone = SolitaireSelectionZone.TABLEAU,
                            column = column,
                            cardIndex = index,
                        )
                    }

                    selected?.zone == SolitaireSelectionZone.TABLEAU && selected.column == column -> {
                        selection = if (selected.cardIndex == index) {
                            null
                        } else if (card.faceUp) {
                            SolitaireSelection(
                                zone = SolitaireSelectionZone.TABLEAU,
                                column = column,
                                cardIndex = index,
                            )
                        } else {
                            selected
                        }
                    }

                    selected != null -> moveSelectionToTableau(column)
                }
            }
        },
        onTableauTargetTap = moveSelectionToTableau,
        onSendHome = moveSelectionToFoundation,
        onUndo = {
            if (!paused) {
                val result = MidnightSolitaireEngine.undo(sessionHolder.value)
                notice = result.message
                if (result.changed) {
                    session = result.session
                    store.saveSession(result.session)
                    selection = null
                }
            }
        },
        onTogglePause = { if (!externallyPaused) manuallyPaused = !manuallyPaused },
        onRestart = { confirmation = SolitaireConfirmation.RESTART },
        onNewDeal = { confirmation = SolitaireConfirmation.NEW_DEAL },
        onToggleLargeText = {
            val updatedSettings = settingsHolder.value.copy(
                largeCardText = !settingsHolder.value.largeCardText,
            )
            settings = updatedSettings
            store.saveSettings(updatedSettings)
        },
        modifier = modifier,
    )

    confirmation?.let { choice ->
        SolitaireResetDialog(
            confirmation = choice,
            onDismiss = { confirmation = null },
            onConfirm = {
                val updatedSession = when (choice) {
                    SolitaireConfirmation.RESTART -> MidnightSolitaireEngine.restart(sessionHolder.value)
                    SolitaireConfirmation.NEW_DEAL -> {
                        val now = System.currentTimeMillis()
                        MidnightSolitaireEngine.freshDeal(
                            sessionHolder.value,
                            seed = if (now == sessionHolder.value.game.dealSeed) now + 1 else now,
                        )
                    }
                }
                session = updatedSession
                store.saveSession(updatedSession)
                selection = null
                manuallyPaused = false
                notice = when (choice) {
                    SolitaireConfirmation.RESTART -> "This deal restarted from its original order."
                    SolitaireConfirmation.NEW_DEAL ->
                        "Fresh deal ready. Like standard Klondike, it is not guaranteed solvable."
                }
                confirmation = null
            },
        )
    }
}

@Composable
private fun MidnightSolitaireLayout(
    state: MidnightSolitaireState,
    settings: MidnightSolitaireSettings,
    selected: SolitaireSelection?,
    paused: Boolean,
    pauseLabel: String,
    notice: String,
    canUndo: Boolean,
    compactLayout: Boolean,
    onDrawOrRecycle: () -> Unit,
    onWasteTap: () -> Unit,
    onFoundationTap: (SolitaireSuit) -> Unit,
    onTableauCardTap: (Int, Int) -> Unit,
    onTableauTargetTap: (Int) -> Unit,
    onSendHome: () -> Unit,
    onUndo: () -> Unit,
    onTogglePause: () -> Unit,
    onRestart: () -> Unit,
    onNewDeal: () -> Unit,
    onToggleLargeText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HunterGreen)
            .testTag("midnight_solitaire_board"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableNavyRaised.copy(alpha = 0.9f))
                .padding(horizontal = if (compactLayout) 8.dp else 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DRAW-ONE KLONDIKE",
                    color = MutedGold,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontSize = if (settings.largeCardText) 14.sp else 12.sp,
                )
                Text(
                    text = "Deal ${state.dealNumber}  \u2022  ${state.moves} moves  \u2022  ${state.recycles} recycles",
                    color = MistBlue,
                    fontSize = if (settings.largeCardText) 13.sp else 11.sp,
                )
            }
            Text(
                text = if (state.status == SolitaireStatus.WON) "WON" else pauseLabel,
                color = if (state.status == SolitaireStatus.WON) MutedGold else WarmIvory,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SolitaireToolbarButton(
                text = if (paused) "RESUME" else "PAUSE",
                description = if (paused) "Resume Midnight Solitaire" else "Pause Midnight Solitaire",
                enabled = pauseLabel != "PAUSED FOR CHAT" && pauseLabel != "PAUSED WHILE AWAY",
                onClick = onTogglePause,
            )
            SolitaireToolbarButton(
                text = if (settings.largeCardText) "TEXT: LARGE" else "TEXT: STANDARD",
                description = "Toggle large Solitaire card text",
                enabled = true,
                onClick = onToggleLargeText,
            )
            SolitaireToolbarButton(
                text = "UNDO",
                description = "Undo last Solitaire move",
                enabled = canUndo && !paused,
                onClick = onUndo,
            )
            SolitaireToolbarButton(
                text = "HOME",
                description = "Move selected card to its foundation",
                enabled = selected != null && selected.zone != SolitaireSelectionZone.FOUNDATION && !paused,
                onClick = onSendHome,
            )
            SolitaireToolbarButton(
                text = "RESTART",
                description = "Restart this Solitaire deal",
                enabled = !paused,
                onClick = onRestart,
            )
            Button(
                onClick = onNewDeal,
                enabled = !paused,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MutedGold, contentColor = DeepInk),
            ) {
                Text("NEW DEAL", maxLines = 1)
            }
        }

        SolitaireTopPiles(
            state = state,
            settings = settings,
            selected = selected,
            enabled = !paused,
            compactLayout = compactLayout,
            onDrawOrRecycle = onDrawOrRecycle,
            onWasteTap = onWasteTap,
            onFoundationTap = onFoundationTap,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 5.dp, vertical = 4.dp),
        ) {
            SolitaireTableau(
                state = state,
                settings = settings,
                selected = selected,
                enabled = !paused,
                compactLayout = compactLayout,
                onCardTap = onTableauCardTap,
                onTargetTap = onTableauTargetTap,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TableNavy.copy(alpha = 0.94f),
        ) {
            Text(
                text = when {
                    state.status == SolitaireStatus.WON ->
                        "You won. There is no timer or loss; undo, restart, or choose another deal."

                    selected != null -> "Selected: ${selectionDescription(selected, state)}. Choose a target."
                    else -> notice
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                color = if (state.status == SolitaireStatus.WON) MutedGold else WarmIvory,
                fontSize = if (settings.largeCardText) 14.sp else 11.sp,
                textAlign = TextAlign.Center,
                maxLines = if (compactLayout) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SolitaireToolbarButton(
    text: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(text, maxLines = 1, fontSize = 11.sp)
    }
}

@Composable
private fun SolitaireTopPiles(
    state: MidnightSolitaireState,
    settings: MidnightSolitaireSettings,
    selected: SolitaireSelection?,
    enabled: Boolean,
    compactLayout: Boolean,
    onDrawOrRecycle: () -> Unit,
    onWasteTap: () -> Unit,
    onFoundationTap: (SolitaireSuit) -> Unit,
) {
    val pileWidth = when {
        settings.largeCardText -> 56.dp
        compactLayout -> 48.dp
        else -> 52.dp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SolitaireCardView(
            card = state.stock.lastOrNull(),
            faceDown = state.stock.isNotEmpty(),
            emptyLabel = if (state.waste.isNotEmpty()) "\u21BB" else "\u2022",
            description = if (state.stock.isNotEmpty()) {
                "Stock, ${state.stock.size} cards. Draw one."
            } else if (state.waste.isNotEmpty()) {
                "Stock empty. Recycle the waste without penalty."
            } else {
                "Stock empty."
            },
            enabled = enabled && (state.stock.isNotEmpty() || state.waste.isNotEmpty()),
            selected = false,
            largeText = settings.largeCardText,
            width = pileWidth,
            tag = "solitaire_stock",
            onClick = onDrawOrRecycle,
        )
        SolitaireCardView(
            card = state.waste.lastOrNull(),
            faceDown = false,
            emptyLabel = "W",
            description = state.waste.lastOrNull()?.let { "Waste, ${it.spokenName}. Tap to select." }
                ?: "Waste empty.",
            enabled = enabled && state.waste.isNotEmpty(),
            selected = selected?.zone == SolitaireSelectionZone.WASTE,
            largeText = settings.largeCardText,
            width = pileWidth,
            tag = "solitaire_waste",
            onClick = onWasteTap,
        )
        Spacer(modifier = Modifier.width(if (compactLayout) 2.dp else 8.dp))
        SolitaireSuit.entries.forEach { suit ->
            val card = state.foundations[suit.ordinal].lastOrNull()
            SolitaireCardView(
                card = card,
                faceDown = false,
                emptyLabel = suit.symbol,
                description = card?.let { "${suit.name.lowercase()} foundation, ${it.spokenName}." }
                    ?: "${suit.name.lowercase()} foundation empty.",
                enabled = enabled,
                selected = selected?.zone == SolitaireSelectionZone.FOUNDATION && selected.suit == suit,
                largeText = settings.largeCardText,
                width = pileWidth,
                tag = "solitaire_foundation_${suit.name.lowercase()}",
                onClick = { onFoundationTap(suit) },
            )
        }
    }
}

@Composable
private fun SolitaireTableau(
    state: MidnightSolitaireState,
    settings: MidnightSolitaireSettings,
    selected: SolitaireSelection?,
    enabled: Boolean,
    compactLayout: Boolean,
    onCardTap: (Int, Int) -> Unit,
    onTargetTap: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minimumWidth = when {
            settings.largeCardText -> 56.dp
            compactLayout -> 48.dp
            else -> 52.dp
        }
        val availableWidth = (maxWidth - 24.dp) / SOLITAIRE_TABLEAU_COUNT
        val columnWidth = if (availableWidth > minimumWidth) availableWidth else minimumWidth
        val overlap = when {
            settings.largeCardText -> (-48).dp
            compactLayout -> (-43).dp
            else -> (-45).dp
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            state.tableau.forEachIndexed { column, pile ->
                Box(
                    modifier = Modifier
                        .width(columnWidth)
                        .fillMaxHeight()
                        .semantics {
                            contentDescription = "Tableau column ${column + 1} target"
                        }
                        .clickable(enabled = enabled && selected != null) { onTargetTap(column) },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (pile.isEmpty()) {
                        EmptyTableauTarget(
                            width = columnWidth,
                            enabled = enabled && selected != null,
                            onClick = { onTargetTap(column) },
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(overlap)) {
                            pile.forEachIndexed { index, tableauCard ->
                                SolitaireCardView(
                                    card = tableauCard.card,
                                    faceDown = !tableauCard.faceUp,
                                    emptyLabel = "",
                                    description = if (tableauCard.faceUp) {
                                        "Tableau ${column + 1}, ${tableauCard.card.spokenName}. " +
                                            if (index == pile.lastIndex) "Top card." else "Move this face-up sequence."
                                    } else {
                                        "Tableau ${column + 1}, face-down card."
                                    },
                                    enabled = enabled && (tableauCard.faceUp || selected != null),
                                    selected = selected?.zone == SolitaireSelectionZone.TABLEAU &&
                                        selected.column == column && selected.cardIndex == index,
                                    largeText = settings.largeCardText,
                                    width = columnWidth,
                                    tag = "solitaire_tableau_${column}_$index",
                                    onClick = { onCardTap(column, index) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTableauTarget(
    width: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(0.70f)
            .defaultMinSize(minWidth = 48.dp, minHeight = 64.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, MutedGold.copy(alpha = 0.65f), RoundedCornerShape(7.dp))
            .background(TableNavy.copy(alpha = 0.35f))
            .semantics { contentDescription = "Empty tableau. Only a king may move here." }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("K", color = MutedGold.copy(alpha = 0.72f), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SolitaireCardView(
    card: SolitaireCard?,
    faceDown: Boolean,
    emptyLabel: String,
    description: String,
    enabled: Boolean,
    selected: Boolean,
    largeText: Boolean,
    width: Dp,
    tag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    Card(
        modifier = Modifier
            .width(width)
            .aspectRatio(0.70f)
            .defaultMinSize(minWidth = 48.dp, minHeight = 64.dp)
            .testTag(tag)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick)
            .then(
                if (selected) Modifier.border(3.dp, MutedGold, shape) else Modifier,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (card == null && !faceDown) HunterGreen.copy(alpha = 0.6f) else WarmIvory,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) WarmIvory else MutedGold.copy(alpha = 0.76f),
        ),
    ) {
        when {
            faceDown -> Image(
                painter = painterResource(R.drawable.riven_card_back),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            card != null -> {
                val ink = if (card.suit.color == SolitaireColor.RED) CardRed else DeepInk
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (largeText) 5.dp else 4.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = card.rankLabel,
                        color = ink,
                        fontWeight = FontWeight.Black,
                        fontSize = if (largeText) 19.sp else 15.sp,
                        lineHeight = if (largeText) 19.sp else 15.sp,
                    )
                    Text(
                        text = card.suit.symbol,
                        color = ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (largeText) 18.sp else 14.sp,
                        lineHeight = if (largeText) 18.sp else 14.sp,
                    )
                }
            }

            else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyLabel,
                    color = MutedGold.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold,
                    fontSize = if (largeText) 22.sp else 17.sp,
                )
            }
        }
    }
}

@Composable
private fun SolitaireResetDialog(
    confirmation: SolitaireConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val fresh = confirmation == SolitaireConfirmation.NEW_DEAL
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fresh) "Start a fresh deal?" else "Restart this deal?") },
        text = {
            Text(
                if (fresh) {
                    "Your current position and undo history will be replaced. Fresh standard Klondike deals are not guaranteed solvable, and there is no penalty for choosing another."
                } else {
                    "Your current progress and undo history will be replaced by the original deal."
                },
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(if (fresh) "Fresh deal" else "Restart") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep playing") } },
        containerColor = TableNavyRaised,
        titleContentColor = WarmIvory,
        textContentColor = MistBlue,
    )
}

private fun selectionDescription(
    selection: SolitaireSelection,
    state: MidnightSolitaireState,
): String = when (selection.zone) {
    SolitaireSelectionZone.WASTE -> state.waste.lastOrNull()?.spokenName ?: "empty waste"
    SolitaireSelectionZone.TABLEAU -> state.tableau
        .getOrNull(selection.column)
        ?.getOrNull(selection.cardIndex)
        ?.card
        ?.spokenName
        ?: "tableau card"

    SolitaireSelectionZone.FOUNDATION -> selection.suit?.let { suit ->
        state.foundations[suit.ordinal].lastOrNull()?.spokenName
    } ?: "foundation card"
}
