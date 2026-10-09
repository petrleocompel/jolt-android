package cz.peelco.jolt.data.device

import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.ButtonAction
import cz.peelco.jolt.domain.model.ButtonConfig
import cz.peelco.jolt.domain.model.ButtonConfigException
import cz.peelco.jolt.domain.model.ButtonConfigReport
import cz.peelco.jolt.domain.model.DeviceButtonSlot
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
import cz.peelco.jolt.domain.repository.DeviceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.UUID

/**
 * A stand-in wearable for UI tests, the demo build and the emulator, which
 * has no Bluetooth. It "fires" by recording what it was asked to do.
 */
class FakeDeviceRepository(
    startsPaired: Boolean = true,
    /** Paired but unreachable in this state, for the device card's other faces. */
    private val unreachableState: DeviceConnectionState? = null,
) : DeviceRepository {
    private val demo =
        PavlokDevice(
            address = "00:11:22:33:44:55",
            name = "Pavlok-3 Demo",
            family = DeviceFamily.PAVLOK3,
            info = DeviceInfo(modelNumber = "Pavlok 3", firmwareRevision = "6.10.0", manufacturer = "Pavlok", batteryLevelPercent = 82),
            lastConnectedAt = Instant.now(),
        )

    private val state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    private val device = MutableStateFlow<PavlokDevice?>(null)
    private val paired = MutableStateFlow(if (startsPaired) PairedDeviceRecord(demo.address, demo.name, demo.family) else null)
    private val settings = MutableStateFlow(StimulusSettings.DEFAULT)
    private val buttons = mutableMapOf<DeviceButtonSlot, ButtonAction>()
    private val overrides = mutableMapOf<StimulusKind, String>()
    private val events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16)

    /** Everything fired, oldest first. */
    val fired = mutableListOf<StimulusConfig>()
    val syncedAlarms = mutableListOf<Alarm>()

    override val connectionState: StateFlow<DeviceConnectionState> = state.asStateFlow()
    override val connectedDevice: StateFlow<PavlokDevice?> = device.asStateFlow()
    override val pairedDevice: StateFlow<PairedDeviceRecord?> = paired.asStateFlow()
    override val stimulusSettings: StateFlow<StimulusSettings> = settings.asStateFlow()

    override fun start() {
        if (paired.value == null) return
        if (unreachableState != null) {
            state.value = unreachableState
        } else {
            state.value = DeviceConnectionState.Connected
            device.value = demo
        }
    }

    override suspend fun reconnect() = start()

    override fun startScan(families: Set<DeviceFamily>): Flow<PavlokDevice> =
        flow {
            state.value = DeviceConnectionState.Scanning
            delay(400)
            if (demo.family in families) emit(demo.copy(rssi = -58))
        }

    override fun stopScan() {
        if (device.value == null) state.value = DeviceConnectionState.Disconnected
    }

    override suspend fun connect(device: PavlokDevice) {
        state.value = DeviceConnectionState.Connecting
        paired.value = PairedDeviceRecord(device.address, device.name, device.family)
        state.value = DeviceConnectionState.Connected
        this.device.value = demo.copy(address = device.address, name = device.name, family = device.family)
    }

    override suspend fun disconnect() {
        state.value = DeviceConnectionState.Disconnected
        device.value = null
    }

    override suspend fun forgetPairedDevice() {
        disconnect()
        paired.value = null
    }

    override suspend fun fire(stimulus: StimulusConfig) {
        check(device.value != null) { "No device connected." }
        fired += stimulus
    }

    override suspend fun readDeviceInfo(): DeviceInfo = device.value?.info ?: error("No device connected.")

    override suspend fun setButtonConfig(config: ButtonConfig) {
        config.action.payload(config.slot) ?: throw ButtonConfigException.ActionNotVerified(config.action)
        buttons[config.slot] = config.action
    }

    override suspend fun readButtonConfig(): ButtonConfigReport {
        val frames =
            buttons.flatMap { (slot, action) ->
                val payload = action.payload(slot)!!
                listOf(byteArrayOf(0xE1.toByte(), slot.wireValue.toByte(), 0x01), payload.copyOfRange(2, payload.size))
            }
        return ButtonConfigReport.parse(frames)
    }

    override suspend fun readRawButtonConfig(): ByteArray = byteArrayOf(0x00)

    override suspend fun syncDeviceAlarm(alarm: Alarm) {
        check(device.value != null) { "No device connected." }
        syncedAlarms.removeAll { it.id == alarm.id }
        syncedAlarms += alarm
    }

    override suspend fun deleteDeviceAlarm(id: UUID) {
        syncedAlarms.removeAll { it.id == id }
    }

    override suspend fun saveStimulusConfig(config: StimulusConfig): StimulusSyncState {
        settings.value = settings.value.with(config)
        return if (device.value != null) StimulusSyncState.SyncedToDevice else StimulusSyncState.LocalOnly("No device connected")
    }

    override suspend fun dumpGatt(readingValues: Boolean): List<GattCharacteristicDump> =
        listOf(
            GattCharacteristicDump("156E1000-A300-4FEA-897B-86F698D74461", "00001001-0000-1000-8000-00805F9B34FB", listOf("read", "write"), "01 0C 23 16 16".takeIf { readingValues }),
            GattCharacteristicDump("156E1000-A300-4FEA-897B-86F698D74461", "00001002-0000-1000-8000-00805F9B34FB", listOf("read", "write"), "01 0C 64 16 16".takeIf { readingValues }),
            GattCharacteristicDump("156E1000-A300-4FEA-897B-86F698D74461", "00001003-0000-1000-8000-00805F9B34FB", listOf("read", "write"), "01 19".takeIf { readingValues }),
            GattCharacteristicDump("156E2000-A300-4FEA-897B-86F698D74461", "00002002-0000-1000-8000-00805F9B34FB", listOf("read", "notify")),
        )

    override suspend fun writeRaw(
        data: ByteArray,
        characteristicUuid: String,
        serviceUuid: String,
        mode: RawWriteMode,
    ) = Unit

    override suspend fun startListeningForDeviceEvents(): Int = 1

    override fun stopListeningForDeviceEvents() = Unit

    override fun deviceEvents(): Flow<DeviceEvent> = events

    /** Test hook: the wearable reports something. */
    fun emit(event: DeviceEvent) {
        events.tryEmit(event)
    }

    override fun stimulusCharacteristicUuid(kind: StimulusKind): String =
        overrides[kind] ?: when (kind) {
            StimulusKind.ZAP -> "1003"
            StimulusKind.VIBE -> "1001"
            StimulusKind.BEEP -> "1002"
        }

    override fun setStimulusCharacteristicUuid(
        kind: StimulusKind,
        uuid: String?,
    ) {
        if (uuid == null) overrides.remove(kind) else overrides[kind] = uuid
    }

    override fun resetStimulusCharacteristics() = overrides.clear()
}
