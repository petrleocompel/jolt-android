package cz.peelco.jolt.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Where an alarm fires. Device alarms live on the wearable's own clock and
 * survive the phone being off; phone alarms ring through `AlarmManager`.
 */
@Serializable
enum class AlarmLocation {
    @SerialName("device")
    DEVICE,

    @SerialName("phone")
    PHONE,
}

/** 1 = Monday ... 7 = Sunday, the same numbering iOS persists. */
@Serializable
enum class Weekday(
    val shortLabel: String,
    val dayOfWeek: DayOfWeek,
) {
    @SerialName("1")
    MONDAY("Mon", DayOfWeek.MONDAY),

    @SerialName("2")
    TUESDAY("Tue", DayOfWeek.TUESDAY),

    @SerialName("3")
    WEDNESDAY("Wed", DayOfWeek.WEDNESDAY),

    @SerialName("4")
    THURSDAY("Thu", DayOfWeek.THURSDAY),

    @SerialName("5")
    FRIDAY("Fri", DayOfWeek.FRIDAY),

    @SerialName("6")
    SATURDAY("Sat", DayOfWeek.SATURDAY),

    @SerialName("7")
    SUNDAY("Sun", DayOfWeek.SUNDAY),
    ;

    /** 1 = Monday ... 7 = Sunday. */
    val number: Int get() = ordinal + 1
}

/** Extra effort required to silence an alarm, the official app's "wake-up guarantees". */
@Serializable
enum class DismissChallenge(
    val displayName: String,
) {
    @SerialName("none")
    NONE("None"),

    @SerialName("mathPuzzle")
    MATH_PUZZLE("Math puzzle"),

    @SerialName("jumpingJacks")
    JUMPING_JACKS("Jumping jacks"),

    @SerialName("qrCodeScan")
    QR_CODE_SCAN("Scan a QR code"),
}

@Serializable
data class Alarm(
    val id: SerialUuid,
    val location: AlarmLocation,
    val hour: Int,
    val minute: Int,
    val repeatDays: Set<Weekday> = emptySet(),
    val isEnabled: Boolean = true,
    val label: String = "",
    val stimulus: StimulusConfig = StimulusConfig(StimulusKind.BEEP),
    val dismissChallenge: DismissChallenge = DismissChallenge.NONE,
    /** The QR code scanned when the alarm was set; null accepts any code. */
    val dismissQrCode: String? = null,
) {
    /**
     * Whether a scanned code may dismiss this alarm. With a saved code only
     * that exact one counts; without one, any code does rather than none.
     */
    fun acceptsDismissCode(scanned: String): Boolean = dismissQrCode == null || scanned == dismissQrCode

    /**
     * The next moment this alarm rings after [now], or null while disabled.
     * Picks the soonest weekday for a repeating alarm.
     */
    fun nextOccurrence(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!isEnabled) return null
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (repeatDays.isEmpty()) {
            return if (today.isAfter(now)) today else today.plusDays(1)
        }
        return repeatDays.minOf { day ->
            val candidate = today.with(TemporalAdjusters.nextOrSame(day.dayOfWeek))
            if (candidate.isAfter(now)) candidate else candidate.with(TemporalAdjusters.next(day.dayOfWeek))
        }
    }

    val timeText: String get() = "%02d:%02d".format(hour, minute)

    companion object {
        /** The new-alarm defaults: phone, 07:00, beep 30%. */
        fun newAlarm(): Alarm =
            Alarm(
                id = java.util.UUID.randomUUID(),
                location = AlarmLocation.PHONE,
                hour = 7,
                minute = 0,
                stimulus = StimulusConfig(StimulusKind.BEEP, 30, 1),
            )
    }
}
