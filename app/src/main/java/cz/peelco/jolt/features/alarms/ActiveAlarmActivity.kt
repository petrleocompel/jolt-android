package cz.peelco.jolt.features.alarms

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.app.AppContainer
import cz.peelco.jolt.app.JoltApplication
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.DismissChallenge
import cz.peelco.jolt.features.alarms.challenges.JumpingJacksChallenge
import cz.peelco.jolt.features.alarms.challenges.MathPuzzleChallenge
import cz.peelco.jolt.features.alarms.challenges.QrCodeChallenge
import cz.peelco.jolt.ui.theme.ForcedDarkTheme
import cz.peelco.jolt.ui.theme.JoltColors
import cz.peelco.jolt.ui.theme.remoteNumeral
import java.util.UUID

/** The ringing screen, over the lock screen. It can't be backed out of. */
class ActiveAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val container = (application as JoltApplication).container
        id?.let { NotificationManagerCompat.from(this).cancel(AlarmReceiver.notificationId(it)) }
        setContent {
            ForcedDarkTheme {
                BackHandler { /* Dismissing takes the challenge or a snooze. */ }
                ActiveAlarmScreen(id, container, onDismiss = ::finish)
            }
        }
    }

    companion object {
        fun intent(
            context: Context,
            id: UUID,
        ): Intent =
            Intent(context, ActiveAlarmActivity::class.java)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
fun ActiveAlarmScreen(
    id: UUID?,
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val alarms by container.alarms.store.alarms.collectAsStateWithLifecycle()
    val alarm = id?.let { alarmId -> alarms.firstOrNull { it.id == alarmId } }
    var challenge by remember { mutableStateOf<DismissChallenge?>(null) }
    var snoozeError by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(JoltColors.RemoteBackground).safeDrawingPadding()) {
        if (alarm == null) {
            if (id != null && alarms.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Alarm", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    AlarmButton("Dismiss", primary = true, onClick = onDismiss)
                }
            }
            return@Box
        }
        val alternatives = switchableChallenges(alarm, challenge)
        Crossfade(challenge, label = "challenge") { current ->
            when (current) {
                null ->
                    Ringing(
                        alarm = alarm,
                        snoozeError = snoozeError,
                        onDismiss = {
                            if (alarm.dismissChallenge == DismissChallenge.NONE) onDismiss() else challenge = alarm.dismissChallenge
                        },
                        onSnooze = {
                            runCatching { container.alarms.snooze(alarm) }
                                .onSuccess { onDismiss() }
                                .onFailure { snoozeError = it.message }
                        },
                    )
                DismissChallenge.MATH_PUZZLE -> MathPuzzleChallenge(alternatives, { challenge = it }, onDismiss)
                DismissChallenge.JUMPING_JACKS -> JumpingJacksChallenge(alternatives, { challenge = it }, onDismiss)
                DismissChallenge.QR_CODE_SCAN -> QrCodeChallenge(alarm, alternatives, { challenge = it }, onDismiss)
                DismissChallenge.NONE -> LaunchedEffect(Unit) { onDismiss() }
            }
        }
    }
}

/** QR is offered only when there is a saved code or QR is the alarm's own challenge. */
fun switchableChallenges(
    alarm: Alarm,
    current: DismissChallenge?,
): List<DismissChallenge> =
    listOf(DismissChallenge.MATH_PUZZLE, DismissChallenge.JUMPING_JACKS, DismissChallenge.QR_CODE_SCAN)
        .filter { it != current }
        .filter { it != DismissChallenge.QR_CODE_SCAN || alarm.dismissQrCode != null || alarm.dismissChallenge == DismissChallenge.QR_CODE_SCAN }

@Composable
private fun Ringing(
    alarm: Alarm,
    snoozeError: String?,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text("ALARM · ${alarm.stimulus.kind.displayName.uppercase()} ${alarm.stimulus.intensity}%", style = MaterialTheme.typography.labelMedium, color = JoltColors.Green)
        Text(alarm.timeText, style = remoteNumeral(80.sp), color = Color.White, maxLines = 1)
        Text(alarm.label.ifEmpty { "Alarm" }, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f))
        Box(
            Modifier.padding(top = 36.dp).size(120.dp).background(JoltColors.Green.copy(alpha = 0.10f), CircleShape).border(1.5.dp, JoltColors.Green.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Bolt, contentDescription = null, tint = JoltColors.Green, modifier = Modifier.size(46.dp)) }
        Text(
            if (alarm.dismissChallenge == DismissChallenge.NONE) {
                "This alarm rang once. Snooze to ring again in 9 minutes."
            } else {
                "This alarm rang once. Finish the challenge to dismiss it, or snooze to ring again in 9 minutes."
            },
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 24.dp),
        )
        snoozeError?.let { Text("Couldn't snooze: $it", color = JoltColors.Danger, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AlarmButton(
                when (alarm.dismissChallenge) {
                    DismissChallenge.NONE -> "Dismiss"
                    DismissChallenge.MATH_PUZZLE -> "Dismiss — solve a puzzle"
                    DismissChallenge.JUMPING_JACKS -> "Dismiss — do 10 jumping jacks"
                    DismissChallenge.QR_CODE_SCAN -> "Dismiss — scan your code"
                },
                primary = true,
                onClick = onDismiss,
            )
            AlarmButton("Snooze 9 min", primary = false, onClick = onSnooze)
        }
    }
}

@Composable
private fun AlarmButton(
    title: String,
    primary: Boolean,
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal) }
}
