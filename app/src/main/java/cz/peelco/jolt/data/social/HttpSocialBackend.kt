package cz.peelco.jolt.data.social

import android.util.Log
import cz.peelco.jolt.data.api.JoltApiClient
import cz.peelco.jolt.data.api.JoltApiException
import cz.peelco.jolt.data.api.JoltJson
import cz.peelco.jolt.data.api.StimulusPermissionUpdate
import cz.peelco.jolt.data.secure.SecretStore
import cz.peelco.jolt.data.store.JsonValueStore
import cz.peelco.jolt.domain.model.ApiToken
import cz.peelco.jolt.domain.model.ApiTokenDraft
import cz.peelco.jolt.domain.model.Friend
import cz.peelco.jolt.domain.model.FriendRequest
import cz.peelco.jolt.domain.model.Me
import cz.peelco.jolt.domain.model.PokeDeliveryStatus
import cz.peelco.jolt.domain.model.PokeEvent
import cz.peelco.jolt.domain.model.PokePushPayload
import cz.peelco.jolt.domain.model.PushConfig
import cz.peelco.jolt.domain.model.PushTransport
import cz.peelco.jolt.domain.model.RegisteredDevice
import cz.peelco.jolt.domain.model.ServerConfiguration
import cz.peelco.jolt.domain.model.ServerPolicies
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.StimulusPermission
import cz.peelco.jolt.domain.model.TestPushPath
import cz.peelco.jolt.domain.model.TestPushPayload
import cz.peelco.jolt.domain.model.TestPushStatus
import cz.peelco.jolt.domain.model.User
import cz.peelco.jolt.domain.repository.PokeSendException
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * Talks to a real Jolt Server; drop-in replacement for [MockSocialBackend].
 *
 * State is held locally, re-fetched after every mutation and republished on
 * `StateFlow`s: the contract has no websocket, so the app's view of the friend
 * graph is whatever the last fetch returned.
 */
