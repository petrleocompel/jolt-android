package cz.peelco.jolt.data.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import cz.peelco.jolt.ble.BleLog
import cz.peelco.jolt.ble.ConnectionWaiter
import cz.peelco.jolt.ble.DeviceController
import cz.peelco.jolt.ble.DeviceInformationReader
import cz.peelco.jolt.ble.StandardGatt
import cz.peelco.jolt.ble.Uuids
import cz.peelco.jolt.ble.legacy.LegacyDeviceController
import cz.peelco.jolt.ble.legacy.LegacyGatt
import cz.peelco.jolt.ble.scmax.ScMaxDeviceController
import cz.peelco.jolt.ble.transport.BleCentral
import cz.peelco.jolt.ble.transport.BleException
import cz.peelco.jolt.ble.transport.GattConnection
import cz.peelco.jolt.ble.transport.WriteType
import cz.peelco.jolt.data.store.AppSettings
import cz.peelco.jolt.domain.model.Alarm
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
import cz.peelco.jolt.domain.model.toHexString
import cz.peelco.jolt.domain.repository.DeviceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The real [DeviceRepository]. Owns the one GATT connection and dispatches
 * per-family work to [LegacyDeviceController] or [ScMaxDeviceController].
 *
 * Also owns connection reliability: remembers the paired device, reconnects
 * at launch and whenever Bluetooth comes back on, reacts to unexpected drops
 * with a background reconnect, keeps the battery reading current and retries
 * device info on a link that is still settling.
 */
