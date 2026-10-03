package com.shai.riven.ui.arcade.comet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
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
import com.shai.riven.data.arcade.ArcadeGameObservation
import com.shai.riven.ui.arcade.ArcadeGame
import com.shai.riven.ui.arcade.ArcadeObservationSignature
import com.shai.riven.ui.arcade.meaningfulArcadeEvent
import com.shai.riven.ui.arcade.toArcadeObservation
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

private val CometTeal = Color(0xFF67A7A2)
private val CometSage = Color(0xFF8FAE84)
private val CometAmber = Color(0xFFD3A65D)
private val CometRose = Color(0xFFB9758E)
private val CometCoral = Color(0xFFC7796E)
private val CometSlate = Color(0xFF6F84A8)

@Composable
fun CometTrailGame(
    externallyPaused: Boolean,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    storeOverride: CometTrailStore? = null,
    initialSeed: Long? = null,
    onObservation: (ArcadeGameObservation) -> Unit = {},
) {
    val context = LocalContext.current
    val store = storeOverride ?: remember(context) { SharedPreferencesCometTrailStore(context) }
    val stateHolder = remember(store, initialSeed) {
        mutableStateOf(
            store.loadSession() ?: CometTrailEngine.newGame(
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

    DisposableEffect(lifecycleOwner, store, stateHolder, settingsHolder) {
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
        }
    }

    LaunchedEffect(state, store) { store.saveSession(state) }
    LaunchedEffect(settings, store) { store.saveSettings(settings) }

    val hostPaused = externallyPaused || manuallyPaused || settingsOpen || !lifecycleResumed
    val collisionPaused = state.collisionDirection != null
    val trapped = collisionPaused && CometTrailEngine.safeDirections(state).isEmpty()
    val paused = hostPaused || collisionPaused
    val observationSessionId = rememberSaveable { UUID.randomUUID().toString() }
    var observationSequence by rememberSaveable(observationSessionId) { mutableStateOf(0L) }
    var previousObservationSignature by remember(observationSessionId) {
        mutableStateOf<ArcadeObservationSignature?>(null)
    }
    val observationSignature = ArcadeObservationSignature(
        primary = if (state.collisionDirection == null) 0 else 1,
        secondary = state.treatsEaten,
        tertiary = state.boardRefreshes,
        paused = paused,
        direction = state.direction.name,
    )
    LaunchedEffect(observationSignature, observationSessionId) {
        observationSequence += 1L
        onObservation(
            state.toArcadeObservation(
                sessionId = observationSessionId,
                sequence = observationSequence,
                paused = paused,
                event = meaningfulArcadeEvent(
                    ArcadeGame.WRAPPING_SNAKE,
                    previousObservationSignature,
                    observationSignature,
                ),
                observedAt = System.currentTimeMillis(),
            ),
        )
        previousObservationSignature = observationSignature
    }
    val onDirection: (TrailDirection) -> Unit = { direction ->
        if (!hostPaused) {
            val updated = CometTrailEngine.turn(state, direction)
            state = updated
            store.saveSession(updated)
        }
    }
    val advance: () -> Unit = {
        if (!paused) {
            val updated = CometTrailEngine.tick(state)
            state = updated
            store.saveSession(updated)
        }
    }
    val currentAdvance by rememberUpdatedState(advance)
    LaunchedEffect(paused, settings.speed) {
        while (currentCoroutineContext().isActive && !paused) {
            delay(settings.speed.tickMillis)
            currentAdvance()
        }
    }

    val pauseLabel = when {
        externallyPaused -> "Paused for chat"
        manuallyPaused -> "Paused"
        settingsOpen -> "Paused for settings"
        !lifecycleResumed -> "Paused while away"
        trapped -> "Untangle to keep gliding"
        collisionPaused -> "Choose a safer turn"
        else -> "Gliding"
    }
    CometTrailLayout(
        state = state,
        settings = settings,
        paused = paused,
        hostPaused = hostPaused,
        trapped = trapped,
        pauseLabel = pauseLabel,
        compactLayout = compactLayout,
        onDirection = onDirection,
        onTogglePause = { manuallyPaused = !manuallyPaused },
        onUntangle = {
            if (!hostPaused && collisionPaused) {
                val updated = CometTrailEngine.untangle(state)
                state = updated
                store.saveSession(updated)
            }
        },
        onOpenSettings = { settingsOpen = true },
        modifier = modifier,
    )
    if (settingsOpen) {
        CometTrailSettingsDialog(
            settings = settings,
            onCycleSpeed = { settings = settings.copy(speed = settings.speed.next()) },
            onDismiss = { settingsOpen = false },
        )
    }
}

@Composable
private fun CometTrailLayout(
    state: CometTrailState,
    settings: CometTrailSettings,
    paused: Boolean,
    hostPaused: Boolean,
    trapped: Boolean,
    pauseLabel: String,
    compactLayout: Boolean,
    onDirection: (TrailDirection) -> Unit,
    onTogglePause: () -> Unit,
    onUntangle: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(if (compactLayout) "comet_trail_compact" else "comet_trail_game"),
        verticalArrangement = Arrangement.spacedBy(if (compactLayout) 4.dp else 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableNavyRaised.copy(alpha = 0.72f))
                .padding(
                    horizontal = if (compactLayout) 8.dp else 14.dp,
                    vertical = if (compactLayout) 4.dp else 9.dp,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "WRAPPING TRAIL",
                color = MutedGold,
                fontSize = if (compactLayout) 11.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = if (paused) {
                    when {
                        trapped && !hostPaused -> "UNTANGLE"
                        state.collisionDirection != null && !hostPaused -> "SAFE TURN"
                        else -> "PAUSED"
                    }
                } else {
                    "${settings.speed.label} • fixed"
                },
                color = if (paused) CometAmber else MistBlue,
                fontSize = if (compactLayout) 10.sp else 12.sp,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = if (compactLayout) 6.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compactLayout) 6.dp else 10.dp),
        ) {
            CometTrailBoard(
                state = state,
                paused = paused,
                pauseLabel = pauseLabel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            CometTrailSidePanel(
                state = state,
                settings = settings,
                compactLayout = compactLayout,
                onOpenSettings = onOpenSettings,
            )
        }
        if (!compactLayout) {
            Text(
                text = when {
                    trapped -> "No progress lost • Untangle keeps length and progress (review default)."
                    state.collisionDirection != null -> {
                        "No progress lost • Choose a safe direction to keep gliding."
                    }
                    else -> "Endless wrapping • Self-contact pauses before contact (review default)."
                },
                modifier = Modifier.fillMaxWidth(),
                color = MistBlue,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        CometTrailControls(
            hostPaused = hostPaused,
            collisionPaused = state.collisionDirection != null,
            trapped = trapped,
            manuallyPaused = pauseLabel == "Paused",
            onDirection = onDirection,
            onTogglePause = onTogglePause,
            onUntangle = onUntangle,
            compactLayout = compactLayout,
        )
    }
}

@Composable
private fun CometTrailBoard(
    state: CometTrailState,
    paused: Boolean,
    pauseLabel: String,
    modifier: Modifier = Modifier,
) {
    val description = buildString {
        append("Comet Trail board. Length ")
        append(state.body.size)
        append(", treats ")
        append(state.treatsEaten)
        append(". Head at column ")
        append(state.head.x + 1)
        append(", row ")
        append(state.head.y + 1)
        append(". Food at column ")
        append(state.food.x + 1)
        append(", row ")
        append(state.food.y + 1)
        append(". ")
        append(pauseLabel)
        append('.')
    }
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val boardSize = minOf(maxWidth, maxHeight)
        Box(
            modifier = Modifier
                .width(boardSize)
                .height(boardSize)
                .background(PenthouseNavy, RoundedCornerShape(14.dp))
                .border(1.dp, MutedGold.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .semantics { contentDescription = description }
                .testTag("comet_trail_board"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cell = min(size.width / COMET_TRAIL_COLUMNS, size.height / COMET_TRAIL_ROWS)
                val origin = Offset(
                    x = (size.width - cell * COMET_TRAIL_COLUMNS) / 2f,
                    y = (size.height - cell * COMET_TRAIL_ROWS) / 2f,
                )
                for (index in 1 until COMET_TRAIL_COLUMNS) {
                    drawLine(
                        color = MistBlue.copy(alpha = 0.07f),
                        start = Offset(origin.x + index * cell, origin.y),
                        end = Offset(origin.x + index * cell, origin.y + COMET_TRAIL_ROWS * cell),
                    )
                }
                for (index in 1 until COMET_TRAIL_ROWS) {
                    drawLine(
                        color = MistBlue.copy(alpha = 0.07f),
                        start = Offset(origin.x, origin.y + index * cell),
                        end = Offset(origin.x + COMET_TRAIL_COLUMNS * cell, origin.y + index * cell),
                    )
                }
                drawCircle(
                    color = CometRose,
                    radius = cell * 0.3f,
                    center = Offset(
                        origin.x + (state.food.x + 0.5f) * cell,
                        origin.y + (state.food.y + 0.5f) * cell,
                    ),
                )
                state.body.asReversed().forEachIndexed { reverseIndex, point ->
                    val isHead = reverseIndex == state.body.lastIndex
                    val inset = cell * if (isHead) 0.1f else 0.16f
                    drawRoundRect(
                        color = if (isHead) CometAmber else CometTeal,
                        topLeft = Offset(
                            origin.x + point.x * cell + inset,
                            origin.y + point.y * cell + inset,
                        ),
                        size = Size(cell - inset * 2f, cell - inset * 2f),
                        cornerRadius = CornerRadius(cell * 0.26f),
                    )
                    drawRoundRect(
                        color = WarmIvory.copy(alpha = 0.35f),
                        topLeft = Offset(
                            origin.x + point.x * cell + inset,
                            origin.y + point.y * cell + inset,
                        ),
                        size = Size(cell - inset * 2f, cell - inset * 2f),
                        cornerRadius = CornerRadius(cell * 0.26f),
                        style = Stroke(width = 1f),
                    )
                }
            }
            if (paused) {
                Text(
                    text = pauseLabel.uppercase(),
                    modifier = Modifier
                        .background(DeepInk.copy(alpha = 0.86f), RoundedCornerShape(12.dp))
                        .border(1.dp, CometAmber.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    color = WarmIvory,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun CometTrailSidePanel(
    state: CometTrailState,
    settings: CometTrailSettings,
    compactLayout: Boolean,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(if (compactLayout) 92.dp else 116.dp)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("TREATS", color = MutedGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            text = state.treatsEaten.toString(),
            color = WarmIvory,
            fontSize = if (compactLayout) 20.sp else 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("LENGTH ${state.body.size}", color = CometSage, fontSize = 9.sp, maxLines = 1)
        if (state.boardRefreshes > 0) {
            Text("${state.boardRefreshes} full orbit", color = MistBlue, fontSize = 9.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = "Open Comet Trail settings" },
            colors = ButtonDefaults.buttonColors(
                containerColor = CometSlate.copy(alpha = 0.76f),
                contentColor = WarmIvory,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        ) {
            Text(
                text = if (compactLayout) "SETTINGS" else "${settings.speed.label}\n${settings.speed.tickMillis} ms",
                fontSize = 9.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun CometTrailControls(
    hostPaused: Boolean,
    collisionPaused: Boolean,
    trapped: Boolean,
    manuallyPaused: Boolean,
    onDirection: (TrailDirection) -> Unit,
    onTogglePause: () -> Unit,
    onUntangle: () -> Unit,
    compactLayout: Boolean,
) {
    Column(
        modifier = Modifier.padding(
            start = if (compactLayout) 6.dp else 10.dp,
            end = if (compactLayout) 6.dp else 10.dp,
            bottom = if (compactLayout) 6.dp else 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TrailControlButton(
                label = "↑",
                description = "Turn Comet Trail up",
                enabled = !hostPaused,
                onClick = { onDirection(TrailDirection.UP) },
                modifier = Modifier.weight(1f),
            )
            TrailControlButton(
                label = when {
                    trapped -> "UNTANGLE"
                    manuallyPaused -> "RESUME"
                    else -> "PAUSE"
                },
                description = when {
                    trapped -> "Untangle Comet Trail"
                    manuallyPaused -> "Resume Comet Trail"
                    else -> "Pause Comet Trail"
                },
                enabled = if (trapped) {
                    !hostPaused
                } else {
                    !collisionPaused && (!hostPaused || manuallyPaused)
                },
                onClick = if (trapped) onUntangle else onTogglePause,
                modifier = Modifier.weight(1f),
                accent = true,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TrailControlButton(
                label = "←",
                description = "Turn Comet Trail left",
                enabled = !hostPaused,
                onClick = { onDirection(TrailDirection.LEFT) },
                modifier = Modifier.weight(1f),
            )
            TrailControlButton(
                label = "↓",
                description = "Turn Comet Trail down",
                enabled = !hostPaused,
                onClick = { onDirection(TrailDirection.DOWN) },
                modifier = Modifier.weight(1f),
            )
            TrailControlButton(
                label = "→",
                description = "Turn Comet Trail right",
                enabled = !hostPaused,
                onClick = { onDirection(TrailDirection.RIGHT) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrailControlButton(
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
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) CometCoral else TableNavyRaised,
            contentColor = WarmIvory,
            disabledContainerColor = TableNavyRaised.copy(alpha = 0.5f),
            disabledContentColor = MistBlue.copy(alpha = 0.6f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp),
    ) {
        Text(
            text = label,
            fontSize = if (label.length > 2) 10.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CometTrailSettingsDialog(
    settings: CometTrailSettings,
    onCycleSpeed: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .background(TableNavyRaised, RoundedCornerShape(20.dp))
                .border(1.dp, MutedGold.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("COMET TRAIL SETTINGS", color = MutedGold, fontWeight = FontWeight.Bold)
            Text(
                text = "Pace stays fixed until you change it.",
                color = MistBlue,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onCycleSpeed,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("comet_trail_speed"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CometSlate.copy(alpha = 0.76f),
                    contentColor = WarmIvory,
                ),
            ) {
                Text("${settings.speed.label} • ${settings.speed.tickMillis} ms")
            }
            Text(
                text = "Forgiving review default: predicted self-contact pauses before impact. Choose a safe direction, or Untangle if fully enclosed; length and progress stay intact.",
                color = WarmIvory,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
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
