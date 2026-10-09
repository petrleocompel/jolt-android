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
    /** Kept as sent, so a transport this build doesn't know is not a decode failure (C18). */
    @SerialName("transport") val transportName: String,
    val apnsEnvironment: String? = null,
    val relay: RelayInfo? = null,
) {
    constructor(transport: PushTransport, relay: RelayInfo? = null) : this(transport.wireName, null, relay)

    /** Null for a transport this build doesn't know. */
    val transport: PushTransport? get() = PushTransport.entries.firstOrNull { it.wireName == transportName }

    @Serializable
    data class RelayInfo(
        val url: String,
        val serverId: String,
    )
}

enum class PushTransport(
    val wireName: String,
) {
    /** The server has its own APNs credentials: iOS only. */
    APNS("apns"),

    /** Pushes go through jolt-relay; the only way to reach Android. */
    RELAY("relay"),

    /** Push is not configured on this server. */
    NONE("none"),
}
