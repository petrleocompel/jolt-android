package cz.peelco.jolt.features.alarms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.peelco.jolt.R
import cz.peelco.jolt.app.JoltApplication
import cz.peelco.jolt.app.Notifications
import cz.peelco.jolt.domain.model.DismissChallenge
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A phone alarm is due: show the full-screen ringing screen through a
 * high-priority notification, fire the alarm's stimulus if the wearable is
 * connected, and schedule the next occurrence.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_RING) return
        val id = intent.getStringExtra(EXTRA_ALARM_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val container = (context.applicationContext as JoltApplication).container
        val alarm = container.alarms.store.alarm(id) ?: return
        showRinging(context, id, alarm.label, alarm.dismissChallenge)

        val pending = goAsync()
        container.scope.launch {
            try {
                // Best effort: the phone rings either way.
                runCatching { container.deviceRepository.fire(alarm.stimulus) }
                if (alarm.repeatDays.isEmpty()) {
                    // A one-off alarm has rung; switch it off, as a clock app does.
                    container.alarms.store.save(alarm.copy(isEnabled = false))
                } else {
                    container.alarms.rescheduleAll()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RING = "cz.peelco.jolt.alarm.RING"
        const val EXTRA_ALARM_ID = "alarmId"

        fun notificationId(id: UUID) = id.hashCode()

        fun showRinging(
            context: Context,
            id: UUID,
            label: String,
            challenge: DismissChallenge,
        ) {
            val open =
                PendingIntent.getActivity(
                    context,
                    notificationId(id),
                    ActiveAlarmActivity.intent(context, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val body =
                if (challenge == DismissChallenge.NONE) {
                    context.getString(R.string.alarm_tap_to_dismiss)
                } else {
                    context.getString(R.string.alarm_complete_challenge, challenge.displayName)
                }
            val notification =
                NotificationCompat
                    .Builder(context, Notifications.CHANNEL_ALARMS)
                    .setSmallIcon(R.drawable.ic_stat_jolt)
                    .setContentTitle(label.ifEmpty { context.getString(R.string.alarm_default_title) })
                    .setContentText(body)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .setAutoCancel(true)
                    .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                // Without notifications the full-screen intent can't be shown;
                // open the ringing screen directly as the last resort.
                runCatching { context.startActivity(ActiveAlarmActivity.intent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return
            }
            NotificationManagerCompat.from(context).notify(notificationId(id), notification)
        }
    }
}

/**
 * Re-arms phone alarms after a reboot, an app update or a clock change (all
 * of which drop `AlarmManager` triggers or move them), and brings the
 * wearable link back after a reboot.
 */
class AlarmRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val container = (context.applicationContext as JoltApplication).container
        container.alarms.rescheduleAll()
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) container.keepWearableConnected()
    }
}
