package cz.peelco.jolt.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand colours, the same values as the iOS asset catalog and `RemoteTheme`.
 *
 * Green is the accent everywhere. Violet stands for the social/poke layer in
 * both light and dark mode, the way the design doc uses it.
 */
object JoltColors {
    val Green = Color(0xFF00E676)
    val Violet = Color(0xFF8F7BFF)
    val VioletInk = Color(0xFFC9BEFF)
    val Amber = Color(0xFFFFB020)

    /** The Remote screens stay branded black in both appearances. */
    val RemoteBackground = Color(0xFF0A0A0A)

    /** Ringing-alarm surfaces. */
    val AlarmCard = Color(0xFF1C1C1E)
    val AlarmRaised = Color(0xFF2C2C2E)
    val ScannerBackground = Color(0xFF141416)

    /** iOS system colours the stimulus kinds are tinted with. */
    val ZapOrange = Color(0xFFFF9500)
    val VibeIndigo = Color(0xFF5E5CE6)
    val BeepTeal = Color(0xFF30B0C7)

    val Danger = Color(0xFFFF453A)
    val Warning = Color(0xFFFF9F0A)
    val Info = Color(0xFF64D2FF)
    val Success = Color(0xFF30D158)
}
