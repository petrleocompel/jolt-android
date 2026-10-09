package cz.peelco.jolt.features.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cz.peelco.jolt.app.Notifications
import cz.peelco.jolt.app.containerViewModel
import cz.peelco.jolt.domain.model.PokeDeliveryStatus
import cz.peelco.jolt.domain.model.PushTransport
import cz.peelco.jolt.domain.model.RegisteredDevice
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.TestPushAck
import cz.peelco.jolt.domain.model.TestPushPath
import cz.peelco.jolt.domain.model.TestPushStatus
import cz.peelco.jolt.domain.repository.PushDiagnosticsRepository
import cz.peelco.jolt.features.settings.NotificationTestViewModel.Outcome
import cz.peelco.jolt.features.shared.FormButton
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.JoltPermissions
import cz.peelco.jolt.features.shared.LabeledValue
import cz.peelco.jolt.push.PushRegistrar
import cz.peelco.jolt.push.PushStatus
import cz.peelco.jolt.ui.theme.JoltColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → Notifications: what Android, FCM, the relay and the server each
 * think is set up, and a round trip that proves it. The confirmation is read
 * back from the server, the same thing the web dashboard sees.
 */
class NotificationTestViewModel(
    private val repository: PushDiagnosticsRepository,
    private val registrar: PushRegistrar,
) : ViewModel() {
    data class State(
        val devices: List<RegisteredDevice> = emptyList(),
        val status: TestPushStatus? = null,
        val isSending: Boolean = false,
        val isWaiting: Boolean = false,
        val error: String? = null,
        val fireOnPavlok: Boolean = false,
        val stimulus: StimulusConfig = StimulusConfig(StimulusKind.VIBE, 20),
    )

    sealed interface Outcome {
        data object Waiting : Outcome

        data class Delivered(
            val ack: TestPushAck,
        ) : Outcome

        data class Rejected(
            val reason: String,
        ) : Outcome

        data object NoConfirmation : Outcome

        data object NotConfigured : Outcome
    }

    private val state = MutableStateFlow(State())
    val ui: StateFlow<State> = state.asStateFlow()
    val pushStatus: StateFlow<PushStatus> = registrar.status
    private var pollJob: Job? = null

    /** This phone in the server's list, matched on the relay token's tail. */
    fun thisDevice(state: State = ui.value): RegisteredDevice? {
        val suffix = (pushStatus.value as? PushStatus.Registered)?.tokenSuffix ?: return null
        return state.devices.firstOrNull { it.tokenSuffix == suffix }
    }

    fun outcome(state: State): Outcome? {
        val status = state.status ?: return null
        status.devices.firstOrNull { !it.isAccepted }?.let { return Outcome.Rejected(it.detail ?: it.reason ?: "unknown") }
        status.acks.firstOrNull()?.let { return Outcome.Delivered(it) }
        if (!status.apnsConfigured) return Outcome.NotConfigured
        return if (state.isWaiting) Outcome.Waiting else Outcome.NoConfirmation
    }

    fun refresh() {
        registrar.refresh()
        viewModelScope.launch {
            val devices = runCatching { repository.registeredDevices() }.getOrDefault(emptyList())
            state.update { it.copy(devices = devices) }
        }
    }

    fun setFireOnPavlok(value: Boolean) = state.update { it.copy(fireOnPavlok = value) }

    fun setStimulus(value: StimulusConfig) = state.update { it.copy(stimulus = value) }

    fun send() {
        pollJob?.cancel()
        state.update { it.copy(isSending = true, error = null, status = null) }
        viewModelScope.launch {
            try {
                // Target this phone when it can be identified: on an account
                // with several devices, "did it reach this one" is the question.
                val current = ui.value
                val status = repository.sendTestPush(thisDevice(current)?.id, if (current.fireOnPavlok) current.stimulus else null)
                state.update { it.copy(status = status, isWaiting = true) }
                startPolling(status)
            } catch (failure: Exception) {
                state.update { it.copy(error = failure.message) }
            } finally {
                state.update { it.copy(isSending = false) }
            }
            refresh()
        }
    }

    private fun startPolling(initial: TestPushStatus) {
        if (initial.acks.isNotEmpty()) {
            state.update { it.copy(isWaiting = false) }
            return
        }
        pollJob =
            viewModelScope.launch {
                val deadline = System.currentTimeMillis() + CONFIRMATION_WINDOW_MILLIS
                while (System.currentTimeMillis() < deadline) {
                    delay(1_000)
                    // A 404 means the server forgot the test; the last state
                    // held is still the useful one.
                    val refreshed = runCatching { repository.testPushStatus(initial.testId) }.getOrNull() ?: break
                    state.update { it.copy(status = refreshed) }
                    if (refreshed.acks.isNotEmpty()) break
                }
                state.update { it.copy(isWaiting = false) }
            }
    }

    fun cancelPolling() {
        pollJob?.cancel()
        state.update { it.copy(isWaiting = false) }
    }

    private companion object {
        const val CONFIRMATION_WINDOW_MILLIS = 30_000L
    }
}

