package cz.peelco.jolt.domain.model

import kotlinx.serialization.Serializable

/**
 * Which physical button and how long it was held. Every button+duration has
 * its own wire value; there is no "double press" in the real protocol. See the
 * iOS repository's `docs/RE-FINDINGS.md` §3.
 */
@Serializable
enum class DeviceButtonSlot(
    val displayName: String,
    /**
     * `setButtonAction`'s second payload byte. The firmware validates it as
     * 1...6 and rejects anything else, `backLong` included.
     */
    val wireValue: Int,
) {
    TOP("Top", 0x01),
    TOP_LONG("Top (long press)", 0x04),
    MIDDLE("Middle", 0x02),
    MIDDLE_LONG("Middle (long press)", 0x05),
    BOTTOM("Bottom", 0x03),
    BOTTOM_LONG("Bottom (long press)", 0x06),
    BACK_LONG("Back (long press)", 0x07),
    ;

    companion object {
        /** `backLong` is excluded: the firmware rejects it, so it can be neither written nor trusted. */
        val configurable: List<DeviceButtonSlot> get() = entries.filter { it != BACK_LONG }

        fun fromWireValue(value: Int): DeviceButtonSlot? = configurable.firstOrNull { it.wireValue == value }
    }
}

/** What a button press does. Every real case is modelled; only some can be written. */
@Serializable
enum class ButtonAction(
    val displayName: String,
) {
    FIND_MY_PHONE("Find my phone"),
    STOP_WATCH("Stopwatch"),
    TIMER("Timer"),
    ZAP("Zap"),
    BEEP("Beep"),
    VIBRATE("Vibrate"),
    TOGGLE_CANDLE("Toggle candle"),
    NEXT_TUNE("Next tune"),
    AIRPLANE_MODE("Airplane mode"),
    DO_NOT_DISTURB("Do not disturb"),
    TOGGLE_SLEEP_TRACKING("Toggle sleep tracking"),
    DISABLED("Off"),
    DEFAULT_ACTION("Device default"),
    ;

    /**
     * Actions the phone has to carry out, which the device therefore reports
     * over BLE. A device-local action is never announced.
     */
    val isPhoneSideEffect: Boolean
        get() = this in setOf(FIND_MY_PHONE, NEXT_TUNE, AIRPLANE_MODE, DO_NOT_DISTURB, TOGGLE_SLEEP_TRACKING)

    /**
     * The full `setButtonAction` payload, or null when its layout isn't
     * recovered. Shape is `[0x02, button, action, …tail]` and the tail length
     * is fixed per action by the firmware's own table:
     *
     * | action | payload | length |
     * |---|---|---|
     * | findMyPhone | `10 00` | 2 |
     * | stopWatch | `11 02 10 01` | 4 |
     * | timer | `11 02 10 02` | 4 |
     * | toggleCandle | `06` | 1 |
     * | nextTune | `07` | 1 |
     * | toggleSleepTracking | `13 01 02` | 3 (rejected by fw 6.8.0) |
     * | disabled | `FF` | 1 |
     * | defaultAction | `00` | restores the firmware default |
     *
     * Zap, beep and vibrate are left out on purpose: writing one arms a real
     * stimulus on the user's wrist. Airplane mode and DND have no traced payload.
     */
    fun payload(slot: DeviceButtonSlot): ByteArray? {
        val tail =
            when (this) {
                FIND_MY_PHONE -> intArrayOf(0x10, 0x00)
                STOP_WATCH -> intArrayOf(0x11, 0x02, 0x10, 0x01)
                TIMER -> intArrayOf(0x11, 0x02, 0x10, 0x02)
                TOGGLE_CANDLE -> intArrayOf(0x06)
                NEXT_TUNE -> intArrayOf(0x07)
                TOGGLE_SLEEP_TRACKING -> intArrayOf(0x13, 0x01, 0x02)
                DISABLED -> intArrayOf(0xFF)
                DEFAULT_ACTION -> intArrayOf(0x00)
                ZAP, BEEP, VIBRATE, AIRPLANE_MODE, DO_NOT_DISTURB -> return null
            }
        return (intArrayOf(0x02, slot.wireValue) + tail).map { it.toByte() }.toByteArray()
    }
}

@Serializable
data class ButtonConfig(
    val slot: DeviceButtonSlot,
    val action: ButtonAction,
)

sealed class ButtonConfigException(
    message: String,
) : Exception(message) {
    class SlotUnsupported(
        slot: DeviceButtonSlot,
    ) : ButtonConfigException("${slot.displayName} can't be configured from the app yet.")

    class ActionNotVerified(
        action: ButtonAction,
    ) : ButtonConfigException(
            "\"${action.displayName}\" has no recovered wire format yet — " +
                "\"Find my phone\", \"Off\", \"Device default\", \"Stopwatch\", \"Timer\", " +
                "\"Next tune\", \"Toggle candle\" and \"Toggle sleep tracking\" can be written.",
        )
}

