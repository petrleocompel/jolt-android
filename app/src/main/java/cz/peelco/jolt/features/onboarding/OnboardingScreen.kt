package cz.peelco.jolt.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.features.device.DeviceControlViewModel
import cz.peelco.jolt.features.shared.BannerStyle
import cz.peelco.jolt.features.shared.InlineBanner

/**
 * The first-run pairing offer. An offer, not a gate: alarms, friends and
 * pokes all work without a wearable.
 */
@Composable
fun OnboardingScreen(
    viewModel: DeviceControlViewModel,
    onContinueWithoutDevice: () -> Unit,
) {
    var showPairing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(44.dp))
        }
        Text("Find your Pavlok", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp), textAlign = TextAlign.Center)
        Text(
            "Pair now to fire from your phone, or carry on without one — alarms, friends and pokes work either way.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { showPairing = true },
                colors = ButtonDefaults.buttonColors(contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("pairDeviceButton"),
            ) { Text("Pair a device", style = MaterialTheme.typography.titleMedium) }
            FilledTonalButton(
                onClick = onContinueWithoutDevice,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("continueWithoutDeviceButton"),
            ) { Text("Continue without a device", style = MaterialTheme.typography.titleMedium) }
        }
    }
    if (showPairing) PairDeviceSheet(viewModel) { showPairing = false }
}

/** Scanning and pairing, as a sheet; closes itself once a device connects. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDeviceSheet(
    viewModel: DeviceControlViewModel,
    onDismiss: () -> Unit,
) {
    val paired by viewModel.pairedDevice.collectAsStateWithLifecycle()
    val connected by viewModel.connectedDevice.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val hadPaired = remember { paired }
    val initialAddress = remember { connected?.address }
    LaunchedEffect(connected?.address) {
        if (connected != null && connected?.address != initialAddress) onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = { Text(if (hadPaired != null) "Pair a different device" else "Pair device") },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("Not now") } },
                )
            },
            bottomBar = { InlineBanner(error, BannerStyle.ERROR, onDismiss = viewModel::dismissError) },
        ) { padding ->
            DeviceScanner(viewModel, Modifier.padding(padding))
        }
    }
}
