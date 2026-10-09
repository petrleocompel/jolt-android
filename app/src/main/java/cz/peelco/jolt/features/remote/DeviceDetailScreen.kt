package cz.peelco.jolt.features.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.features.device.DeviceControlViewModel
import cz.peelco.jolt.features.onboarding.PairDeviceSheet
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.LabeledValue
import cz.peelco.jolt.features.shared.NavigationRow
import cz.peelco.jolt.features.shared.RemoteCard
import cz.peelco.jolt.features.shared.RemoteCardStyle
import cz.peelco.jolt.ui.theme.EyebrowStyle
import cz.peelco.jolt.ui.theme.ForcedDarkTheme
import cz.peelco.jolt.ui.theme.JoltColors
import cz.peelco.jolt.ui.theme.remoteNumeral

/** Destinations reachable from the device detail screen. */
enum class DeviceTool { DIAGNOSTICS, PROTOCOL_LAB, BLUETOOTH_LOG, BUTTON_CONFIG }

@Composable
fun DeviceDetailScreen(
    viewModel: DeviceControlViewModel,
    onBack: () -> Unit,
    onOpen: (DeviceTool) -> Unit,
) {
    val device by viewModel.connectedDevice.collectAsStateWithLifecycle()
    val paired by viewModel.pairedDevice.collectAsStateWithLifecycle()
    var loadError by remember { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    LaunchedEffect(device?.address) {
        if (device != null) loadError = runCatching { viewModel.readDeviceInfo() }.exceptionOrNull()?.message
    }
    val info = device?.info
    val tint =
        when {
            device != null -> JoltColors.Green
            paired != null -> JoltColors.Warning
            else -> Color.White.copy(alpha = 0.5f)
        }
    ForcedDarkTheme {
        FormScreen(title = device?.name ?: paired?.name ?: "Device", onBack = onBack, containerColor = JoltColors.RemoteBackground) {
            RemoteCard(if (device != null) RemoteCardStyle.DEVICE else RemoteCardStyle.NEUTRAL, Modifier.padding(16.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
                    }
                    Text(device?.name ?: "No device", style = remoteNumeral(26.sp, FontWeight.Bold), color = Color.White, modifier = Modifier.padding(top = 10.dp))
                    val status =
                        when {
                            device != null -> info?.batteryLevelPercent?.let { "CONNECTED · $it%" } ?: "CONNECTED"
                            paired != null -> "NOT CONNECTED · Out of range or switched off"
                            else -> "NO DEVICE"
                        }
                    Text(status, style = EyebrowStyle.copy(fontSize = 13.sp), color = tint, modifier = Modifier.padding(top = 4.dp))
                }
            }
            FormSection(header = "Device") {
                LabeledValue("Model", info?.modelNumber ?: device?.family?.displayName ?: "—")
                LabeledValue("Serial", info?.serialNumber ?: "—")
                LabeledValue("Firmware", info?.firmwareRevision ?: "—")
                LabeledValue("Hardware", info?.hardwareRevision ?: "—")
                LabeledValue("Manufacturer", info?.manufacturer ?: "—")
                LabeledValue("Battery", info?.batteryLevelPercent?.let { "$it%" } ?: "—")
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
            }
            FormSection {
                NavigationRow("Pair a different device", Icons.Filled.Add) { showPairing = true }
                FormDivider()
                NavigationRow("GATT inspector & diagnostics", Icons.Filled.MedicalServices) { onOpen(DeviceTool.DIAGNOSTICS) }
                FormDivider()
                NavigationRow("Protocol lab", Icons.Filled.Terminal) { onOpen(DeviceTool.PROTOCOL_LAB) }
                FormDivider()
                NavigationRow("Bluetooth log", Icons.AutoMirrored.Filled.List) { onOpen(DeviceTool.BLUETOOTH_LOG) }
                FormDivider()
                NavigationRow("Button configuration", Icons.Filled.SmartButton) { onOpen(DeviceTool.BUTTON_CONFIG) }
            }
            if (device != null) {
                Button(
                    onClick = viewModel::disconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = JoltColors.Danger.copy(alpha = 0.12f), contentColor = JoltColors.Danger),
                    shape = RoundedCornerShape(18.dp),
                    modifier =
                        Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .border(1.dp, JoltColors.Danger.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
                ) {
                    Icon(Icons.Filled.Cancel, contentDescription = null)
                    Text("  Disconnect", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (showPairing) PairDeviceSheet(viewModel) { showPairing = false }
    }
}
