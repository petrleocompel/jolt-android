package cz.peelco.jolt.features.friends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.domain.model.Friend
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.StimulusPermission
import cz.peelco.jolt.features.shared.BannerStyle
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.InlineBanner
import cz.peelco.jolt.features.shared.icon
import cz.peelco.jolt.features.shared.tint
import java.util.UUID

private data class Preset(
    val name: String,
    val icon: ImageVector,
    val subtitle: String,
    val permission: StimulusPermission,
)

private val presets =
    listOf(
        Preset("Full", Icons.Filled.Shield, "Allow everything, 100% intensity, no cooldown", StimulusPermission.allowed(100, 0)),
        Preset("Trusted", Icons.Filled.GppGood, "Allow everything, up to 60%, 15s cooldown", StimulusPermission.allowed(60, 15)),
        Preset("Cautious", Icons.Filled.GppMaybe, "Allow everything, up to 20%, 2 min cooldown", StimulusPermission.allowed(20, 120)),
        Preset("Off", Icons.Filled.GppBad, "Don't allow any stimulus from this friend", StimulusPermission.DISABLED),
    )

/** What this friend may send you, per stimulus, plus their scripts' consent. */
@Composable
fun PermissionEditScreen(
    friendId: UUID,
    viewModel: FriendsViewModel,
    onBack: () -> Unit,
) {
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val policies by viewModel.serverPolicies.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val friend = friends?.firstOrNull { it.id == friendId } ?: return
    val supportsConsent = StimulusKind.entries.any { friend.permissionsIGranted[it].supportsAutomationConsent }

    FormScreen(title = "Permissions", onBack = onBack, bottomBar = { InlineBanner(error, BannerStyle.ERROR, onDismiss = viewModel::dismissError) }) {
        FormSection(
            header = "Quick setup",
            footer = "Applies to all three stimuli at once. Fine-tune any of them below." + if (supportsConsent) " Automated-poke choices stay as they are." else "",
        ) {
            presets.forEachIndexed { index, preset ->
                if (index > 0) FormDivider()
                val active = StimulusKind.entries.all { friend.permissionsIGranted[it].hasSameGrant(preset.permission) }
                FormRow(Modifier.clickable { StimulusKind.entries.forEach { viewModel.updatePermission(friend, it, preset.permission) } }) {
                    Icon(preset.icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(preset.name)
                        Text(preset.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (active) Icon(Icons.Filled.CheckCircle, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        StimulusKind.entries.forEach { kind -> KindSection(friend, kind, policies?.automationConsentRequired, viewModel) }
    }
}

@Composable
private fun KindSection(
    friend: Friend,
    kind: StimulusKind,
    consentRequired: Boolean?,
    viewModel: FriendsViewModel,
) {
    val permission = friend.permissionsIGranted[kind]
    val footer =
        if (permission.isAllowed && permission.supportsAutomationConsent) {
            "Automated pokes come from ${friend.displayName}'s scripts (their API tokens), not from ${friend.displayName} in person." +
                when (consentRequired) {
                    true -> " Default: blocked until you allow them."
                    false -> " Default: allowed unless you block them."
                    null -> ""
                }
        } else {
            null
        }
    Column {
        Row(Modifier.padding(start = 28.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon, contentDescription = null, tint = kind.tint, modifier = Modifier.size(16.dp))
            Text(" ${kind.displayName.uppercase()}", color = kind.tint, style = MaterialTheme.typography.labelMedium)
        }
        FormSection(footer = footer) {
            FormRow {
                Text("Allow", modifier = Modifier.weight(1f))
                Switch(checked = permission.isAllowed, onCheckedChange = { viewModel.updatePermission(friend, kind, permission.copy(isAllowed = it)) })
            }
            if (permission.isAllowed) {
                FormDivider()
                EditableSliderRow("MAX INTENSITY", permission.maxIntensity, 0..100, "%", kind.tint) { viewModel.updatePermission(friend, kind, permission.copy(maxIntensity = it)) }
                FormDivider()
                EditableSliderRow("COOLDOWN", permission.cooldownSeconds, 0..600, "s", kind.tint) { viewModel.updatePermission(friend, kind, permission.copy(cooldownSeconds = it)) }
                if (permission.supportsAutomationConsent) {
                    FormDivider()
                    AutomationPicker(permission, consentRequired) { viewModel.updateAutomationConsent(friend, kind, it) }
                }
            }
        }
    }
}

@Composable
private fun AutomationPicker(
    permission: StimulusPermission,
    consentRequired: Boolean?,
    onSelect: (Boolean?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val defaultAllowed = if (permission.automationAllowed == null) permission.automationAllowedEffective else consentRequired?.not()
    val defaultLabel =
        when (defaultAllowed) {
            true -> "Default (allowed)"
            false -> "Default (blocked)"
            null -> "Default"
        }
    val current =
        when (permission.automationAllowed) {
            null -> defaultLabel
            true -> "Allow"
            false -> "Block"
        }
    Column {
        FormRow(Modifier.clickable { open = true }) {
            Text("Automated pokes", modifier = Modifier.weight(1f))
            Text(current, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf<Pair<String, Boolean?>>(defaultLabel to null, "Allow" to true, "Block" to false).forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}

/**
 * A slider that commits on release, one write per drag, with a tappable
 * value that turns into a number field for an exact entry.
 */
@Composable
private fun EditableSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    tint: Color,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(value) { dragging = value.toFloat() }

    fun commitText() {
        editing = false
        text.toIntOrNull()?.let { onCommit(it.coerceIn(range)) }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (editing) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitText() }),
                    modifier = Modifier.width(88.dp),
                )
                Text(unit)
                IconButton(onClick = ::commitText) { Icon(Icons.Filled.CheckCircle, contentDescription = "Set $label", tint = MaterialTheme.colorScheme.primary) }
            } else {
                Text(
                    "${dragging.toInt()}$unit",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier.clickable {
                            text = ""
                            editing = true
                        },
                )
            }
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onCommit(dragging.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
        )
    }
}
