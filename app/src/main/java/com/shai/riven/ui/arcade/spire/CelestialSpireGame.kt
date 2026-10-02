package com.shai.riven.ui.arcade.spire

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shai.riven.ui.theme.DeepInk
import com.shai.riven.ui.theme.MistBlue
import com.shai.riven.ui.theme.MutedGold
import com.shai.riven.ui.theme.PenthouseNavy
import com.shai.riven.ui.theme.TableNavyRaised
import com.shai.riven.ui.theme.WarmIvory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

private val DustyTeal = Color(0xFF67A7A2)
private val MutedCoral = Color(0xFFC7796E)
private val Lavender = Color(0xFF9B87C9)
private val Sage = Color(0xFF8FAE84)
private val SoftAmber = Color(0xFFD3A65D)
private val SlateBlue = Color(0xFF6F84A8)
private val Rose = Color(0xFFB9758E)

@Composable
fun CelestialSpireGame(
    externallyPaused: Boolean,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    storeOverride: CelestialSpireStore? = null,
    soundPlayerFactory: () -> CelestialSpireSoundPlayer = { AndroidCelestialSpireSoundPlayer() },
    initialSeed: Long? = null,
) {
    val context = LocalContext.current
    val store = storeOverride ?: remember(context) { SharedPreferencesCelestialSpireStore(context) }
    val soundPlayer = remember(soundPlayerFactory) { soundPlayerFactory() }
    val stateHolder = remember(store, initialSeed) {
        mutableStateOf(
            store.loadSession() ?: CelestialSpireEngine.newGame(
                seed = initialSeed ?: System.currentTimeMillis(),
            ),
        )
    }
    var state by stateHolder
    val settingsHolder = remember(store) { mutableStateOf(store.loadSettings()) }
    var settings by settingsHolder
    var manuallyPaused by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner, store, soundPlayer) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!lifecycleResumed) {
                store.saveSession(stateHolder.value)
                store.saveSettings(settingsHolder.value)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            store.saveSession(stateHolder.value)
            store.saveSettings(settingsHolder.value)
            soundPlayer.release()
        }
    }

    LaunchedEffect(state, store) {
        store.saveSession(state)
    }
    LaunchedEffect(settings, store, soundPlayer) {
        val normalized = settings.normalized()
        store.saveSettings(normalized)
        soundPlayer.update(normalized.soundVolume, normalized.soundMuted)
    }

    val paused = externallyPaused || manuallyPaused || settingsOpen || !lifecycleResumed
    val performAction: (SpireAction) -> Unit = { action ->
        if (!paused) {
            val result = CelestialSpireEngine.step(state, action)
            state = result.state
            store.saveSession(result.state)
            result.sounds.forEach(soundPlayer::play)
        }
    }
    val currentPerformAction by rememberUpdatedState(performAction)
    LaunchedEffect(paused, settings.fallSpeed) {
        while (currentCoroutineContext().isActive && !paused) {
            delay(settings.fallSpeed.tickMillis)
            currentPerformAction(SpireAction.TICK)
        }
    }

    CelestialSpireLayout(
        state = state,
        settings = settings,
        paused = paused,
        pauseLabel = when {
            externallyPaused -> "Paused for chat"
            manuallyPaused -> "Paused"
            settingsOpen -> "Paused for settings"
            !lifecycleResumed -> "Paused while away"
            else -> "Playing"
        },
        onAction = performAction,
        onTogglePause = { manuallyPaused = !manuallyPaused },
        onCycleSpeed = { settings = settings.copy(fallSpeed = settings.fallSpeed.next()) },
        onToggleMute = { settings = settings.copy(soundMuted = !settings.soundMuted) },
        onVolumeDown = { settings = settings.copy(soundVolume = settings.soundVolume - 10).normalized() },
        onVolumeUp = { settings = settings.copy(soundVolume = settings.soundVolume + 10).normalized() },
        compactLayout = compactLayout,
        onOpenSettings = { settingsOpen = true },
        modifier = modifier,
    )
    if (settingsOpen) {
        CompactSpireSettingsDialog(
            state = state,
            settings = settings,
            onDismiss = { settingsOpen = false },
            onCycleSpeed = { settings = settings.copy(fallSpeed = settings.fallSpeed.next()) },
            onToggleMute = { settings = settings.copy(soundMuted = !settings.soundMuted) },
            onVolumeDown = { settings = settings.copy(soundVolume = settings.soundVolume - 10).normalized() },
            onVolumeUp = { settings = settings.copy(soundVolume = settings.soundVolume + 10).normalized() },
        )
    }
}

