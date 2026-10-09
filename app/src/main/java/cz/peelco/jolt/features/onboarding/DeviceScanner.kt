package cz.peelco.jolt.features.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.domain.model.DeviceConnectionState
import cz.peelco.jolt.domain.model.DeviceFamily
import cz.peelco.jolt.features.device.DeviceControlViewModel
import cz.peelco.jolt.features.shared.JoltPermissions
import cz.peelco.jolt.features.shared.rememberPermissionsRequest

/** Pick a family, scan, tap a result to connect. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceScanner(
    viewModel: DeviceControlViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var family by remember { mutableStateOf(DeviceFamily.PAVLOK3) }
    var isScanning by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()

    val requestPermissions =
        rememberPermissionsRequest { granted ->
            permissionDenied = !granted
            if (granted) {
                viewModel.reconnect()
                isScanning = true
                viewModel.startScan(setOf(family))
            }
        }

    fun toggleScan() {
        if (isScanning) {
            isScanning = false
            viewModel.stopScan()
        } else if (!JoltPermissions.hasBluetooth(context)) {
            requestPermissions(JoltPermissions.bluetooth)
        } else {
            isScanning = true
            viewModel.startScan(setOf(family))
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 12.dp).size(88.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(40.dp))
        }
        Text("Find your Pavlok", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(
            "Make sure your device is charged and nearby, then start scanning.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 22.dp).fillMaxWidth(),
        ) {
            DeviceFamily.entries.forEach { option ->
                val selected = option == family
                Surface(
                    onClick = {
                        family = option
                        if (isScanning) viewModel.startScan(setOf(option))
                    },
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                ) {
                    Text(
                        option.displayName,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
            }
        }

        if (permissionDenied) {
            Text(
                "Jolt needs Bluetooth permission to find your Pavlok. You can grant it in Android Settings → Apps → Jolt.",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
        }

        if (isScanning && devices.isEmpty()) {
            CircularProgressIndicator(Modifier.padding(top = 34.dp))
            Text("Scanning…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (isScanning && devices.isNotEmpty()) {
            Column(Modifier.padding(top = 24.dp).fillMaxWidth()) {
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.connect(device) }.padding(vertical = 14.dp).testTag("discoveredDevice"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            val rssi = device.rssi?.let { " · ${it.toString().replace('-', '−')} dBm" }.orEmpty()
                            Text(device.family.displayName + rssi, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state == DeviceConnectionState.Connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        Spacer(Modifier.height(36.dp))
        Button(
            onClick = ::toggleScan,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("scanButton"),
        ) { Text(if (isScanning) "Stop scanning" else "Start scanning", style = MaterialTheme.typography.titleMedium) }
    }
}