@SuppressLint("MissingPermission")
class CompositeDeviceRepository(
    private val context: Context,
    private val central: BleCentral,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) : DeviceRepository {
    private val legacy = LegacyDeviceController { kind -> Uuids.from(stimulusCharacteristicUuid(kind)) }
    private val scMax = ScMaxDeviceController()

    private val state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    private val device = MutableStateFlow<PavlokDevice?>(null)

    override val connectionState: StateFlow<DeviceConnectionState> = state.asStateFlow()
    override val connectedDevice: StateFlow<PavlokDevice?> = device.asStateFlow()
    override val pairedDevice: StateFlow<PairedDeviceRecord?> = settings.pairedDevice.flow
    override val stimulusSettings: StateFlow<StimulusSettings> = settings.stimulusSettings.flow

    @Volatile private var connection: GattConnection? = null

    @Volatile private var family: DeviceFamily? = null
    private val started = AtomicBoolean(false)
    private val autoReconnecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private var batteryJob: Job? = null
    private var captureJob: Job? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { central.stateUpdates().collect(::handleAdapterState) }
        scope.launch { attemptAutoReconnect() }
    }

    // Scanning.

    override fun startScan(families: Set<DeviceFamily>): Flow<PavlokDevice> {
        if (!central.hasScanPermission() || !central.hasConnectPermission()) {
            state.value = DeviceConnectionState.Failed("Bluetooth permission denied")
            return emptyFlow()
        }
        val seen = mutableSetOf<String>()
        return central
            .scan(listOfNotNull(pairedDevice.value?.address))
            .onStart { state.value = DeviceConnectionState.Scanning }
            .mapNotNull { discovery ->
                val name = discovery.name ?: return@mapNotNull null
                val family =
                    DeviceFamily.matching(name, families) ?: run {
                        BleLog.debug("Ignoring $name — no matching device family")
                        return@mapNotNull null
                    }
                if (!seen.add(discovery.device.address)) return@mapNotNull null
                BleLog.info("Matched $name as ${family.displayName}${if (discovery.wasAlreadyKnown) " (already known to the phone)" else ""}")
                PavlokDevice(discovery.device.address, name, family, rssi = discovery.rssi)
            }
    }

    override fun stopScan() {
        if (connection == null && state.value == DeviceConnectionState.Scanning) state.value = DeviceConnectionState.Disconnected
    }

    // Connection.

    override suspend fun connect(device: PavlokDevice) {
        reconnectJob?.cancel()
        if (!central.hasConnectPermission()) throw BleException.PermissionDenied()
        state.value = DeviceConnectionState.Connecting
        val bluetoothDevice =
            central.device(device.address) ?: run {
                state.value = DeviceConnectionState.Failed("Device not found")
                throw BleException.ConnectFailed("Device not found")
            }
        val link = GattConnection(context, bluetoothDevice)
        try {
            link.connect(autoConnect = false)
            adopt(link, device.family, device.name)
        } catch (error: Exception) {
            link.close()
            BleLog.error("Connect failed: ${error.message}")
            state.value = DeviceConnectionState.Failed(error.message ?: "Connect failed")
            throw error
        }
    }

    private fun adopt(
        link: GattConnection,
        family: DeviceFamily,
        name: String,
    ) {
        connection = link
        this.family = family
        settings.pairedDevice.save(PairedDeviceRecord(link.address, name, family))
        state.value = DeviceConnectionState.Connected
        device.value = PavlokDevice(link.address, name, family, lastConnectedAt = Instant.now())

        scope.launch {
            link.link.first { it is GattConnection.Link.Disconnected }
            handleUnexpectedDisconnection(link)
        }
        scope.launch {
            // One dump per connection: it is the difference between "the zap
            // did nothing" and "the zap went to a characteristic this device
            // doesn't have".
            runCatching { dumpGatt(readingValues = false) }.getOrNull()?.let { dump ->
                BleLog.info("GATT: ${dump.size} characteristic(s) across ${dump.map { it.serviceUuid }.distinct().size} service(s)")
            }
            publishDeviceInfo(link)
            if (connection === link) startBatteryMonitoring(link)
        }
    }

    override suspend fun disconnect() {
        reconnectJob?.cancel()
        stopBatteryMonitoring()
        val link = connection
        connection = null
        family = null
        link?.close()
        state.value = DeviceConnectionState.Disconnected
        device.value = null
    }

    override suspend fun forgetPairedDevice() {
        disconnect()
        settings.pairedDevice.save(null)
    }

    override suspend fun reconnect() {
        start()
        attemptAutoReconnect()
    }

    /**
     * Connects to the paired device: a direct attempt first, then a
     * background one that waits for the device to come into range.
     */
    private suspend fun attemptAutoReconnect() {
        if (!autoReconnecting.compareAndSet(false, true)) return
        try {
            val record = pairedDevice.value ?: return
            if (!central.hasConnectPermission()) {
                state.value = DeviceConnectionState.Failed("Bluetooth permission denied")
                return
            }
            // Wait rather than bail: right after boot the adapter may still be
            // coming up, and giving up stranded a perfectly good pairing.
            if (!central.waitUntilOn()) {
                BleLog.error("Auto-reconnect aborted: Bluetooth is off")
                return
            }
            if (connection != null) return
            val bluetoothDevice =
                central.device(record.address) ?: run {
                    BleLog.error("Paired device ${record.address} not known to Android")
                    state.value = DeviceConnectionState.Failed("Paired device not found")
                    return
                }
            scheduleReconnect(bluetoothDevice, record, direct = true)
        } finally {
            autoReconnecting.set(false)
        }
    }

    private fun scheduleReconnect(
        bluetoothDevice: BluetoothDevice,
        record: PairedDeviceRecord,
        direct: Boolean,
    ) {
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                if (direct) {
                    state.value = DeviceConnectionState.Connecting
                    val link = GattConnection(context, bluetoothDevice)
                    try {
                        link.connect(autoConnect = false)
                        if (!isActive) return@launch link.close()
                        adopt(link, record.family, record.name)
                        return@launch
                    } catch (error: Exception) {
                        link.close()
                        if (!isActive) return@launch
                        BleLog.error("Reconnect failed: ${error.message}")
                        state.value = DeviceConnectionState.Failed(error.message ?: "Reconnect failed")
                    }
                }
                // Background: Android's autoConnect waits for the device to
                // advertise again, however long that takes, at low power.
                val link = GattConnection(context, bluetoothDevice)
                try {
                    link.connect(autoConnect = true, timeout = null)
                    if (connection == null) adopt(link, record.family, record.name) else link.close()
                } catch (error: Exception) {
                    link.close()
                    BleLog.error("Background reconnect ended: ${error.message}")
                }
            }
    }

    private fun handleAdapterState(adapter: BleCentral.AdapterState) {
        when (adapter) {
            BleCentral.AdapterState.ON -> if (connection == null) scope.launch { attemptAutoReconnect() }
            BleCentral.AdapterState.OFF -> {
                reconnectJob?.cancel()
                stopBatteryMonitoring()
                connection?.close()
                connection = null
                if (pairedDevice.value != null) state.value = DeviceConnectionState.Failed("Bluetooth is off")
                device.value = null
            }
            BleCentral.AdapterState.UNAUTHORIZED -> if (pairedDevice.value != null) state.value = DeviceConnectionState.Failed("Bluetooth permission denied")
            BleCentral.AdapterState.UNSUPPORTED -> state.value = DeviceConnectionState.Failed("Bluetooth not supported on this device")
        }
    }

    /**
     * An unexpected drop: out of range, switched off, crashed. A manual
     * disconnect clears [connection] first, so it never gets here.
     */
    private fun handleUnexpectedDisconnection(link: GattConnection) {
        if (connection !== link) return
        stopBatteryMonitoring()
        connection = null
        link.close()
        state.value = DeviceConnectionState.Disconnected
        device.value = null
        val record = pairedDevice.value ?: return
        val bluetoothDevice = central.device(record.address) ?: return
        scheduleReconnect(bluetoothDevice, record, direct = false)
    }

    private fun requireConnection(): Pair<GattConnection, DeviceFamily> {
        val link = connection
        val family = family
        if (link == null || family == null || !link.isConnected) {
            BleLog.error("Operation requires a connected device, but none is connected")
            throw BleException.NotConnected()
        }
        return link to family
    }

    private fun controller(family: DeviceFamily): DeviceController =
        when (family) {
            DeviceFamily.PAVLOK2, DeviceFamily.PAVLOK3 -> legacy
            DeviceFamily.SHOCK_CLOCK_MAX -> scMax
        }

    // Stimulus.

    /**
     * A stimulus is the one operation worth waiting on a reconnect for: a
     * push carrying a poke often arrives just before the link is back.
     */
    override suspend fun fire(stimulus: StimulusConfig) {
        val waiter =
            ConnectionWaiter(
                budget = RECONNECT_BUDGET_FOR_FIRING,
                isPaired = { pairedDevice.value != null },
                currentConnection = { connection?.takeIf { it.isConnected }?.let { link -> family?.let { link to it } } },
                startReconnect = { scope.launch { reconnect() } },
            )
        val (link, family) = waiter.connection() ?: requireConnection()
        controller(family).fire(stimulus, link)
    }

    override suspend fun saveStimulusConfig(config: StimulusConfig): StimulusSyncState {
        settings.stimulusSettings.update { it.with(config) }
        val link = connection ?: return StimulusSyncState.LocalOnly("No device connected")
        val family = family ?: return StimulusSyncState.LocalOnly("No device connected")
        return try {
            controller(family).saveStimulusConfig(config, link)
            StimulusSyncState.SyncedToDevice
        } catch (error: Exception) {
            BleLog.error("Saving ${config.kind.wireName} config to device failed: ${error.message}")
            StimulusSyncState.LocalOnly(error.message)
        }
    }

    override suspend fun syncDeviceAlarm(alarm: Alarm) {
        val (link, family) = requireConnection()
        controller(family).syncAlarm(alarm, link)
    }

    override suspend fun deleteDeviceAlarm(id: UUID) {
        val (link, family) = requireConnection()
        controller(family).deleteAlarm(id, link)
    }

    // Device information and battery.

    override suspend fun readDeviceInfo(): DeviceInfo {
        val (link, _) = requireConnection()
        return DeviceInformationReader.read(link).also { publish(it, link) }
    }

    /** The foreground hook: a level that moved while backgrounded is corrected at once. */
    suspend fun refreshDeviceInfoIfConnected() {
        if (connection != null) runCatching { readDeviceInfo() }
    }

    /**
     * Fills in model, firmware and battery, retried while the battery is
     * missing: it is the last read and the one most likely to come back empty
     * on a link that is still settling.
     */
    private suspend fun publishDeviceInfo(link: GattConnection) {
        for (attempt in 1..DEVICE_INFO_ATTEMPTS) {
            if (connection !== link) return
            val info = runCatching { DeviceInformationReader.read(link) }.getOrNull()
            if (info != null) {
                publish(info, link)
                BleLog.info("Device info: model=${info.modelNumber ?: "?"} fw=${info.firmwareRevision ?: "?"} battery=${info.batteryLevelPercent ?: "?"}")
                if (info.batteryLevelPercent != null) return
            }
            if (attempt == DEVICE_INFO_ATTEMPTS) return
            BleLog.info("No battery level yet — re-reading device info (attempt ${attempt + 1} of $DEVICE_INFO_ATTEMPTS)")
            delay(2.seconds)
        }
    }

    /**
     * Amends the published device rather than a copy taken earlier, and never
     * resurrects one that disconnected while a read was in flight.
     */
    private fun publish(
        info: DeviceInfo,
        link: GattConnection,
    ) {
        if (connection !== link) return
        val current = device.value ?: return
        if (current.address != link.address || current.info == info) return
        device.value = current.copy(info = info)
    }

    private fun startBatteryMonitoring(link: GattConnection) {
        stopBatteryMonitoring()
        batteryJob =
            scope.launch {
                if (link.canNotify(StandardGatt.BATTERY, StandardGatt.BATTERY_LEVEL)) {
                    runCatching { link.notificationsOf(StandardGatt.BATTERY, StandardGatt.BATTERY_LEVEL).collect { publishBattery(it, link) } }
                    return@launch
                }
                // A device that won't notify is polled, slowly: battery moves
                // in single digits per hour and a tight poll costs more than it tells.
                BleLog.info("Battery level won't notify — polling instead")
                while (isActive && connection === link) {
                    delay(15.minutes)
                    runCatching { link.read(StandardGatt.BATTERY, StandardGatt.BATTERY_LEVEL) }.getOrNull()?.let { publishBattery(it, link) }
                }
            }
    }

    private fun stopBatteryMonitoring() {
        batteryJob?.cancel()
        batteryJob = null
    }

    private fun publishBattery(
        data: ByteArray,
        link: GattConnection,
    ) {
        val level = data.firstOrNull()?.toInt()?.and(0xFF) ?: return
        val current = device.value ?: return
        if (current.info.batteryLevelPercent == level) return
        BleLog.info("Battery level now $level%")
        publish(current.info.copy(batteryLevelPercent = level), link)
    }

    // Button configuration.

    override suspend fun setButtonConfig(config: ButtonConfig) {
        val (link, family) = requireConnection()
        if (family == DeviceFamily.SHOCK_CLOCK_MAX) throw ScMaxDeviceController.NotImplementedException("button config")
        // The firmware validates the button byte as 1...6.
        if (config.slot == DeviceButtonSlot.BACK_LONG) throw ButtonConfigException.SlotUnsupported(config.slot)
        val payload = config.action.payload(config.slot) ?: throw ButtonConfigException.ActionNotVerified(config.action)
        // With response: the setup characteristic needs write authorization,
        // and the ATT error for a bad payload is the only sign it didn't take.
        link.write(LegacyGatt.SETUP_SERVICE, LegacyGatt.SETUP_CHARACTERISTIC, payload, WriteType.WITH_RESPONSE)
    }

    /**
     * Subscribe to `7001` first, then write the query `01 01`: the device
     * answers at once and a later subscription misses the burst. There is no
     * end-of-report frame, so the window closes on a timer.
     */
    override suspend fun readButtonConfig(): ButtonConfigReport {
        val (link, family) = requireConnection()
        if (family == DeviceFamily.SHOCK_CLOCK_MAX) throw ScMaxDeviceController.NotImplementedException("button config")
        val frames = mutableListOf<ByteArray>()
        val subscribed = CompletableDeferred<Unit>()
        val collector =
            scope.launch {
                link.notificationsOf(LegacyGatt.SETUP_SERVICE, LegacyGatt.SETUP_CHARACTERISTIC) { subscribed.complete(Unit) }.collect { frame ->
                    if (frame.isNotEmpty()) synchronized(frames) { frames += frame }
                }
            }
        try {
            withTimeout(5.seconds) { subscribed.await() }
            link.write(LegacyGatt.SETUP_SERVICE, LegacyGatt.SETUP_CHARACTERISTIC, byteArrayOf(0x01, 0x01), WriteType.WITH_RESPONSE)
            delay(BUTTON_CONFIG_REPORT_WINDOW)
        } finally {
            collector.cancel()
        }
        val report = ButtonConfigReport.parse(synchronized(frames) { frames.toList() })
        BleLog.info("Button config report: ${report.frames.size} frame(s), ${report.records.size} button(s) decoded")
        return report
    }

    /** A plain read of `7001`: a status byte, not the config. Diagnostics only. */
    override suspend fun readRawButtonConfig(): ByteArray {
        val (link, family) = requireConnection()
        if (family == DeviceFamily.SHOCK_CLOCK_MAX) throw ScMaxDeviceController.NotImplementedException("button config")
        return link.read(LegacyGatt.SETUP_SERVICE, LegacyGatt.SETUP_CHARACTERISTIC)
    }

    // Protocol diagnostics.

    override suspend fun dumpGatt(readingValues: Boolean): List<GattCharacteristicDump> {
        val (link, _) = requireConnection()
        return link.characteristics().map { characteristic ->
            val readable = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
            val value =
                if (readingValues && readable) {
                    // A short timeout each: one unresponsive characteristic
                    // mustn't hold up the whole table.
                    runCatching { link.read(characteristic.service.uuid, characteristic.uuid, timeout = 3.seconds).toHexString() }.getOrNull()
                } else {
                    null
                }
            GattCharacteristicDump(
                serviceUuid = Uuids.short(characteristic.service.uuid),
                uuid = Uuids.short(characteristic.uuid),
                properties = GattConnection.propertyLabels(characteristic.properties),
                value = value,
            )
        }
    }

    override suspend fun writeRaw(
        data: ByteArray,
        characteristicUuid: String,
        serviceUuid: String,
        mode: RawWriteMode,
    ) {
        val (link, _) = requireConnection()
        val type = if (mode == RawWriteMode.WITH_RESPONSE) WriteType.WITH_RESPONSE else WriteType.WITHOUT_RESPONSE
        link.write(Uuids.from(serviceUuid), Uuids.from(characteristicUuid), data, type)
    }

    /** Every characteristic that can notify or indicate, with its service. */
    private fun notifyingCharacteristics(link: GattConnection): List<BluetoothGattCharacteristic> =
        link.characteristics().filter {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }

    /**
     * Notifications from every notifying characteristic until the link drops.
     * Every characteristic, because the exact one a firmware reports a press
     * on isn't pinned down.
     */
    private fun allNotifications(link: GattConnection): Flow<DeviceEvent> =
        channelFlow {
            for (characteristic in notifyingCharacteristics(link)) {
                launch {
                    runCatching {
                        link.notificationsOf(characteristic.service.uuid, characteristic.uuid).collect { value ->
                            send(DeviceEvent(Uuids.canonical(characteristic.service.uuid), Uuids.canonical(characteristic.uuid), value))
                        }
                    }.onFailure { BleLog.error("Couldn't subscribe to ${Uuids.short(characteristic.uuid)}: ${it.message}") }
                }
            }
            link.link.first { it is GattConnection.Link.Disconnected }
            close()
        }

    override suspend fun startListeningForDeviceEvents(): Int {
        val (link, _) = requireConnection()
        val count = notifyingCharacteristics(link).size
        captureJob?.cancel()
        captureJob =
            scope.launch {
                allNotifications(link).collect { event ->
                    BleLog.info("${BleLog.EVENT_PREFIX}${Uuids.short(Uuids.from(event.characteristicUuid))} ${event.hexString}")
                }
            }
        BleLog.info("Listening on $count notifying characteristic(s) — trigger something on the device now")
        return count
    }

    override fun stopListeningForDeviceEvents() {
        captureJob?.cancel()
        captureJob = null
    }

    override fun deviceEvents(): Flow<DeviceEvent> =
        flow {
            val (link, _) = requireConnection()
            BleLog.info("Poke trigger listening on ${notifyingCharacteristics(link).size} notifying characteristic(s)")
            allNotifications(link).collect { emit(it) }
        }

    // Stimulus characteristic overrides.

    override fun stimulusCharacteristicUuid(kind: StimulusKind): String =
        settings.legacyStimulusCharacteristics.value[kind] ?: Uuids.short(LegacyGatt.characteristic(kind))

    override fun setStimulusCharacteristicUuid(
        kind: StimulusKind,
        uuid: String?,
    ) {
        settings.legacyStimulusCharacteristics.update { if (uuid == null) it - kind else it + (kind to uuid) }
        BleLog.info("Stimulus ${kind.wireName} remapped to characteristic ${uuid ?: "default"}")
    }

    override fun resetStimulusCharacteristics() = settings.legacyStimulusCharacteristics.reset()

    private companion object {
        /** Sized for a push handler's short window; a late stimulus is worse than a missed one. */
        val RECONNECT_BUDGET_FOR_FIRING = 4.seconds
        val BUTTON_CONFIG_REPORT_WINDOW = 1500.milliseconds
        const val DEVICE_INFO_ATTEMPTS = 3
    }
}
