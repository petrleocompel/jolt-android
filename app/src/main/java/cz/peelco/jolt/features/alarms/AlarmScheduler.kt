package cz.peelco.jolt.features.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.AlarmLocation
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Rings phone alarms through `AlarmManager.setAlarmClock`, which Android
 * treats as a user-visible alarm: exact, allowed in Doze, shown in the status
 * bar. Unlike iOS local notifications it rings on the alarm stream and opens
 * over the lock screen.
 *
 * Each alarm holds one pending trigger at a time, its next occurrence; the
 * receiver schedules the one after when it rings.
 */
class AlarmScheduler(
    private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** False on Android 12 when the user revoked exact alarms; triggers are then inexact. */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    fun schedule(
        alarm: Alarm,
        now: ZonedDateTime = ZonedDateTime.now(),
    ) {
        cancel(alarm.id)
        if (alarm.location != AlarmLocation.PHONE) return
        val next = alarm.nextOccurrence(now) ?: return
        setAt(next.toInstant().toEpochMilli(), triggerIntent(alarm.id, SLOT_SCHEDULED))
    }

    /** "Snooze 9 min": one more ring of the same alarm. */
    fun snooze(
        alarm: Alarm,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        setAt(snoozeTime(nowMillis), triggerIntent(alarm.id, SLOT_SNOOZE))
    }

    /** Drops the scheduled ring and any pending snooze. */
    fun cancel(id: UUID) {
        alarmManager?.cancel(triggerIntent(id, SLOT_SCHEDULED))
        alarmManager?.cancel(triggerIntent(id, SLOT_SNOOZE))
    }

    fun rescheduleAll(alarms: List<Alarm>) = alarms.forEach(::schedule)

    private fun setAt(
        triggerAtMillis: Long,
        operation: PendingIntent,
    ) {
        val manager = alarmManager ?: return
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(context, 0, Intent(context, cz.peelco.jolt.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), operation)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    private fun triggerIntent(
        id: UUID,
        slot: Int,
    ): PendingIntent {
        val intent =
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_RING)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id.toString())
        return PendingIntent.getBroadcast(context, requestCode(id, slot), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val SNOOZE_MINUTES = 9L
        private const val SLOT_SCHEDULED = 0
        private const val SLOT_SNOOZE = 1

        fun snoozeTime(nowMillis: Long): Long = nowMillis + SNOOZE_MINUTES * 60_000

        /** Distinct per alarm and slot, so cancelling one never touches another. */
        fun requestCode(
            id: UUID,
            slot: Int,
        ): Int = (id.hashCode() and 0x7FFFFFFE) or slot
    }
}
