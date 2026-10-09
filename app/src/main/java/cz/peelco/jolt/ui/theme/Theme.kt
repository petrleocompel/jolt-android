package cz.peelco.jolt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val DarkScheme =
    darkColorScheme(
        primary = JoltColors.Green,
        onPrimary = Color.Black,
        secondary = JoltColors.Violet,
        onSecondary = Color.White,
        tertiary = JoltColors.Amber,
        error = JoltColors.Danger,
        background = Color.Black,
        surface = Color(0xFF111111),
        surfaceContainer = Color(0xFF1C1C1E),
        surfaceContainerHigh = Color(0xFF2C2C2E),
    )

private val LightScheme =
    lightColorScheme(
        primary = Color(0xFF00A152),
        onPrimary = Color.White,
        secondary = JoltColors.Violet,
        onSecondary = Color.White,
        tertiary = JoltColors.Amber,
        error = Color(0xFFD70015),
    )

/**
 * Stand-in for the design's numerals, like iOS's rounded system design: no
 * custom font is bundled.
 */
fun remoteNumeral(
    size: TextUnit,
    weight: FontWeight = FontWeight.SemiBold,
): TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size)

@Composable
fun JoltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}

/** For the screens the design keeps black whatever the system says. */
@Composable
fun ForcedDarkTheme(content: @Composable () -> Unit) {
    JoltTheme(darkTheme = true, content = content)
}

/** Small uppercase labels ("QUICK POKE", "NEXT ALARM"). */
val EyebrowStyle =
    TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