/**
 * The device's representation of "what this button does": the first byte is
 * the action and the length is fixed per action, transcribed from the
 * firmware's `action_size` compare chain (`pavlok.bin` 6.8.0, `0x225AC`).
 */
class ButtonActionRecord(
    val bytes: ByteArray,
) {
    val actionByte: Int? get() = bytes.firstOrNull()?.toInt()?.and(0xFF)

    /**
     * The action this record encodes. Stopwatch and timer share `0x11` and
     * differ in the last byte, which is why this can't be a plain byte map.
     */
    val action: ButtonAction?
        get() =
            when (actionByte) {
                0x00 -> ButtonAction.DEFAULT_ACTION
                0x01 -> ButtonAction.VIBRATE
                0x02 -> ButtonAction.BEEP
                0x03 -> ButtonAction.ZAP
                0x06 -> ButtonAction.TOGGLE_CANDLE
                0x07 -> ButtonAction.NEXT_TUNE
                0x08 -> ButtonAction.AIRPLANE_MODE
                0x0D -> ButtonAction.DO_NOT_DISTURB
                0x10 -> ButtonAction.FIND_MY_PHONE
                0x11 -> if (bytes.size >= 4 && bytes[3].toInt() == 0x02) ButtonAction.TIMER else ButtonAction.STOP_WATCH
                0x13 -> ButtonAction.TOGGLE_SLEEP_TRACKING
                0xFF -> ButtonAction.DISABLED
                else -> null
            }

    val hexString: String get() = bytes.toHexString()

    override fun equals(other: Any?): Boolean = other is ButtonActionRecord && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        private val explicitLengths =
            mapOf(
                0x01 to 6, // vibrate
                0x02 to 6, // beep
                0x03 to 3, // zap
                0x05 to 2,
                0x0B to 5,
                0x0C to 5,
                0x0E to 3,
                0x10 to 2, // findMyPhone
                0x11 to 4, // stopwatch / timer
                0x12 to 3,
                0xFF to 1, // disabled
            )

        /**
         * Record length including the action byte. Zero means the firmware has
         * no length for it and refuses the write. Everything else at or below
         * `0x12` is a bare one-byte action: the chain's own fallback.
         */
        fun lengthForAction(action: Int): Int = explicitLengths[action] ?: if (action <= 0x12) 1 else 0

        fun isWritable(action: Int): Boolean = lengthForAction(action) > 0
    }
}

/**
 * The device's answer to "what is every button set to?", collected from the
 * notifications on `7001` after writing the query `01 01`.
 *
 * The report is a 3-byte header `[0xE0 | kind, button, 0x01]` followed by
 * record frames. Whether a framing byte precedes each record is not pinned
 * down without a live capture, so the parse accepts a well-formed record and
 * otherwise retries one byte in; every frame is kept verbatim.
 */
data class ButtonConfigReport(
    val records: Map<DeviceButtonSlot, ButtonActionRecord> = emptyMap(),
    val frames: List<ByteArray> = emptyList(),
    val unparsed: List<ByteArray> = emptyList(),
) {
    val isEmpty: Boolean get() = frames.isEmpty()

    val actions: Map<DeviceButtonSlot, ButtonAction>
        get() = records.mapNotNull { (slot, record) -> record.action?.let { slot to it } }.toMap()

    companion object {
        fun parse(frames: List<ByteArray>): ButtonConfigReport {
            val records = mutableMapOf<DeviceButtonSlot, ButtonActionRecord>()
            val unparsed = mutableListOf<ByteArray>()
            var currentSlot: DeviceButtonSlot? = null
            for (frame in frames) {
                val header = headerButton(frame)
                if (header != null) {
                    // A header outside 1...6 is a section that isn't button
                    // config; stop attributing records until the next one.
                    currentSlot = DeviceButtonSlot.fromWireValue(header)
                    continue
                }
                val slot = currentSlot
                val record = record(frame)
                if (slot == null || record == null) {
                    unparsed += frame
                    continue
                }
                records[slot] = record
            }
            return ButtonConfigReport(records, frames, unparsed)
        }

        private fun headerButton(frame: ByteArray): Int? {
            if (frame.size != 3) return null
            val first = frame[0].toInt() and 0xFF
            if (first and 0xF0 != 0xE0 || frame[2].toInt() != 0x01) return null
            return frame[1].toInt() and 0xFF
        }

        private fun record(frame: ByteArray): ButtonActionRecord? =
            wellFormedRecord(frame) ?: if (frame.size > 1) wellFormedRecord(frame.copyOfRange(1, frame.size)) else null

        private fun wellFormedRecord(bytes: ByteArray): ButtonActionRecord? {
            val action = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: return null
            val length = ButtonActionRecord.lengthForAction(action)
            if (length <= 0 || bytes.size < length) return null
            return ButtonActionRecord(bytes.copyOfRange(0, length))
        }
    }
}
