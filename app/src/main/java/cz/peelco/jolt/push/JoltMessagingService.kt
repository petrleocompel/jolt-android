package cz.peelco.jolt.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import cz.peelco.jolt.app.JoltApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * FCM entry point. Each relay push is one high-priority data message, which
 * wakes the app from Doze and allows starting the wearable's foreground
 * service; the handler has to finish inside the short window FCM grants.
 */
class JoltMessagingService : FirebaseMessagingService() {
    private val container get() = (application as JoltApplication).container

    /** The current FCM API reports a token here once `register()` has run. */
    override fun onRegistered(token: String) {
        container.pushRegistrar.onNewToken(token)
    }

    /** Older Play Services still report through this one. */
    @Deprecated("Superseded by onRegistered in firebase-messaging 25")
    override fun onNewToken(token: String) {
        container.pushRegistrar.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        container.keepWearableConnected()
        // FCM calls this on its own worker thread and expects it to finish
        // within about ten seconds.
        runBlocking { withTimeoutOrNull(HANDLER_BUDGET) { container.incomingPushHandler.handle(message.data) } }
    }

    private companion object {
        val HANDLER_BUDGET = 9.seconds
    }
}
