package cz.peelco.jolt.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One phone registered to the account, as `GET /devices` reports it. */
@Serializable
data class RegisteredDevice(
    val id: SerialUuid,
    val platform: String,
    /** The tail of the push token, enough for this phone to spot itself. */
    val tokenSuffix: String,
    /** False once the push provider said the token is dead. */
    val isActive: Boolean,
    val createdAt: SerialInstant,
    val lastSeenAt: SerialInstant,
)

/** A device confirming a test push arrived. */
@Serializable
data class TestPushAck(
    @SerialName("deviceId") val deviceId: SerialUuid? = null,
    val path: TestPushPath,
    val status: PokeDeliveryStatus? = null,
    val receivedAt: SerialInstant,
    val elapsedMs: Long,
)

/** Per-device outcome as the provider reported it: accepted, not yet received. */
@Serializable
data class TestPushDeviceResult(
    @SerialName("deviceId") val deviceId: SerialUuid,
    @SerialName("ok") val isAccepted: Boolean,
    val reason: String? = null,
    val detail: String? = null,
)

/** Live state of one test push; the server forgets it after ten minutes. */
@Serializable
data class TestPushStatus(
    @SerialName("testID") val testId: SerialUuid,
    val sentAt: SerialInstant,
    val stimulus: StimulusConfig? = null,
    /** False when the server can't send pushes and is only logging them. */
    val apnsConfigured: Boolean = true,
    val devices: List<TestPushDeviceResult> = emptyList(),
    val acks: List<TestPushAck> = emptyList(),
)

/** `GET /push/config`: how this server delivers pushes. */
@Serializable
data class PushConfig(
    val transport: PushTransport,
    val apnsEnvironment: String? = null,
    val relay: RelayInfo? = null,
) {
    @Serializable
    data class RelayInfo(
        val url: String,
        val serverId: String,
    )
}

@Serializable
enum class PushTransport {
    /** The server has its own APNs credentials: iOS only. */
    @SerialName("apns")
    APNS,

    /** Pushes go through jolt-relay; the only way to reach Android. */
    @SerialName("relay")
    RELAY,

    /** Push is not configured on this server. */
    @SerialName("none")
    NONE,
}
