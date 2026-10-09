package cz.peelco.jolt.ble.scmax

import java.util.UUID

/**
 * Shock Clock Max GATT layout. The service UUIDs and the write/notify control
 * point pair are confirmed from the official app; the ESF opcodes sent over it
 * are not recovered (see [ScMaxProtocolMap]).
 */
object ScMaxGatt {
    val CONTROL_POINTS_SERVICE: UUID = UUID.fromString("66651000-39F4-11ED-92BD-832ABAC11AB4")
    val CONTROL_POINT_WRITE: UUID = UUID.fromString("66651001-39F4-11ED-92BD-832ABAC11AB4")
    val CONTROL_POINT_NOTIFY: UUID = UUID.fromString("66651002-39F4-11ED-92BD-832ABAC11AB4")

    /** Purpose unconfirmed; likely bulk file and log transfer. */
    val SECONDARY_SERVICE: UUID = UUID.fromString("66657000-39F4-11ED-92BD-832ABAC11AB4")
    val SECONDARY_CHARACTERISTIC: UUID = UUID.fromString("66657001-39F4-11ED-92BD-832ABAC11AB4")
}

/**
 * Every value here is a placeholder, and filling it in is the single blocker
 * on Shock Clock Max control, on iOS too. The iOS repository's
 * `BLE/SCMax/ProtocolMap.swift` documents the HCI-snoop capture procedure.
 */
object ScMaxProtocolMap {
    val handshakeOpcode: Int? = null
    val fireStimulusOpcode: Int? = null
}
