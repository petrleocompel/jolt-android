package cz.peelco.jolt.features.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.peelco.jolt.domain.model.ButtonConfig
import cz.peelco.jolt.domain.model.ButtonConfigReport
import cz.peelco.jolt.domain.model.DeviceConnectionState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Device state and actions for Remote, Settings and the pairing sheets. */
class DeviceControlViewModel(
    private val repository: DeviceRepository,
    /** Called after a successful pairing, to start the background link. */
    private val onPaired: () -> Unit = {},
) : ViewModel() {
    val connectionState: StateFlow<DeviceConnectionState> = repository.connectionState
    val connectedDevice: StateFlow<PavlokDevice?> = repository.connectedDevice
    val pairedDevice: StateFlow<PairedDeviceRecord?> = repository.pairedDevice
    val stimulusSettings: StateFlow<StimulusSettings> = repository.stimulusSettings

    private val discovered = MutableStateFlow<List<PavlokDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<PavlokDevice>> = discovered.asStateFlow()

    private val error = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = error.asStateFlow()

    /** A short-lived confirmation, shown inline: a modal per fire would be unusable. */
    private val actionMessage = MutableStateFlow<String?>(null)
    val lastActionMessage: StateFlow<String?> = actionMessage.asStateFlow()

    private var scanJob: Job? = null
    private var messageJob: Job? = null

    fun dismissError() {
        error.value = null
    }

    fun startScan(families: Set<DeviceFamily> = DeviceFamily.entries.toSet()) {
        discovered.value = emptyList()
        scanJob?.cancel()
        scanJob =
            viewModelScope.launch {
                runCatching {
                    repository.startScan(families).collect { device ->
                        discovered.update { list -> if (list.any { it.address == device.address }) list else list + device }
                    }
                }.onFailure { error.value = it.message }
            }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        repository.stopScan()
    }

    fun connect(device: PavlokDevice) {
        stopScan()
        viewModelScope.launch {
            try {
                repository.connect(device)
                onPaired()
            } catch (_: Exception) {
                error.value = "Couldn't connect to ${device.name}. Move closer and try again."
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }

    fun forgetPairedDevice() {
        viewModelScope.launch { repository.forgetPairedDevice() }
    }

    /** "Try again" on a dropped or failed connection. */
    fun reconnect() {
        error.value = null
        viewModelScope.launch { repository.reconnect() }
    }

    /** Fire controls stay tappable without a device so a tap can say why nothing happened. */
    fun fire(stimulus: StimulusConfig) {
        if (connectedDevice.value == null) {
            error.value = NO_DEVICE_MESSAGE
            return
        }
        viewModelScope.launch {
            try {
                repository.fire(stimulus)
                note("${stimulus.kind.displayName} sent at ${stimulus.intensity}%")
            } catch (failure: Exception) {
                error.value = failure.message
            }
        }
    }

    fun saveStimulusConfig(config: StimulusConfig) {
        viewModelScope.launch {
            when (val result = repository.saveStimulusConfig(config)) {
                StimulusSyncState.SyncedToDevice -> note("${config.kind.displayName} saved to device")
                is StimulusSyncState.LocalOnly -> note("${config.kind.displayName} saved on phone only${result.reason?.let { " — $it" } ?: ""}")
            }
        }
    }

    private fun note(message: String) {
        actionMessage.value = message
        messageJob?.cancel()
        messageJob =
            viewModelScope.launch {
                delay(3_000)
                actionMessage.value = null
            }
    }

    suspend fun readDeviceInfo(): DeviceInfo = repository.readDeviceInfo()

    suspend fun dumpGatt(readingValues: Boolean = false): List<GattCharacteristicDump> = repository.dumpGatt(readingValues)

    suspend fun writeRaw(
        data: ByteArray,
        characteristicUuid: String,
        serviceUuid: String,
        mode: RawWriteMode,
    ) = repository.writeRaw(data, characteristicUuid, serviceUuid, mode)

    suspend fun startListeningForDeviceEvents(): Int = repository.startListeningForDeviceEvents()

    fun stopListeningForDeviceEvents() = repository.stopListeningForDeviceEvents()

    suspend fun setButtonConfig(config: ButtonConfig) = repository.setButtonConfig(config)

    suspend fun readButtonConfig(): ButtonConfigReport = repository.readButtonConfig()

    fun stimulusCharacteristicUuid(kind: StimulusKind) = repository.stimulusCharacteristicUuid(kind)

    fun setStimulusCharacteristicUuid(
        kind: StimulusKind,
        uuid: String?,
    ) = repository.setStimulusCharacteristicUuid(kind, uuid)

    fun resetStimulusCharacteristics() = repository.resetStimulusCharacteristics()

    override fun onCleared() {
        repository.stopScan()
    }

    companion object {
        const val NO_DEVICE_MESSAGE = "No device connected. Pair one to fire."
    }
}

/** How the device card reads the link, folding scanning into the paired/unpaired cases. */
sealed interface DeviceLink {
    data object Connected : DeviceLink

    data object Connecting : DeviceLink

    data object Offline : DeviceLink

    data object None : DeviceLink

    data class Failed(
        val reason: String,
    ) : DeviceLink

    companion object {
        fun of(
            state: DeviceConnectionState,
            hasPaired: Boolean,
        ): DeviceLink =
            when (state) {
                DeviceConnectionState.Connected -> Connected
                DeviceConnectionState.Connecting -> Connecting
                DeviceConnectionState.Scanning -> if (hasPaired) Connecting else None
                DeviceConnectionState.Disconnected -> if (hasPaired) Offline else None
                is DeviceConnectionState.Failed -> Failed(state.reason)
            }
    }
}
