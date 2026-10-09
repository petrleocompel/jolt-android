package cz.peelco.jolt.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A friend on a Pavlok account. Separate from [Friend]: different ids,
 * permissions and capabilities, kept in a separate section so it is never
 * ambiguous which account a poke goes through.
 */
data class PavlokFriend(
    /** Pavlok's numeric user id, the path of `/pokes/send/user/{id}`. */
    val id: Int,
    val firstName: String? = null,
    val lastName: String? = null,
    val username: String? = null,
    val profilePictureUrl: String? = null,
) {
    val displayName: String
        get() {
            val full = listOfNotNull(firstName, lastName).joinToString(" ").trim()
            return when {
                full.isNotEmpty() -> full
                !username.isNullOrEmpty() -> username
                else -> "Pavlok user $id"
            }
        }
}

/** What a friend allows me to send them. `canChime` is Pavlok's name for beep. */
data class PavlokPokePermission(
    val friendId: Int,
    val canVibrate: Boolean,
    val canChime: Boolean,
    val canZap: Boolean,
    val maxZapValue: Int,
) {
    fun allows(kind: StimulusKind): Boolean =
        when (kind) {
            StimulusKind.ZAP -> canZap
            StimulusKind.VIBE -> canVibrate
            StimulusKind.BEEP -> canChime
        }

    /** Only zap is capped by the API; vibe and beep are all-or-nothing. */
    fun maxIntensity(kind: StimulusKind): Int =
        when (kind) {
            StimulusKind.ZAP -> maxZapValue.coerceIn(0, 100)
            StimulusKind.VIBE, StimulusKind.BEEP -> 100
        }

    companion object {
        val NONE = PavlokPokePermission(0, canVibrate = false, canChime = false, canZap = false, maxZapValue = 0)
    }
}

/**
 * One entry from the wearable's uploaded log. There is no sender: it records
 * that the device fired, not who caused it.
 */
data class PavlokStimulusLogEntry(
    val id: String,
    val kind: StimulusKind?,
    val rawName: String,
    val timestamp: Instant,
)

@Serializable
data class PavlokAccount(
    val userId: Int,
    val email: String,
    val firstName: String? = null,
    val lastName: String? = null,
) {
    val displayName: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ").trim().ifEmpty { email }
}
