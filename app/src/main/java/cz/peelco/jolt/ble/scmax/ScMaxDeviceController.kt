package cz.peelco.jolt.ble.scmax

import cz.peelco.jolt.ble.DeviceController
import cz.peelco.jolt.ble.transport.GattConnection
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.StimulusConfig
import java.util.UUID

/**
 * Shock Clock Max. Standard GATT reads work; everything that needs an ESF
 * message throws [NotImplementedException] until the opcodes are recovered.
 */
class ScMaxDeviceController : DeviceController {
    class NotImplementedException(
        what: String,
    ) : Exception("Shock Clock Max $what opcode not recovered — see the iOS repository's BLE/SCMax/ProtocolMap.swift")

    override suspend fun fire(
        stimulus: StimulusConfig,
        connection: GattConnection,
    ) {
        if (ScMaxProtocolMap.fireStimulusOpcode == null) throw NotImplementedException("stimulus")
    }

    override suspend fun saveStimulusConfig(
        stimulus: StimulusConfig,
        connection: GattConnection,
    ): Unit = throw NotImplementedException("stimulus config")

    override suspend fun syncAlarm(
        alarm: Alarm,
        connection: GattConnection,
    ): Unit = throw NotImplementedException("alarm")

    override suspend fun deleteAlarm(
        id: UUID,
        connection: GattConnection,
    ): Unit = throw NotImplementedException("alarm")
}
