package cz.peelco.jolt.ble

import cz.peelco.jolt.ble.transport.GattConnection
import cz.peelco.jolt.domain.model.DeviceInfo
import java.util.UUID

/** Reads the standard Device Information and Battery services; the same on every family. */
object DeviceInformationReader {
    suspend fun read(connection: GattConnection): DeviceInfo {
        suspend fun string(characteristic: UUID): String? =
            runCatching { connection.read(StandardGatt.DEVICE_INFORMATION, characteristic) }
                .getOrNull()
                ?.toString(Charsets.UTF_8)
                ?.trimEnd('\u0000')

        return DeviceInfo(
            modelNumber = string(StandardGatt.MODEL_NUMBER),
            serialNumber = string(StandardGatt.SERIAL_NUMBER),
            firmwareRevision = string(StandardGatt.FIRMWARE_REVISION),
            hardwareRevision = string(StandardGatt.HARDWARE_REVISION),
            softwareRevision = string(StandardGatt.SOFTWARE_REVISION),
            manufacturer = string(StandardGatt.MANUFACTURER_NAME),
            batteryLevelPercent =
                runCatching { connection.read(StandardGatt.BATTERY, StandardGatt.BATTERY_LEVEL) }
                    .getOrNull()
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF),
        )
    }
}
