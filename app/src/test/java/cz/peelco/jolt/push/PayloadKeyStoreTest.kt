package cz.peelco.jolt.push

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.data.secure.InMemorySecretStore
import org.junit.Test

class PayloadKeyStoreTest {
    private var now = 1_000_000L
    private val store = PayloadKeyStore(InMemorySecretStore()) { now }
    private val server = "srv_x"

    @Test
    fun thePreviousKeyOpensPushesForTwentyFourHoursOnly() {
        val first = store.rotate(server)
        val second = store.rotate(server)
        assertThat(store.find(server, EnvelopeCrypto.kid(second))).isEqualTo(second)
        assertThat(store.find(server, EnvelopeCrypto.kid(first))).isEqualTo(first)

        now += PayloadKeyStore.PREVIOUS_KEY_LIFETIME_MILLIS - 1
        assertThat(store.find(server, EnvelopeCrypto.kid(first))).isEqualTo(first)
        now += 1
        assertThat(store.find(server, EnvelopeCrypto.kid(first))).isNull()
        // The current key never expires.
        assertThat(store.find(server, EnvelopeCrypto.kid(second))).isEqualTo(second)
    }

    @Test
    fun onlyTheImmediatelyPreviousKeyIsKept() {
        val first = store.rotate(server)
        store.rotate(server)
        store.rotate(server)
        assertThat(store.find(server, EnvelopeCrypto.kid(first))).isNull()
    }

    @Test
    fun aDiscardedKeyIsGoneAndOthersStay() {
        val first = store.rotate(server)
        val second = store.rotate(server)
        store.discard(server, EnvelopeCrypto.kid(second))
        assertThat(store.find(server, EnvelopeCrypto.kid(second))).isNull()
        assertThat(store.find(server, EnvelopeCrypto.kid(first))).isEqualTo(first)
    }

    @Test
    fun keysAreKeptPerServer() {
        val key = store.rotate(server)
        assertThat(store.find("srv_other", EnvelopeCrypto.kid(key))).isNull()
        store.forget(server)
        assertThat(store.find(server, EnvelopeCrypto.kid(key))).isNull()
    }
}
