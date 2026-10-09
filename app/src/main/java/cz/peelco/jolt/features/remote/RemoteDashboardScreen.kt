package cz.peelco.jolt.features.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.app.LocalAppContainer
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.DismissChallenge
import cz.peelco.jolt.domain.model.FiringInteractionMode
import cz.peelco.jolt.domain.model.PavlokDevice
import cz.peelco.jolt.domain.model.PokeEvent
import cz.peelco.jolt.domain.model.RemoteWidgetKind
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.features.alarms.AlarmEditSheet
import cz.peelco.jolt.features.device.DeviceControlViewModel
import cz.peelco.jolt.features.device.DeviceLink
import cz.peelco.jolt.features.onboarding.PairDeviceSheet
import cz.peelco.jolt.features.shared.BannerStyle
import cz.peelco.jolt.features.shared.FireControl
import cz.peelco.jolt.features.shared.HIGH_INTENSITY_THRESHOLD
import cz.peelco.jolt.features.shared.HoldFillBar
import cz.peelco.jolt.features.shared.InlineBanner
import cz.peelco.jolt.features.shared.RemoteCard
import cz.peelco.jolt.features.shared.RemoteCardStyle
import cz.peelco.jolt.features.shared.StimulusFireRing
import cz.peelco.jolt.features.shared.icon
import cz.peelco.jolt.features.shared.initials
import cz.peelco.jolt.features.shared.relativeTime
import cz.peelco.jolt.ui.theme.EyebrowStyle
import cz.peelco.jolt.ui.theme.ForcedDarkTheme
import cz.peelco.jolt.ui.theme.JoltColors
import cz.peelco.jolt.ui.theme.remoteNumeral
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime

