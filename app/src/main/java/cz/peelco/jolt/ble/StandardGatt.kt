package cz.peelco.jolt.ble

/** Bluetooth SIG services, the same on every device family. */
object StandardGatt {
    val DEVICE_INFORMATION = Uuids.from("180A")
    val MODEL_NUMBER = Uuids.from("2A24")
    val SERIAL_NUMBER = Uuids.from("2A25")
    val FIRMWARE_REVISION = Uuids.from("2A26")
    val HARDWARE_REVISION = Uuids.from("2A27")
    val SOFTWARE_REVISION = Uuids.from("2A28")
    val MANUFACTURER_NAME = Uuids.from("2A29")

    val BATTERY = Uuids.from("180F")
    val BATTERY_LEVEL = Uuids.from("2A19")
}
