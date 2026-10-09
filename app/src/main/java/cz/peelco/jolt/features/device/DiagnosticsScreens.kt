package cz.peelco.jolt.features.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.ble.BleLog
import cz.peelco.jolt.domain.model.ButtonAction
import cz.peelco.jolt.domain.model.ButtonConfig
import cz.peelco.jolt.domain.model.ButtonConfigReport
import cz.peelco.jolt.domain.model.DeviceButtonSlot
import cz.peelco.jolt.domain.model.GattCharacteristicDump
import cz.peelco.jolt.domain.model.RawWriteMode
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.parseHexBytes
import cz.peelco.jolt.domain.model.toHexString
import cz.peelco.jolt.domain.model.transcript
import cz.peelco.jolt.features.shared.BannerStyle
import cz.peelco.jolt.features.shared.FormButton
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.InlineBanner
import cz.peelco.jolt.features.shared.LabeledValue
import cz.peelco.jolt.features.shared.NavigationRow
import cz.peelco.jolt.features.shared.StatusCard
import cz.peelco.jolt.features.shared.StatusCardButton
import cz.peelco.jolt.features.shared.copyToClipboard
import cz.peelco.jolt.features.shared.shareText
import cz.peelco.jolt.ui.theme.JoltColors
import kotlinx.coroutines.launch

/** The first group of a UUID, the way the lab labels characteristics. */
fun shortUuid(uuid: String): String = uuid.substringBefore('-')