/** The Remote tab: the pinned device card, then the user's widgets in their order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDashboardScreen(
    deviceViewModel: DeviceControlViewModel,
    onOpenDeviceDetail: () -> Unit,
) {
    val container = LocalAppContainer.current
    val layout by container.settings.dashboardLayout.flow.collectAsStateWithLifecycle()
    val device by deviceViewModel.connectedDevice.collectAsStateWithLifecycle()
    val state by deviceViewModel.connectionState.collectAsStateWithLifecycle()
    val paired by deviceViewModel.pairedDevice.collectAsStateWithLifecycle()
    val stimulusSettings by deviceViewModel.stimulusSettings.collectAsStateWithLifecycle()
    val firing by container.settings.firingModes.flow.collectAsStateWithLifecycle()
    val quickPoke by container.quickPoke.settings.collectAsStateWithLifecycle()
    val alarms by container.alarms.store.alarms.collectAsStateWithLifecycle()
    val activity by container.social.activity.collectAsStateWithLifecycle()
    val deviceError by deviceViewModel.lastError.collectAsStateWithLifecycle()
    val quickPokeError by container.quickPoke.lastError.collectAsStateWithLifecycle()
    val actionMessage by deviceViewModel.lastActionMessage.collectAsStateWithLifecycle()
    val feedbackMessage by container.pokeFeedback.lastSuccessMessage.collectAsStateWithLifecycle()

    var showCustomize by remember { mutableStateOf(false) }
    var editingKind by remember { mutableStateOf<StimulusKind?>(null) }
    var editingAlarm by remember { mutableStateOf<Alarm?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    var showQuickPokeComposer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ForcedDarkTheme {
        Scaffold(
            containerColor = JoltColors.RemoteBackground,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Remote") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = JoltColors.RemoteBackground),
                )
            },
            bottomBar = {
                // One slot; an error beats a success.
                when {
                    deviceError != null -> InlineBanner(deviceError, BannerStyle.ERROR, onDismiss = deviceViewModel::dismissError)
                    quickPokeError != null -> InlineBanner(quickPokeError, BannerStyle.ERROR, onDismiss = container.quickPoke::dismissError)
                    actionMessage != null -> InlineBanner(actionMessage, BannerStyle.SUCCESS)
                    else -> InlineBanner(feedbackMessage, BannerStyle.SUCCESS)
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DeviceHeroCard(
                    device = device,
                    link = DeviceLink.of(state, paired != null),
                    pairedName = paired?.name,
                    onPair = { showPairing = true },
                    onTryAgain = deviceViewModel::reconnect,
                    onMoreInfo = onOpenDeviceDetail,
                )
                layout.visible.forEach { widget ->
                    when (widget) {
                        RemoteWidgetKind.ZAP, RemoteWidgetKind.VIBE, RemoteWidgetKind.BEEP -> {
                            val kind = widget.stimulusKind!!
                            StimulusRow(
                                config = stimulusSettings[kind],
                                isConnected = device != null,
                                deviceName = device?.name,
                                mode = firing[kind],
                                onEdit = { editingKind = kind },
                                onFire = { deviceViewModel.fire(stimulusSettings[kind]) },
                            )
                        }
                        RemoteWidgetKind.QUICK_POKE ->
                            if (quickPoke.isConfigured) {
                                QuickPokeCard(onAdjust = { showQuickPokeComposer = true })
                            }
                        RemoteWidgetKind.NEXT_ALARM -> {
                            val next =
                                alarms
                                    .mapNotNull { alarm -> alarm.nextOccurrence()?.let { alarm to it } }
                                    .minByOrNull { it.second }
                            if (next != null) NextAlarmCard(next.first, next.second) { editingAlarm = next.first }
                        }
                        RemoteWidgetKind.RECENT_ACTIVITY -> if (activity.isNotEmpty()) RecentActivityCard(activity.take(2))
                    }
                }
                CustomizeRow { showCustomize = true }
            }
        }

        if (showCustomize) RemoteCustomizeSheet(isQuickPokeConfigured = quickPoke.isConfigured) { showCustomize = false }
        editingKind?.let { kind ->
            StimulusEditorSheet(stimulusSettings[kind], onSave = deviceViewModel::saveStimulusConfig) { editingKind = null }
        }
        editingAlarm?.let { alarm ->
            AlarmEditSheet(
                alarm = alarm,
                onSave = { updated -> scope.launch { container.alarms.save(updated) } },
                onDismiss = { editingAlarm = null },
            )
        }
        if (showPairing) PairDeviceSheet(deviceViewModel) { showPairing = false }
        if (showQuickPokeComposer) QuickPokeComposerSheet { showQuickPokeComposer = false }
    }
}

@Composable
private fun CustomizeRow(onClick: () -> Unit) {
    val dash = Color.White.copy(alpha = 0.18f)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                )
            }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Customize home screen", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceHeroCard(
    device: PavlokDevice?,
    link: DeviceLink,
    pairedName: String?,
    onPair: () -> Unit,
    onTryAgain: () -> Unit,
    onMoreInfo: () -> Unit,
) {
    if (link == DeviceLink.Connected) {
        RemoteCard(RemoteCardStyle.DEVICE, Modifier.testTag("deviceStatusRow")) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    StatusBadge("CONNECTED", JoltColors.Green)
                    Text(device?.name ?: "No device", style = remoteNumeral(22.sp), color = Color.White, modifier = Modifier.padding(top = 6.dp))
                    Text(device?.family?.displayName ?: "Connected", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                }
                device?.info?.batteryLevelPercent?.let { battery ->
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("$battery", style = remoteNumeral(50.sp), color = Color.White)
                            Text("%", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
                        }
                        Text("BATTERY", style = EyebrowStyle, color = Color.White.copy(alpha = 0.42f))
                    }
                }
            }
            device?.info?.batteryLevelPercent?.let { battery ->
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(battery / 100f).background(JoltColors.Green))
                }
            }
            Text(
                "More info ›",
                color = JoltColors.Green,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.End).padding(top = 10.dp).clickable(onClick = onMoreInfo),
            )
        }
        return
    }

    val (badge, tint, message) =
        when (link) {
            DeviceLink.None -> Triple("NO DEVICE", Color.White.copy(alpha = 0.5f), "Alarms, friends and pokes still work. Firing needs a paired Pavlok.")
            DeviceLink.Offline -> Triple("NOT CONNECTED · Out of range or switched off", JoltColors.Warning, "Still paired. The app keeps trying to reconnect in the background.")
            DeviceLink.Connecting -> Triple("CONNECTING · Looking for your Pavlok", JoltColors.Info, "Keep the device close while it connects.")
            is DeviceLink.Failed -> Triple("FAILED · ${link.reason}", JoltColors.Danger, "The connection didn't go through. Move closer, then try again.")
            DeviceLink.Connected -> error("handled above")
        }
    RemoteCard(RemoteCardStyle.NEUTRAL, Modifier.testTag("deviceStatusRow")) {
        StatusBadge(badge, tint)
        Text(
            if (link == DeviceLink.None) "Not connected" else pairedName ?: "Not connected",
            style = remoteNumeral(30.sp),
            color = Color.White,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(message, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (link == DeviceLink.None) {
                PillButton("Pair a device", primary = true, modifier = Modifier.weight(1f), onClick = onPair)
            } else {
                PillButton("Try again", primary = true, modifier = Modifier.weight(1f), onClick = onTryAgain)
                PillButton("Pair a different device", primary = false, modifier = Modifier.weight(1f), onClick = onPair)
            }
        }
    }
}

@Composable
private fun PillButton(
    title: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (primary) JoltColors.Green else Color.White.copy(alpha = 0.10f),
                contentColor = if (primary) Color.Black else Color.White,
            ),
        modifier = modifier.heightIn(min = 50.dp),
    ) { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun StatusBadge(
    text: String,
    tint: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(tint, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = EyebrowStyle.copy(fontSize = 10.sp), color = tint)
    }
}

@Composable
private fun StimulusRow(
    config: StimulusConfig,
    isConnected: Boolean,
    deviceName: String?,
    mode: FiringInteractionMode,
    onEdit: () -> Unit,
    onFire: () -> Unit,
) {
    val kind = config.kind
    // Dimmed without a device, but still tappable so a tap can say why.
    RemoteCard(RemoteCardStyle.STIMULUS, Modifier.alpha(if (isConnected) 1f else 0.4f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable(onClick = onEdit).semantics { contentDescription = "${kind.displayName}, ${config.intensity} percent. Opens intensity and repetitions settings" },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(kind.icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(kind.displayName, style = remoteNumeral(17.sp), color = Color.White)
                    if (config.intensity > HIGH_INTENSITY_THRESHOLD) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "HIGH",
                            style = EyebrowStyle.copy(fontSize = 10.sp),
                            color = JoltColors.Amber,
                            modifier = Modifier.border(1.dp, JoltColors.Amber.copy(alpha = 0.42f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                    Text("${config.intensity}", style = remoteNumeral(36.sp), color = Color.White)
                    Text("%", color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (config.repetitions > 1) "×${config.repetitions}" else "${mode.actionVerb.lowercase()} to fire",
                        color = Color.White.copy(alpha = 0.42f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            FireControl(
                mode = mode,
                confirmTitle = "Fire ${kind.displayName}?",
                confirmMessage = "${kind.displayName} at ${config.intensity}%${deviceName?.let { " on $it" }.orEmpty()}.",
                confirmActionTitle = "Fire",
                enabled = isConnected,
                onUnavailable = onFire,
                highIntensityPercent = config.intensity,
                onFire = onFire,
                modifier = Modifier.testTag("fire_${kind.wireName}"),
            ) { state -> StimulusFireRing(kind.icon, state) }
        }
    }
}

@Composable
private fun QuickPokeCard(onAdjust: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.quickPoke.settings.collectAsStateWithLifecycle()
    val firing by container.settings.firingModes.flow.collectAsStateWithLifecycle()
    val error by container.quickPoke.lastError.collectAsStateWithLifecycle()
    val flashing by container.pokeFeedback.isFlashing.collectAsStateWithLifecycle()
    val sentLabel by container.pokeFeedback.isShowingSuccessLabel.collectAsStateWithLifecycle()
    val name = settings.targetFriendName ?: "friend"
    val mode = firing[settings.stimulus.kind]
    RemoteCard(RemoteCardStyle.QUICK_POKE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(JoltColors.Violet.copy(alpha = 0.22f)).border(1.dp, JoltColors.Violet.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(initials(settings.targetFriendName ?: "Friend"), color = JoltColors.VioletInk, style = remoteNumeral(16.sp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("QUICK POKE", style = EyebrowStyle.copy(fontSize = 10.sp), color = JoltColors.Violet)
                Text(settings.targetFriendName ?: "Friend", style = remoteNumeral(19.sp), color = Color.White)
            }
            IconButton(onClick = onAdjust, modifier = Modifier.semantics { contentDescription = "Adjust and send a one-off poke" }) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
            }
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("${settings.stimulus.kind.displayName} · ${settings.stimulus.intensity}%")
            if (settings.stimulus.repetitions > 1) Chip("×${settings.stimulus.repetitions}")
            error?.let { Text(it, color = JoltColors.Danger, style = MaterialTheme.typography.bodySmall) }
        }
        FireControl(
            mode = mode,
            confirmTitle = "Poke $name?",
            onFire = container.quickPoke::sendQuickPoke,
            modifier = Modifier.testTag("quickPokeButton"),
        ) { state ->
            HoldFillBar(
                idleText = "${mode.actionVerb} to poke $name",
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

@Composable
private fun Chip(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.75f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun NextAlarmCard(
    alarm: Alarm,
    next: ZonedDateTime,
    onClick: () -> Unit,
) {
    val until = Duration.between(ZonedDateTime.now(), next)
    RemoteCard(RemoteCardStyle.NEUTRAL, Modifier.clickable(onClick = onClick)) {
        Text("NEXT ALARM", style = EyebrowStyle.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.45f))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(alarm.timeText, style = remoteNumeral(32.sp), color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("in ${until.toHours()}h ${until.toMinutes() % 60}m", color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        val challenge = if (alarm.dismissChallenge != DismissChallenge.NONE) " · ${alarm.dismissChallenge.displayName}" else ""
        Text("${alarm.stimulus.kind.displayName} ${alarm.stimulus.intensity}%$challenge", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecentActivityCard(events: List<PokeEvent>) {
    RemoteCard(RemoteCardStyle.NEUTRAL) {
        Text("RECENT ACTIVITY", style = EyebrowStyle.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.45f))
        events.forEach { event ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(event.sentence, color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (event.isAutomated) Icon(Icons.Filled.SettingsSuggest, contentDescription = "Sent by a script", tint = Color.White.copy(alpha = 0.38f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(relativeTime(event.createdAt), color = Color.White.copy(alpha = 0.38f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