@Composable
private fun CelestialSpireLayout(
    state: CelestialSpireState,
    settings: CelestialSpireSettings,
    paused: Boolean,
    pauseLabel: String,
    onAction: (SpireAction) -> Unit,
    onTogglePause: () -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    compactLayout: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (compactLayout) {
        CompactCelestialSpireLayout(
            state = state,
            settings = settings,
            paused = paused,
            pauseLabel = pauseLabel,
            onAction = onAction,
            onTogglePause = onTogglePause,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableNavyRaised.copy(alpha = 0.72f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FALLING BLOCKS",
                color = MutedGold,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                text = if (paused) "PAUSED" else "${settings.fallSpeed.label} • fixed",
                color = if (paused) SoftAmber else MistBlue,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CelestialSpireBoard(
                    state = state,
                    paused = paused,
                    pauseLabel = pauseLabel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                CelestialSpireSidePanel(
                    state = state,
                    settings = settings,
                    onCycleSpeed = onCycleSpeed,
                    onToggleMute = onToggleMute,
                    onVolumeDown = onVolumeDown,
                    onVolumeUp = onVolumeUp,
                )
            }
            Text(
                text = "Endless play • Reaching the top refreshes the board at your selected speed.",
                modifier = Modifier.fillMaxWidth(),
                color = MistBlue,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpireControlButton(
                    label = "←",
                    description = "Move piece left",
                    enabled = !paused,
                    onClick = { onAction(SpireAction.MOVE_LEFT) },
                    modifier = Modifier.weight(1f),
                )
                SpireControlButton(
                    label = "↻",
                    description = "Rotate piece clockwise",
                    enabled = !paused,
                    onClick = { onAction(SpireAction.ROTATE_CLOCKWISE) },
                    modifier = Modifier.weight(1f),
                )
                SpireControlButton(
                    label = "→",
                    description = "Move piece right",
                    enabled = !paused,
                    onClick = { onAction(SpireAction.MOVE_RIGHT) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpireControlButton(
                    label = "↓",
                    description = "Soft drop piece",
                    enabled = !paused,
                    onClick = { onAction(SpireAction.SOFT_DROP) },
                    modifier = Modifier.weight(1f),
                )
                SpireControlButton(
                    label = "DROP",
                    description = "Hard drop piece",
                    enabled = !paused,
                    onClick = { onAction(SpireAction.HARD_DROP) },
                    modifier = Modifier.weight(1f),
                )
                SpireControlButton(
                    label = if (paused) "RESUME" else "PAUSE",
                    description = if (paused) "Resume Celestial Spire" else "Pause Celestial Spire",
                    enabled = !externallyBlocked(paused, pauseLabel),
                    onClick = onTogglePause,
                    modifier = Modifier.weight(1f),
                    accent = true,
                )
            }
        }
    }
}

@Composable
private fun CompactCelestialSpireLayout(
    state: CelestialSpireState,
    settings: CelestialSpireSettings,
    paused: Boolean,
    pauseLabel: String,
    onAction: (SpireAction) -> Unit,
    onTogglePause: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("celestial_spire_compact"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableNavyRaised.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FALLING BLOCKS",
                color = MutedGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            Text(
                text = if (paused) "PAUSED" else "${settings.fallSpeed.label} • fixed",
                color = if (paused) SoftAmber else MistBlue,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CelestialSpireBoard(
                state = state,
                paused = paused,
                pauseLabel = pauseLabel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .width(92.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("LINES", color = MutedGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = state.linesCleared.toString(),
                    color = WarmIvory,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (state.boardRefreshes > 0) {
                    Text(
                        text = "${state.boardRefreshes} fresh",
                        color = MistBlue,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Open Celestial Spire settings" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlateBlue.copy(alpha = 0.72f),
                        contentColor = WarmIvory,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                ) {
                    Text("SETTINGS", fontSize = 9.sp, maxLines = 1)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SpireControlButton(
                label = "←",
                description = "Move piece left",
                enabled = !paused,
                onClick = { onAction(SpireAction.MOVE_LEFT) },
                modifier = Modifier.weight(1f),
            )
            SpireControlButton(
                label = "↻",
                description = "Rotate piece clockwise",
                enabled = !paused,
                onClick = { onAction(SpireAction.ROTATE_CLOCKWISE) },
                modifier = Modifier.weight(1f),
            )
            SpireControlButton(
                label = "→",
                description = "Move piece right",
                enabled = !paused,
                onClick = { onAction(SpireAction.MOVE_RIGHT) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SpireControlButton(
                label = "↓",
                description = "Soft drop piece",
                enabled = !paused,
                onClick = { onAction(SpireAction.SOFT_DROP) },
                modifier = Modifier.weight(1f),
            )
            SpireControlButton(
                label = "DROP",
                description = "Hard drop piece",
                enabled = !paused,
                onClick = { onAction(SpireAction.HARD_DROP) },
                modifier = Modifier.weight(1f),
            )
            SpireControlButton(
                label = if (paused) "RESUME" else "PAUSE",
                description = if (paused) "Resume Celestial Spire" else "Pause Celestial Spire",
                enabled = !externallyBlocked(paused, pauseLabel),
                onClick = onTogglePause,
                modifier = Modifier.weight(1f),
                accent = true,
            )
        }
    }
}

@Composable
private fun CompactSpireSettingsDialog(
    state: CelestialSpireState,
    settings: CelestialSpireSettings,
    onDismiss: () -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .background(TableNavyRaised, RoundedCornerShape(20.dp))
                .border(1.dp, MutedGold.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("CELESTIAL SPIRE SETTINGS", color = MutedGold, fontWeight = FontWeight.Bold)
            Text("NEXT", color = MutedGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            NextPiecePreview(type = state.next)
            Button(
                onClick = onCycleSpeed,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("celestial_spire_speed"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SlateBlue.copy(alpha = 0.72f),
                    contentColor = WarmIvory,
                ),
            ) {
                Text("${settings.fallSpeed.label} • ${settings.fallSpeed.tickMillis} ms")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("SOUND", color = MistBlue)
                Switch(
                    checked = !settings.soundMuted,
                    onCheckedChange = { onToggleMute() },
                    modifier = Modifier.semantics { contentDescription = "Celestial Spire sound" },
                )
            }
            SpireVolumeControls(
                settings = settings,
                onVolumeDown = onVolumeDown,
                onVolumeUp = onVolumeUp,
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
            ) {
                Text("DONE")
            }
        }
    }
}

private fun externallyBlocked(paused: Boolean, pauseLabel: String): Boolean =
    paused && pauseLabel != "Paused"

@Composable
private fun CelestialSpireBoard(
    state: CelestialSpireState,
    paused: Boolean,
    pauseLabel: String,
    modifier: Modifier = Modifier,
) {
    val description = buildString {
        append("Celestial Spire board. ")
        append(state.linesCleared)
        append(" lines cleared. Active ")
        append(state.active.type.name)
        append(" at column ")
        append(state.active.x + 1)
        append(", row ")
        append(state.active.y + 1)
        append(". ")
        append(pauseLabel)
        append('.')
    }
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val boardWidth = minOf(maxWidth, maxHeight / 2)
        val boardHeight = boardWidth * 2
        Box(
            modifier = Modifier
                .width(boardWidth)
                .height(boardHeight)
                .background(PenthouseNavy, RoundedCornerShape(14.dp))
                .border(1.dp, MutedGold.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .semantics { contentDescription = description }
                .testTag("celestial_spire_board"),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cell = min(size.width / CELESTIAL_SPIRE_COLUMNS, size.height / CELESTIAL_SPIRE_ROWS)
                val left = (size.width - cell * CELESTIAL_SPIRE_COLUMNS) / 2f
                val top = (size.height - cell * CELESTIAL_SPIRE_ROWS) / 2f
                for (column in 1 until CELESTIAL_SPIRE_COLUMNS) {
                    drawLine(
                        color = MistBlue.copy(alpha = 0.07f),
                        start = Offset(left + column * cell, top),
                        end = Offset(left + column * cell, top + CELESTIAL_SPIRE_ROWS * cell),
                    )
                }
                for (row in 1 until CELESTIAL_SPIRE_ROWS) {
                    drawLine(
                        color = MistBlue.copy(alpha = 0.07f),
                        start = Offset(left, top + row * cell),
                        end = Offset(left + CELESTIAL_SPIRE_COLUMNS * cell, top + row * cell),
                    )
                }
                state.board.forEachIndexed { index, type ->
                    type?.let {
                        drawSpireCell(
                            column = index % CELESTIAL_SPIRE_COLUMNS,
                            row = index / CELESTIAL_SPIRE_COLUMNS,
                            type = it,
                            cell = cell,
                            origin = Offset(left, top),
                        )
                    }
                }
                val ghost = CelestialSpireEngine.ghostPiece(state)
                CelestialSpireEngine.cells(ghost).filter { it.y >= 0 }.forEach { point ->
                    drawSpireCell(
                        column = point.x,
                        row = point.y,
                        type = ghost.type,
                        cell = cell,
                        origin = Offset(left, top),
                        alpha = 0.2f,
                        outlineOnly = true,
                    )
                }
                CelestialSpireEngine.cells(state.active).filter { it.y >= 0 }.forEach { point ->
                    drawSpireCell(
                        column = point.x,
                        row = point.y,
                        type = state.active.type,
                        cell = cell,
                        origin = Offset(left, top),
                    )
                }
            }
            if (paused) {
                Text(
                    text = pauseLabel.uppercase(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(DeepInk.copy(alpha = 0.86f), RoundedCornerShape(12.dp))
                        .border(1.dp, SoftAmber.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = WarmIvory,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CelestialSpireSidePanel(
    state: CelestialSpireState,
    settings: CelestialSpireSettings,
    onCycleSpeed: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Column(
        modifier = Modifier
            .width(if (largeText) 144.dp else 116.dp)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("LINES", color = MutedGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            text = state.linesCleared.toString(),
            color = WarmIvory,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("NEXT", color = MutedGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        NextPiecePreview(type = state.next)
        if (state.boardRefreshes > 0) {
            Text(
                text = "${state.boardRefreshes} fresh start${if (state.boardRefreshes == 1) "" else "s"}",
                color = MistBlue,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onCycleSpeed,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("celestial_spire_speed"),
            colors = ButtonDefaults.buttonColors(
                containerColor = SlateBlue.copy(alpha = 0.72f),
                contentColor = WarmIvory,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
        ) {
            Text(
                text = "${settings.fallSpeed.label}\n${settings.fallSpeed.tickMillis} ms",
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                lineHeight = 13.sp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("SOUND", color = MistBlue, fontSize = 10.sp)
            Switch(
                checked = !settings.soundMuted,
                onCheckedChange = { onToggleMute() },
                modifier = Modifier
                    .semantics { contentDescription = "Celestial Spire sound" }
                    .testTag("celestial_spire_sound"),
            )
        }
        SpireVolumeControls(
            settings = settings,
            onVolumeDown = onVolumeDown,
            onVolumeUp = onVolumeUp,
        )
    }
}

@Composable
private fun SpireVolumeControls(
    settings: CelestialSpireSettings,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${settings.soundVolume}%",
            modifier = Modifier.testTag("celestial_spire_volume"),
            color = WarmIvory,
            fontSize = 11.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onVolumeDown,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Lower game sound volume" },
            ) { Text("-") }
            TextButton(
                onClick = onVolumeUp,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Raise game sound volume" },
            ) { Text("+") }
        }
    }
}

@Composable
private fun NextPiecePreview(type: SpirePieceType) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(TableNavyRaised, RoundedCornerShape(12.dp))
            .border(1.dp, MutedGold.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Next piece ${type.name}" }
            .testTag("celestial_spire_next"),
    ) {
        val points = CelestialSpireEngine.cells(SpirePiece(type, 0, 0, 0))
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val cell = min(size.width / (maxX - minX + 2.5f), size.height / (maxY - minY + 2.5f))
        val left = (size.width - (maxX - minX + 1) * cell) / 2f
        val top = (size.height - (maxY - minY + 1) * cell) / 2f
        points.forEach { point ->
            drawSpireCell(
                column = point.x - minX,
                row = point.y - minY,
                type = type,
                cell = cell,
                origin = Offset(left, top),
            )
        }
    }
}

@Composable
private fun SpireControlButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) MutedCoral else TableNavyRaised,
            contentColor = WarmIvory,
            disabledContainerColor = TableNavyRaised.copy(alpha = 0.5f),
            disabledContentColor = MistBlue.copy(alpha = 0.6f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp),
    ) {
        Text(
            text = label,
            fontSize = if (label.length > 2) 11.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpireCell(
    column: Int,
    row: Int,
    type: SpirePieceType,
    cell: Float,
    origin: Offset,
    alpha: Float = 1f,
    outlineOnly: Boolean = false,
) {
    val inset = cell * 0.1f
    val topLeft = Offset(
        x = origin.x + column * cell + inset,
        y = origin.y + row * cell + inset,
    )
    val cellSize = Size(cell - inset * 2f, cell - inset * 2f)
    val radius = CornerRadius(cell * 0.18f)
    val color = pieceColor(type).copy(alpha = alpha)
    if (!outlineOnly) {
        drawRoundRect(color = color, topLeft = topLeft, size = cellSize, cornerRadius = radius)
    }
    drawRoundRect(
        color = WarmIvory.copy(alpha = if (outlineOnly) 0.38f else 0.42f),
        topLeft = topLeft,
        size = cellSize,
        cornerRadius = radius,
        style = Stroke(width = if (outlineOnly) 1.5f else 1f),
    )
}

private fun pieceColor(type: SpirePieceType): Color = when (type) {
    SpirePieceType.I -> DustyTeal
    SpirePieceType.O -> SoftAmber
    SpirePieceType.T -> Lavender
    SpirePieceType.S -> Sage
    SpirePieceType.Z -> MutedCoral
    SpirePieceType.J -> SlateBlue
    SpirePieceType.L -> Rose
}
