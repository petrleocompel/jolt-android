package cz.peelco.jolt.features.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.app.LocalAppContainer
import cz.peelco.jolt.app.containerViewModel
import cz.peelco.jolt.domain.model.RemoteWidgetKind
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.features.friends.FriendsViewModel
import cz.peelco.jolt.features.friends.PokeComposer
import cz.peelco.jolt.features.friends.PokeViewModel
import cz.peelco.jolt.features.shared.BannerStyle
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.InlineBanner
import cz.peelco.jolt.features.shared.StatusCard
import cz.peelco.jolt.features.shared.StatusCardButton
import cz.peelco.jolt.features.shared.Stepper
import cz.peelco.jolt.features.shared.tint
import cz.peelco.jolt.ui.theme.ForcedDarkTheme
import cz.peelco.jolt.ui.theme.JoltColors
import kotlinx.coroutines.delay

/** A sheet with a title row and leading/trailing text actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitledSheet(
    title: String,
    onDismiss: () -> Unit,
    leading: Pair<String, () -> Unit>? = null,
    trailing: Pair<String, () -> Unit>? = null,
    trailingEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { leading?.let { (label, action) -> TextButton(onClick = action) { Text(label) } } }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                trailing?.let { (label, action) -> TextButton(onClick = action, enabled = trailingEnabled) { Text(label, fontWeight = FontWeight.SemiBold) } }
            }
        }
        content()
    }
}

/** "Zap settings": intensity 0…100 step 1, repetitions 1…5. */
@Composable
fun StimulusEditorSheet(
    config: StimulusConfig,
    onSave: (StimulusConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var intensity by remember { mutableIntStateOf(config.intensity) }
    var repetitions by remember { mutableIntStateOf(config.repetitions) }
    TitledSheet(
        title = "${config.kind.displayName} settings",
        onDismiss = onDismiss,
        leading = "Cancel" to onDismiss,
        trailing =
            "Save" to {
                onSave(StimulusConfig(config.kind, intensity, repetitions))
                onDismiss()
            },
    ) {
        FormSection {
            FormRow {
                Text("Intensity", modifier = Modifier.weight(1f))
                Text("$intensity%", fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = intensity.toFloat(),
                onValueChange = { intensity = it.toInt() },
                valueRange = 0f..100f,
                steps = 99,
                colors = SliderDefaults.colors(thumbColor = config.kind.tint, activeTrackColor = config.kind.tint),
                modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "$intensity percent" },
            )
            FormDivider()
            Stepper("Repetitions", "${repetitions}×", repetitions, StimulusConfig.REPETITIONS_RANGE) { repetitions = it }
        }
        Spacer(Modifier.size(24.dp))
    }
}

/** Customize home: reorder and hide widgets, or bring them back from the gallery. */
@Composable
fun RemoteCustomizeSheet(
    isQuickPokeConfigured: Boolean,
    onDismiss: () -> Unit,
) {
    val settings = LocalAppContainer.current.settings
    val layout by settings.dashboardLayout.flow.collectAsStateWithLifecycle()
    ForcedDarkTheme {
        TitledSheet(title = "Customize home", onDismiss = onDismiss, trailing = "Done" to onDismiss) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                FormSection(header = "On your home screen", footer = "Use the arrows to reorder, or remove a widget to send it to the gallery below.") {
                    layout.visible.forEachIndexed { index, kind ->
                        if (index > 0) FormDivider()
                        FormRow {
                            Column(Modifier.weight(1f)) {
                                Text(kind.displayName)
                                Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(
                                onClick = { settings.dashboardLayout.update { it.moving(kind, -1) } },
                                enabled = index > 0,
                                modifier = Modifier.semantics { contentDescription = "Move ${kind.displayName} up" },
                            ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) }
                            IconButton(
                                onClick = { settings.dashboardLayout.update { it.moving(kind, 1) } },
                                enabled = index < layout.visible.lastIndex,
                                modifier = Modifier.semantics { contentDescription = "Move ${kind.displayName} down" },
                            ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) }
                            IconButton(
                                onClick = { settings.dashboardLayout.update { it.hiding(kind) } },
                                modifier = Modifier.semantics { contentDescription = "Remove ${kind.displayName} from home screen" },
                            ) { Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                val footer =
                    if (!isQuickPokeConfigured && RemoteWidgetKind.QUICK_POKE in layout.visible + layout.hidden) {
                        "Quick poke only appears on your dashboard once it's configured in Settings → Quick poke."
                    } else {
                        null
                    }
                FormSection(header = "Widget gallery", footer = footer) {
                    if (layout.hidden.isEmpty()) {
                        FormRow { Text("Every widget is already on your home screen.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        layout.hidden.chunked(2).forEach { row ->
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { kind ->
                                    Surface(
                                        onClick = { settings.dashboardLayout.update { it.showing(kind) } },
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Icon(Icons.Filled.AddCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.End))
                                            Text(kind.displayName, fontWeight = FontWeight.Medium)
                                            Text(kind.hint, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A one-off poke to the quick-poke friend, with the full composer. */
@Composable
fun QuickPokeComposerSheet(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.quickPoke.settings.collectAsStateWithLifecycle()
    val friendsViewModel = containerViewModel(key = "quickPokeFriends") { FriendsViewModel(it.social) }
    val pokeViewModel = containerViewModel(key = "quickPokeComposer") { PokeViewModel(it.social, it.pokeFeedback) }
    val friends by friendsViewModel.friends.collectAsStateWithLifecycle()
    val feedback by container.pokeFeedback.lastSuccessMessage.collectAsStateWithLifecycle()
    var timedOut by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        friendsViewModel.refresh()
        delay(8_000)
        if (friendsViewModel.friends.value == null) timedOut = true
    }

    val friend = friends?.firstOrNull { it.id == settings.targetFriendId }
    ForcedDarkTheme {
        TitledSheet(
            title = friend?.let { "Poke ${it.displayName}" } ?: settings.targetFriendName?.let { "Poke $it" } ?: "Quick poke",
            onDismiss = onDismiss,
            leading = "Close" to onDismiss,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                when {
                    friend != null -> PokeComposer(friend, pokeViewModel)
                    friends == null && !timedOut ->
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    friends == null ->
                        StatusCard(
                            "COULDN'T REACH THE SERVER",
                            "Your friends list didn't load, so nothing can be sent yet. Check your connection and try again.",
                            JoltColors.Danger,
                        ) {
                            StatusCardButton("Retry", {
                                timedOut = false
                                attempt++
                            }, fill = JoltColors.Violet)
                            StatusCardButton("Close", onDismiss)
                        }
                    else ->
                        StatusCard(
                            "FRIEND NOT FOUND",
                            "${settings.targetFriendName ?: "This friend"} is no longer on your friends list. Send a new request, or pick someone else in Settings → Quick poke.",
                            JoltColors.Warning,
                        ) { StatusCardButton("Close", onDismiss) }
                }
                Spacer(Modifier.width(1.dp))
            }
            InlineBanner(feedback, BannerStyle.SUCCESS)
            Spacer(Modifier.size(16.dp))
        }
    }
}
