package cz.peelco.jolt.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The hardware families Jolt talks to, each with its own GATT layout:
 * `ble/legacy` (Pavlok 2 and 3) or `ble/scmax` (Shock Clock Max).
 */
@Serializable
enum class DeviceFamily(
    val displayName: String,
    /**
     * Pavlok 2/3 prefixes are confirmed from the official app. Shock Clock
     * Max's are placeholders until confirmed against real hardware.
     *
     * Deliberately absent: a bare "pavlok", which would match every Pavlok
     * ever made (and the rings this app does not support).
     */
    val advertisedNamePrefixes: List<String>,
) {
    PAVLOK2("Pavlok 2", listOf("pavlok-2", "pavlok-1")),
    PAVLOK3("Pavlok 3", listOf("pavlok-3")),
    SHOCK_CLOCK_MAX("Shock Clock Max", listOf("shockclockmax", "shock-clock-max", "scmax")),
    ;

    companion object {
        /** Most specific first, so a classification never depends on declaration order. */
        private val matchOrder = listOf(PAVLOK3, PAVLOK2, SHOCK_CLOCK_MAX)

        /** Classifies an advertised name; null for anything unrecognised. */
        fun matching(
            name: String,
            candidates: Set<DeviceFamily> = entries.toSet(),
        ): DeviceFamily? {
            val normalized = name.lowercase()
            return matchOrder.firstOrNull { family ->
                family in candidates && family.advertisedNamePrefixes.any { normalized.contains(it) }
            }
        }
    }
}

sealed interface DeviceConnectionState {
    data object Disconnected : DeviceConnectionState

    data object Scanning : DeviceConnectionState

    data object Connecting : DeviceConnectionState

    data object Connected : DeviceConnectionState

    data class Failed(
        val reason: String,
    ) : DeviceConnectionState
}

@Serializable
data class DeviceInfo(
    val modelNumber: String? = null,
    val serialNumber: String? = null,
    val firmwareRevision: String? = null,
    val hardwareRevision: String? = null,
    val softwareRevision: String? = null,
    val manufacturer: String? = null,
    val batteryLevelPercent: Int? = null,
)

/**
 * A paired or discovered Pavlok. [address] is the Bluetooth MAC, Android's
 * stable identity for a peripheral (iOS uses a per-phone UUID instead).
 */
data class PavlokDevice(
    val address: String,
    val name: String,
    val family: DeviceFamily,
    val info: DeviceInfo = DeviceInfo(),
    val lastConnectedAt: Instant? = null,
    /** Signal strength from a scan, in dBm; null from anywhere else. */
    val rssi: Int? = null,
)

/** What `PairedDeviceStore` remembers to reconnect to. */
@Serializable
data class PairedDeviceRecord(
    val address: String,
    val name: String,
    val family: DeviceFamily,
)

/** One characteristic as the connected device reports it. */
@Serializable
data class GattCharacteristicDump(
    val serviceUuid: String,
    val uuid: String,
    val properties: List<String>,
    /** Hex of the current value when read. Null is "not read", not "empty". */
    val value: String? = null,
) {
    val id: String get() = "$serviceUuid/$uuid"
    val isWritable: Boolean get() = "write" in properties || "writeNoResp" in properties
}

/** Human-readable dump, grouped by service: what Diagnostics shares. */
fun List<GattCharacteristicDump>.transcript(): String =
    groupBy { it.serviceUuid }
        .toSortedMap()
        .map { (service, characteristics) ->
            val rows =
                characteristics.sortedBy { it.uuid }.joinToString("\n") { characteristic ->
                    "  ${characteristic.uuid}  [${characteristic.properties.joinToString(", ")}]" +
                        (characteristic.value?.let { "  = $it" } ?: "")
                }
            "Service $service\n$rows"
        }.joinToString("\n\n")

/** How the Protocol lab sends a raw write. */
enum class RawWriteMode(
    val displayName: String,
    /** The `GattCharacteristicDump.properties` label that permits this mode. */
    val requiredProperty: String,
) {
    WITH_RESPONSE("Write with response", "write"),
    WITHOUT_RESPONSE("Write without response", "writeNoResp"),
}

/** Space-separated upper-hex, the diagnostics format. */
fun ByteArray.toHexString(separator: String = " "): String =
    if (isEmpty()) "(empty)" else joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

/**
 * Parses "01 14", "0x0114" or "01,14". Null when anything is not a hex pair.
 */
fun parseHexBytes(text: String): ByteArray? {
    val cleaned =
        text
            .replace("0x", "", ignoreCase = true)
            .filterNot { it.isWhitespace() || it == ',' }
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    return runCatching {
        ByteArray(cleaned.length / 2) { index -> cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}

/**
 * One unsolicited notification the wearable pushed: a timer tick, a
 * find-my-phone toggle, whatever the firmware reports. Deliberately raw;
 * decoding goes only as far as the firmware disassembly supports.
 */
data class DeviceEvent(
    /** Canonical, long-form, upper-hex service UUID. */
    val serviceUuid: String,
    /** Canonical characteristic UUID. */
    val characteristicUuid: String,
    val data: ByteArray,
    val receivedAt: Instant = Instant.now(),
) {
    val hexString: String get() = data.toHexString()

    val isEventsCharacteristic: Boolean get() = characteristicUuid.uppercase() in EVENTS_CHARACTERISTIC_UUIDS

    /** Byte 0 of an events frame: what happened. */
    val eventType: Int? get() = if (isEventsCharacteristic && data.isNotEmpty()) data[0].toInt() and 0xFF else null

    /**
     * True for `[0x0C, state, flag]`, what a button configured to find-my-phone
     * produces. It says nothing about which button: the firmware doesn't
     * put that in the frame.
     */
    val isFindMyPhoneEvent: Boolean get() = eventType == FIND_MY_PHONE_EVENT_TYPE

    override fun equals(other: Any?): Boolean =
        other is DeviceEvent &&
            serviceUuid == other.serviceUuid &&
            characteristicUuid == other.characteristicUuid &&
            data.contentEquals(other.data) &&
            receivedAt == other.receivedAt

    override fun hashCode(): Int = (characteristicUuid.hashCode() * 31 + data.contentHashCode()) * 31 + receivedAt.hashCode()

    companion object {
        /**
         * The events characteristic (`2002`) in both forms a peripheral can
         * report it: the Bluetooth-base expansion real hardware uses and the
         * vendor base a fixture might declare.
         */
        val EVENTS_CHARACTERISTIC_UUIDS =
            setOf(
                "00002002-0000-1000-8000-00805F9B34FB",
                "156E2002-A300-4FEA-897B-86F698D74461",
            )
        const val FIND_MY_PHONE_EVENT_TYPE = 0x0C
    }
}
