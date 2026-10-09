package cz.peelco.jolt.features.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.ui.theme.EyebrowStyle
import cz.peelco.jolt.ui.theme.JoltColors
import java.time.Duration
import java.time.Instant

/** Icon per stimulus, matching the iOS SF Symbols in spirit. */
val StimulusKind.icon: ImageVector
    get() =
        when (this) {
            StimulusKind.ZAP -> Icons.Filled.Bolt
            StimulusKind.VIBE -> Icons.Filled.GraphicEq
            StimulusKind.BEEP -> Icons.AutoMirrored.Filled.VolumeUp
        }

/** Tint per stimulus: zap orange, vibe indigo, beep teal. */
val StimulusKind.tint: Color
    get() =
        when (this) {
            StimulusKind.ZAP -> JoltColors.ZapOrange
            StimulusKind.VIBE -> JoltColors.VibeIndigo
            StimulusKind.BEEP -> JoltColors.BeepTeal
        }

/** "just now", else "5 minutes ago" / "yesterday"; computed once per render. */
fun relativeTime(
    instant: Instant,
    now: Instant = Instant.now(),
): String {
    val seconds = Duration.between(instant, now).seconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> plural(seconds / 60, "minute") + " ago"
        seconds < 86_400 -> plural(seconds / 3_600, "hour") + " ago"
        seconds < 172_800 -> "yesterday"
        seconds < 2_592_000 -> plural(seconds / 86_400, "day") + " ago"
        else -> plural(seconds / 2_592_000, "month") + " ago"
    }
}

private fun plural(
    count: Long,
    unit: String,
) = if (count == 1L) "1 $unit" else "$count ${unit}s"

/** Initials from up to two words, or "?". */
fun initials(name: String): String =
    name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

enum class BannerStyle { ERROR, SUCCESS }

/**
 * The app's one transient-message surface, pinned to the bottom. Errors stay
 * until dismissed; successes clear themselves and have no close button.
 */
@Composable
fun InlineBanner(
    message: String?,
    style: BannerStyle,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val tint = if (style == BannerStyle.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Row(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .background(tint.copy(alpha = 0.14f))
                    .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag(if (style == BannerStyle.ERROR) "errorFeedback" else "successFeedback"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (style == BannerStyle.ERROR) Icons.Filled.Warning else Icons.Filled.CheckCircle, contentDescription = null, tint = tint)
            Spacer(Modifier.width(10.dp))
            Text(message.orEmpty(), color = tint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (onDismiss != null && style == BannerStyle.ERROR) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp).semantics { contentDescription = "Dismiss" }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** A tinted card that replaces a control while something needs attention. */
@Composable
fun StatusCard(
    eyebrow: String,
    message: String,
    tint: Color,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(tint.copy(alpha = 0.12f))
                .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(eyebrow, style = EyebrowStyle, color = tint)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
fun RowScope.StatusCardButton(
    title: String,
    onClick: () -> Unit,
    fill: Color? = null,
    ink: Color = Color.White,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = fill ?: MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(title, color = if (fill != null) ink else MaterialTheme.colorScheme.onSurface, fontWeight = if (fill != null) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

enum class RemoteCardStyle { DEVICE, STIMULUS, QUICK_POKE, NEUTRAL }

/** The Remote redesign's card: black base, a role gradient and a hairline border. */
@Composable
fun RemoteCard(
    style: RemoteCardStyle,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 22,
    content: @Composable ColumnScope.() -> Unit,
) {
    val (top, bottom, border) =
        when (style) {
            RemoteCardStyle.DEVICE -> Triple(JoltColors.Green.copy(alpha = 0.10f), JoltColors.Green.copy(alpha = 0.015f), JoltColors.Green.copy(alpha = 0.32f))
            RemoteCardStyle.STIMULUS, RemoteCardStyle.NEUTRAL -> Triple(Color.White.copy(alpha = 0.035f), Color.White.copy(alpha = 0.008f), Color.White.copy(alpha = 0.13f))
            RemoteCardStyle.QUICK_POKE -> Triple(JoltColors.Violet.copy(alpha = 0.12f), JoltColors.Violet.copy(alpha = 0.02f), JoltColors.Violet.copy(alpha = 0.34f))
        }
    val shape = RoundedCornerShape(cornerRadius.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.Black)
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .border(BorderStroke(1.dp, border), shape)
                .padding(18.dp),
        content = content,
    )
}

/** A small uppercase label. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text.uppercase(), style = EyebrowStyle, color = color, modifier = modifier)
}

/** A settings-style section with an optional header and footer. */
@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, bottom = 6.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp),
            )
        }
    }
}

/** A row inside a [FormSection]. */
@Composable
fun FormRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** "Label ........ value". */
@Composable
fun LabeledValue(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    FormRow {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A tappable row with a leading icon, title, trailing value and chevron. */
@Composable
fun NavigationRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) {
        FormRow {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (tint == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.primary else tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
            }
            Text(title, color = tint, modifier = Modifier.weight(1f))
            if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text("  ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FormDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

/** A full-width text button inside a form. */
@Composable
fun FormButton(
    title: String,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(title, color = if (enabled) color else color.copy(alpha = 0.4f), modifier = Modifier.weight(1f), fontSize = 16.sp)
    }
}

/** "Label: value" with − and + buttons, the iOS Stepper. */
@Composable
fun Stepper(
    label: String,
    valueText: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    FormRow {
        Text(label, modifier = Modifier.weight(1f))
        Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        androidx.compose.material3.OutlinedIconButton(
            onClick = { onChange((value - step).coerceIn(range)) },
            enabled = value > range.first,
            modifier = Modifier.size(36.dp).semantics { contentDescription = "Decrease $label" },
        ) { Text("−") }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.OutlinedIconButton(
            onClick = { onChange((value + step).coerceIn(range)) },
            enabled = value < range.last,
            modifier = Modifier.size(36.dp).semantics { contentDescription = "Increase $label" },
        ) { Text("+") }
    }
}
