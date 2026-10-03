package com.shai.riven.ui.reminder

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shai.riven.data.reminder.ReminderController
import com.shai.riven.data.reminder.ReminderDeliveryMode
import com.shai.riven.data.reminder.ReminderDraft
import com.shai.riven.data.reminder.ReminderFeature
import com.shai.riven.data.reminder.ReminderFeatureControl
import com.shai.riven.data.reminder.ReminderOperationResult
import com.shai.riven.data.reminder.ReminderQuietHours
import com.shai.riven.data.reminder.ReminderSettingsSnapshot
import com.shai.riven.data.reminder.ReminderSnapshot
import com.shai.riven.data.reminder.ReminderSoundKind
import com.shai.riven.data.reminder.ReminderStatus
import com.shai.riven.data.reminder.ReminderTimeZonePolicy
import com.shai.riven.data.reminder.defaultFeatureControl
import com.shai.riven.data.reminder.platform.ReminderPermissionInspector
import com.shai.riven.data.reminder.platform.ReminderPermissionSnapshot
import com.shai.riven.data.reminder.platform.ReminderRuntime
import com.shai.riven.ui.theme.MistBlue
import com.shai.riven.ui.theme.MutedGold
import com.shai.riven.ui.theme.WarmIvory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.launch

private val HunterGreen = Color(0xFF173B2A)
private val DeepHunterGreen = Color(0xFF0D291D)

