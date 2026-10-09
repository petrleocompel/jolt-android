package cz.peelco.jolt.ble.legacy

import cz.peelco.jolt.ble.BleLog
import cz.peelco.jolt.ble.DeviceController
import cz.peelco.jolt.ble.Uuids
import cz.peelco.jolt.ble.transport.GattConnection
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Talks to Pavlok 2/3 over [LegacyGatt]. Firing and configuring are the same
 * write and differ only in a flag added to the first byte: `0x80` fires,
 * `0x40` stores the device-side default. Payloads, cross-checked against a
 * live Pavlok 3 (fw 6.10.0):
 *
 *     zap   1003  [count|flag, level]                                2 bytes
 *     vibe  1001  [count|flag, 0x0C, level, onInterval, offInterval] 5 bytes
 *     beep  1002  [count|flag, 0x0C, level, onInterval, offInterval] 5 bytes
 */
class LegacyDeviceController(
    /** Per-kind characteristic, honouring any override set in Diagnostics. */
    private val characteristicFor: (StimulusKind) -> UUID = LegacyGatt::characteristic,
) : DeviceController {
    class UnknownLayoutException(
        uuid: UUID,
        length: Int,
    ) : Exception("${Uuids.short(uuid)} returned $length bytes, which doesn't match any known Pavlok layout. Nothing was written.")

    enum class Command(
        val flag: Int,
    ) {
        /** `perform*`: fire now. */
        FIRE(0x80),

        /** `update*`: store as the device-side default. */
        STORE(0x40),
    }

    private suspend fun write(
        stimulus: StimulusConfig,
        command: Command,
        connection: GattConnection,
    ) {
        val characteristic = characteristicFor(stimulus.kind)
        val existing = connection.read(LegacyGatt.CONFIG_SERVICE, characteristic, timeout = 3.seconds)
        val payload =
            payload(stimulus, existing, command) ?: run {
                BleLog.error("Unrecognised ${existing.size}-byte layout at ${Uuids.short(characteristic)} — refusing to write")
                throw UnknownLayoutException(characteristic, existing.size)
            }
        BleLog.info(
            "${if (command == Command.FIRE) "Fire" else "Store"} ${stimulus.kind.wireName} " +
                "intensity=${stimulus.intensity} reps=${stimulus.repetitions} → ${Uuids.short(characteristic)}",
        )
        connection.write(LegacyGatt.CONFIG_SERVICE, characteristic, payload)
    }

    override suspend fun fire(
        stimulus: StimulusConfig,
        connection: GattConnection,
    ) = write(stimulus, Command.FIRE, connection)

    override suspend fun saveStimulusConfig(
        stimulus: StimulusConfig,
        connection: GattConnection,
    ) = write(stimulus, Command.STORE, connection)

    /**
     * Alarm slot bytes go to `5002` per `writeAlarmBytesToDevice`; the target
     * is confirmed, the payload is the same unverified guess iOS writes.
     */
    override suspend fun syncAlarm(
        alarm: Alarm,
        connection: GattConnection,
    ) {
        connection.write(LegacyGatt.APPLICATION_SERVICE, LegacyGatt.APPLICATION_DOWNLOAD, alarmPayload(alarm))
    }

    /** Deleting is a command, so it goes to the control point. Payload unverified. */
    override suspend fun deleteAlarm(
        id: UUID,
        connection: GattConnection,
    ) {
        connection.write(LegacyGatt.APPLICATION_SERVICE, LegacyGatt.APPLICATION_CONTROL, byteArrayOf(0xFF.toByte()) + idPrefix(id))
    }

    companion object {
        /**
         * Builds the payload from the device's current value. Count and level
         * are overwritten; the constant and the two encoded interval bytes are
         * carried through so the user's pattern isn't flattened. Count is
         * masked to six bits so it can never carry into the command flag.
         */
        fun payload(
            stimulus: StimulusConfig,
            existing: ByteArray,
            command: Command,
        ): ByteArray? {
            val level = stimulus.intensity.coerceIn(0, 255).toByte()
            val first = ((stimulus.repetitions.coerceIn(0, 255) and 0x3F) or command.flag).toByte()
            return when (existing.size) {
                2 -> byteArrayOf(first, level)
                5 -> existing.copyOf().also {
                    it[0] = first
                    it[2] = level
                }
                else -> null
            }
        }

        /** `[hour, minute, days, enabled] + first four bytes of the id`; bit 0 is Monday. */
        fun alarmPayload(alarm: Alarm): ByteArray {
            val days = alarm.repeatDays.fold(0) { mask, day -> mask or (1 shl (day.number - 1)) }
            return byteArrayOf(alarm.hour.toByte(), alarm.minute.toByte(), days.toByte(), if (alarm.isEnabled) 1 else 0) + idPrefix(alarm.id)
        }

        private fun idPrefix(id: UUID): ByteArray {
            val high = id.mostSignificantBits
            return ByteArray(4) { index -> (high ushr (56 - 8 * index)).toByte() }
        }
    }
}