/** GATT inspector: device info, the table, stimulus remapping and live events. */
@Composable
fun DiagnosticsScreen(
    viewModel: DeviceControlViewModel,
    onBack: () -> Unit,
    onOpenProtocolLab: (List<GattCharacteristicDump>) -> Unit,
    onOpenLog: () -> Unit,
    onOpenEvents: (isListening: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device by viewModel.connectedDevice.collectAsStateWithLifecycle()
    val log by BleLog.entries.collectAsStateWithLifecycle()
    var gatt by remember { mutableStateOf<List<GattCharacteristicDump>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var readingValues by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var listenCount by remember { mutableStateOf(0) }
    var listenError by remember { mutableStateOf<String?>(null) }
    var startingListen by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var remapVersion by remember { mutableStateOf(0) }

    suspend fun load() {
        loading = true
        copied = false
        loadError = null
        runCatching { viewModel.readDeviceInfo() }.onFailure { loadError = it.message }
        runCatching { gatt = viewModel.dumpGatt(false) }.onFailure { loadError = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }
    // Listening stops only when this screen goes away for good.
    DisposableEffect(Unit) { onDispose { if (listening) viewModel.stopListeningForDeviceEvents() } }

    val info = device?.info
    val capturedCount = log.count { it.message.startsWith(BleLog.EVENT_PREFIX) }
    FormScreen(
        title = "Diagnostics",
        onBack = onBack,
        actions = {
            IconButton(onClick = { shareText(context, gatt.transcript()) }, enabled = gatt.isNotEmpty()) { Icon(Icons.Filled.Share, contentDescription = "Share GATT dump") }
            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        },
    ) {
        FormSection(header = "Device") {
            if (loading && info?.modelNumber == null) FormRow { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            LabeledValue("Model", info?.modelNumber ?: "—")
            LabeledValue("Serial", info?.serialNumber ?: "—")
            LabeledValue("Firmware", info?.firmwareRevision ?: "—")
            LabeledValue("Hardware", info?.hardwareRevision ?: "—")
            LabeledValue("Software", info?.softwareRevision ?: "—")
            LabeledValue("Manufacturer", info?.manufacturer ?: "—")
            LabeledValue("Battery", info?.batteryLevelPercent?.let { "$it%" } ?: "—")
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }

        FormSection(header = "GATT table") {
            if (gatt.isEmpty()) {
                FormRow { Text(if (loading) "Reading…" else "No services discovered.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            gatt.groupBy { it.serviceUuid }.toSortedMap().forEach { (service, characteristics) ->
                val open = expanded[service] ?: false
                FormRow(Modifier.clickable { expanded[service] = !open }) {
                    Text("Service $service", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
                if (open) {
                    characteristics.sortedBy { it.uuid }.forEach { characteristic ->
                        Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
                            Text(characteristic.uuid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text(characteristic.properties.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            characteristic.value?.let { Text(it.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
                FormDivider()
            }
            FormButton(if (readingValues) "Reading…" else "Read all values", icon = Icons.Filled.Download, enabled = !loading && !readingValues) {
                scope.launch {
                    readingValues = true
                    runCatching { gatt = viewModel.dumpGatt(true) }.onFailure { loadError = it.message }
                    readingValues = false
                }
            }
            FormButton(if (copied) "Copied" else "Copy GATT dump", icon = Icons.Filled.ContentCopy, enabled = gatt.isNotEmpty()) {
                copyToClipboard(context, "GATT dump", gatt.transcript())
                copied = true
            }
        }

        FormSection(
            header = "Stimulus characteristics",
            footer =
                "Remapping writes to a different characteristic. See docs/RE-FINDINGS.md before changing anything. " +
                    "Only remap if a stimulus does nothing or fires the wrong output; read all values first, then test with Beep.",
        ) {
            val writable = gatt.filter { it.isWritable }.map { it.uuid }
            StimulusKind.entries.forEach { kind ->
                key(remapVersion) {
                    RemapRow(kind, viewModel.stimulusCharacteristicUuid(kind), writable) { uuid ->
                        viewModel.setStimulusCharacteristicUuid(kind, uuid)
                        remapVersion++
                    }
                }
                FormDivider()
            }
            FormButton("Reset to defaults", color = MaterialTheme.colorScheme.error) {
                viewModel.resetStimulusCharacteristics()
                remapVersion++
            }
        }

        FormSection(
            header = "Live events",
            footer = "Listening subscribes to button presses and battery notifications. It drains the device faster. Protocol lab opens with the table already read here.",
        ) {
            FormRow {
                Column(Modifier.weight(1f)) {
                    Text("Listen for device events")
                    if (listening && listenCount > 0) {
                        Text(
                            "Listening on $listenCount characteristic${if (listenCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Switch(
                    checked = listening,
                    enabled = !startingListen,
                    onCheckedChange = { on ->
                        listenError = null
                        if (!on) {
                            viewModel.stopListeningForDeviceEvents()
                            listening = false
                            return@Switch
                        }
                        startingListen = true
                        listening = true
                        scope.launch {
                            try {
                                listenCount = viewModel.startListeningForDeviceEvents()
                                if (listenCount == 0) {
                                    listening = false
                                    listenError = "No notifying characteristics could be subscribed."
                                }
                            } catch (error: Exception) {
                                listening = false
                                listenError = error.message
                            }
                            startingListen = false
                        }
                    },
                )
            }
            listenError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (listening || capturedCount > 0) {
                NavigationRow("Captured events", value = "$capturedCount") { onOpenEvents(listening) }
            }
            NavigationRow("Protocol lab", tint = MaterialTheme.colorScheme.primary) { onOpenProtocolLab(gatt) }
            NavigationRow("Bluetooth log", tint = MaterialTheme.colorScheme.primary, onClick = onOpenLog)
        }
    }
}

@Composable
private fun RemapRow(
    kind: StimulusKind,
    current: String,
    writable: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FormRow(Modifier.clickable { open = true }) {
            Text(kind.displayName, modifier = Modifier.weight(1f))
            Text("${shortUuid(current)} · remap", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = if (current in writable) writable else listOf(current) + writable
            options.forEach { uuid ->
                DropdownMenuItem(
                    text = { Text(if (uuid in writable) uuid else "$uuid (not found)", fontFamily = FontFamily.Monospace) },
                    onClick = {
                        open = false
                        onSelect(uuid)
                    },
                )
            }
        }
    }
}

/**
 * Raw writes to any characteristic. No default target on purpose: an
 * unknown characteristic could be the zap output.
 */
@Composable
fun ProtocolLabScreen(
    viewModel: DeviceControlViewModel,
    initialGatt: List<GattCharacteristicDump>?,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var gatt by remember { mutableStateOf(initialGatt) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        if (gatt == null) {
            loadError = null
            runCatching { gatt = viewModel.dumpGatt(false) }.onFailure { loadError = it.message ?: "Unknown error" }
        }
    }

    FormScreen(
        title = "Protocol lab",
        onBack = onBack,
        actions = { IconButton(onClick = onOpenLog) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Bluetooth log") } },
    ) {
        val table = gatt
        when {
            table == null && loadError == null ->
                Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Reading GATT table…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            table == null ->
                StatusCard(
                    "READ FAILED",
                    "${loadError!!.trimEnd('.')}. Protocol lab needs the table before it can write anything.",
                    JoltColors.Danger,
                    Modifier.padding(16.dp),
                ) { StatusCardButton("Retry read", { attempt++ }) }
            else -> ProtocolLabForm(viewModel, table, scope)
        }
    }
}

@Composable
private fun ProtocolLabForm(
    viewModel: DeviceControlViewModel,
    gatt: List<GattCharacteristicDump>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val writable = gatt.filter { it.isWritable }.sortedWith(compareBy({ it.serviceUuid }, { it.uuid }))
    var selected by remember { mutableStateOf<GattCharacteristicDump?>(null) }
    var hex by remember { mutableStateOf("01 14") }
    var edited by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(RawWriteMode.WITH_RESPONSE) }
    var sending by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    val exchange = remember { mutableStateListOf<Pair<String, Color?>>() }

    fun label(characteristic: GattCharacteristicDump): String {
        val short = shortUuid(characteristic.uuid)
        val clash = writable.count { shortUuid(it.uuid) == short } > 1
        return if (clash) "${shortUuid(characteristic.serviceUuid)}/$short" else short
    }

    val bytes = parseHexBytes(hex)
    val target = selected
    val supportsMode = target?.properties?.contains(mode.requiredProperty) ?: false

    FormSection(
        header = "Write to characteristic",
        footer = "Raw writes bypass every cap and cooldown in the app. Keep the device off your wrist. Start with the characteristic you believe is Beep — an unknown one could be the zap output.",
    ) {
        if (writable.isEmpty()) {
            FormRow { Text("No writable characteristics discovered. Connect a device and reload Diagnostics.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@FormSection
        }
        Box {
            FormRow(Modifier.clickable { pickerOpen = true }) {
                Text("Characteristic", modifier = Modifier.weight(1f))
                Text(target?.let(::label) ?: "Choose…", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                writable.forEach { characteristic ->
                    DropdownMenuItem(
                        text = { Text(label(characteristic), fontFamily = FontFamily.Monospace) },
                        onClick = {
                            pickerOpen = false
                            selected = characteristic
                            if (!characteristic.properties.contains(mode.requiredProperty)) {
                                RawWriteMode.entries.firstOrNull { characteristic.properties.contains(it.requiredProperty) }?.let { mode = it }
                            }
                            if (!edited && !characteristic.value.isNullOrEmpty() && characteristic.value != "(empty)") hex = characteristic.value
                        },
                    )
                }
            }
        }
        FormDivider()
        FormRow {
            Text("Payload", modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = hex,
                onValueChange = {
                    hex = it
                    edited = true
                },
                singleLine = true,
                placeholder = { Text("01 14") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.weight(1.4f),
            )
        }
        val held = target?.value?.takeIf { it != "(empty)" }?.let(::parseHexBytes)
        val lengthDiffers = held != null && held.isNotEmpty() && bytes != null && held.size != bytes.size
        val validation =
            if (bytes == null && hex.isNotBlank()) {
                "Not valid hex — use pairs like 01 14."
            } else {
                buildString {
                    append("${bytes?.size ?: 0} byte(s)")
                    target?.let { append(" · ${it.properties.joinToString(", ")}") }
                    target?.value?.let { append(" · holds ${it.ifEmpty { "(empty)" }}") }
                    if (lengthDiffers) append(" — length differs")
                }
            }
        Text(
            validation,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = if ((bytes == null && hex.isNotBlank()) || lengthDiffers) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        FormDivider()
        RawWriteMode.entries.forEach { option ->
            val allowed = target?.properties?.contains(option.requiredProperty) ?: true
            FormRow(Modifier.clickable(enabled = allowed) { mode = option }) {
                RadioButton(selected = mode == option, onClick = { mode = option }, enabled = allowed)
                Text(option.displayName, color = if (allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FormButton(
            if (sending) "Sending…" else "Send payload",
            enabled = target != null && bytes != null && !sending && supportsMode,
        ) {
            val payload = bytes ?: return@FormButton
            val characteristic = target ?: return@FormButton
            sending = true
            exchange += "→ ${label(characteristic)} · ${payload.toHexString()}" to null
            scope.launch {
                val started = System.currentTimeMillis()
                try {
                    viewModel.writeRaw(payload, characteristic.uuid, characteristic.serviceUuid, mode)
                    exchange +=
                        if (mode == RawWriteMode.WITH_RESPONSE) {
                            "← ACK · ${System.currentTimeMillis() - started} ms" to JoltColors.Green
                        } else {
                            "← sent, no response expected" to null
                        }
                } catch (error: Exception) {
                    exchange += "← ${error.message}" to JoltColors.Danger
                }
                while (exchange.size > 20) exchange.removeAt(0)
                sending = false
            }
        }
    }
    FormSection(header = "Last exchange") {
        if (exchange.isEmpty()) {
            FormRow { Text("Nothing sent from here yet.", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            SelectionContainer {
                Column(Modifier.padding(16.dp)) {
                    exchange.forEach { (line, color) -> Text(line, fontFamily = FontFamily.Monospace, color = color ?: MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** The last 500 lines of this launch's Bluetooth activity. */
@Composable
fun BluetoothLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by BleLog.entries.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex) }
    FormScreen(
        title = "Bluetooth log",
        onBack = onBack,
        scrollable = false,
        actions = { IconButton(onClick = { shareText(context, BleLog.transcript()) }, enabled = entries.isNotEmpty()) { Icon(Icons.Filled.Share, contentDescription = "Share log") } },
    ) {
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Bluetooth activity yet", style = MaterialTheme.typography.titleMedium)
                Text("Connect a device or send a stimulus and it will show up here.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 12.dp)) {
                    items(entries) { entry ->
                        val color =
                            when {
                                entry.level == BleLog.Level.ERROR -> JoltColors.Warning
                                entry.message.startsWith("Connected to") || entry.message.startsWith("Write acknowledged") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(entry.timeText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" ${entry.message}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
                        }
                    }
                }
            }
        }
        FormSection(footer = "The log keeps the last 500 lines from this launch only.") {
            FormButton(if (copied) "Copied" else "Copy log", enabled = entries.isNotEmpty()) {
                copyToClipboard(context, "Bluetooth log", BleLog.transcript())
                copied = true
            }
            FormButton("Clear log", color = MaterialTheme.colorScheme.error, enabled = entries.isNotEmpty()) {
                BleLog.clear()
                copied = false
            }
        }
    }
}

/** What the device reported while Diagnostics was listening, newest first. */
@Composable
fun DeviceEventsScreen(
    isListening: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val entries by BleLog.entries.collectAsStateWithLifecycle()
    val events = entries.filter { it.message.startsWith(BleLog.EVENT_PREFIX) }.reversed()
    FormScreen(
        title = "Device events",
        onBack = onBack,
        actions = {
            IconButton(onClick = { shareText(context, events.joinToString("\n") { it.line }) }, enabled = events.isNotEmpty()) {
                Icon(Icons.Filled.Share, contentDescription = "Share captured events")
            }
        },
    ) {
        FormSection(
            footer =
                "While listening, make the device act on its own: press its button, trigger hand-detect, or let an alarm fire. " +
                    "Whatever it reports appears here, in the device's own encoding.",
        ) {
            if (events.isEmpty()) {
                FormRow { Text(if (isListening) "Nothing yet — press the button on your Pavlok." else "Not listening.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            events.forEach { event ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(event.message.removePrefix(BleLog.EVENT_PREFIX), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(event.timeText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** What every button does, read from the device and writable where the payload is known. */
@Composable
fun ButtonConfigScreen(
    viewModel: DeviceControlViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ButtonConfigReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        runCatching { report = viewModel.readButtonConfig() }.onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    FormScreen(
        title = "Button",
        onBack = onBack,
        actions = { IconButton(onClick = { scope.launch { load() } }, enabled = !loading) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
        bottomBar = { InlineBanner(error, BannerStyle.ERROR) { error = null } },
    ) {
        if (loading && report == null) FormSection { FormRow { Text("Reading configuration…") } }
        DeviceButtonSlot.configurable.forEach { slot ->
            val current = report?.actions?.get(slot) ?: ButtonAction.DEFAULT_ACTION
            FormSection(header = slot.displayName) {
                ButtonAction.entries.forEach { action ->
                    FormRow(
                        Modifier.clickable {
                            if (action == current) return@clickable
                            scope.launch {
                                runCatching { viewModel.setButtonConfig(ButtonConfig(slot, action)) }.onFailure { error = it.message }
                                load()
                            }
                        },
                    ) {
                        Text(action.displayName, modifier = Modifier.weight(1f))
                        if (action == current) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
                report?.records?.get(slot)?.let { LabeledValue("On device", it.hexString) }
            }
        }
        FormSection(
            header = "Raw report",
            footer =
                "Writable actions are the ones whose payload length is recovered — the device rejects a payload of the wrong length outright. " +
                    "\"Zap\", \"Beep\" and \"Vibrate\" are listed but not written, because a wrong guess at their intensity bytes fires a real stimulus on your wrist. See docs/RE-FINDINGS.md.",
        ) {
            val frames = report?.frames.orEmpty()
            if (frames.isNotEmpty()) {
                LabeledValue("Frames", frames.joinToString(" · ") { it.toHexString("") })
            } else if (!loading) {
                FormRow {
                    Text(
                        "The device sent nothing back. Either it is not connected or this firmware answers the config query differently.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
