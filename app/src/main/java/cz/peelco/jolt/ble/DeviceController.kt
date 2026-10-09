package cz.peelco.jolt.ble

import cz.peelco.jolt.ble.transport.GattConnection
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.StimulusConfig
import java.util.UUID

/** The per-family operations `CompositeDeviceRepository` dispatches. */
interface DeviceController {
    suspend fun fire(
        stimulus: StimulusConfig,
        connection: GattConnection,
    )

    suspend fun saveStimulusConfig(
        stimulus: StimulusConfig,
        connection: GattConnection,
    )

    suspend fun syncAlarm(
        alarm: Alarm,
        connection: GattConnection,
    )

    suspend fun deleteAlarm(
        id: UUID,
        connection: GattConnection,
    )
}
