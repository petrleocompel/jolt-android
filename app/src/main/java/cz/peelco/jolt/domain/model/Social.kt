package cz.peelco.jolt.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: SerialUuid,
    /** Unique, lowercase, no `@`; the UI adds it for display. */
    val handle: String,
    val displayName: String,
    val email: String,
)

@Serializable
data class Friend(
    val id: SerialUuid,
    val handle: String,
    val displayName: String,
    /** What THEY allow ME to send them; drives the poke composer. */
    val permissionsGrantedToMe: FriendPermissionSet,
    /** What I allow THEM to send me; mine to edit. */
    val permissionsIGranted: FriendPermissionSet,
)

@Serializable
enum class FriendRequestDirection {
    @SerialName("incoming")
    INCOMING,

    @SerialName("outgoing")
    OUTGOING,
}

@Serializable
data class FriendRequest(
    val id: SerialUuid,
    val handle: String,
    val displayName: String,
    val direction: FriendRequestDirection,
    val createdAt: SerialInstant,
)

@Serializable
enum class PokeDirection {
    @SerialName("sent")
    SENT,

    @SerialName("received")
    RECEIVED,
}

@Serializable
enum class PokeDeliveryStatus(
    val title: String,
) {
    /**
     * Accepted and pushed, no device has acked yet. Every other value is
     * terminal and only ever set by the recipient's ack.
     */
    @SerialName("pending")
    PENDING("Pending"),

    /** Reached the device and fired. */
    @SerialName("fired")
    FIRED("Fired"),

    /** The push arrived but the recipient's device wasn't connected. */
    @SerialName("deviceNotConnected")
    DEVICE_NOT_CONNECTED("Device not connected"),

    /** The receiving side refused it on a permission re-check. */
    @SerialName("notAllowed")
    NOT_ALLOWED("Not allowed"),

    /** The recipient had "Do not disturb incoming pokes" on. */
    @SerialName("muted")
    MUTED("Muted"),
}

@Serializable
data class PokeEvent(
    val id: SerialUuid,
    val direction: PokeDirection,
    val friendHandle: String,
    val friendDisplayName: String,
    val stimulus: StimulusConfig,
    val status: PokeDeliveryStatus,
    val createdAt: SerialInstant,
    /** When the recipient reported back. Optional so an older server still decodes. */
    val ackedAt: SerialInstant? = null,
    /** Sent by the sender's scripts. Optional for the same reason; read [isAutomated]. */
    val viaApiToken: Boolean? = null,
    /** The token's name; only ever present for the sender's own view. */
    val apiTokenName: String? = null,
) {
    /** How long the poke took to be confirmed, in milliseconds. */
    val timeToAckMillis: Long? get() = ackedAt?.let { it.toEpochMilli() - createdAt.toEpochMilli() }

    val isAutomated: Boolean get() = viaApiToken ?: false

    /** "You zapped Alice" / "Alice buzzed you". */
    val sentence: String
        get() =
            when (direction) {
                PokeDirection.SENT -> "You ${stimulus.kind.pastTenseVerb} $friendDisplayName"
                PokeDirection.RECEIVED -> "$friendDisplayName ${stimulus.kind.pastTenseVerb} you"
            }
}

/** The signed-in profile from `GET /me`, signup and login. */
@Serializable
data class Me(
    val id: SerialUuid,
    val handle: String,
    val displayName: String,
    val email: String,
    val inviteCode: String,
    /** Optional: a server that predates it sends none, which must not fail sign-in. */
    val policies: ServerPolicies? = null,
) {
    val user: User get() = User(id, handle, displayName, email)
}
