package cz.peelco.jolt.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.peelco.jolt.R
import cz.peelco.jolt.domain.model.PokePushPayload
import cz.peelco.jolt.domain.model.TestPushPayload
import cz.peelco.jolt.push.PushTapExtras
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Notification channels and the notifications the app draws itself. */
object Notifications {
    const val CHANNEL_POKES = "pokes"
    const val CHANNEL_ALARMS = "alarms"
    const val CHANNEL_CONNECTION = "connection"

    const val ID_CONNECTION = 1
    private const val ID_FALLBACK_POKE = 2
    private const val ID_FALLBACK_TEST = 3

    private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_POKES, context.getString(R.string.channel_pokes), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_pokes_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alarms_description)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_connection_description)
                setShowBadge(false)
            },
        )
    }

    fun canPost(context: Context): Boolean =
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun post(
        context: Context,
        id: Int,
        builder: NotificationCompat.Builder,
    ) = post(context, id, builder.build())

    /** Posts [notification] if the user allows notifications; silently skips otherwise. */
    fun post(
        context: Context,
        id: Int,
        notification: android.app.Notification,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun tapIntent(
        context: Context,
        requestCode: Int,
        configure: Intent.() -> Unit,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                configure()
            }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun pushBuilder(context: Context) =
        NotificationCompat
            .Builder(context, CHANNEL_POKES)
            .setSmallIcon(R.drawable.ic_stat_jolt)
            .setColor(ContextCompat.getColor(context, R.color.jolt_green))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)

    /** "Alice — zapped you — 30% at 14:32", in this phone's own time zone. */
    fun showPoke(
        context: Context,
        payload: PokePushPayload,
    ) {
        val time = TIME.format(payload.sentAt ?: Instant.now())
        val body =
            context.getString(
                if (payload.viaApiToken == true) R.string.push_poke_body_automated else R.string.push_poke_body,
                payload.stimulus.kind.pastTenseVerb,
                payload.stimulus.intensity,
                time,
            )
        val id = payload.pokeId.hashCode()
        post(
            context,
            id,
            pushBuilder(context)
                .setContentTitle(payload.senderDisplayName)
                .setContentText(body)
                .setContentIntent(tapIntent(context, id) { PushTapExtras.putPoke(this, payload) }),
        )
    }

    fun showTest(
        context: Context,
        payload: TestPushPayload,
    ) {
        val id = payload.testId.hashCode()
        post(
            context,
            id,
            pushBuilder(context)
                .setContentTitle(context.getString(R.string.push_test_title))
                .setContentText(context.getString(R.string.push_test_body, TIME.format(payload.sentAt ?: Instant.now())))
                .setContentIntent(tapIntent(context, id) { PushTapExtras.putTest(this, payload) }),
        )
    }

    /** What an unreadable push shows, the Android side of the APNs `PUSH_FALLBACK_*` loc-keys. */
    fun showFallback(
        context: Context,
        kind: String,
    ) {
        val isTest = kind == "test"
        post(
            context,
            if (isTest) ID_FALLBACK_TEST else ID_FALLBACK_POKE,
            pushBuilder(context)
                .setContentTitle(context.getString(R.string.push_fallback_title))
                .setContentText(context.getString(if (isTest) R.string.push_fallback_test_body else R.string.push_fallback_poke_body))
                .setContentIntent(tapIntent(context, 0) {}),
        )
    }
}
