package cz.peelco.jolt.features.pavlok

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cz.peelco.jolt.app.containerViewModel
import cz.peelco.jolt.data.pavlok.PavlokApiException
import cz.peelco.jolt.data.pavlok.PavlokSession
import cz.peelco.jolt.domain.model.PavlokAccount
import cz.peelco.jolt.domain.model.PavlokFriend
import cz.peelco.jolt.domain.model.PavlokPokePermission
import cz.peelco.jolt.domain.model.PavlokStimulusLogEntry
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.features.remote.TitledSheet
import cz.peelco.jolt.features.settings.BusyRow
import cz.peelco.jolt.features.settings.Picker
import cz.peelco.jolt.features.shared.FormButton
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.LabeledValue
import cz.peelco.jolt.features.shared.Stepper
import cz.peelco.jolt.ui.theme.JoltColors
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The optional Pavlok account: sign in, poke Pavlok friends within what they
 * allow, and show the wearable's own log. Kept apart from the Jolt server:
 * different ids, permissions and capabilities.
 *
 * Receiving pokes isn't possible here: Pavlok delivers them only to its own
 * app, and its log has no sender.
 */
class PavlokAccountViewModel(
    private val session: PavlokSession,
) : ViewModel() {
    data class State(
        val friends: List<PavlokFriend> = emptyList(),
        val permissions: Map<Int, PavlokPokePermission> = emptyMap(),
        val activity: List<PavlokStimulusLogEntry> = emptyList(),
        val isLoading: Boolean = false,
        val isSending: Boolean = false,
        val error: String? = null,
        val sentMessage: String? = null,
    )

    val account: StateFlow<PavlokAccount?> = session.account
    private val state = MutableStateFlow(State())
    val ui: StateFlow<State> = state.asStateFlow()
    private var journalMac: String? = null

    fun restore() {
        if (session.restore()) refresh()
    }

    fun signIn(
        email: String,
        password: String,
    ) {
        state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                session.signIn(email, password)
                refreshNow()
            } catch (failure: Exception) {
                state.update { it.copy(error = failure.message) }
            } finally {
                state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun signOut() {
        session.signOut()
        journalMac = null
        state.value = State()
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        if (account.value == null) return
        state.update { it.copy(isLoading = true) }
        try {
            val friends = viewModelScope.async { session.client.friends() }
            val grants = viewModelScope.async { session.client.receivedPokePermissions() }
            state.update { it.copy(friends = friends.await(), permissions = grants.await(), error = null) }
        } catch (failure: Exception) {
            handle(failure)
        } finally {
            state.update { it.copy(isLoading = false) }
        }
        try {
            val mac = journalMac ?: session.client.devices().firstOrNull()?.macAddress
            journalMac = mac
            if (mac != null) state.update { it.copy(activity = session.client.stimulusJournal(mac)) }
        } catch (failure: Exception) {
            handle(failure)
        }
    }

    fun permission(friend: PavlokFriend): PavlokPokePermission = ui.value.permissions[friend.id] ?: PavlokPokePermission.NONE

    /** Clamped to what the friend allows, again, in case it changed underneath. */
    fun poke(
        friend: PavlokFriend,
        stimulus: StimulusConfig,
    ) {
        val grant = permission(friend)
        if (!grant.allows(stimulus.kind)) {
            state.update { it.copy(error = "${friend.displayName} hasn't allowed ${stimulus.kind.displayName.lowercase()}.") }
            return
        }
        val clamped = stimulus.copy(intensity = minOf(stimulus.intensity, grant.maxIntensity(stimulus.kind)))
        state.update { it.copy(isSending = true, error = null, sentMessage = null) }
        viewModelScope.launch {
            try {
                session.client.sendPoke(friend.id, clamped)
                state.update { it.copy(sentMessage = "Sent ${clamped.kind.displayName.lowercase()} to ${friend.displayName}") }
                delay(3_000)
                state.update { it.copy(sentMessage = null) }
            } catch (failure: Exception) {
                handle(failure)
            } finally {
                state.update { it.copy(isSending = false) }
            }
        }
    }

    private fun handle(failure: Exception) {
        state.update { it.copy(error = failure.message) }
        // An expired Pavlok token drops back to the sign-in form.
        if (failure is PavlokApiException.Unauthorized) signOut()
    }
}

private val JOURNAL_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun PavlokAccountScreen(onBack: () -> Unit) {
    val viewModel = containerViewModel { PavlokAccountViewModel(it.pavlok) }
    val account by viewModel.account.collectAsStateWithLifecycle()
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var composing by remember { mutableStateOf<PavlokFriend?>(null) }
    LaunchedEffect(Unit) { viewModel.restore() }

    FormScreen(title = "Pavlok account", onBack = onBack) {
        state.sentMessage?.let { FormSection { FormRow { Text(it, color = JoltColors.Success) } } }
        state.error?.let { FormSection { FormRow { Text(it, color = MaterialTheme.colorScheme.error) } } }
        val signedIn = account
        if (signedIn == null) {
            SignInSection(state.isLoading, viewModel::signIn)
            return@FormScreen
        }
        FormSection(header = "Account") {
            LabeledValue("Signed in as", signedIn.displayName)
            FormButton("Sign out", color = MaterialTheme.colorScheme.error, onClick = viewModel::signOut)
        }
        FormSection(header = "Pavlok friends", footer = "You can only send what each friend has allowed. Zap intensity is capped by their limit.") {
            if (state.friends.isEmpty()) FormRow { Text(if (state.isLoading) "Loading…" else "No Pavlok friends found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.friends.forEachIndexed { index, friend ->
                if (index > 0) FormDivider()
                val grant = viewModel.permission(friend)
                val allowed = StimulusKind.entries.filter(grant::allows)
                FormRow(Modifier.clickable(enabled = allowed.isNotEmpty()) { composing = friend }) {
                    Column(Modifier.weight(1f)) {
                        Text(friend.displayName)
                        Text(summary(grant), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.TouchApp, contentDescription = null, tint = if (allowed.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FormSection(
            header = "Device activity",
            footer =
                "This is your wearable's own log of stimuli it fired, uploaded to Pavlok. It doesn't record who caused each one, so a friend's poke looks the same as pressing the button yourself. " +
                    "Pavlok delivers incoming pokes only to their own app, so Jolt can't show them.",
        ) {
            if (state.activity.isEmpty()) FormRow { Text("Nothing logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.activity.take(20).forEach { entry -> LabeledValue(entry.kind?.displayName ?: entry.rawName, JOURNAL_TIME.format(entry.timestamp)) }
        }
        FormSection { FormButton("Refresh", onClick = viewModel::refresh) }
    }
    composing?.let { friend ->
        PavlokPokeComposer(friend, viewModel.permission(friend), onSend = { viewModel.poke(friend, it) }) { composing = null }
    }
}

private fun summary(grant: PavlokPokePermission): String {
    val parts =
        buildList {
            if (grant.canVibrate) add("vibe")
            if (grant.canChime) add("beep")
            if (grant.canZap) add("zap ≤${grant.maxIntensity(StimulusKind.ZAP)}%")
        }
    return if (parts.isEmpty()) "Hasn't allowed pokes" else "Allows ${parts.joinToString(", ")}"
}

@Composable
private fun SignInSection(
    loading: Boolean,
    onSignIn: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    FormSection(
        header = "Sign in to Pavlok",
        footer =
            "Optional, and separate from your Jolt account. Signing in lets you poke your Pavlok friends from Jolt. " +
                "Your password is sent only to Pavlok and isn't stored — the session token is kept encrypted on this phone.",
    ) {
        OutlinedTextField(email, { email = it }, label = { Text("Pavlok email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        if (loading) BusyRow("Signing in…") else FormButton("Sign in", enabled = email.isNotBlank() && password.isNotBlank()) { onSignIn(email, password) }
    }
}

@Composable
private fun PavlokPokeComposer(
    friend: PavlokFriend,
    grant: PavlokPokePermission,
    onSend: (StimulusConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val allowed = StimulusKind.entries.filter(grant::allows)
    var kind by remember { mutableStateOf(allowed.first()) }
    var intensity by remember { mutableIntStateOf(30) }
    var repetitions by remember { mutableIntStateOf(1) }
    val max = grant.maxIntensity(kind).coerceAtLeast(1)
    TitledSheet(title = "Poke ${friend.displayName}", onDismiss = onDismiss, leading = "Cancel" to onDismiss) {
        FormSection(header = "Stimulus") {
            Picker("Type", kind.displayName, allowed.map { it.displayName to it }) {
                kind = it
                intensity = intensity.coerceAtMost(grant.maxIntensity(it))
            }
            FormRow { Text("Intensity: $intensity%") }
            Slider(
                value = intensity.toFloat().coerceAtMost(max.toFloat()),
                onValueChange = { intensity = ((it / 5).toInt() * 5).coerceAtMost(max) },
                valueRange = 0f..max.toFloat(),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (kind == StimulusKind.ZAP) {
                Text("Capped at ${grant.maxZapValue}% by ${friend.displayName}.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Stepper("Repeat: $repetitions×", "", repetitions, StimulusConfig.REPETITIONS_RANGE) { repetitions = it }
        }
        FormSection {
            FormButton("Send poke") {
                onSend(StimulusConfig(kind, intensity, repetitions))
                onDismiss()
            }
        }
    }
}
