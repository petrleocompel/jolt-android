package cz.peelco.jolt.features.alarms

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.app.LocalAppContainer
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.AlarmLocation
import cz.peelco.jolt.domain.model.DismissChallenge
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.Weekday
import cz.peelco.jolt.features.remote.TitledSheet
import cz.peelco.jolt.features.shared.BannerStyle
import cz.peelco.jolt.features.shared.FormButton
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.InlineBanner
import cz.peelco.jolt.features.shared.QrScanner
import cz.peelco.jolt.features.shared.Stepper
import kotlinx.coroutines.launch

/** The Alarms tab. */
@Composable
fun AlarmsListScreen() {
    val container = LocalAppContainer.current
    val controller = container.alarms
    val alarms by controller.store.alarms.collectAsStateWithLifecycle()
    val error by controller.lastError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Alarm?>(null) }

    FormScreen(
        title = "Alarms",
        onBack = null,
        actions = { IconButton(onClick = { editing = Alarm.newAlarm() }, modifier = Modifier.testTag("addAlarmButton")) { Icon(Icons.Filled.Add, contentDescription = "Add alarm") } },
        bottomBar = { InlineBanner(error, BannerStyle.ERROR, onDismiss = controller::dismissError) },
    ) {
        if (alarms.any { it.location == AlarmLocation.PHONE } && !controller.canScheduleExact) {
            FormSection(footer = "Without the \"Alarms & reminders\" permission phone alarms may ring late.") {
                FormButton("Allow exact alarms") {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        if (alarms.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Alarm, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No alarms", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text("Tap + to add one. Device alarms fire even when your phone is off.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FormSection {
                alarms.forEachIndexed { index, alarm ->
                    if (index > 0) FormDivider()
                    FormRow(Modifier.clickable { editing = alarm }) {
                        Column(Modifier.weight(1f)) {
                            Text(alarm.timeText, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                            Text(
                                alarm.label.ifEmpty { if (alarm.location == AlarmLocation.DEVICE) "Device alarm" else "Phone alarm" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { scope.launch { controller.delete(alarm) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${alarm.label.ifEmpty { alarm.timeText }}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = alarm.isEnabled,
                            onCheckedChange = { scope.launch { controller.toggle(alarm) } },
                            modifier = Modifier.semantics { contentDescription = "${alarm.label.ifEmpty { alarm.timeText }} enabled" },
                        )
                    }
                }
            }
        }
    }
    editing?.let { alarm ->
        AlarmEditSheet(alarm, onSave = { scope.launch { controller.save(it) } }, onDismiss = { editing = null })
    }
}

/** New or existing alarm: time, repeat, where it rings, stimulus, wake-up guarantee, label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditSheet(
    alarm: Alarm,
    onSave: (Alarm) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(alarm) }
    var scanning by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }
    var challengeMenu by remember { mutableStateOf(false) }
    val time = rememberTimePickerState(initialHour = alarm.hour, initialMinute = alarm.minute, is24Hour = true)

    TitledSheet(
        title = draft.label.ifEmpty { "New alarm" },
        onDismiss = onDismiss,
        leading = "Cancel" to onDismiss,
        trailing =
            "Save" to {
                onSave(draft.copy(hour = time.hour, minute = time.minute))
                onDismiss()
            },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (scanning) {
                FormSection(footer = "Point the camera at the QR code you'll scan to dismiss this alarm.") {
                    QrScanner(
                        onScanned = { code ->
                            draft = draft.copy(dismissQrCode = code)
                            scanning = false
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp),
                    )
                    FormButton("Cancel") { scanning = false }
                }
            }
            FormSection {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { TimeInput(state = time) }
            }
            FormSection(header = "Repeat") {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Weekday.entries.forEach { day ->
                        FilterChip(
                            selected = day in draft.repeatDays,
                            onClick = { draft = draft.copy(repeatDays = if (day in draft.repeatDays) draft.repeatDays - day else draft.repeatDays + day) },
                            label = { Text(day.shortLabel, fontSize = 11.sp, maxLines = 1) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            FormSection(
                header = "Where",
                footer =
                    if (draft.location == AlarmLocation.DEVICE) {
                        "Fires from the wearable's own clock — works even if your phone is off or out of range."
                    } else {
                        "Rings on this phone's alarm sound and opens over the lock screen, and fires the stimulus if your Pavlok is connected."
                    },
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                    listOf(AlarmLocation.DEVICE to "Device", AlarmLocation.PHONE to "Phone").forEachIndexed { index, (location, label) ->
                        SegmentedButton(
                            selected = draft.location == location,
                            onClick = { draft = draft.copy(location = location) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
            }
            FormSection(header = "Stimulus") {
                Column {
                    FormRow(Modifier.clickable { kindMenu = true }) {
                        Text("Kind", modifier = Modifier.weight(1f))
                        Text(draft.stimulus.kind.displayName, color = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                        StimulusKind.entries.forEach { kind ->
                            DropdownMenuItem(text = { Text(kind.displayName) }, onClick = {
                                kindMenu = false
                                draft = draft.copy(stimulus = draft.stimulus.copy(kind = kind))
                            })
                        }
                    }
                }
                FormDivider()
                Stepper("Intensity: ${draft.stimulus.intensity}", "", draft.stimulus.intensity, StimulusConfig.INTENSITY_RANGE) {
                    draft = draft.copy(stimulus = draft.stimulus.copy(intensity = it))
                }
            }
            FormSection(
                header = "Wake-up guarantee",
                footer =
                    when {
                        draft.dismissChallenge != DismissChallenge.QR_CODE_SCAN -> null
                        draft.dismissQrCode == null ->
                            "Print a QR code or pick one on something you own, and keep it away from your bed. Until you save one, any QR code dismisses this alarm."
                        else -> "Only this code will dismiss the alarm — keep it somewhere away from your bed."
                    },
            ) {
                Column {
                    FormRow(Modifier.clickable { challengeMenu = true }) {
                        Text("Dismiss challenge", modifier = Modifier.weight(1f))
                        Text(draft.dismissChallenge.displayName, color = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = challengeMenu, onDismissRequest = { challengeMenu = false }) {
                        DismissChallenge.entries.forEach { challenge ->
                            DropdownMenuItem(text = { Text(challenge.displayName) }, onClick = {
                                challengeMenu = false
                                draft = draft.copy(dismissChallenge = challenge)
                            })
                        }
                    }
                }
                if (draft.dismissChallenge == DismissChallenge.QR_CODE_SCAN) {
                    FormDivider()
                    FormButton(
                        if (draft.dismissQrCode == null) "Scan code to save" else "Code saved · Rescan",
                        icon = if (draft.dismissQrCode == null) Icons.Filled.QrCodeScanner else Icons.Filled.CheckCircle,
                    ) { scanning = true }
                }
            }
            FormSection {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { draft = draft.copy(label = it) },
                    placeholder = { Text("Label") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
