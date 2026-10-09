package cz.peelco.jolt.ble

import java.util.UUID

/**
 * UUID forms. A peripheral reports Bluetooth-SIG characteristics in the base
 * expansion (`0000xxxx-0000-1000-8000-00805F9B34FB`), but the protocol notes
 * and the overrides store the 16-bit short form, so every string comparison
 * goes through [canonical] or [short] to avoid "characteristic not found" for
 * what is really a formatting difference.
 */
object Uuids {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805F9B34FB"

    /** `"1001"` → the Bluetooth-base UUID; anything longer is parsed as-is. */
    fun from(text: String): UUID {
        val trimmed = text.trim()
        return when (trimmed.length) {
            4 -> UUID.fromString("0000$trimmed$BASE_SUFFIX")
            8 -> UUID.fromString("$trimmed$BASE_SUFFIX")
            else -> UUID.fromString(trimmed)
        }
    }

    /** Full upper-case form, for comparisons and `DeviceEvent`. */
    fun canonical(uuid: UUID): String = uuid.toString().uppercase()

    fun canonical(text: String): String = canonical(from(text))

    /** The 16-bit form for Bluetooth-base UUIDs, the full form otherwise; what iOS shows. */
    fun short(uuid: UUID): String {
        val full = canonical(uuid)
        return if (full.startsWith("0000") && full.endsWith(BASE_SUFFIX)) full.substring(4, 8) else full
    }

    /** CCCD, for enabling notifications. */
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = from("2902")
}