@Composable
fun NotificationTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel = containerViewModel { NotificationTestViewModel(it.social, it.pushRegistrar) }
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val pushState = viewModel.pushStatus.collectAsStateWithLifecycle()
    var permissionVersion by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionVersion++ }
    LaunchedEffect(Unit) { viewModel.refresh() }
    DisposableEffect(Unit) { onDispose { viewModel.cancelPolling() } }

    val allowed = remember(permissionVersion) { Notifications.canPost(context) }
    val granted = remember(permissionVersion) { JoltPermissions.hasNotifications(context) }

    FormScreen(title = "Notifications", onBack = onBack) {
        FormSection(
            header = "This device",
            footer = "A push has to clear every step: Android must allow it, Firebase must have a token for this install, the server must use the push relay, and it must know this phone belongs to your account.",
        ) {
            LabeledValue("Permission", if (allowed) "Allowed" else if (!granted) "Not allowed" else "Turned off")
            if (!granted && JoltPermissions.needsNotificationPermission()) {
                FormButton("Allow notifications") { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else if (!allowed) {
                FormButton("Open notification settings") {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            FormDivider()
            val push = pushState.value
            val (pushText, pushColor) = pushDescription(push)
            FormRow {
                Text("Push", modifier = Modifier.weight(1f))
                Text(pushText, color = pushColor, style = MaterialTheme.typography.bodyMedium)
            }
            val thisDevice = viewModel.thisDevice(state)
            LabeledValue(
                "Registered with server",
                when {
                    thisDevice != null -> "Yes (…${thisDevice.tokenSuffix})"
                    push is PushStatus.Registered -> "Yes (…${push.tokenSuffix})"
                    else -> "Not registered"
                },
            )
            (push as? PushStatus.Registered)?.let { LabeledValue("Relay", it.relayHost) }
            FormButton("Check again", onClick = viewModel::refresh)
        }

        FormSection(
            header = "Test",
            footer =
                if (state.fireOnPavlok) {
                    "Tests the whole chain, down to the wearable. Needs a connected Pavlok — and \"Do not disturb incoming pokes\" still applies, so it reports muted rather than firing while that's on."
                } else {
                    "Sends a notification only. Nothing fires, so this works with no Pavlok nearby."
                },
        ) {
            FormRow {
                Text("Also fire it on my Pavlok", modifier = Modifier.weight(1f))
                Switch(checked = state.fireOnPavlok, onCheckedChange = viewModel::setFireOnPavlok)
            }
            if (state.fireOnPavlok) {
                Picker("Kind", state.stimulus.kind.displayName, StimulusKind.entries.map { it.displayName to it }) { viewModel.setStimulus(state.stimulus.copy(kind = it)) }
                FormRow { Text("Intensity: ${state.stimulus.intensity}%") }
                Slider(
                    value = state.stimulus.intensity.toFloat(),
                    onValueChange = { viewModel.setStimulus(state.stimulus.copy(intensity = (it / 5).toInt() * 5)) },
                    valueRange = 0f..100f,
                    steps = 19,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.isSending) {
                BusyRow("Sending…")
            } else {
                FormButton("Send test notification", enabled = pushState.value != PushStatus.SignedOut, onClick = viewModel::send)
            }
        }

        val outcome = viewModel.outcome(state)
        if (outcome != null || state.error != null) {
            FormSection(header = "Result") {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                when (outcome) {
                    Outcome.Waiting -> BusyRow("Sent — waiting for it to arrive…")
                    is Outcome.Delivered -> {
                        FormRow { Text("Arrived in ${"%.1f".format(outcome.ack.elapsedMs / 1000.0)}s", color = JoltColors.Success) }
                        Text(deliveredDetail(outcome.ack), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    is Outcome.Rejected -> {
                        FormRow { Text("The push provider rejected the push", color = MaterialTheme.colorScheme.error) }
                        Text("${outcome.reason}. Tap \"Check again\" above to register this phone afresh.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    Outcome.NoConfirmation -> {
                        FormRow { Text("No confirmation", color = JoltColors.Warning) }
                        Text(
                            "The server sent it but nothing arrived. Notifications may be off for Jolt, battery optimisation may be holding it back, or the relay may not have delivered it.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Outcome.NotConfigured -> {
                        FormRow { Text("Server can't send pushes", color = JoltColors.Warning) }
                        Text(
                            "It has no push delivery configured, so it logged the push instead of delivering it. Pokes will reach you only while the app is open.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun pushDescription(status: PushStatus) =
    when (status) {
        PushStatus.SignedOut -> "Sign in to register" to MaterialTheme.colorScheme.onSurfaceVariant
        PushStatus.Unavailable -> "Not configured in this build" to JoltColors.Warning
        PushStatus.NoToken -> "No token from Firebase" to JoltColors.Warning
        PushStatus.Checking -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        is PushStatus.ServerUnsupported ->
            (
                if (status.transport == PushTransport.APNS) {
                    "This server doesn't support push for Android (iOS only)"
                } else {
                    "This server doesn't support push for Android (push is off)"
                }
            ) to JoltColors.Warning
        is PushStatus.RelayNotAllowed -> "Relay ${status.host} isn't trusted by this build" to MaterialTheme.colorScheme.error
        is PushStatus.Registered -> "Registered" to JoltColors.Success
        is PushStatus.Failed -> status.message to MaterialTheme.colorScheme.error
    }

private fun deliveredDetail(ack: TestPushAck): String {
    val path =
        when (ack.path) {
            TestPushPath.ALERT -> "You tapped the notification"
            TestPushPath.FOREGROUND -> "Shown while the app was open"
            TestPushPath.BACKGROUND -> "Handled in the background"
        }
    val result =
        when (ack.status) {
            PokeDeliveryStatus.FIRED -> " — the stimulus fired"
            PokeDeliveryStatus.DEVICE_NOT_CONNECTED -> " — no Pavlok was connected, so nothing fired"
            PokeDeliveryStatus.MUTED -> " — \"Do not disturb incoming pokes\" is on, so nothing fired"
            PokeDeliveryStatus.NOT_ALLOWED -> " — the stimulus wasn't allowed"
            PokeDeliveryStatus.PENDING -> " — the device hasn't reported back"
            null -> ""
        }
    return "$path$result."
}
