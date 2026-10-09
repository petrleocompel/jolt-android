package cz.peelco.jolt.features.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

/** What Android needs before Jolt may scan for and connect to a wearable. */
object JoltPermissions {
    val bluetooth: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 8–11 can only scan with location access.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasBluetooth(context: Context): Boolean = bluetooth.all { granted(context, it) }

    fun needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasNotifications(context: Context): Boolean = !needsNotificationPermission() || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(
        context: Context,
        permission: String,
    ) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** A launcher for several permissions at once; the callback gets whether all were granted. */
@Composable
fun rememberPermissionsRequest(onResult: (Boolean) -> Unit): (Array<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results -> onResult(results.values.all { it }) }
    return { permissions -> launcher.launch(permissions) }
}
