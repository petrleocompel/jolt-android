package cz.peelco.jolt.features.shared

import cz.peelco.jolt.ble.BleLog
import cz.peelco.jolt.data.store.JsonValueStore
import cz.peelco.jolt.domain.model.ButtonAction
import cz.peelco.jolt.domain.model.ButtonConfig
import cz.peelco.jolt.domain.model.DeviceButtonSlot
import cz.peelco.jolt.domain.model.DeviceEvent
import cz.peelco.jolt.domain.model.PokeTrigger
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.toHexString
import cz.peelco.jolt.domain.repository.DeviceRepository
import cz.peelco.jolt.domain.repository.PokeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * "Poke a friend from your Pavlok": while a wearable is connected and the
 * trigger is on, watches the device's notifications and, when the configured
 * press is announced, sends a poke through the normal path. Also drives the
 * "learn a gesture" capture in Settings.
 *
 * Runs for the app's lifetime and subscribes only while it has a reason to,
 * so it costs nothing with no device connected.
 */
class PokeTriggerService(
    private val store: JsonValueStore<PokeTrigger>,
    private val device: DeviceRepository,
    private val pokes: PokeRepository,
    private val scope: CoroutineScope,
    private val retryDelayMillis: Long = 2_000,
    private val now: () -> Instant = Instant::now,
) {
    val trigger: StateFlow<PokeTrigger> = store.flow

    data class Status(
        val isDeviceConnected: Boolean = false,
        val isListening: Boolean = false,
        val isLearning: Boolean = false,
        val learnCandidate: DeviceEvent? = null,
        val lastPokeSentAt: Instant? = null,
        val lastError: String? = null,
        val lastButtonConfigNote: String? = null,
        val isWritingButtonConfig: Boolean = false,
        /** Newest first, so "did the press even reach the phone?" has an answer. */
        val recentEvents: List<DeviceEvent> = emptyList(),
    )

    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state.asStateFlow()

    private var connectionJob: Job? = null
    private var eventJob: Job? = null
    private var eventLoopGeneration = 0
    private var eventLoopFailures = 0
    private var lastFiredAt: Instant? = null

    /** Begins observing the connection; idempotent. */
    fun start() {
        if (connectionJob != null) return
        connectionJob =
            scope.launch {
                device.connectedDevice.collect { connected ->
                    state.update { it.copy(isDeviceConnected = connected != null) }
                    reconcile()
                }
            }
    }

    private fun update(transform: (PokeTrigger) -> PokeTrigger) {
        store.update(transform)
        reconcile()
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(isEnabled = enabled) }

    fun setTarget(
        friendId: UUID,
        name: String,
    ) = update { it.copy(targetFriendId = friendId, targetFriendName = name) }

    fun clearTarget() = update { it.copy(targetFriendId = null, targetFriendName = null) }

    fun setStimulus(stimulus: StimulusConfig) = update { it.copy(stimulus = stimulus) }

    /** Chooses the poke button; clears a learned signature, its alternative. */
    fun setButtonSlot(slot: DeviceButtonSlot?) =
        update { if (slot != null) it.copy(buttonSlot = slot, learnedCharacteristicUuid = null, learnedBytesHex = null) else it.copy(buttonSlot = null) }

    /**
     * Sets the chosen button to find-my-phone, the one phone-side action with
     * a recovered payload, so its press reaches the phone at all; then reads
     * the configuration back, because an acknowledged write only says the
     * bytes were accepted.
     */
    suspend fun makeButtonReportPresses() {
        val slot =
            trigger.value.buttonSlot ?: run {
                state.update { it.copy(lastButtonConfigNote = "Pick a button first.") }
                return
            }
        state.update { it.copy(isWritingButtonConfig = true) }
        try {
            device.setButtonConfig(ButtonConfig(slot, ButtonAction.FIND_MY_PHONE))
            val report = runCatching { device.readButtonConfig() }.getOrNull()
            when (val action = report?.actions?.get(slot)) {
                ButtonAction.FIND_MY_PHONE ->
                    state.update { it.copy(lastButtonConfigNote = "${slot.displayName} is set to report presses — press it to send a poke.", lastError = null) }
                null ->
                    state.update {
                        it.copy(
                            lastButtonConfigNote =
                                "${slot.displayName} accepted by the device — press it to send a poke. " +
                                    "(The device did not report its configuration back, so this is unconfirmed.)",
                            lastError = null,
                        )
                    }
                else ->
                    state.update {
                        it.copy(
                            lastButtonConfigNote = null,
                            lastError = "The device accepted the write but ${slot.displayName} still reads as \"${action.displayName}\". It will not send a poke.",
                        )
                    }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { it.copy(lastButtonConfigNote = null, lastError = error.message) }
        } finally {
            state.update { it.copy(isWritingButtonConfig = false) }
        }
    }

    fun startLearning() {
        state.update { it.copy(learnCandidate = null, isLearning = true, lastError = null) }
        reconcile()
    }

    fun cancelLearning() {
        state.update { it.copy(isLearning = false, learnCandidate = null) }
        reconcile()
    }

    fun confirmLearn(matchMode: PokeTrigger.MatchMode = PokeTrigger.MatchMode.EXACT) {
        val candidate = state.value.learnCandidate ?: return
        state.update { it.copy(isLearning = false, learnCandidate = null) }
        update {
            it.copy(
                buttonSlot = null,
                learnedCharacteristicUuid = candidate.characteristicUuid,
                learnedBytesHex = candidate.data.toHexString(),
                matchMode = matchMode,
            )
        }
    }

    fun clearLearnedGesture() = update { it.copy(learnedCharacteristicUuid = null, learnedBytesHex = null) }

    fun setMatchMode(mode: PokeTrigger.MatchMode) = update { it.copy(matchMode = mode) }

    /** Starts or stops the event feed to match connection and configuration. */
    private fun reconcile() {
        val current = state.value
        val shouldListen = current.isDeviceConnected && (trigger.value.isEnabled || current.isLearning)
        if (shouldListen) startEventLoop() else stopEventLoop()
    }

    private fun startEventLoop() {
        if (eventJob != null) return
        eventLoopGeneration++
        val generation = eventLoopGeneration
        eventJob =
            scope.launch {
                var failed = false
                try {
                    state.update { it.copy(isListening = true) }
                    device.deviceEvents().collect { event ->
                        eventLoopFailures = 0
                        handle(event)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    state.update { it.copy(lastError = error.message) }
                    BleLog.error("Poke trigger event feed failed: ${error.message}")
                }
                // Only the current loop may clear the slot, or a loop ending
                // right after a replacement started would erase it.
                if (eventLoopGeneration != generation) return@launch
                state.update { it.copy(isListening = false) }
                eventJob = null
                // The feed also ends without an error when the link drops;
                // either way the loop must be restartable.
                if (failed) {
                    eventLoopFailures++
                    if (eventLoopFailures > MAX_EVENT_LOOP_RETRIES) {
                        BleLog.error("Poke trigger giving up after $eventLoopFailures failed attempts")
                        return@launch
                    }
                    delay(retryDelayMillis)
                }
                reconcile()
            }
    }

    private fun stopEventLoop() {
        eventJob?.cancel()
        eventJob = null
        eventLoopFailures = 0
        state.update { it.copy(isListening = false) }
    }

    private fun handle(event: DeviceEvent) {
        state.update { it.copy(recentEvents = (listOf(event) + it.recentEvents).take(RECENT_EVENT_LIMIT)) }
        if (state.value.isLearning) {
            state.update { it.copy(learnCandidate = event) }
            return
        }
        val current = trigger.value
        if (!current.isArmed || !current.matches(event)) return
        val last = lastFiredAt
        val time = now()
        if (last != null && (time.toEpochMilli() - last.toEpochMilli()) < current.debounceSeconds * 1000) return
        lastFiredAt = time
        fire(current)
    }

    private fun fire(current: PokeTrigger) {
        val friendId = current.targetFriendId ?: return
        scope.launch {
            try {
                pokes.sendPoke(friendId, current.stimulus)
                state.update { it.copy(lastPokeSentAt = now(), lastError = null) }
                BleLog.info("Poke trigger fired → poked ${current.targetFriendName ?: friendId}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(lastError = error.message) }
                BleLog.error("Poke trigger failed to send: ${error.message}")
            }
        }
    }

    companion object {
        const val RECENT_EVENT_LIMIT = 12
        private const val MAX_EVENT_LOOP_RETRIES = 5
    }
}
