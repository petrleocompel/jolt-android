package cz.peelco.jolt.domain

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.domain.model.DeviceButtonSlot
import cz.peelco.jolt.domain.model.DeviceEvent
import cz.peelco.jolt.domain.model.PokeTrigger
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.UUID

class PokeTriggerTest {
    private val events = "00002002-0000-1000-8000-00805F9B34FB"

    private fun event(
        vararg data: Int,
        characteristic: String = events,
    ) = DeviceEvent("156E2000-A300-4FEA-897B-86F698D74461", characteristic, ByteArray(data.size) { data[it].toByte() })

    private val learned =
        PokeTrigger(
            isEnabled = true,
            targetFriendId = UUID.randomUUID(),
            learnedCharacteristicUuid = events,
            learnedBytesHex = "0C 01 5A",
        )

    @Test
    fun exactMatchRequiresIdenticalBytesAndCharacteristic() {
        assertThat(learned.matches(event(0x0C, 0x01, 0x5A))).isTrue()
        assertThat(learned.matches(event(0x0C, 0x01, 0x5B))).isFalse()
        assertThat(learned.matches(event(0x0C, 0x01, 0x5A, characteristic = "00002009-0000-1000-8000-00805F9B34FB"))).isFalse()
    }

    @Test
    fun characteristicComparisonIsCaseInsensitive() {
        assertThat(learned.matches(event(0x0C, 0x01, 0x5A, characteristic = events.lowercase()))).isTrue()
    }

    @Test
    fun prefixModeToleratesAChangingTail() {
        val tolerant = learned.copy(matchMode = PokeTrigger.MatchMode.PREFIX)
        assertThat(tolerant.matches(event(0x0C, 0x00, 0x00))).isTrue()
        assertThat(tolerant.matches(event(0x0D, 0x01, 0x5A))).isFalse()
    }

    @Test
    fun configuredPressMatchesRegardlessOfTrailingBytesAndWinsOverALearnedSignature() {
        val trigger = learned.copy(buttonSlot = DeviceButtonSlot.TOP_LONG, learnedBytesHex = "09 09 09")
        assertThat(trigger.matches(event(0x0C, 0x01, 0x22))).isTrue()
        assertThat(trigger.matches(event(0x09, 0x09, 0x09))).isFalse()
    }

    @Test
    fun onlyTheEventsCharacteristicDecodes() {
        assertThat(event(0x0C, 0x01, characteristic = "00002009-0000-1000-8000-00805F9B34FB").isFindMyPhoneEvent).isFalse()
        assertThat(event(0x0C, 0x01, characteristic = "156E2002-A300-4FEA-897B-86F698D74461").isFindMyPhoneEvent).isTrue()
    }

    @Test
    fun armingNeedsEnabledFriendAndAGesture() {
        assertThat(PokeTrigger.DEFAULT.isArmed).isFalse()
        assertThat(learned.isArmed).isTrue()
        assertThat(learned.copy(isEnabled = false).isArmed).isFalse()
        assertThat(learned.copy(targetFriendId = null).isArmed).isFalse()
        assertThat(learned.copy(learnedBytesHex = null).isArmed).isFalse()
        assertThat(learned.copy(learnedBytesHex = null, buttonSlot = DeviceButtonSlot.TOP).isArmed).isTrue()
    }

    @Test
    fun triggerSurvivesAJsonRoundTrip() {
        val trigger = learned.copy(buttonSlot = DeviceButtonSlot.MIDDLE, matchMode = PokeTrigger.MatchMode.PREFIX)
        val json = Json.encodeToString(PokeTrigger.serializer(), trigger)
        assertThat(Json.decodeFromString(PokeTrigger.serializer(), json)).isEqualTo(trigger)
    }
}
