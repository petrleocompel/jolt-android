package cz.peelco.jolt.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import cz.peelco.jolt.features.shared.JoltPermissions
import cz.peelco.jolt.push.PushTapExtras
import cz.peelco.jolt.ui.theme.JoltTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as JoltApplication).container

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { container.pushRegistrar.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                JoltTheme { JoltRoot() }
            }
        }
        handleTap(intent)
        askForNotificationsOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTap(intent)
    }

    /** A tapped poke or test notification: the "alert" path. */
    private fun handleTap(intent: Intent?) {
        val push = intent?.let(PushTapExtras::read) ?: return
        PushTapExtras.clear(intent)
        container.scope.launch { container.incomingPushHandler.handleTap(push) }
    }

    /**
     * Pokes, tests and alarms all need notifications, so Android 13+ is asked
     * once at first launch, as iOS asks at launch. Settings → Notifications
     * offers it again.
     */
    private fun askForNotificationsOnce() {
        if (!JoltPermissions.needsNotificationPermission() || JoltPermissions.hasNotifications(this)) return
        val asked = container.settings.notificationPermissionAsked
        if (asked.value) return
        asked.set(true)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
