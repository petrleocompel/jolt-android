package cz.peelco.jolt.features.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.app.LocalAppContainer
import cz.peelco.jolt.domain.model.Friend
import cz.peelco.jolt.domain.model.FriendPokeDraft
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.features.shared.FireControl
import cz.peelco.jolt.features.shared.HoldFillBar
import cz.peelco.jolt.features.shared.StatusCard
import cz.peelco.jolt.features.shared.StatusCardButton
import cz.peelco.jolt.features.shared.Stepper
import cz.peelco.jolt.features.shared.icon
import cz.peelco.jolt.ui.theme.EyebrowStyle
import cz.peelco.jolt.ui.theme.JoltColors
import cz.peelco.jolt.ui.theme.remoteNumeral

/**
 * Poke one friend: kind, intensity up to their cap, repetitions, and a fire
 * control in the user's firing mode. Remembers kind and intensity per friend.
 */
@Composable
fun PokeComposer(
    friend: Friend,
    viewModel: PokeViewModel,
) {
    val container = LocalAppContainer.current
    val drafts = container.settings.friendPokeDrafts
    val firing by container.settings.firingModes.flow.collectAsStateWithLifecycle()
    val send by viewModel.sendState.collectAsStateWithLifecycle()
    val flashing by container.pokeFeedback.isFlashing.collectAsStateWithLifecycle()
    val sentLabel by container.pokeFeedback.isShowingSuccessLabel.collectAsStateWithLifecycle()

    val permissions = friend.permissionsGrantedToMe
    val resolved = remember(friend.id, permissions) { FriendPokeDraft.resolved(drafts.value[friend.id.toString()], permissions) }
    if (resolved == null) {
        Text("${friend.displayName} hasn't allowed you to poke them yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var kind by remember(friend.id) { mutableStateOf(resolved.kind) }
    var intensity by remember(friend.id) { mutableIntStateOf(resolved.intensity) }
    var repetitions by remember(friend.id) { mutableIntStateOf(1) }
    val cap = permissions[kind].maxIntensity.coerceAtLeast(1)

    fun saveDraft() = drafts.update { it + (friend.id.toString() to FriendPokeDraft(kind, intensity)) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            permissions.allowedKinds.forEach { option ->
                val selected = option == kind
                Surface(
                    onClick = {
                        kind = option
                        intensity = intensity.coerceAtMost(permissions[option].maxIntensity.coerceAtLeast(0))
                        saveDraft()
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) JoltColors.Violet.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (selected) BorderStroke(1.dp, JoltColors.Violet.copy(alpha = 0.6f)) else null,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                ) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val tint = if (selected) JoltColors.VioletInk else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(option.icon, contentDescription = null, tint = tint)
                        Text(option.displayName, color = tint, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("INTENSITY", style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("$intensity", style = remoteNumeral(30.sp))
                Text("%", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
            Slider(
                value = intensity.toFloat().coerceAtMost(cap.toFloat()),
                onValueChange = { intensity = it.toInt() },
                onValueChangeFinished = ::saveDraft,
                valueRange = 0f..cap.toFloat(),
                colors = SliderDefaults.colors(thumbColor = JoltColors.Violet, activeTrackColor = JoltColors.Violet),
            )
            Text("${friend.displayName}'s cap: ${permissions[kind].maxIntensity}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (permissions[kind].automationAllowedEffective == false) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.SettingsSuggest, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Your scripts can't send ${friend.displayName} ${kind.displayName.lowercase()}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Stepper("Repetitions: $repetitions", "", repetitions, StimulusConfig.REPETITIONS_RANGE) { repetitions = it }

        when {
            send.isSending ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).background(JoltColors.Violet.copy(alpha = 0.35f), RoundedCornerShape(17.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Sending…", style = remoteNumeral(16.sp), color = Color.White)
                    }
                }
            send.error != null -> {
                val unconfirmed = send.failure == PokeViewModel.SendFailure.UNCONFIRMED
                StatusCard(
                    eyebrow =
                        when (send.failure) {
                            PokeViewModel.SendFailure.UNCONFIRMED -> "NOT CONFIRMED"
                            PokeViewModel.SendFailure.NOT_SENT -> "COULDN'T REACH THE SERVER"
                            else -> "POKE NOT SENT"
                        },
                    message = send.error.orEmpty(),
                    tint = if (unconfirmed) JoltColors.Warning else JoltColors.Danger,
                ) {
                    StatusCardButton("Retry", viewModel::retryLastSend, fill = JoltColors.Violet)
                    StatusCardButton("Dismiss", viewModel::dismissError)
                }
            }
            else -> {
                val mode = firing[kind]
                FireControl(
                    mode = mode,
                    confirmTitle = "Poke ${friend.displayName}?",
                    confirmMessage = "${kind.displayName} at $intensity%.",
                    onFire = { viewModel.send(friend, StimulusConfig(kind, intensity.coerceAtMost(cap), repetitions)) },
                    modifier = Modifier.testTag("pokeButton"),
                ) { state ->
                    HoldFillBar(
                        idleText = "${mode.actionVerb} to poke ${friend.displayName}",
                        holdingText = "Keep holding…",
                        tint = JoltColors.Violet,
                        ink = Color.White,
                        state = state,
                        successText = if (sentLabel) "Sent" else null,
                        isFlashing = flashing,
                    )
                }
            }
        }
    }
}
