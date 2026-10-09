package cz.peelco.jolt.features.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.BuildConfig
import cz.peelco.jolt.app.LocalAppContainer
import cz.peelco.jolt.data.api.JoltApiClient
import cz.peelco.jolt.domain.model.DeviceButtonSlot
import cz.peelco.jolt.domain.model.Friend
import cz.peelco.jolt.domain.model.FiringInteractionMode
import cz.peelco.jolt.domain.model.PokeFeedbackProfile
import cz.peelco.jolt.domain.model.PokeTrigger
import cz.peelco.jolt.domain.model.ServerConfiguration
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.features.device.DeviceControlViewModel
import cz.peelco.jolt.features.onboarding.PairDeviceSheet
import cz.peelco.jolt.features.shared.FormButton
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.LabeledValue
import cz.peelco.jolt.features.shared.NavigationRow
import cz.peelco.jolt.features.shared.Stepper
import cz.peelco.jolt.features.shared.relativeTime
import cz.peelco.jolt.ui.theme.JoltColors
import kotlinx.coroutines.launch

/** Destinations reachable from the Settings tab. */
enum class SettingsDestination {
    POKE_TRIGGER,
    QUICK_POKE,
    FIRING,
    POKE_FEEDBACK,
    NOTIFICATIONS,
    PAVLOK,
    SERVER,
    API_TOKENS,
    ABOUT,
}

