package cz.peelco.jolt.features.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cz.peelco.jolt.app.containerViewModel
import cz.peelco.jolt.domain.model.ApiToken
import cz.peelco.jolt.domain.model.ApiTokenDraft
import cz.peelco.jolt.domain.model.ApiTokenFriendScope
import cz.peelco.jolt.domain.model.ApiTokenScope
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.repository.ApiTokensRepository
import cz.peelco.jolt.features.remote.TitledSheet
import cz.peelco.jolt.features.shared.BannerStyle
import cz.peelco.jolt.features.shared.FormButton
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.InlineBanner
import cz.peelco.jolt.features.shared.copyToClipboard
import cz.peelco.jolt.features.shared.relativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Personal access tokens: what the web dashboard offers, in the app. */
class ApiTokensViewModel(
    private val repository: ApiTokensRepository,
) : ViewModel() {
    private val tokenList = MutableStateFlow<List<ApiToken>?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val minted = MutableStateFlow<ApiToken?>(null)
    val tokens: StateFlow<List<ApiToken>?> = tokenList.asStateFlow()
    val lastError: StateFlow<String?> = error.asStateFlow()

    /** The token just minted, the only time its secret is visible. */
    val newToken: StateFlow<ApiToken?> = minted.asStateFlow()

    fun load() {
        viewModelScope.launch {
            runCatching { repository.listTokens() }
                .onSuccess { tokenList.value = it }
                .onFailure { error.value = it.message }
        }
    }

    fun create(draft: ApiTokenDraft) {
        viewModelScope.launch {
            runCatching { repository.createToken(draft) }
                .onSuccess {
                    minted.value = it
                    load()
                }.onFailure { error.value = it.message }
        }
    }

    fun revoke(token: ApiToken) {
        viewModelScope.launch {
            runCatching { repository.revokeToken(token.id) }.onFailure { error.value = it.message }
            load()
        }
    }

    fun dismissNewToken() {
        minted.value = null
    }

    fun dismissError() {
        error.value = null
    }
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

@Composable
fun ApiTokensScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel = containerViewModel { ApiTokensViewModel(it.social) }
    val tokens by viewModel.tokens.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val newToken by viewModel.newToken.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<ApiToken?>(null) }
    LaunchedEffect(Unit) { viewModel.load() }

    FormScreen(
        title = "API tokens",
        onBack = onBack,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, contentDescription = "New token") } },
        bottomBar = { InlineBanner(error, BannerStyle.ERROR, onDismiss = viewModel::dismissError) },
    ) {
        Text(
            "Tokens let your own scripts fire at you or poke friends. Each friend decides separately whether your scripts may poke them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        FormSection {
            when {
                tokens == null -> BusyRow("Loading…")
                tokens!!.isEmpty() -> FormRow { Text("No tokens yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            tokens.orEmpty().forEachIndexed { index, token ->
                if (index > 0) FormDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(token.name, fontWeight = FontWeight.SemiBold)
                    Text("${token.prefix}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(describe(token), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { revoking = token }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (creating) {
        NewTokenSheet(
            onCreate = {
                viewModel.create(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    newToken?.let { token -> SecretDialog(context, token, viewModel::dismissNewToken) }
    revoking?.let { token ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke ${token.name}?") },
            text = { Text("It stops working immediately. Scripts using it will get 401.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revoke(token)
                    revoking = null
                }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
}

private fun describe(token: ApiToken): String =
    buildList {
        add(token.scopes.joinToString(", ") { it.title })
        add(if (token.friendScope == ApiTokenFriendScope.ALL) "all friends" else "${token.friendIds.size} selected friend(s)")
        token.allowedKinds?.let { kinds -> add(kinds.joinToString("/") { it.displayName }) }
        token.maxIntensity?.let { add("≤$it%") }
        if (token.minIntervalSeconds > 1) add("every ${token.minIntervalSeconds}s at most")
        add(token.lastUsedAt?.let { "used ${relativeTime(it)}" } ?: "never used")
        add(token.expiresAt?.let { "expires ${DATE.format(it)}" } ?: "no expiry")
    }.joinToString(" · ")

@Composable
private fun SecretDialog(
    context: Context,
    token: ApiToken,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this token now") },
        text = {
            Column {
                Text("It is shown only once; the server keeps just its hash.")
                SelectionContainer { Text(token.token.orEmpty(), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, "Jolt API token", token.token.orEmpty())
                onDismiss()
            }) { Text("Copy and close") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NewTokenSheet(
    onCreate: (ApiTokenDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf(setOf(ApiTokenScope.STIMULUS_SELF)) }
    var kinds by remember { mutableStateOf(StimulusKind.entries.toSet()) }
    var maxIntensity by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && scopes.isNotEmpty() && kinds.isNotEmpty()

    // Every kind selected means no restriction, which the server spells as absent.
    fun draft() =
        ApiTokenDraft(
            name = name.trim(),
            scopes = scopes.toList(),
            expiresInDays = expiry.toIntOrNull()?.coerceIn(1, 365),
            allowedKinds = if (kinds.size == StimulusKind.entries.size) null else kinds.toList(),
            maxIntensity = maxIntensity.toIntOrNull()?.coerceIn(0, 100),
            minIntervalSeconds = interval.toIntOrNull()?.coerceIn(1, 86_400),
        )
    TitledSheet(
        title = "New token",
        onDismiss = onDismiss,
        leading = "Cancel" to onDismiss,
        trailing = "Create" to { onCreate(draft()) },
        trailingEnabled = valid,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            FormSection(footer = "What it is for, such as \"home assistant\" or \"focus timer\".") {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
            FormSection(header = "Scopes") {
                ApiTokenScope.entries.forEach { scope ->
                    FormRow {
                        Column(Modifier.weight(1f)) {
                            Text(scope.title)
                            Text(scope.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = scope in scopes, onCheckedChange = { scopes = if (it) scopes + scope else scopes - scope })
                    }
                }
            }
            FormSection(header = "Limits", footer = "Limits apply on top of each friend's own permissions; the lower cap wins. Leave a field empty for no limit of your own.") {
                StimulusKind.entries.forEach { kind ->
                    FormRow {
                        Text(kind.displayName, modifier = Modifier.weight(1f))
                        Checkbox(checked = kind in kinds, onCheckedChange = { kinds = if (it) kinds + kind else kinds - kind })
                    }
                }
                NumberField("Max intensity (%)", maxIntensity) { maxIntensity = it }
                NumberField("Seconds between stimuli", interval) { interval = it }
                NumberField("Expires in days", expiry) { expiry = it }
            }
            FormButton("Create token", enabled = valid) { onCreate(draft()) }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
