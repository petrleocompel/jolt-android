package cz.peelco.jolt.ble

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cz.peelco.jolt.R
import cz.peelco.jolt.app.JoltApplication
import cz.peelco.jolt.app.MainActivity
import cz.peelco.jolt.app.Notifications
import cz.peelco.jolt.domain.model.DeviceConnectionState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process, and with it the GATT link, alive while Jolt is in the
 * background, so a poke can fire without the phone being touched. The iOS
 * app gets this from the `bluetooth-central` background mode; Android needs a
 * visible foreground service of type `connectedDevice`.
 *
 * It holds nothing itself: the connection lives in the device repository.
 * It only shows the ongoing notification and stops when there is nothing
 * left to keep connected.
 */
class WearableConnectionService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        val container = (application as JoltApplication).container
        startInForeground(notification(container.deviceRepository.connectionState.value))
        lifecycleScope.launch {
            combine(
                container.deviceRepository.connectionState,
                container.deviceRepository.pairedDevice,
                container.settings.stayConnectedInBackground.flow,
            ) { state, paired, stay -> Triple(state, paired, stay) }
                .collect { (state, paired, stay) ->
                    if (paired == null || !stay) {
                        stopSelf()
                    } else {
                        Notifications.post(this@WearableConnectionService, Notifications.ID_CONNECTION, notification(state))
                    }
                }
        }
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, Notifications.ID_CONNECTION, notification, type)
    }

    private fun notification(state: DeviceConnectionState): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val connected = state == DeviceConnectionState.Connected
        return NotificationCompat
            .Builder(this, Notifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_jolt)
            .setContentTitle(getString(R.string.connection_notification_title))
            .setContentText(getString(if (connected) R.string.connection_notification_text else R.string.connection_notification_text_waiting))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        /**
         * Starts the service if it may run. Android forbids starting a
         * foreground service from the background except in exempt moments
         * (a visible activity, boot, a high-priority push), so a refusal is
         * logged rather than treated as an error.
         */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WearableConnectionService::class.java)) }
                .onFailure { BleLog.error("Couldn't start the connection service: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WearableConnectionService::class.java))
        }
    }
}
