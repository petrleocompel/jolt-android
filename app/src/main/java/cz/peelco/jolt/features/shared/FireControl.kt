package cz.peelco.jolt.features.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.peelco.jolt.domain.model.FiringInteractionMode
import cz.peelco.jolt.ui.theme.EyebrowStyle
import cz.peelco.jolt.ui.theme.ForcedDarkTheme
import cz.peelco.jolt.ui.theme.JoltColors
import cz.peelco.jolt.ui.theme.remoteNumeral
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a fire control's label should show. */
sealed interface FireState {
    data object Idle : FireState

    data class Holding(
        val progress: Float,
    ) : FireState
}

/** How long a hold takes, the iOS 0.85 s. */
const val HOLD_DURATION_MILLIS = 850L

/** Above this, a hold asks for a second hold on a confirm sheet. */
const val HIGH_INTENSITY_THRESHOLD = 60

/**
 * Turns a touch into a send according to the user's firing mode: a tap,
 * a press-and-hold, or a tap and a confirm dialog.
 *
 * With [enabled] false and [onUnavailable] set, any tap calls it instead, so a
 * control without a device can still say why nothing happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireControl(
    mode: FiringInteractionMode,
    confirmTitle: String,
    onFire: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    confirmMessage: String? = null,
    confirmActionTitle: String = "Send",
    onUnavailable: (() -> Unit)? = null,
    highIntensityPercent: Int? = null,
    label: @Composable (FireState) -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    var showHighIntensity by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    val needsSecondHold = highIntensityPercent != null && highIntensityPercent > HIGH_INTENSITY_THRESHOLD
    val semanticsModifier =
        Modifier.semantics {
            role = Role.Button
            onClick(confirmTitle) {
                when {
                    !enabled -> onUnavailable?.invoke()
                    mode == FiringInteractionMode.CONFIRM -> showConfirm = true
                    mode == FiringInteractionMode.HOLD && needsSecondHold -> showHighIntensity = true
                    else -> onFire()
                }
                true
            }
        }

    val gestureModifier =
        when {
            !enabled -> Modifier.clickable(enabled = onUnavailable != null) { onUnavailable?.invoke() }
            mode == FiringInteractionMode.TAP -> Modifier.clickable { onFire() }
            mode == FiringInteractionMode.CONFIRM -> Modifier.clickable { showConfirm = true }
            else ->
                Modifier.pointerInput(needsSecondHold) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (needsSecondHold) {
                            showHighIntensity = true
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        val job: Job =
                            scope.launch {
                                val start = System.currentTimeMillis()
                                while (isActive) {
                                    progress = ((System.currentTimeMillis() - start).toFloat() / HOLD_DURATION_MILLIS).coerceAtMost(1f)
                                    if (progress >= 1f) {
                                        onFire()
                                        break
                                    }
                                    delay(16)
                                }
                            }
                        waitForUpOrCancellation()
                        job.cancel()
                        progress = 0f
                    }
                }
        }

    Box(modifier = modifier.then(semanticsModifier).then(gestureModifier)) {
        label(if (progress > 0f) FireState.Holding(progress) else FireState.Idle)
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(confirmTitle) },
            text = confirmMessage?.let { { Text(it) } },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onFire()
                }) { Text(confirmActionTitle, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showHighIntensity) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showHighIntensity = false }, sheetState = sheetState, containerColor = Color.Black) {
            ForcedDarkTheme {
                HighIntensityConfirm(
                    title = confirmTitle,
                    message = confirmMessage.orEmpty(),
                    onFire = {
                        showHighIntensity = false
                        onFire()
                    },
                    onCancel = { showHighIntensity = false },
                )
            }
        }
    }
}

@Composable
private fun HighIntensityConfirm(
    title: String,
    message: String,
    onFire: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("HIGH INTENSITY", style = EyebrowStyle, color = JoltColors.Amber)
        Text(title, style = remoteNumeral(24.sp), color = Color.White)
        if (message.isNotEmpty()) Text(message, color = Color.White.copy(alpha = 0.56f), style = MaterialTheme.typography.bodyMedium)
        FireControl(mode = FiringInteractionMode.HOLD, confirmTitle = title, onFire = onFire) { state ->
            HoldFillBar(idleText = "Hold to fire", holdingText = "Keep holding…", tint = JoltColors.Green, ink = Color.Black, state = state)
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Text("Cancel", color = Color.White.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** The shared hold bar: a tinted pill that fills while held. */
@Composable
fun HoldFillBar(
    idleText: String,
    holdingText: String,
    tint: Color,
    ink: Color,
    state: FireState,
    modifier: Modifier = Modifier,
    successText: String? = null,
    isFlashing: Boolean = false,
) {
    val flash by animateFloatAsState(if (isFlashing) 0.35f else 0f, tween(150), label = "flash")
    val progress = (state as? FireState.Holding)?.progress ?: 0f
    Box(
        modifier = modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(17.dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress).background(Color.White.copy(alpha = 0.22f)))
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash)))
        Text(
            successText ?: if (state is FireState.Holding) holdingText else idleText,
            style = remoteNumeral(16.sp),
            color = ink,
        )
    }
}

/** The 64 dp ring a stimulus row fires through. */
@Composable
fun StimulusFireRing(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    state: FireState,
) {
    val holding = state is FireState.Holding
    val progress = (state as? FireState.Holding)?.progress ?: 0f
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(JoltColors.Green.copy(alpha = if (holding) 0.18f else 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(64.dp),
            color = JoltColors.Green,
            strokeWidth = 3.dp,
            trackColor = JoltColors.Green.copy(alpha = if (holding) 0.5f else 0.35f),
        )
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = JoltColors.Green, modifier = Modifier.size(22.dp))
    }
}
