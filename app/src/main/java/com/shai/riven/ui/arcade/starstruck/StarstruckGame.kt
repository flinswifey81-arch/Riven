package com.shai.riven.ui.arcade.starstruck

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.shai.riven.ui.theme.AquaHeart
import com.shai.riven.ui.theme.DeepInk
import com.shai.riven.ui.theme.LimeHeart
import com.shai.riven.ui.theme.MistBlue
import com.shai.riven.ui.theme.MutedGold
import com.shai.riven.ui.theme.PenthouseNavy
import com.shai.riven.ui.theme.RubyHeart
import com.shai.riven.ui.theme.TableNavyRaised
import com.shai.riven.ui.theme.VioletHeart
import com.shai.riven.ui.theme.WarmIvory
import com.shai.riven.data.arcade.ArcadeGameObservation
import com.shai.riven.ui.arcade.ArcadeGame
import com.shai.riven.ui.arcade.ArcadeObservationSignature
import com.shai.riven.ui.arcade.meaningfulArcadeEvent
import com.shai.riven.ui.arcade.toArcadeObservation
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val CometCoral = Color(0xFFC7796E)
private val PlanetSage = Color(0xFF8FAE84)
private val CrystalBlue = Color(0xFF6F84A8)

@Composable
fun StarstruckGame(
    externallyPaused: Boolean,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    storeOverride: StarstruckStore? = null,
    soundPlayerFactory: () -> StarstruckSoundPlayer = { AndroidStarstruckSoundPlayer() },
    initialSeed: Long? = null,
    onObservation: (ArcadeGameObservation) -> Unit = {},
) {
    val context = LocalContext.current
    val store = storeOverride ?: remember(context) { SharedPreferencesStarstruckStore(context) }
    val soundPlayer = remember(soundPlayerFactory) { soundPlayerFactory() }
    val stateHolder = remember(store, initialSeed) {
        mutableStateOf(
            store.loadSession() ?: StarstruckEngine.newGame(
                seed = initialSeed ?: System.currentTimeMillis(),
            ),
        )
    }
    var state by stateHolder
    val settingsHolder = remember(store) { mutableStateOf(store.loadSettings().normalized()) }
    var settings by settingsHolder
    var selected by remember { mutableStateOf<StarPoint?>(null) }
    val manuallyPausedHolder = rememberSaveable { mutableStateOf(false) }
    var manuallyPaused by manuallyPausedHolder
    val settingsOpenHolder = rememberSaveable { mutableStateOf(false) }
    var settingsOpen by settingsOpenHolder
    var effectMessage by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleResumedHolder = remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var lifecycleResumed by lifecycleResumedHolder

    DisposableEffect(lifecycleOwner, store, soundPlayer, stateHolder, settingsHolder) {
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

    LaunchedEffect(state, store) { store.saveSession(state) }
    LaunchedEffect(settings, store, soundPlayer) {
        val normalized = settings.normalized()
        store.saveSettings(normalized)
        soundPlayer.update(normalized.soundVolume, normalized.soundMuted)
    }
    val paused = externallyPaused || manuallyPaused || settingsOpen || !lifecycleResumed
    val observationSessionId = rememberSaveable { UUID.randomUUID().toString() }
    var observationSequence by rememberSaveable(observationSessionId) { mutableStateOf(0L) }
    var previousObservationSignature by remember(observationSessionId) {
        mutableStateOf<ArcadeObservationSignature?>(null)
    }
    val observationSignature = ArcadeObservationSignature(
        primary = state.movesMade,
        secondary = state.matchesMade,
        tertiary = state.reshuffles,
        paused = paused,
    )
    LaunchedEffect(observationSignature, observationSessionId) {
        observationSequence += 1L
        onObservation(
            state.toArcadeObservation(
                sessionId = observationSessionId,
                sequence = observationSequence,
                paused = paused,
                event = meaningfulArcadeEvent(
                    ArcadeGame.HEART_MATCH,
                    previousObservationSignature,
                    observationSignature,
                ),
                observedAt = System.currentTimeMillis(),
            ),
        )
        previousObservationSignature = observationSignature
    }
    val updateSettings: (StarstruckSettings) -> Unit = { updated ->
        val normalized = updated.normalized()
        settings = normalized
        store.saveSettings(normalized)
        soundPlayer.update(normalized.soundVolume, normalized.soundMuted)
    }
    val onTile: (StarPoint) -> Unit = tileClick@{ point ->
        if (
            externallyPaused ||
            manuallyPausedHolder.value ||
            settingsOpenHolder.value ||
            !lifecycleResumedHolder.value
        ) {
            return@tileClick
        }
        val first = selected
        when {
            first == null -> selected = point
            first == point -> selected = null
            !StarstruckEngine.areAdjacent(first, point) -> selected = point
            else -> {
                val result = StarstruckEngine.swap(state, first, point)
                selected = null
                if (!result.accepted) {
                    effectMessage = "No match there — board restored"
                } else {
                    state = result.state
                    store.saveSession(result.state)
                    result.sounds.forEach(soundPlayer::play)
                    effectMessage = when {
                        result.reshuffled -> "Fresh constellation — no progress lost"
                        result.powerupsCreated > 0 -> "A gentle powerup is ready"
                        result.resolutionWaves > 1 -> "${result.resolutionWaves - 1} bright cascade waves"
                        else -> "Celestial match"
                    }
                }
            }
        }
    }

    StarstruckLayout(
        state = state,
        selected = selected,
        paused = paused,
        pauseLabel = when {
            externallyPaused -> "Paused for chat"
            manuallyPaused -> "Paused"
            settingsOpen -> "Paused for settings"
            !lifecycleResumed -> "Paused while away"
            else -> effectMessage ?: "Choose adjacent tiles"
        },
        gentleEffects = settings.gentleEffects,
        compactLayout = compactLayout,
        onTile = onTile,
        onTogglePause = { manuallyPaused = !manuallyPaused },
        onOpenSettings = { settingsOpen = true },
        manuallyPaused = manuallyPaused,
        modifier = modifier,
    )
    if (settingsOpen) {
        StarstruckSettingsDialog(
            settings = settings,
            onSettings = updateSettings,
            onDismiss = { settingsOpen = false },
        )
    }
}

@Composable
private fun StarstruckLayout(
    state: StarstruckState,
    selected: StarPoint?,
    paused: Boolean,
    pauseLabel: String,
    gentleEffects: Boolean,
    compactLayout: Boolean,
    onTile: (StarPoint) -> Unit,
    onTogglePause: () -> Unit,
    onOpenSettings: () -> Unit,
    manuallyPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(if (compactLayout) "starstruck_compact" else "starstruck_game"),
        verticalArrangement = Arrangement.spacedBy(if (compactLayout) 3.dp else 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableNavyRaised.copy(alpha = 0.72f))
                .padding(horizontal = if (compactLayout) 8.dp else 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CELESTIAL GRID",
                color = MutedGold,
                fontSize = if (compactLayout) 11.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = if (paused) "PAUSED" else "ENDLESS • NO TIMER",
                color = if (paused) CometCoral else MistBlue,
                fontSize = if (compactLayout) 9.sp else 11.sp,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric("MOVES", state.movesMade)
            Metric("MATCHES", state.matchesMade)
            Metric("CASCADES", state.cascadeWaves)
            Metric("RESHUFFLES", state.reshuffles)
        }
        StarstruckBoard(
            state = state,
            selected = selected,
            paused = paused,
            pauseLabel = pauseLabel,
            gentleEffects = gentleEffects,
            onTile = onTile,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Text(
            text = pauseLabel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("starstruck_status"),
            color = if (paused) CometCoral else MistBlue,
            fontSize = if (compactLayout) 9.sp else 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compactLayout) 6.dp else 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Open Starstruck settings" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CrystalBlue.copy(alpha = 0.82f),
                    contentColor = WarmIvory,
                ),
            ) {
                Text("SETTINGS", fontSize = 10.sp)
            }
            Button(
                onClick = onTogglePause,
                enabled = !paused || manuallyPaused,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics {
                        contentDescription = if (manuallyPaused) "Resume Starstruck" else "Pause Starstruck"
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CometCoral,
                    contentColor = WarmIvory,
                ),
            ) {
                Text(if (manuallyPaused) "RESUME" else "PAUSE", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MistBlue, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value.toString(), color = WarmIvory, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StarstruckBoard(
    state: StarstruckState,
    selected: StarPoint?,
    paused: Boolean,
    pauseLabel: String,
    gentleEffects: Boolean,
    onTile: (StarPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val boardSize = minOf(maxWidth, maxHeight)
        Box(
            modifier = Modifier
                .size(boardSize)
                .background(PenthouseNavy, RoundedCornerShape(14.dp))
                .border(1.dp, MutedGold.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(4.dp)
                .testTag("starstruck_board"),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(STARSTRUCK_ROWS) { y ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        repeat(STARSTRUCK_COLUMNS) { x ->
                            val point = StarPoint(x, y)
                            StarstruckTile(
                                tile = state[point],
                                point = point,
                                selected = selected == point,
                                gentleEffects = gentleEffects,
                                enabled = !paused,
                                onClick = { onTile(point) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
            if (paused) {
                Text(
                    text = pauseLabel.uppercase(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(DeepInk.copy(alpha = 0.88f), RoundedCornerShape(12.dp))
                        .border(1.dp, MutedGold.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = WarmIvory,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StarstruckTile(
    tile: StarTile,
    point: StarPoint,
    selected: Boolean,
    gentleEffects: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        if (selected && gentleEffects) 0.86f else 1f,
        label = "starstruck-selection",
    )
    val kindName = tile.kind.name.lowercase().replaceFirstChar(Char::uppercase)
    val powerName = when (tile.power) {
        StarPower.NONE -> ""
        StarPower.ROW_CLEAR -> ", Row Burst powerup"
        StarPower.COLOR_CLEAR -> ", Color Nova powerup"
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(
                if (selected) MutedGold.copy(alpha = 0.24f) else TableNavyRaised,
                RoundedCornerShape(8.dp),
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) WarmIvory else MutedGold.copy(alpha = 0.18f),
                shape = RoundedCornerShape(8.dp),
            )
            .semantics {
                contentDescription = buildString {
                    append(kindName)
                    append(" tile, row ")
                    append(point.y + 1)
                    append(", column ")
                    append(point.x + 1)
                    append(powerName)
                    if (selected) append(", selected")
                }
            }
            .testTag("starstruck_tile_${point.x}_${point.y}")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = center
            val radius = size.minDimension * 0.31f
            when (tile.kind) {
                StarTileKind.MOON -> {
                    drawCircle(AquaHeart, radius, center)
                    drawCircle(TableNavyRaised, radius * 0.82f, center + Offset(radius * 0.48f, -radius * 0.1f))
                }
                StarTileKind.HEART -> {
                    val path = Path().apply {
                        moveTo(center.x, center.y + radius)
                        cubicTo(center.x - radius * 1.15f, center.y + radius * 0.2f, center.x - radius * 0.8f, center.y - radius, center.x, center.y - radius * 0.32f)
                        cubicTo(center.x + radius * 0.8f, center.y - radius, center.x + radius * 1.15f, center.y + radius * 0.2f, center.x, center.y + radius)
                        close()
                    }
                    drawPath(path, RubyHeart)
                }
                StarTileKind.STAR -> drawPath(starPath(center, radius), MutedGold)
                StarTileKind.COMET -> {
                    drawLine(CometCoral.copy(alpha = 0.65f), center - Offset(radius * 1.2f, -radius * 0.8f), center, radius * 0.45f)
                    drawCircle(CometCoral, radius * 0.68f, center)
                }
                StarTileKind.PLANET -> {
                    drawCircle(PlanetSage, radius * 0.74f, center)
                    drawOval(
                        color = WarmIvory.copy(alpha = 0.75f),
                        topLeft = Offset(center.x - radius * 1.15f, center.y - radius * 0.36f),
                        size = Size(radius * 2.3f, radius * 0.72f),
                        style = Stroke(width = radius * 0.16f),
                    )
                }
                StarTileKind.CRYSTAL -> {
                    val crystal = Path().apply {
                        moveTo(center.x, center.y - radius)
                        lineTo(center.x + radius * 0.78f, center.y - radius * 0.28f)
                        lineTo(center.x + radius * 0.58f, center.y + radius)
                        lineTo(center.x - radius * 0.58f, center.y + radius)
                        lineTo(center.x - radius * 0.78f, center.y - radius * 0.28f)
                        close()
                    }
                    drawPath(crystal, CrystalBlue)
                    drawLine(WarmIvory.copy(alpha = 0.6f), center - Offset(0f, radius * 0.75f), center + Offset(0f, radius * 0.72f), radius * 0.1f)
                }
            }
            when (tile.power) {
                StarPower.NONE -> Unit
                StarPower.ROW_CLEAR -> {
                    drawLine(WarmIvory, Offset(size.width * 0.12f, center.y), Offset(size.width * 0.88f, center.y), radius * 0.15f)
                    drawCircle(VioletHeart, radius = radius * 0.18f, center = center)
                }
                StarPower.COLOR_CLEAR -> {
                    drawCircle(WarmIvory, radius * 1.18f, center, style = Stroke(width = radius * 0.13f))
                    drawCircle(LimeHeart, radius * 0.18f, center)
                }
            }
        }
    }
}

private fun starPath(center: Offset, radius: Float): Path = Path().apply {
    repeat(10) { index ->
        val pointRadius = if (index % 2 == 0) radius else radius * 0.42f
        val angle = -PI / 2.0 + index * PI / 5.0
        val x = center.x + cos(angle).toFloat() * pointRadius
        val y = center.y + sin(angle).toFloat() * pointRadius
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun StarstruckSettingsDialog(
    settings: StarstruckSettings,
    onSettings: (StarstruckSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .background(TableNavyRaised, RoundedCornerShape(24.dp))
                .border(1.dp, MutedGold.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("STARSTRUCK SETTINGS", color = MutedGold, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Sound", color = WarmIvory)
                    Text(
                        if (settings.soundMuted) "Muted" else "${settings.soundVolume}%",
                        color = MistBlue,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = !settings.soundMuted,
                    onCheckedChange = { enabled -> onSettings(settings.copy(soundMuted = !enabled)) },
                    modifier = Modifier.semantics { contentDescription = "Starstruck sound" },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSettings(settings.copy(soundVolume = settings.soundVolume - 10)) },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                ) { Text("QUIETER") }
                Button(
                    onClick = { onSettings(settings.copy(soundVolume = settings.soundVolume + 10)) },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                ) { Text("LOUDER") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Gentle effects", color = WarmIvory)
                    Text("Brief text and selection pulses", color = MistBlue, fontSize = 11.sp)
                }
                Switch(
                    checked = settings.gentleEffects,
                    onCheckedChange = { onSettings(settings.copy(gentleEffects = it)) },
                    modifier = Modifier.semantics { contentDescription = "Starstruck gentle effects" },
                )
            }
            Text(
                text = "Review defaults: a straight four creates a Row Burst at the moved tile; a straight five or more creates a Color Nova. Row Bursts clear their row when matched. Swap a Color Nova to clear that tile type. Dead boards reshuffle automatically. No timer, lives, or game over.",
                color = WarmIvory,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(
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