@Composable
fun ReminderAlarmScreen(
    modifier: Modifier = Modifier,
    controllerFactory: (android.content.Context) -> ReminderController = {
        ReminderRuntime.from(it).repository
    },
) {
    val context = LocalContext.current
    val controller = remember(context, controllerFactory) {
        controllerFactory(context.applicationContext)
    }
    val permissionInspector = remember(context) { ReminderPermissionInspector(context) }
    val scope = rememberCoroutineScope()
    var reminders by remember { mutableStateOf(emptyList<ReminderSnapshot>()) }
    var settings by remember {
        mutableStateOf(
            ReminderSettingsSnapshot(
                quietHours = ReminderQuietHours(),
                featureControls = ReminderFeature.entries.associateWith(::defaultFeatureControl),
            ),
        )
    }
    var permissions by remember { mutableStateOf(permissionInspector.snapshot()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var localDateTimeText by remember {
        mutableStateOf(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).toString())
    }
    var audible by remember { mutableStateOf(false) }
    var soundKind by remember { mutableStateOf(ReminderSoundKind.SYSTEM_DEFAULT) }
    var customSoundUri by remember { mutableStateOf<String?>(null) }

    fun refreshPermissionsAndSchedules() {
        permissions = permissionInspector.snapshot()
        scope.launch { controller.rescheduleAll("permission_screen_return") }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissionsAndSchedules() }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshPermissionsAndSchedules() }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onSuccess {
                customSoundUri = uri.toString()
                soundKind = ReminderSoundKind.CUSTOM_URI
                feedback = "Custom local sound selected."
            }.onFailure {
                feedback = "Android did not grant durable access to that audio file."
            }
        }
    }

    LaunchedEffect(controller) {
        controller.observeReminders().collect { reminders = it }
    }
    LaunchedEffect(controller) {
        controller.observeSettings().collect { settings = it }
    }

    fun clearForm() {
        editingId = null
        title = ""
        note = ""
        localDateTimeText = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).toString()
        audible = false
        soundKind = ReminderSoundKind.SYSTEM_DEFAULT
        customSoundUri = null
    }

    fun saveReminder() {
        val parsed = try {
            LocalDateTime.parse(localDateTimeText.trim())
        } catch (_: DateTimeParseException) {
            feedback = "Use a local date and time like 2026-10-04T09:30."
            return
        }
        val draft = ReminderDraft(
            title = title,
            note = note,
            feature = ReminderFeature.REMINDERS,
            localDateTime = parsed,
            zoneId = ZoneId.systemDefault(),
            timeZonePolicy = ReminderTimeZonePolicy.FOLLOW_DEVICE,
            deliveryMode = if (audible) {
                ReminderDeliveryMode.AUDIBLE_ALARM
            } else {
                ReminderDeliveryMode.NOTIFICATION
            },
            soundKind = soundKind,
            customSoundUri = customSoundUri,
        )
        scope.launch {
            val result = editingId?.let { controller.edit(it, draft) } ?: controller.create(draft)
            feedback = result.userMessage()
            if (result is ReminderOperationResult.Success) clearForm()
            permissions = permissionInspector.snapshot()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("reminder_alarm_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "RIVEN REMINDERS",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MutedGold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Local, durable, and explicit. Every alarm reports its real Android state.",
                    color = WarmIvory,
                )
                HorizontalDivider(color = MutedGold.copy(alpha = 0.7f))
            }
        }

        item {
            PermissionPanel(
                permissions = permissions,
                onRequestNotifications = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onOpenNotificationSettings = {
                    settingsLauncher.launch(permissionInspector.notificationSettingsIntent())
                },
                onOpenExactAlarmSettings = {
                    settingsLauncher.launch(permissionInspector.exactAlarmPermissionIntent())
                },
            )
        }

        item {
            ReminderEditor(
                editing = editingId != null,
                title = title,
                onTitleChange = { title = it },
                note = note,
                onNoteChange = { note = it },
                localDateTimeText = localDateTimeText,
                onLocalDateTimeChange = { localDateTimeText = it },
                audible = audible,
                onAudibleChange = { audible = it },
                soundKind = soundKind,
                onSoundKindChange = { soundKind = it },
                customSoundReady = customSoundUri != null,
                onChooseCustomSound = { audioPicker.launch(arrayOf("audio/*")) },
                onSave = ::saveReminder,
                onCancelEdit = ::clearForm,
            )
        }

        item {
            QuietHoursPanel(
                settings = settings,
                onSave = { quiet, featureControl ->
                    scope.launch {
                        controller.updateQuietHours(quiet)
                        controller.updateFeatureControl(featureControl)
                        feedback = "Reminder controls saved and active items rescheduled."
                    }
                },
            )
        }

        feedback?.let { message ->
            item {
                Text(
                    text = message,
                    color = if (message.contains("required", ignoreCase = true) ||
                        message.contains("failed", ignoreCase = true)
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MistBlue
                    },
                    modifier = Modifier.testTag("reminder_feedback"),
                )
            }
        }

        item {
            Text(
                text = "SCHEDULED ITEMS",
                color = MutedGold,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (reminders.isEmpty()) {
            item { Text("No reminders yet.", color = MistBlue) }
        } else {
            items(reminders, key = ReminderSnapshot::id) { reminder ->
                ReminderCard(
                    reminder = reminder,
                    onEdit = {
                        editingId = reminder.id
                        title = reminder.title
                        note = reminder.note.orEmpty()
                        localDateTimeText = reminder.localDateTime.toString()
                        audible = reminder.deliveryMode == ReminderDeliveryMode.AUDIBLE_ALARM
                        soundKind = reminder.soundKind
                        customSoundUri = reminder.customSoundUri
                    },
                    onComplete = {
                        scope.launch { feedback = controller.complete(reminder.id).userMessage() }
                    },
                    onCancel = {
                        scope.launch { feedback = controller.cancel(reminder.id).userMessage() }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionPanel(
    permissions: ReminderPermissionSnapshot,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
) {
    GatsbyCard {
        Text("ANDROID DELIVERY STATUS", color = MutedGold, fontWeight = FontWeight.Bold)
        val notificationsAvailable = permissions.notificationPermissionGranted &&
            permissions.notificationsEnabled
        PermissionRow(
            label = "Reminder notifications",
            allowed = notificationsAvailable && permissions.reminderChannelEnabled,
            deniedText = when {
                !permissions.notificationPermissionGranted -> "Permission required"
                !permissions.notificationsEnabled -> "App notifications disabled"
                else -> "Reminder channel disabled"
            },
        )
        PermissionRow(
            label = "Alarm controls",
            allowed = notificationsAvailable && permissions.alarmChannelEnabled,
            deniedText = when {
                !permissions.notificationPermissionGranted -> "Permission required"
                !permissions.notificationsEnabled -> "App notifications disabled"
                else -> "Alarm channel disabled"
            },
        )
        if (!permissions.notificationPermissionGranted) {
            OutlinedButton(onClick = onRequestNotifications) { Text("Request notification permission") }
        } else if (!permissions.notificationsEnabled ||
            !permissions.reminderChannelEnabled ||
            !permissions.alarmChannelEnabled
        ) {
            OutlinedButton(onClick = onOpenNotificationSettings) { Text("Open notification settings") }
        }
        PermissionRow(
            label = "Exact audible alarms",
            allowed = permissions.exactAlarmsAllowed,
            deniedText = "Permission required",
        )
        if (!permissions.exactAlarmsAllowed) {
            OutlinedButton(onClick = onOpenExactAlarmSettings) { Text("Open exact alarm settings") }
        }
        Text(
            "Alarm sound follows Android volume and Do Not Disturb. Riven does not override DND.",
            color = MistBlue,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermissionRow(label: String, allowed: Boolean, deniedText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = WarmIvory)
        Text(if (allowed) "READY" else deniedText.uppercase(), color = if (allowed) MutedGold else MistBlue)
    }
}

@Composable
private fun ReminderEditor(
    editing: Boolean,
    title: String,
    onTitleChange: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    localDateTimeText: String,
    onLocalDateTimeChange: (String) -> Unit,
    audible: Boolean,
    onAudibleChange: (Boolean) -> Unit,
    soundKind: ReminderSoundKind,
    onSoundKindChange: (ReminderSoundKind) -> Unit,
    customSoundReady: Boolean,
    onChooseCustomSound: () -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    GatsbyCard {
        Text(if (editing) "EDIT REMINDER" else "NEW REMINDER", color = MutedGold, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth().testTag("reminder_title"),
            singleLine = true,
        )
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text("Note optional") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = localDateTimeText,
            onValueChange = onLocalDateTimeChange,
            label = { Text("Local date and time") },
            supportingText = { Text("Example 2026-10-04T09:30 • follows device time zone") },
            modifier = Modifier.fillMaxWidth().testTag("reminder_local_time"),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Audible alarm", color = WarmIvory)
                Text("Exact timing, snooze and dismiss", color = MistBlue, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = audible, onCheckedChange = onAudibleChange)
        }
        if (audible) {
            Text("ALARM SOUND", color = MutedGold, style = MaterialTheme.typography.labelMedium)
            SoundChip("Riven voice", soundKind == ReminderSoundKind.SYSTEM_DEFAULT) {
                onSoundKindChange(ReminderSoundKind.SYSTEM_DEFAULT)
            }
            SoundChip(
                if (customSoundReady) "Custom clip selected" else "Custom local clip",
                soundKind == ReminderSoundKind.CUSTOM_URI,
                onChooseCustomSound,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSave, modifier = Modifier.testTag("save_reminder")) {
                Text(if (editing) "Save changes" else "Schedule")
            }
            if (editing) TextButton(onClick = onCancelEdit) { Text("Cancel edit") }
        }
    }
}

@Composable
private fun SoundChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun QuietHoursPanel(
    settings: ReminderSettingsSnapshot,
    onSave: (ReminderQuietHours, ReminderFeatureControl) -> Unit,
) {
    val initialControl = settings.featureControls[ReminderFeature.REMINDERS]
        ?: defaultFeatureControl(ReminderFeature.REMINDERS)
    var enabled by remember(settings.quietHours) { mutableStateOf(settings.quietHours.enabled) }
    var start by remember(settings.quietHours) {
        mutableStateOf(formatMinute(settings.quietHours.startMinuteOfDay))
    }
    var end by remember(settings.quietHours) {
        mutableStateOf(formatMinute(settings.quietHours.endMinuteOfDay))
    }
    var featureEnabled by remember(initialControl) { mutableStateOf(initialControl.enabled) }
    var allowDuringQuiet by remember(initialControl) { mutableStateOf(initialControl.allowDuringQuietHours) }

    GatsbyCard {
        Text("DELIVERY CONTROLS", color = MutedGold, fontWeight = FontWeight.Bold)
        ToggleRow("Reminder feature enabled", featureEnabled) { featureEnabled = it }
        ToggleRow("Quiet hours", enabled) { enabled = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = start,
                onValueChange = { start = it },
                label = { Text("Starts") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = end,
                onValueChange = { end = it },
                label = { Text("Ends") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        ToggleRow("Allow reminders during quiet hours", allowDuringQuiet) { allowDuringQuiet = it }
        Button(
            onClick = {
                val startMinute = parseMinute(start) ?: return@Button
                val endMinute = parseMinute(end) ?: return@Button
                onSave(
                    ReminderQuietHours(enabled, startMinute, endMinute),
                    ReminderFeatureControl(
                        feature = ReminderFeature.REMINDERS,
                        enabled = featureEnabled,
                        allowDuringQuietHours = allowDuringQuiet,
                    ),
                )
            },
        ) { Text("Save controls") }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = WarmIvory, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ReminderCard(
    reminder: ReminderSnapshot,
    onEdit: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    GatsbyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(reminder.title, color = WarmIvory, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(reminder.status.name, color = statusColor(reminder.status))
        }
        reminder.note?.let { Text(it, color = MistBlue) }
        Text(
            "${reminder.localDateTime} • ${reminder.deliveryMode.name.lowercase().replace('_', ' ')}",
            color = MistBlue,
            style = MaterialTheme.typography.bodySmall,
        )
        reminder.lastFailureDetail?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (reminder.status in setOf(
                ReminderStatus.SCHEDULED,
                ReminderStatus.SNOOZED,
                ReminderStatus.FAILED,
            )
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onComplete) { Text("Complete") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun GatsbyCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DeepHunterGreen),
        border = BorderStroke(1.dp, MutedGold.copy(alpha = 0.68f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

private fun ReminderOperationResult.userMessage(): String = when (this) {
    is ReminderOperationResult.Success -> when (reminder.status) {
        ReminderStatus.COMPLETED -> "Reminder completed."
        ReminderStatus.CANCELLED -> "Reminder cancelled."
        ReminderStatus.SNOOZED -> "Reminder snoozed."
        else -> "Reminder ${reminder.status.name.lowercase()}."
    }
    is ReminderOperationResult.Failure -> message
}

private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

private fun parseMinute(value: String): Int? {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun statusColor(status: ReminderStatus): Color = when (status) {
    ReminderStatus.SCHEDULED,
    ReminderStatus.SNOOZED,
    ReminderStatus.RINGING,
    ReminderStatus.DELIVERED,
    -> MutedGold
    ReminderStatus.FAILED -> Color(0xFFFF7B7B)
    else -> MistBlue
}
