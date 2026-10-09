package cz.peelco.jolt.ble.legacy

import cz.peelco.jolt.ble.Uuids
import cz.peelco.jolt.domain.model.StimulusKind
import java.util.UUID

/**
 * Pavlok 2 / Pavlok 3 GATT layout, recovered from the official Android app
 * and matching a live Pavlok 3 (fw 6.10.0) exactly; see the iOS repository's
 * `BLE/Legacy/LegacyGATT.swift` and `docs/RE-FINDINGS.md`.
 *
 * Services use a vendor base; characteristics are 16-bit. `156E5000` is the
 * application service and `156E0000` the diagnostic one, not the reverse.
 */
object LegacyGatt {
    private fun vendorService(prefix: String): UUID = UUID.fromString("156E${prefix}000-A300-4FEA-897B-86F698D74461")

    val PAVLOK_SERVICE = vendorService("0")

    /** The stimulus outputs live here. */
    val CONFIG_SERVICE = vendorService("1")
    val NOTIFICATION_SERVICE = vendorService("2")

    /** Alarms and app control. */
    val APPLICATION_SERVICE = vendorService("5")
    val FIRMWARE_SERVICE = vendorService("6")
    val SETUP_SERVICE = vendorService("7")

    val VIBRATION = Uuids.from("1001")
    val BEEP = Uuids.from("1002")

    /** Zap is last, not first: an earlier guess had zap and beep swapped. */
    val ZAP = Uuids.from("1003")
    val TIME = Uuids.from("1005")
    val HAND_DETECT = Uuids.from("1006")
    val DAQ_CONTROL = Uuids.from("1008")

    val BATTERY_DIAGNOSTIC = Uuids.from("0001")
    val DIAGNOSTIC_COMMAND = Uuids.from("0008")

    /** The device's own event stream. */
    val EVENTS_NOTIFICATIONS = Uuids.from("2002")
    val NOTIFICATION_FILES = Uuids.from("2009")
    val APPLICATION_ALARM_LOADED = Uuids.from("200A")

    val APPLICATION_CONTROL = Uuids.from("5001")
    val APPLICATION_DOWNLOAD = Uuids.from("5002")
    val APPLICATION_ALARM_NOTIFY = Uuids.from("5003")

    val FIRMWARE_CHARACTERISTIC = Uuids.from("6002")
    val SETUP_CHARACTERISTIC = Uuids.from("7001")

    fun characteristic(kind: StimulusKind): UUID =
        when (kind) {
            StimulusKind.ZAP -> ZAP
            StimulusKind.VIBE -> VIBRATION
            StimulusKind.BEEP -> BEEP
        }
}
