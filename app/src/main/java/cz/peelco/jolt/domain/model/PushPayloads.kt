package cz.peelco.jolt.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * An incoming poke, self-contained (sender name and the exact stimulus) so
 * handling it never needs a round trip first. See `PokePushPayload` in the
 * server contract.
 */
@Serializable
data class PokePushPayload(
    @SerialName("pokeID") val pokeId: SerialUuid,
    val senderHandle: String,
    val senderDisplayName: String,
    /**
     * Who the poke is addressed to. Optional: an absent addressee means
     * "can't tell", which must never be read as "not mine".
     */
    val recipientHandle: String? = null,
    val stimulus: StimulusConfig,
    /** When the server accepted it. Optional on older servers. */
    val sentAt: SerialInstant? = null,
    /** Sent by the sender's scripts. Optional on older servers. */
    val viaApiToken: Boolean? = null,
    /** The relay-era addition: the server this came from. */
    val serverId: String? = null,
)

/**
 * A diagnostic push from Settings → Notifications or the web dashboard. No
 * poke stands behind it, so it is acked to `/devices/test-push/{id}/ack`.
 */
@Serializable
data class TestPushPayload(
    @SerialName("testID") val testId: SerialUuid,
    /** Which of the account's devices this copy went to; echoed in the ack. */
    @SerialName("deviceID") val deviceId: SerialUuid,
    /** Absent for a notification-only test. */
    val stimulus: StimulusConfig? = null,
    val sentAt: SerialInstant? = null,
    val serverId: String? = null,
)

/** Which entry point saw a test push, reported in the ack. */
@Serializable
enum class TestPushPath {
    /** The user tapped the notification. */
    @SerialName("alert")
    ALERT,

    /** Handled with no UI in front: on Android, the FCM data message. */
    @SerialName("background")
    BACKGROUND,

    /** Handled while the app was open. */
    @SerialName("foreground")
    FOREGROUND,
}

/** A decoded push body: the plaintext of an envelope, minus `aps`. */
sealed interface IncomingPush {
    data class Poke(
        val payload: PokePushPayload,
    ) : IncomingPush

    data class Test(
        val payload: TestPushPayload,
    ) : IncomingPush

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses `{"type":"poke","poke":{…}}` or `{"type":"test","test":{…}}`.
         * Null for anything else, including a payload that doesn't decode.
         */
        fun parse(body: JsonObject): IncomingPush? =
            runCatching {
                when (body["type"]?.jsonPrimitive?.contentOrNull) {
                    "poke" -> Poke(json.decodeFromJsonElement<PokePushPayload>(body.getValue("poke")))
                    "test" -> Test(json.decodeFromJsonElement<TestPushPayload>(body.getValue("test")))
                    else -> null
                }
            }.getOrNull()
    }
}
