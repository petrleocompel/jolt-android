package cz.peelco.jolt.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a personal access token may do. */
@Serializable
enum class ApiTokenScope(
    val title: String,
    val description: String,
) {
    @SerialName("*")
    ALL("Everything", "Every scope, including ones added later"),

    @SerialName("stimulus:self")
    STIMULUS_SELF("Fire at me", "Fire stimuli at your own devices"),

    @SerialName("pokes:send")
    POKES_SEND("Send pokes", "Poke your friends"),

    @SerialName("friends:read")
    FRIENDS_READ("Read friends", "List your friends"),

    @SerialName("pokes:read")
    POKES_READ("Read activity", "Read your poke activity log"),
}

@Serializable
enum class ApiTokenFriendScope {
    @SerialName("all")
    ALL,

    @SerialName("selected")
    SELECTED,
}

/** A token as listed back to its owner; the secret is never shown again. */
@Serializable
data class ApiToken(
    val id: SerialUuid,
    val name: String,
    val prefix: String,
    val createdAt: SerialInstant,
    val lastUsedAt: SerialInstant? = null,
    val expiresAt: SerialInstant? = null,
    val scopes: List<ApiTokenScope> = emptyList(),
    val friendScope: ApiTokenFriendScope = ApiTokenFriendScope.ALL,
    val friendIds: List<SerialUuid> = emptyList(),
    val allowedKinds: List<StimulusKind>? = null,
    val maxIntensity: Int? = null,
    val minIntervalSeconds: Int = 1,
    /** Only in the answer to minting it. */
    val token: String? = null,
)

/** The body of `POST /me/tokens`. Strict server-side: no unknown keys. */
@Serializable
data class ApiTokenDraft(
    val name: String,
    val scopes: List<ApiTokenScope>,
    val expiresInDays: Int? = null,
    /** Present (even empty) makes the token `selected`; absent reaches everyone. */
    val friendIds: List<SerialUuid>? = null,
    val allowedKinds: List<StimulusKind>? = null,
    val maxIntensity: Int? = null,
    val minIntervalSeconds: Int? = null,
)
