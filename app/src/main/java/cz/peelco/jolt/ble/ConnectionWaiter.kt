package cz.peelco.jolt.ble

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Waits a bounded time for a dropped link to come back before a stimulus is
 * given up on. A push that carries a poke routinely arrives before the link
 * is back; failing at once would report a wearable sitting on the wrist as
 * not connected. An unpaired phone has nothing to wait for and fails at once.
 *
 * Pure and injectable, so the policy is testable in microseconds.
 */
class ConnectionWaiter<C : Any>(
    private val budget: Duration,
    private val isPaired: () -> Boolean,
    private val currentConnection: () -> C?,
    private val startReconnect: () -> Unit,
    private val pollInterval: Duration = 100.milliseconds,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    suspend fun connection(): C? {
        currentConnection()?.let { return it }
        if (!isPaired()) return null
        startReconnect()
        var waited = Duration.ZERO
        while (waited < budget) {
            sleep(pollInterval)
            waited += pollInterval
            currentConnection()?.let { return it }
        }
        return null
    }
}