@Composable
fun SettingsScreen(
    deviceViewModel: DeviceControlViewModel,
    onOpen: (SettingsDestination) -> Unit,
) {
    val container = LocalAppContainer.current
    val device by deviceViewModel.connectedDevice.collectAsStateWithLifecycle()
    val paired by deviceViewModel.pairedDevice.collectAsStateWithLifecycle()
    val dnd by container.settings.doNotDisturb.flow.collectAsStateWithLifecycle()
    val stayConnected by container.settings.stayConnectedInBackground.flow.collectAsStateWithLifecycle()
    val trigger by container.pokeTrigger.trigger.collectAsStateWithLifecycle()
    val quickPoke by container.quickPoke.settings.collectAsStateWithLifecycle()
    val firing by container.settings.firingModes.flow.collectAsStateWithLifecycle()
    val feedback by container.pokeFeedback.settings.collectAsStateWithLifecycle()
    val pavlok by container.pavlok.account.collectAsStateWithLifecycle()
    val server by container.social.configuration.collectAsStateWithLifecycle()
    val user by container.social.currentUser.collectAsStateWithLifecycle()
    var showPairing by remember { mutableStateOf(false) }

    FormScreen(title = "Settings", onBack = null) {
        FormSection(
            header = "Device",
            footer =
                if (paired != null) {
                    "Disconnect keeps this device paired — the app reconnects to it automatically next time. Forget removes the pairing entirely. Pairing a different device replaces the current one."
                } else {
                    "No device is paired. Alarms, friends and pokes work without one; firing needs a paired Pavlok."
                },
        ) {
            FormRow {
                Icon(
                    if (device != null) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint =
                        when {
                            device != null -> MaterialTheme.colorScheme.primary
                            paired != null -> JoltColors.Warning
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(Modifier.width(14.dp))
                Text(if (device != null) "Connected" else "Device", modifier = Modifier.weight(1f))
                Text(
                    device?.name ?: if (paired != null) "Not connected" else "None paired",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (paired != null) {
                FormDivider()
                FormButton("Disconnect", icon = Icons.Filled.Cancel, onClick = deviceViewModel::disconnect)
                FormButton("Forget device", color = MaterialTheme.colorScheme.error, icon = Icons.Filled.Delete, onClick = deviceViewModel::forgetPairedDevice)
                FormDivider()
                FormRow {
                    Column(Modifier.weight(1f)) {
                        Text("Stay connected in background")
                        Text("Keeps a notification up so pokes can fire while Jolt is closed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = stayConnected, onCheckedChange = {
                        container.settings.stayConnectedInBackground.set(it)
                        if (it) container.keepWearableConnected()
                    })
                }
            }
            FormDivider()
            NavigationRow(if (paired != null) "Pair a different device" else "Pair device", Icons.Filled.Add) { showPairing = true }
        }

        FormSection(
            header = "Pokes & Firing",
            footer = "While Do Not Disturb is on, incoming pokes are logged in Friends activity but never fire your device. Each stimulus can use its own firing gesture.",
        ) {
            FormRow {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text("Do not disturb incoming pokes", modifier = Modifier.weight(1f))
                Switch(checked = dnd, onCheckedChange = container.settings.doNotDisturb::set, modifier = Modifier.testTag("dndSwitch"))
            }
            FormDivider()
            NavigationRow("Poke from your Pavlok", Icons.Filled.SmartButton, if (trigger.isArmed) "On" else "Off") { onOpen(SettingsDestination.POKE_TRIGGER) }
            FormDivider()
            NavigationRow("Quick poke", Icons.Filled.Bolt, if (quickPoke.isConfigured) "On" else "Off") { onOpen(SettingsDestination.QUICK_POKE) }
            FormDivider()
            NavigationRow("Firing", Icons.Filled.Tune, firing.summaryLabel) { onOpen(SettingsDestination.FIRING) }
            FormDivider()
            NavigationRow("Poke feedback", Icons.Filled.GraphicEq, feedback.profile.displayName) { onOpen(SettingsDestination.POKE_FEEDBACK) }
        }

        FormSection(footer = "Check that a poke sent from the server actually reaches this phone — without needing a friend to send one.") {
            NavigationRow("Notifications", Icons.Filled.NotificationsActive) { onOpen(SettingsDestination.NOTIFICATIONS) }
        }

        FormSection(
            header = "Account",
            footer = "Sign in with a Pavlok account to poke your Pavlok friends from Jolt — optional, and separate from the Jolt server that runs Friends and pokes here.",
        ) {
            NavigationRow("Pavlok account", Icons.Filled.ManageAccounts, pavlok?.displayName ?: "Not signed in") { onOpen(SettingsDestination.PAVLOK) }
            FormDivider()
            NavigationRow("Server", Icons.Filled.Dns, server.host ?: "Not set") { onOpen(SettingsDestination.SERVER) }
            if (user != null) {
                FormDivider()
                NavigationRow("API tokens", Icons.Filled.Key) { onOpen(SettingsDestination.API_TOKENS) }
            }
        }

        FormSection { NavigationRow("About", Icons.Filled.Info) { onOpen(SettingsDestination.ABOUT) } }
    }
    if (showPairing) PairDeviceSheet(deviceViewModel) { showPairing = false }
}

@Composable
fun FiringModesScreen(onBack: () -> Unit) {
    val store = LocalAppContainer.current.settings.firingModes
    val settings by store.flow.collectAsStateWithLifecycle()
    FormScreen(title = "Firing", onBack = onBack) {
        FormSection(
            footer = "Tap fires straight away. Hold needs a press of just under a second on the Remote button. Confirm asks first. In Hold mode, anything above 60% asks for a second hold.",
        ) {
            StimulusKind.entries.forEachIndexed { index, kind ->
                if (index > 0) FormDivider()
                Picker(kind.displayName, settings[kind].displayName, FiringInteractionMode.entries.map { it.displayName to it }) { mode ->
                    store.update { it.with(kind, mode) }
                }
            }
        }
    }
}

/** A row that opens a menu of [options]. */
@Composable
fun <T> Picker(
    label: String,
    current: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        FormRow(Modifier.clickable { open = true }) {
            Text(label, modifier = Modifier.weight(1f))
            Text(current, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (title, value) ->
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    FormRow {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PokeFeedbackScreen(onBack: () -> Unit) {
    val service = LocalAppContainer.current.pokeFeedback
    val settings by service.settings.collectAsStateWithLifecycle()
    FormScreen(title = "Poke feedback", onBack = onBack) {
        FormSection(footer = "Profiles set the toggles below. Changing a toggle switches the profile to Custom.") {
            val profiles = PokeFeedbackProfile.entries.filter { it != PokeFeedbackProfile.CUSTOM || settings.profile == PokeFeedbackProfile.CUSTOM }
            Picker("Profile", settings.profile.displayName, profiles.filter { it != PokeFeedbackProfile.CUSTOM }.map { it.displayName to it }, service::applyProfile)
        }
        FormSection(header = "Effects", footer = "Applies after a successful poke from Quick Poke and Friends, whether you fire with tap, hold, or confirm.") {
            SwitchRow("Banner", settings.showBanner, service::setShowBanner)
            SwitchRow("Flash button", settings.flashButton, service::setFlashButton)
            SwitchRow("Haptic", settings.playHaptic, service::setPlayHaptic)
            SwitchRow("\"Sent\" on button", settings.swapButtonLabel, service::setSwapButtonLabel)
        }
    }
}

/** Friend picker shared by quick poke and the poke trigger. */
@Composable
private fun FriendPicker(
    friends: List<Friend>?,
    selectedId: java.util.UUID?,
    onSelect: (Friend?) -> Unit,
) {
    if (friends.isNullOrEmpty()) {
        FormRow { Text("Add a friend first — pokes go to someone on your friends list.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val current = friends.firstOrNull { it.id == selectedId }?.displayName ?: "None"
    Picker("Friend", current, listOf<Pair<String, Friend?>>("None" to null) + friends.map { it.displayName to it }, onSelect)
}

@Composable
private fun StimulusPickerRows(
    stimulus: StimulusConfig,
    showRepetitions: Boolean,
    onChange: (StimulusConfig) -> Unit,
) {
    Picker("Stimulus", stimulus.kind.displayName, StimulusKind.entries.map { it.displayName to it }) { onChange(stimulus.copy(kind = it)) }
    FormDivider()
    FormRow { Text("Intensity: ${stimulus.intensity}%") }
    Slider(
        value = stimulus.intensity.toFloat(),
        onValueChange = { onChange(stimulus.copy(intensity = (it / 5).toInt() * 5)) },
        valueRange = 0f..100f,
        steps = 19,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (showRepetitions) {
        FormDivider()
        Stepper("Repetitions: ${stimulus.repetitions}", "", stimulus.repetitions, StimulusConfig.REPETITIONS_RANGE) { onChange(stimulus.copy(repetitions = it)) }
    }
}

private const val PERMISSIONS_FOOTER = "Your friend's own permissions still apply — the server caps or blocks anything they haven't allowed you to send."

@Composable
fun QuickPokeSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val service = container.quickPoke
    val settings by service.settings.collectAsStateWithLifecycle()
    val friends by container.social.friends.collectAsStateWithLifecycle()
    val lastSent by service.lastPokeSentAt.collectAsStateWithLifecycle()
    val error by service.lastError.collectAsStateWithLifecycle()
    FormScreen(title = "Quick Poke", onBack = onBack) {
        FormSection(footer = "Adds a one-tap poke button to the Remote tab for the friend you choose below.") {
            SwitchRow("Quick poke on Remote", settings.isEnabled, service::setEnabled)
        }
        if (settings.isEnabled) {
            FormSection(header = "Who to poke") {
                FriendPicker(friends, settings.targetFriendId) { friend -> if (friend == null) service.clearTarget() else service.setTarget(friend.id, friend.displayName) }
            }
        }
        if (settings.targetFriendId != null) {
            FormSection(header = "What they get", footer = PERMISSIONS_FOOTER) {
                StimulusPickerRows(settings.stimulus, showRepetitions = true, onChange = service::setStimulus)
            }
        }
        FormSection(header = "Status") {
            LabeledValue("Ready", if (settings.isConfigured) "Yes" else "No")
            lastSent?.let { LabeledValue("Last poke sent", relativeTime(it)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
fun PokeTriggerSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val service = container.pokeTrigger
    val trigger by service.trigger.collectAsStateWithLifecycle()
    val status by service.status.collectAsStateWithLifecycle()
    val friends by container.social.friends.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { service.cancelLearning() } }

    FormScreen(title = "Poke from Pavlok", onBack = onBack) {
        FormSection(footer = "Pick a button on your Pavlok and Jolt reconfigures it to report presses. Pressing it then pokes the friend you choose.") {
            SwitchRow("Poke a friend from your Pavlok", trigger.isEnabled, service::setEnabled)
        }
        if (trigger.isEnabled) {
            FormSection(header = "Who to poke") {
                FriendPicker(friends, trigger.targetFriendId) { friend -> if (friend == null) service.clearTarget() else service.setTarget(friend.id, friend.displayName) }
            }
            FormSection(header = "What they get", footer = PERMISSIONS_FOOTER) {
                StimulusPickerRows(trigger.stimulus, showRepetitions = false, onChange = service::setStimulus)
            }
            val pressFooter =
                when {
                    !status.isDeviceConnected -> "Connect your Pavlok to set this up."
                    trigger.buttonSlot != null ->
                        "The Pavlok only tells the phone about a press when the button is set to something the phone has to do. This sets it to Find my phone — Jolt ignores the ringing and takes the press. The button stops doing whatever it did before, and only one button at a time can be the poke button."
                    else ->
                        "Learning captures the raw notification your press produces and matches it again later. Use it only if the button above doesn't fire — it needs no capture and survives the changing bytes the device puts after the event type."
                }
            FormSection(header = "Which press", footer = pressFooter) {
                Picker(
                    "Button",
                    trigger.buttonSlot?.displayName ?: "Custom (learn it)",
                    DeviceButtonSlot.configurable.map<DeviceButtonSlot, Pair<String, DeviceButtonSlot?>> { it.displayName to it } + ("Custom (learn it)" to null),
                    service::setButtonSlot,
                )
                FormDivider()
                if (trigger.buttonSlot != null) {
                    FormButton(
                        if (status.isWritingButtonConfig) "Setting up…" else "Set this button up for poking",
                        enabled = status.isDeviceConnected && !status.isWritingButtonConfig,
                    ) { scope.launch { service.makeButtonReportPresses() } }
                } else if (status.isLearning) {
                    val candidate = status.learnCandidate
                    if (candidate != null) {
                        LabeledValue("Captured", candidate.hexString)
                        FormButton("Use this gesture") { service.confirmLearn() }
                    } else {
                        FormRow { Text("Press the button on your Pavlok now…", color = JoltColors.Warning) }
                    }
                    FormButton("Cancel", onClick = service::cancelLearning)
                } else {
                    trigger.learnedBytesHex?.let { learned ->
                        LabeledValue("Learned", learned)
                        Picker("Matching", trigger.matchMode.displayName, PokeTrigger.MatchMode.entries.map { it.displayName to it }, service::setMatchMode)
                        FormButton("Clear", color = MaterialTheme.colorScheme.error, onClick = service::clearLearnedGesture)
                    }
                    FormButton(if (trigger.learnedBytesHex == null) "Learn a gesture" else "Re-learn gesture", enabled = status.isDeviceConnected, onClick = service::startLearning)
                }
            }
            FormSection(header = "Recent device events") {
                if (status.recentEvents.isEmpty()) {
                    FormRow {
                        Text(
                            if (status.isListening) "Nothing yet. Press a button on the device." else "Not listening — connect the device and enable the trigger.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                status.recentEvents.forEach { event ->
                    FormRow {
                        Text(event.hexString, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text(
                            if (event.isFindMyPhoneEvent) "poke button press" else "other device event",
                            color = if (event.isFindMyPhoneEvent) JoltColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        FormSection(header = "Status") {
            LabeledValue("Device", if (status.isDeviceConnected) "Connected" else "Not connected")
            LabeledValue("Listening", if (status.isListening) "Yes" else "No")
            LabeledValue("Armed", if (trigger.isArmed) "Yes" else "No")
            status.lastPokeSentAt?.let { LabeledValue("Last poke sent", relativeTime(it)) }
            status.lastButtonConfigNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            status.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val current by container.social.configuration.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current.baseUrl) }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<Result<Unit>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val parsed = ServerConfiguration.parse(text)
    val valid = (parsed as? ServerConfiguration.ParseResult.Valid)?.configuration

    FormScreen(title = "Server", onBack = onBack) {
        FormSection(header = "Server URL", footer = "Include the API path, usually /api/v1. Accounts don't transfer between servers — switching signs you out.") {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    probe = null
                },
                placeholder = { Text("https://jolt.example.com/api/v1") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("serverUrlField"),
            )
            val (line, color) =
                when {
                    probing -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
                    probe?.isSuccess == true -> "Reachable — this is a Jolt server" to JoltColors.Success
                    probe?.isFailure == true -> (probe?.exceptionOrNull()?.message ?: "Couldn't reach it.") to MaterialTheme.colorScheme.error
                    parsed is ServerConfiguration.ParseResult.Invalid && text.isNotBlank() -> parsed.error.message to JoltColors.Warning
                    else -> "" to Color.Unspecified
                }
            if (line.isNotEmpty()) Text(line, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            FormButton(if (probing) "Checking…" else "Test connection", enabled = valid != null && !probing) {
                val target = valid ?: return@FormButton
                probing = true
                scope.launch {
                    probe = JoltApiClient.probe(target.baseUrl, container.httpEngine())
                    probing = false
                }
            }
            FormButton("Save and switch server", enabled = valid != null && valid != current) { confirm = true }
        }
        if (container.settings.isCustomServer) {
            FormSection(footer = "Default is ${container.settings.defaultServer.baseUrl}.") {
                FormButton("Reset to default server", color = MaterialTheme.colorScheme.error) {
                    scope.launch {
                        container.social.switchServer(container.settings.defaultServer)
                        text = container.settings.defaultServer.baseUrl
                    }
                }
            }
        }
        FormSection(footer = "Jolt Server is open source. The repository's docs/SELFHOSTING.md walks through running one with Docker Compose.") {
            NavigationRow("How to host your own", Icons.AutoMirrored.Filled.MenuBook) {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/petrleocompel/jolt-server".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Switch server?") },
            text = { Text("You'll be signed out. Your friends and poke history stay on the old server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    valid?.let { target -> scope.launch { container.social.switchServer(target) } }
                }) { Text("Switch", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    FormScreen(title = "About", onBack = onBack) {
        Text(
            "Jolt is an independent Android client for Pavlok wearables. Not affiliated with or endorsed by Pavlok Inc.",
            modifier = Modifier.padding(16.dp),
        )
        FormSection {
            LabeledValue("Version", BuildConfig.VERSION_NAME)
            FormDivider()
            NavigationRow("Website") { open("https://petrleocompel.github.io/jolt-ios/") }
            NavigationRow("Privacy policy") { open("https://petrleocompel.github.io/jolt-ios/privacy/") }
            NavigationRow("Support") { open("https://petrleocompel.github.io/jolt-ios/support/") }
            NavigationRow("Source code") { open("https://github.com/petrleocompel/jolt-android") }
        }
    }
}

@Composable
internal fun BusyRow(text: String) {
    FormRow {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text)
    }
}