class HttpSocialBackend(
    private val serverStore: JsonValueStore<ServerConfiguration>,
    private val secrets: SecretStore,
    private val engineFactory: () -> HttpClientEngine,
    private val firer: LocalStimulusFirer,
    private val scope: CoroutineScope,
) : SocialBackend {
    override val configuration: StateFlow<ServerConfiguration> = serverStore.flow
    override var sessionListener: SessionListener? = null

    @Volatile private var client = makeClient(serverStore.value)

    private val user = MutableStateFlow<User?>(null)
    private val friendsState = MutableStateFlow<List<Friend>?>(null)
    private val incoming = MutableStateFlow<List<FriendRequest>>(emptyList())
    private val outgoing = MutableStateFlow<List<FriendRequest>>(emptyList())
    private val activityLog = MutableStateFlow<List<PokeEvent>>(emptyList())
    private val policies = MutableStateFlow<ServerPolicies?>(null)
    @Volatile private var inviteCode = ""

    /** Keeps sign-out and server switches from interleaving. */
    private val sessionMutex = Mutex()

    override val currentUser: StateFlow<User?> = user.asStateFlow()
    override val friends: StateFlow<List<Friend>?> = friendsState.asStateFlow()
    override val incomingRequests: StateFlow<List<FriendRequest>> = incoming.asStateFlow()
    override val outgoingRequests: StateFlow<List<FriendRequest>> = outgoing.asStateFlow()
    override val activity: StateFlow<List<PokeEvent>> = activityLog.asStateFlow()
    override val serverPolicies: StateFlow<ServerPolicies?> = policies.asStateFlow()
    override val myHandle: String get() = user.value?.handle.orEmpty()
    override val myInviteCode: String get() = inviteCode

    private fun makeClient(configuration: ServerConfiguration) =
        JoltApiClient(configuration.baseUrl, engineFactory(), secrets.get(tokenKey(configuration)))

    private fun tokenKey(configuration: ServerConfiguration) = "authToken:${configuration.baseUrl}"

    override fun start() {
        scope.launch { restoreSession() }
    }

    // Requests. Every authenticated call goes through these so a token
    // rejected mid-session signs out at once instead of at the next launch.

    private suspend fun <T> send(
        method: HttpMethod,
        path: String,
        deserializer: DeserializationStrategy<T>,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): T =
        try {
            client.send(method, path, deserializer, body, query)
        } catch (error: JoltApiException.Unauthorized) {
            logOut()
            throw error
        }

    private suspend fun sendIgnoringResponse(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
    ) {
        try {
            client.sendIgnoringResponse(method, path, body)
        } catch (error: JoltApiException.Unauthorized) {
            logOut()
            throw error
        }
    }

    // Auth.

    @Serializable
    private data class AuthResponse(
        val token: String,
        val user: Me,
    )

    override suspend fun signUp(
        email: String,
        password: String,
        handle: String,
        displayName: String,
    ) {
        val body =
            buildJsonObject {
                put("email", JsonPrimitive(email.trim()))
                put("password", JsonPrimitive(password))
                put("handle", JsonPrimitive(handle.trim().lowercase()))
                put("displayName", JsonPrimitive(displayName))
            }
        adopt(client.send(HttpMethod.Post, "auth/signup", AuthResponse.serializer(), body))
    }

    override suspend fun logIn(
        email: String,
        password: String,
    ) {
        val body =
            buildJsonObject {
                put("email", JsonPrimitive(email.trim()))
                put("password", JsonPrimitive(password))
            }
        adopt(client.send(HttpMethod.Post, "auth/login", AuthResponse.serializer(), body))
    }

    private suspend fun adopt(response: AuthResponse) {
        secrets.put(tokenKey(configuration.value), response.token)
        client.token = response.token
        apply(response.user)
        sessionListener?.onSignedIn()
        refreshAll()
    }

    private fun apply(profile: Me) {
        inviteCode = profile.inviteCode
        policies.value = profile.policies
        user.value = profile.user
    }

    override suspend fun logOut() {
        sessionMutex.withLock {
            if (client.token == null && user.value == null) return
            // Best effort: a failed call must not leave the app stuck signed
            // in. Unbind push first, while the token still works, or this
            // phone stays a delivery target for the account it just left.
            if (user.value != null) runCatching { sessionListener?.beforeSignOut() }
            runCatching { client.sendIgnoringResponse(HttpMethod.Post, "auth/logout") }
            secrets.remove(tokenKey(configuration.value))
            client.token = null
            user.value = null
            inviteCode = ""
            policies.value = null
            friendsState.value = null
            incoming.value = emptyList()
            outgoing.value = emptyList()
            activityLog.value = emptyList()
        }
    }

    private suspend fun restoreSession() {
        if (client.token == null) return
        try {
            apply(client.send(HttpMethod.Get, "me", Me.serializer()))
            sessionListener?.onSignedIn()
            refreshAll()
        } catch (_: JoltApiException.Unauthorized) {
            // Expired, revoked, or from a server we no longer point at.
            logOut()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Offline or server down: keep the token rather than destroying a
            // session over a flaky network.
        }
    }

    override suspend fun switchServer(configuration: ServerConfiguration) {
        if (configuration == this.configuration.value) return
        logOut()
        sessionMutex.withLock {
            client.close()
            serverStore.save(configuration)
            client = makeClient(configuration)
        }
        restoreSession()
    }

    // Friends.

    @Serializable
    private data class RequestsResponse(
        val incoming: List<FriendRequest> = emptyList(),
        val outgoing: List<FriendRequest> = emptyList(),
    )

    override suspend fun sendRequest(handle: String) {
        val body = buildJsonObject { put("handle", JsonPrimitive(handle.trim().removePrefix("@").lowercase())) }
        send(HttpMethod.Post, "friends/requests", FriendRequest.serializer(), body)
        refreshRequests()
    }

    override suspend fun sendRequestByInviteCode(inviteCode: String) {
        val body = buildJsonObject { put("inviteCode", JsonPrimitive(inviteCode.trim())) }
        send(HttpMethod.Post, "friends/requests", FriendRequest.serializer(), body)
        refreshRequests()
    }

    override suspend fun acceptRequest(id: UUID) {
        send(HttpMethod.Post, "friends/requests/$id/accept", Friend.serializer())
        refreshFriends()
        refreshRequests()
    }

    override suspend fun rejectRequest(id: UUID) {
        sendIgnoringResponse(HttpMethod.Post, "friends/requests/$id/reject")
        refreshRequests()
    }

    override suspend fun removeFriend(id: UUID) {
        sendIgnoringResponse(HttpMethod.Delete, "friends/$id")
        refreshFriends()
    }

    override suspend fun updatePermission(
        friendId: UUID,
        kind: StimulusKind,
        permission: StimulusPermission,
    ) = putPermission(StimulusPermissionUpdate.of(permission), friendId, kind)

    override suspend fun updateAutomationConsent(
        friendId: UUID,
        kind: StimulusKind,
        permission: StimulusPermission,
        allowed: Boolean?,
    ) = putPermission(StimulusPermissionUpdate.of(permission, StimulusPermissionUpdate.Automation.of(allowed)), friendId, kind)

    private suspend fun putPermission(
        update: StimulusPermissionUpdate,
        friendId: UUID,
        kind: StimulusKind,
    ) {
        send(HttpMethod.Put, "friends/$friendId/permissions/${kind.wireName}", StimulusPermission.serializer(), update.toJson())
        refreshFriends()
    }

    // Re-fetching.

    private suspend fun refreshAll() {
        refreshFriends()
        refreshRequests()
        refreshActivity()
    }

    override suspend fun refreshFriends() {
        val list = runCatching { send(HttpMethod.Get, "friends", ListSerializer(Friend.serializer())) }.getOrNull() ?: return
        friendsState.value = list
    }

    private suspend fun refreshRequests() {
        val response = runCatching { send(HttpMethod.Get, "friends/requests", RequestsResponse.serializer()) }.getOrNull() ?: return
        incoming.value = response.incoming
        outgoing.value = response.outgoing
    }

    private suspend fun refreshActivity() {
        val events = fetchActivity() ?: return
        activityLog.value = events
    }

    private suspend fun fetchActivity(): List<PokeEvent>? =
        runCatching { send(HttpMethod.Get, "pokes", ListSerializer(PokeEvent.serializer())) }.getOrNull()

    // Pokes.

    override suspend fun sendPoke(
        friendId: UUID,
        stimulus: StimulusConfig,
        pokeId: UUID,
    ) {
        val body =
            buildJsonObject {
                put("friendId", JsonPrimitive(friendId.toString()))
                put("stimulus", JoltJson.encodeToJsonElement(StimulusConfig.serializer(), stimulus))
                // Makes a resend of the same poke a retry the server answers
                // from its records rather than a second poke.
                put("pokeId", JsonPrimitive(pokeId.toString()))
            }
        try {
            send(HttpMethod.Post, "pokes", PokeEvent.serializer(), body)
        } catch (_: JoltApiException.Transport) {
            // The connection dropped. The request may have arrived before the
            // answer was lost, so ask the feed rather than guess: "not sent"
            // for a poke that landed is what invites a second one.
            val events = fetchActivity()
            when {
                events == null -> throw PokeSendException.Unconfirmed()
                events.none { it.id == pokeId } -> throw PokeSendException.NotSent()
                else -> activityLog.value = events
            }
        }
        refreshActivity()
    }

    override suspend fun handleIncomingPoke(payload: PokePushPayload): PokeDeliveryStatus {
        // Addressed to somebody else: only possible when this phone is still
        // registered to an account it is no longer signed in as. Firing here
        // would shock whoever holds this phone. Deliberately narrow: no handle
        // (older server) or no known account means "can't tell".
        val addressee = payload.recipientHandle
        if (addressee != null && myHandle.isNotEmpty() && addressee != myHandle) {
            Log.w(TAG, "Dropped a poke addressed to @$addressee — signed in as @$myHandle")
            sessionListener?.onStaleRegistration()
            return PokeDeliveryStatus.NOT_ALLOWED
        }

        val status = firer.fire(payload.pokeId, payload.stimulus)
        try {
            val body = buildJsonObject { put("status", JoltJson.encodeToJsonElement(PokeDeliveryStatus.serializer(), status)) }
            sendIgnoringResponse(HttpMethod.Post, "pokes/${payload.pokeId}/ack", body)
        } catch (error: JoltApiException.Server) {
            if (error.status == 404) {
                // The server accepts an ack only from the recipient: this
                // phone fired something meant for another account.
                Log.w(TAG, "Server rejected the ack for ${payload.pokeId} — this poke was not ours")
                sessionListener?.onStaleRegistration()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Costs the sender their delivery status and nothing else.
        }
        refreshActivity()
        return status
    }

    override suspend fun simulateIncomingPoke(
        friendId: UUID,
        stimulus: StimulusConfig,
    ) {
        val friend = friendsState.value?.firstOrNull { it.id == friendId } ?: return
        handleIncomingPoke(
            PokePushPayload(
                pokeId = UUID.randomUUID(),
                senderHandle = friend.handle,
                senderDisplayName = friend.displayName,
                recipientHandle = myHandle,
                stimulus = stimulus,
            ),
        )
    }

    // Push diagnostics.

    override suspend fun registeredDevices(): List<RegisteredDevice> =
        send(HttpMethod.Get, "devices", ListSerializer(RegisteredDevice.serializer()))

    override suspend fun sendTestPush(
        deviceId: UUID?,
        stimulus: StimulusConfig?,
    ): TestPushStatus {
        val body =
            buildJsonObject {
                deviceId?.let { put("deviceId", JsonPrimitive(it.toString())) }
                stimulus?.let { put("stimulus", JoltJson.encodeToJsonElement(StimulusConfig.serializer(), it)) }
            }
        return send(HttpMethod.Post, "devices/test-push", TestPushStatus.serializer(), body)
    }

    override suspend fun testPushStatus(testId: UUID): TestPushStatus =
        send(HttpMethod.Get, "devices/test-push/$testId", TestPushStatus.serializer())

    override suspend fun handleIncomingTestPush(
        payload: TestPushPayload,
        path: TestPushPath,
    ): PokeDeliveryStatus? {
        // Only a test that asked for a stimulus touches the wearable; the
        // notification-only case still acks, since arriving is the result.
        val status = payload.stimulus?.let { firer.fire(payload.testId, it) }
        val body =
            buildJsonObject {
                put("deviceId", JsonPrimitive(payload.deviceId.toString()))
                put("path", JoltJson.encodeToJsonElement(TestPushPath.serializer(), path))
                status?.let { put("status", JoltJson.encodeToJsonElement(PokeDeliveryStatus.serializer(), it)) }
            }
        // Best effort: a failed ack costs the "arrived in 1.2s" line, nothing else.
        runCatching { sendIgnoringResponse(HttpMethod.Post, "devices/test-push/${payload.testId}/ack", body) }
        return status
    }

    // API tokens.

    override suspend fun listTokens(): List<ApiToken> = send(HttpMethod.Get, "me/tokens", ListSerializer(ApiToken.serializer()))

    override suspend fun createToken(draft: ApiTokenDraft): ApiToken =
        send(HttpMethod.Post, "me/tokens", ApiToken.serializer(), JoltJson.encodeToJsonElement(ApiTokenDraft.serializer(), draft))

    override suspend fun revokeToken(id: UUID) = sendIgnoringResponse(HttpMethod.Delete, "me/tokens/$id")

    // Push registration.

    override suspend fun pushConfig(): PushConfig =
        try {
            send(HttpMethod.Get, "push/config", PushConfig.serializer())
        } catch (error: JoltApiException.Server) {
            // A server from before the relay has no such endpoint and can only
            // push through its own APNs credentials.
            if (error.status == 404) PushConfig(PushTransport.APNS) else throw error
        }

    override suspend fun registerRelayPushToken(
        relayToken: String,
        payloadKey: String,
        keyId: String,
    ) {
        val body =
            buildJsonObject {
                put("transport", JsonPrimitive("relay"))
                put("platform", JsonPrimitive("android"))
                put("relayToken", JsonPrimitive(relayToken))
                put("payloadKey", JsonPrimitive(payloadKey))
                put("keyId", JsonPrimitive(keyId))
            }
        sendIgnoringResponse(HttpMethod.Post, "devices/push-token", body)
    }

    override suspend fun forgetRelayPushToken(relayToken: String) {
        val body: JsonObject = buildJsonObject { put("relayToken", JsonPrimitive(relayToken)) }
        // Through the client directly: this runs from logOut(), and the
        // wrapper's own logOut() on a 401 would recurse.
        runCatching { client.sendIgnoringResponse(HttpMethod.Delete, "devices/push-token", body) }
    }

    private companion object {
        const val TAG = "Jolt"
    }
}
