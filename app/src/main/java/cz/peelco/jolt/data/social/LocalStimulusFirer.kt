package cz.peelco.jolt.data.social

import cz.peelco.jolt.domain.model.PokeDeliveryStatus
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The do-not-disturb → fire → status logic shared by both backends' poke and
 * test-push paths, so neither can diverge from it.
 */
class LocalStimulusFirer(
    private val deviceRepository: DeviceRepository,
    private val scope: CoroutineScope,
    private val isDoNotDisturb: () -> Boolean,
) {
    private val mutex = Mutex()

    /**
     * Outcomes worth remembering: the stimulus reached the wearable, or do not
     * disturb swallowed it. Replaying either would be a second shock the
     * sender never sent. A failed attempt is deliberately not remembered.
     */
    private val settled = mutableMapOf<UUID, PokeDeliveryStatus>()

    /** Attempts in flight, so two arrivals of one poke share one BLE write. */
    private val inFlight = mutableMapOf<UUID, Deferred<PokeDeliveryStatus>>()

    /**
     * Fires [stimulus] for [id] at most once successfully. A push can reach
     * the app more than once (a retry from the relay, a notification tap
     * after the data message), and the second call must never shock twice.
     *
     * Only a delivered outcome is remembered: a data message that arrives
     * before the link is back fails with `deviceNotConnected` through no fault
     * of the wearable, and the next arrival must be free to try again.
     */
    suspend fun fire(
        id: UUID,
        stimulus: StimulusConfig,
    ): PokeDeliveryStatus {
        val attempt =
            mutex.withLock {
                settled[id]?.let { return it }
                inFlight.getOrPut(id) {
                    // Lazy, so it starts in await() below, outside the lock.
                    scope.async(start = CoroutineStart.LAZY) {
                        val status = deliver(stimulus)
                        mutex.withLock {
                            inFlight.remove(id)
                            if (status == PokeDeliveryStatus.FIRED || status == PokeDeliveryStatus.MUTED) settled[id] = status
                        }
                        status
                    }
                }
            }
        return attempt.await()
    }

    private suspend fun deliver(stimulus: StimulusConfig): PokeDeliveryStatus {
        if (isDoNotDisturb()) return PokeDeliveryStatus.MUTED
        return try {
            deviceRepository.fire(stimulus)
            PokeDeliveryStatus.FIRED
        } catch (_: Exception) {
            PokeDeliveryStatus.DEVICE_NOT_CONNECTED
        }
    }
}
