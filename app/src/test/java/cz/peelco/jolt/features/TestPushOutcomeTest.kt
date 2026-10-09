package cz.peelco.jolt.features

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.domain.model.TestPushStatus
import cz.peelco.jolt.features.settings.NotificationTestViewModel
import cz.peelco.jolt.features.settings.NotificationTestViewModel.Outcome
import org.junit.Test
import java.time.Instant
import java.util.UUID

class TestPushOutcomeTest {
    private fun status(
        apnsConfigured: Boolean,
        pushTransport: String?,
    ) = TestPushStatus(UUID.randomUUID(), Instant.now(), apnsConfigured = apnsConfigured, pushTransport = pushTransport)

    @Test
    fun pushTransportDecidesWhetherTheServerCanDeliver() {
        assertThat(NotificationTestViewModel.outcomeOf(status(apnsConfigured = false, pushTransport = "relay"), isWaiting = true)).isEqualTo(Outcome.Waiting)
        assertThat(NotificationTestViewModel.outcomeOf(status(apnsConfigured = true, pushTransport = "none"), isWaiting = true)).isEqualTo(Outcome.NotConfigured)
    }

    @Test
    fun anOlderServerIsReadFromApnsConfigured() {
        assertThat(NotificationTestViewModel.outcomeOf(status(apnsConfigured = false, pushTransport = null), isWaiting = true)).isEqualTo(Outcome.NotConfigured)
        assertThat(NotificationTestViewModel.outcomeOf(status(apnsConfigured = true, pushTransport = null), isWaiting = false)).isEqualTo(Outcome.NoConfirmation)
        assertThat(NotificationTestViewModel.outcomeOf(null, isWaiting = false)).isNull()
    }
}
