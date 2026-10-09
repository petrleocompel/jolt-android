package cz.peelco.jolt.ble

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ConnectionWaiterTest {
    @Test
    fun anExistingConnectionIsReturnedWithoutReconnecting() =
        runTest {
            var reconnects = 0
            val waiter = ConnectionWaiter(4.seconds, { true }, { "link" }, { reconnects++ }, sleep = {})
            assertThat(waiter.connection()).isEqualTo("link")
            assertThat(reconnects).isEqualTo(0)
        }

    @Test
    fun anUnpairedPhoneFailsAtOnce() =
        runTest {
            var reconnects = 0
            val waiter = ConnectionWaiter<String>(4.seconds, { false }, { null }, { reconnects++ }, sleep = { error("must not wait") })
            assertThat(waiter.connection()).isNull()
            assertThat(reconnects).isEqualTo(0)
        }

    @Test
    fun aLinkThatComesBackWithinTheBudgetIsUsed() =
        runTest {
            var polls = 0
            val waiter = ConnectionWaiter(4.seconds, { true }, { if (polls >= 3) "link" else null }, {}, sleep = { polls++ })
            assertThat(waiter.connection()).isEqualTo("link")
            assertThat(polls).isEqualTo(3)
        }

    @Test
    fun theWaitIsBounded() =
        runTest {
            var polls = 0
            val waiter = ConnectionWaiter<String>(1.seconds, { true }, { null }, {}, sleep = { polls++ })
            assertThat(waiter.connection()).isNull()
            assertThat(polls).isEqualTo(10)
        }
}
