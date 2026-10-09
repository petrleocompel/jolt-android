package cz.peelco.jolt.features.alarms.challenges

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.DismissChallenge
import cz.peelco.jolt.features.shared.QrScanner
import cz.peelco.jolt.ui.theme.JoltColors
import cz.peelco.jolt.ui.theme.remoteNumeral
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/** "Use a math puzzle instead" and friends. */
fun DismissChallenge.switchTitle(): String =
    when (this) {
        DismissChallenge.NONE -> "Dismiss without a challenge"
        DismissChallenge.MATH_PUZZLE -> "Use a math puzzle instead"
        DismissChallenge.JUMPING_JACKS -> "Use jumping jacks instead"
        DismissChallenge.QR_CODE_SCAN -> "Use a QR code instead"
    }

@Composable
fun ChallengeHeader(
    eyebrow: String,
    title: String,
) {
    Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White)
}

@Composable
private fun ColumnScope.SwitchLinks(
    alternatives: List<DismissChallenge>,
    onSwitch: (DismissChallenge) -> Unit,
) {
    alternatives.forEach { challenge ->
        TextButton(onClick = { onSwitch(challenge) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(challenge.switchTitle(), color = JoltColors.Green) }
    }
}

@Composable
fun MathPuzzleChallenge(
    alternatives: List<DismissChallenge>,
    onSwitch: (DismissChallenge) -> Unit,
    onSolved: () -> Unit,
) {
    var problem by remember { mutableStateOf(MathProblem.random()) }
    var wrong by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 24.dp)) {
        ChallengeHeader("Challenge 1 of 1", "Solve to dismiss")
        Box(
            Modifier
                .padding(top = 28.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(JoltColors.AlarmCard)
                .padding(vertical = 34.dp, horizontal = 16.dp)
                .semantics { contentDescription = "What is ${problem.spokenQuestion}?" },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { Text(problem.question, style = remoteNumeral(54.sp), color = Color.White, maxLines = 1) }
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            problem.choices.forEach { choice ->
                Surface(
                    onClick = {
                        if (choice == problem.answer) {
                            onSolved()
                        } else {
                            wrong = true
                            problem = MathProblem.random()
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = JoltColors.AlarmRaised,
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                ) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text("$choice", style = remoteNumeral(26.sp), color = Color.White) }
                }
            }
        }
        if (wrong) {
            Text(
                "Wrong answer. Try again.",
                color = JoltColors.Danger,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        SwitchLinks(alternatives, onSwitch)
        Spacer(Modifier.weight(1f))
        Text(
            "The alarm rang once and won't ring again on its own — this screen stays open until you solve it.",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Counts jumps with the phone's accelerometer. */
class JumpCounter(
    private val target: Int = TARGET,
    private val threshold: Float = THRESHOLD_G,
    private val minimumIntervalMillis: Long = MINIMUM_INTERVAL_MILLIS,
) {
    var count = 0
        private set
    private var lastPeakMillis = Long.MIN_VALUE / 2

    /** Feeds one sample in g; true when it counted a jump. */
    fun onSample(
        x: Float,
        y: Float,
        z: Float,
        timeMillis: Long,
    ): Boolean {
        if (count >= target) return false
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude <= threshold || timeMillis - lastPeakMillis <= minimumIntervalMillis) return false
        lastPeakMillis = timeMillis
        count = minOf(target, count + 1)
        return true
    }

    val isDone: Boolean get() = count >= target

    companion object {
        const val TARGET = 10
        const val THRESHOLD_G = 1.8f
        const val MINIMUM_INTERVAL_MILLIS = 600L
    }
}

@Composable
fun JumpingJacksChallenge(
    alternatives: List<DismissChallenge>,
    onSwitch: (DismissChallenge) -> Unit,
    onSolved: () -> Unit,
) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(SensorManager::class.java) }
    val accelerometer = remember { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(accelerometer) {
        val counter = JumpCounter()
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val g = SensorManager.GRAVITY_EARTH
                    if (counter.onSample(event.values[0] / g, event.values[1] / g, event.values[2] / g, event.timestamp / 1_000_000)) {
                        count = counter.count
                        if (counter.isDone) onSolved()
                    }
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }
        // 30 Hz, the rate the iOS counter samples at.
        accelerometer?.let { sensorManager?.registerListener(listener, it, 33_333) }
        onDispose { sensorManager?.unregisterListener(listener) }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ChallengeHeader("Movement challenge", "${JumpCounter.TARGET} jumping jacks")
        AnimatedContent(count, label = "count") { value ->
            Text(
                "$value / ${JumpCounter.TARGET}",
                style = remoteNumeral(78.sp),
                color = Color.White,
                modifier = Modifier.padding(top = 24.dp).semantics { contentDescription = "$value of ${JumpCounter.TARGET} jumping jacks" },
            )
        }
        Box(Modifier.padding(top = 22.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(JoltColors.AlarmRaised)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(count / JumpCounter.TARGET.toFloat()).background(JoltColors.Green))
        }
        Text(
            if (accelerometer != null) {
                "Counted with your phone's motion sensor — hold it in your hand or pocket while you jump."
            } else {
                "This phone has no motion sensor to count jumps. Switch to another challenge."
            },
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(Modifier.weight(1f))
        SwitchLinks(alternatives, onSwitch)
    }
}

@Composable
fun QrCodeChallenge(
    alarm: Alarm,
    alternatives: List<DismissChallenge>,
    onSwitch: (DismissChallenge) -> Unit,
    onSolved: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var wrong by remember { mutableStateOf(false) }
    LaunchedEffect(wrong) {
        if (wrong) {
            // The scanner stops after each read; rebuild it for another try.
            delay(1_500)
            wrong = false
            attempt++
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ChallengeHeader("QR challenge", "Scan your saved code")
        Box(
            Modifier
                .padding(top = 30.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(JoltColors.ScannerBackground)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp)),
        ) {
            androidx.compose.runtime.key(attempt) {
                QrScanner(
                    onScanned = { scanned ->
                        if (alarm.acceptsDismissCode(scanned)) {
                            onSolved()
                        } else {
                            wrong = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (wrong) {
            Text(
                "That's not the code saved for this alarm.",
                color = JoltColors.Danger,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Text(
            if (alarm.dismissQrCode == null) {
                "No code was saved for this alarm, so any QR code will dismiss it. Save one in the alarm's settings."
            } else {
                "Only the code you saved when you set the alarm will dismiss it."
            },
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        SwitchLinks(alternatives, onSwitch)
    }
}
