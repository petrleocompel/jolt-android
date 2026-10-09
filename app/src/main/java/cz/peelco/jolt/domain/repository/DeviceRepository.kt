package cz.peelco.jolt.domain.repository

import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.ButtonConfig
import cz.peelco.jolt.domain.model.ButtonConfigReport
import cz.peelco.jolt.domain.model.DeviceConnectionState
import cz.peelco.jolt.domain.model.DeviceEvent
import cz.peelco.jolt.domain.model.DeviceFamily
import cz.peelco.jolt.domain.model.DeviceInfo
import cz.peelco.jolt.domain.model.GattCharacteristicDump
import cz.peelco.jolt.domain.model.PairedDeviceRecord
import cz.peelco.jolt.domain.model.PavlokDevice
import cz.peelco.jolt.domain.model.RawWriteMode
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.StimulusSettings
import cz.peelco.jolt.domain.model.StimulusSyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * The one door between UI and hardware. `ble/` implements it; `features/`
 * never touches `BluetoothGatt`, so a fake can stand in.
 */
interface DeviceRepository {
    val connectionState: StateFlow<DeviceConnectionState>
    val connectedDevice: StateFlow<PavlokDevice?>

    /**
     * The device paired on this phone, reachable or not. Out of range,
     * Bluetooth off or mid-reconnect all mean "paired but not connected".
     */
    val pairedDevice: StateFlow<PairedDeviceRecord?>

    /** Starts observing Bluetooth and reconnecting; idempotent. */
    fun start()

    /** Tries the paired device now instead of waiting: the "Try again". */
    suspend fun reconnect()

    fun startScan(families: Set<DeviceFamily>): Flow<PavlokDevice>

    fun stopScan()

    suspend fun connect(device: PavlokDevice)

    suspend fun disconnect()

    /** Disconnects and forgets the pairing so nothing reconnects to it. */
    suspend fun forgetPairedDevice()

    suspend fun fire(stimulus: StimulusConfig)

    /** Reads model, firmware and battery, and republishes the connected device. */
    suspend fun readDeviceInfo(): DeviceInfo

    suspend fun setButtonConfig(config: ButtonConfig)

    /** What every button is set to, straight from the device. */
    suspend fun readButtonConfig(): ButtonConfigReport

    /** The setup characteristic's raw contents: a status byte, not the config. */
    suspend fun readRawButtonConfig(): ByteArray

    /** Pushes a device alarm onto the wearable's own clock. */
    suspend fun syncDeviceAlarm(alarm: Alarm)

    suspend fun deleteDeviceAlarm(id: UUID)

    /** Per-kind defaults, stored on the phone; always available. */
    val stimulusSettings: StateFlow<StimulusSettings>

    /** Saves for its kind and, when connected, writes it to the wearable too. */
    suspend fun saveStimulusConfig(config: StimulusConfig): StimulusSyncState

    // Protocol diagnostics.

    suspend fun dumpGatt(readingValues: Boolean): List<GattCharacteristicDump>

    suspend fun writeRaw(
        data: ByteArray,
        characteristicUuid: String,
        serviceUuid: String,
        mode: RawWriteMode,
    )

    /** Subscribes to every notifying characteristic and logs; returns how many. */
    suspend fun startListeningForDeviceEvents(): Int

    fun stopListeningForDeviceEvents()

    /**
     * Unsolicited notifications for features that react to the wearable (the
     * poke trigger). Subscribes on collection; ends when the link drops.
     */
    fun deviceEvents(): Flow<DeviceEvent>

    /** Stimulus characteristic overrides for Pavlok 2/3 (Diagnostics). */
    fun stimulusCharacteristicUuid(kind: StimulusKind): String

    fun setStimulusCharacteristicUuid(
        kind: StimulusKind,
        uuid: String?,
    )

    fun resetStimulusCharacteristics()
}

interface AlarmRepository {
    val alarms: StateFlow<List<Alarm>>

    suspend fun save(alarm: Alarm)

    suspend fun delete(id: UUID)
}
