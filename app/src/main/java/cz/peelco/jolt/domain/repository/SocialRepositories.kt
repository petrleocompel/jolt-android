package cz.peelco.jolt.domain.repository

import cz.peelco.jolt.domain.model.ApiToken
import cz.peelco.jolt.domain.model.ApiTokenDraft
import cz.peelco.jolt.domain.model.Friend
import cz.peelco.jolt.domain.model.FriendRequest
import cz.peelco.jolt.domain.model.PokeDeliveryStatus
import cz.peelco.jolt.domain.model.PokeEvent
import cz.peelco.jolt.domain.model.PokePushPayload
import cz.peelco.jolt.domain.model.RegisteredDevice
import cz.peelco.jolt.domain.model.ServerPolicies
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.StimulusPermission
import cz.peelco.jolt.domain.model.TestPushPath
import cz.peelco.jolt.domain.model.TestPushPayload
import cz.peelco.jolt.domain.model.TestPushStatus
import cz.peelco.jolt.domain.model.User
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

interface AuthRepository {
    /** Null while signed out. */
    val currentUser: StateFlow<User?>

    suspend fun signUp(
        email: String,
        password: String,
        handle: String,
        displayName: String,
    )

    suspend fun logIn(
        email: String,
        password: String,
    )

    suspend fun logOut()
}

interface FriendsRepository {
    /** Null until the first fetch lands, so "not loaded" and "empty" differ. */
    val friends: StateFlow<List<Friend>?>
    val incomingRequests: StateFlow<List<FriendRequest>>
    val outgoingRequests: StateFlow<List<FriendRequest>>

    /** Your own shareable identifiers, shown as text and QR. */
    val myHandle: String
    val myInviteCode: String

    /** Null while signed out or from a server that predates them. */
    val serverPolicies: StateFlow<ServerPolicies?>

    suspend fun sendRequest(handle: String)

    suspend fun sendRequestByInviteCode(inviteCode: String)

    suspend fun acceptRequest(id: UUID)

    suspend fun rejectRequest(id: UUID)

    suspend fun removeFriend(id: UUID)

    /** Overwrites allow, cap and cooldown and leaves the automation answer alone. */
    suspend fun updatePermission(
        friendId: UUID,
        kind: StimulusKind,
        permission: StimulusPermission,
    )

    /**
     * Answers "may this friend's scripts send me [kind]?"; null hands it back
     * to the server default. The PUT overwrites the grant too, so [permission]
     * carries the one currently shown.
     */
    suspend fun updateAutomationConsent(
        friendId: UUID,
        kind: StimulusKind,
        permission: StimulusPermission,
        allowed: Boolean?,
    )

    /** Retries the fetch that normally runs only at sign-in. */
    suspend fun refreshFriends()
}

interface PokeRepository {
    val activity: StateFlow<List<PokeEvent>>

    /**
     * Sends one poke. [pokeId] names it: sending the same id again is a retry
     * of the same poke, never a second one.
     */
    suspend fun sendPoke(
        friendId: UUID,
        stimulus: StimulusConfig,
        pokeId: UUID = UUID.randomUUID(),
    )

    /**
     * The one "a poke arrived" path: fires (if connected and allowed), acks,
     * and refreshes the activity log.
     */
    suspend fun handleIncomingPoke(payload: PokePushPayload): PokeDeliveryStatus

    /** Debug: runs a poke from a known friend through [handleIncomingPoke]. */
    suspend fun simulateIncomingPoke(
        friendId: UUID,
        stimulus: StimulusConfig,
    )
}

/**
 * Why a poke might not have gone out. "The connection dropped" alone says
 * nothing about whether it arrived.
 */
sealed class PokeSendException(
    message: String,
) : Exception(message) {
    /** The feed confirms the server never recorded it. */
    class NotSent : PokeSendException("The connection dropped before the server got it — nothing was sent.")

    /** The feed could not be checked either. Retrying is safe. */
    class Unconfirmed :
        PokeSendException(
            "The connection dropped before the server answered, so it isn't clear the poke arrived. " +
                "Retrying is safe: it can't be delivered twice.",
        )
}

/** "Do notifications from the server actually reach this phone?" */
interface PushDiagnosticsRepository {
    suspend fun registeredDevices(): List<RegisteredDevice>

    /** Pushes to [deviceId], or every device when null; [stimulus] fires it too. */
    suspend fun sendTestPush(
        deviceId: UUID?,
        stimulus: StimulusConfig?,
    ): TestPushStatus

    /** Throws once the server has forgotten the test (ten minutes). */
    suspend fun testPushStatus(testId: UUID): TestPushStatus

    /** Fires the test's stimulus, if any, then acks it. */
    suspend fun handleIncomingTestPush(
        payload: TestPushPayload,
        path: TestPushPath,
    ): PokeDeliveryStatus?
}

/** Personal access tokens for scripts (`/me/tokens`). */
interface ApiTokensRepository {
    suspend fun listTokens(): List<ApiToken>

    /** The answer carries the secret, shown once. */
    suspend fun createToken(draft: ApiTokenDraft): ApiToken

    suspend fun revokeToken(id: UUID)
}
