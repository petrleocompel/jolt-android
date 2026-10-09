package cz.peelco.jolt.domain

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.domain.model.Friend
import cz.peelco.jolt.domain.model.IncomingPush
import cz.peelco.jolt.domain.model.PokeEvent
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.TestPushStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.util.UUID

class PayloadDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aPokePayloadDecodesWithAndWithoutOptionalFields() {
        val full =
            json.parseToJsonElement(
                """{"type":"poke","poke":{"pokeID":"3fa85f64-5717-4562-b3fc-2c963f66afa6","senderHandle":"alice",
                "senderDisplayName":"Alice","recipientHandle":"bob","stimulus":{"kind":"zap","intensity":30,"repetitions":1},
                "sentAt":"2026-09-21T14:32:00Z","viaApiToken":true,"serverId":"srv_x"}}""",
            ).jsonObject
        val poke = (IncomingPush.parse(full) as IncomingPush.Poke).payload
        assertThat(poke.pokeId).isEqualTo(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
        assertThat(poke.recipientHandle).isEqualTo("bob")
        assertThat(poke.viaApiToken).isTrue()
        assertThat(poke.stimulus.kind).isEqualTo(StimulusKind.ZAP)

        val minimal =
            json.parseToJsonElement(
                """{"type":"poke","poke":{"pokeID":"3fa85f64-5717-4562-b3fc-2c963f66afa6","senderHandle":"alice",
                "senderDisplayName":"Alice","stimulus":{"kind":"vibe","intensity":10,"repetitions":2}}}""",
            ).jsonObject
        val older = (IncomingPush.parse(minimal) as IncomingPush.Poke).payload
        assertThat(older.recipientHandle).isNull()
        assertThat(older.viaApiToken).isNull()
    }

    @Test
    fun aTestPayloadDecodesWithoutAStimulus() {
        val body =
            json.parseToJsonElement(
                """{"type":"test","test":{"testID":"7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "deviceID":"3fa85f64-5717-4562-b3fc-2c963f66afa6","sentAt":"2026-09-07T10:15:00Z","source":"web"}}""",
            ).jsonObject
        val test = (IncomingPush.parse(body) as IncomingPush.Test).payload
        assertThat(test.stimulus).isNull()
        assertThat(test.deviceId.toString()).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    }

    @Test
    fun unknownTypesAndBrokenPayloadsAreNotPushes() {
        assertThat(IncomingPush.parse(json.parseToJsonElement("""{"type":"other"}""").jsonObject)).isNull()
        assertThat(IncomingPush.parse(json.parseToJsonElement("""{"type":"poke","poke":{"pokeID":"nope"}}""").jsonObject)).isNull()
    }

    @Test
    fun automationConsentFieldsAreOptional() {
        val permission = """{"isAllowed":true,"maxIntensity":40,"cooldownSeconds":60}"""
        val withConsent =
            """{"isAllowed":true,"maxIntensity":40,"cooldownSeconds":60,"automationAllowed":null,"automationAllowedEffective":false}"""
        val friend =
            """{"id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","handle":"alice","displayName":"Alice",
            "permissionsGrantedToMe":{"zap":$permission,"vibe":$permission,"beep":$permission},
            "permissionsIGranted":{"zap":$withConsent,"vibe":$withConsent,"beep":$withConsent}}"""
        val decoded = json.decodeFromString(Friend.serializer(), friend)
        assertThat(decoded.permissionsGrantedToMe.zap.supportsAutomationConsent).isFalse()
        assertThat(decoded.permissionsIGranted.zap.supportsAutomationConsent).isTrue()
        assertThat(decoded.permissionsIGranted.zap.automationAllowed).isNull()
        assertThat(decoded.permissionsIGranted.zap.automationAllowedEffective).isFalse()
    }

    @Test
    fun pokeEventsToleratePredatingServersAndFractionalDates() {
        val event =
            """{"id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","direction":"received","friendHandle":"alice",
            "friendDisplayName":"Alice","stimulus":{"kind":"beep","intensity":60,"repetitions":1},"status":"pending",
            "createdAt":"2026-09-21T14:32:00.123Z"}"""
        val decoded = json.decodeFromString(PokeEvent.serializer(), event)
        assertThat(decoded.isAutomated).isFalse()
        assertThat(decoded.ackedAt).isNull()
        assertThat(decoded.sentence).isEqualTo("Alice beeped you")
    }

    @Test
    fun testPushStatusDecodesTheServerShape() {
        val status =
            """{"testID":"7c9e6679-7425-40de-944b-e07fc1f90ae7","sentAt":"2026-09-07T10:15:00+00:00","source":"app",
            "apnsConfigured":true,"devices":[{"deviceId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","ok":false,"reason":"unregistered"}],
            "acks":[{"deviceId":null,"path":"background","status":"fired","receivedAt":"2026-09-07T10:15:01Z","elapsedMs":1200}]}"""
        val decoded = json.decodeFromString(TestPushStatus.serializer(), status)
        assertThat(decoded.devices.single().isAccepted).isFalse()
        assertThat(decoded.acks.single().elapsedMs).isEqualTo(1200)
    }
}
