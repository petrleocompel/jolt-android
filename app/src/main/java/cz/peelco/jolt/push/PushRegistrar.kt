package cz.peelco.jolt.push

import android.util.Log
import cz.peelco.jolt.data.api.JoltApiException
import cz.peelco.jolt.data.secure.SecretStore
import cz.peelco.jolt.data.social.SessionListener
import cz.peelco.jolt.data.social.SocialBackend
import cz.peelco.jolt.domain.model.PushTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where push registration stands, for Settings → Notifications. */
sealed interface PushStatus {
    data object SignedOut : PushStatus

    /** This build has no Firebase configuration. */
    data object Unavailable : PushStatus

    /** Firebase hasn't produced a token (no Play Services, or offline). */
    data object NoToken : PushStatus

    data object Checking : PushStatus

    /** The server pushes through its own APNs, or not at all: no way to reach Android. */
    data class ServerUnsupported(
        val transport: PushTransport,
    ) : PushStatus

    /** The server names a relay this build doesn't trust. */
    data class RelayNotAllowed(
        val host: String,
    ) : PushStatus

    data class Registered(
        val serverId: String,
        val relayHost: String,
        /** The relay token's tail, which `GET /devices` may show as `tokenSuffix`. */
        val tokenSuffix: String,
    ) : PushStatus

    data class Failed(
        val message: String,
    ) : PushStatus
}

/**
 * Registers this phone for pushes through jolt-relay (protocol §4 and §7)
 * whenever a session exists, and unregisters on the way out.
 *
 * Android always goes through the relay: jolt-server has no FCM sender of its
 * own. A server that answers `transport: apns` or `none` can't reach this
 * phone, and the status says so.
 */
class PushRegistrar(
    private val backend: SocialBackend,
    private val secrets: SecretStore,
    private val payloadKeys: PayloadKeyStore,
    private val tokenProvider: PushTokenProvider,
    private val attestor: Attestor,
    private val allowList: RelayAllowList,
    private val relayClient: (baseUrl: String) -> RelayClient,
    private val appId: String,
    private val scope: CoroutineScope,
    /** How long sign-out waits on the two unregister calls. */
    private val signOutBudget: Duration = 8.seconds,
) : SessionListener {
    private val state = MutableStateFlow<PushStatus>(PushStatus.SignedOut)
    val status: StateFlow<PushStatus> = state.asStateFlow()

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** What was registered last, so an unchanged registration isn't redone. */
    @Serializable
    private data class Registration(
        val serverUrl: String,
        val serverId: String,
        val relayUrl: String,
        val fcmToken: String,
        val relayToken: String,
        val kid: String,
    )

    private var registration: Registration?
        get() = secrets.get(REGISTRATION_KEY)?.let { runCatching { json.decodeFromString(Registration.serializer(), it) }.getOrNull() }
        set(value) {
            if (value == null) secrets.remove(REGISTRATION_KEY) else secrets.put(REGISTRATION_KEY, json.encodeToString(Registration.serializer(), value))
        }

    /** The key a push from [serverId] was sealed with, for the message handler. */
    fun payloadKey(
        serverId: String,
        kid: String,
    ): ByteArray? = payloadKeys.find(serverId, kid)

    override suspend fun onSignedIn() {
        // Not awaited: signing in mustn't wait on two more services.
        scope.launch { register() }
    }

    /** A poke for another account arrived: re-assert this phone's binding. */
    override suspend fun onStaleRegistration() = register()

    /** Unbinds while the session token still works, bounded so sign-out never hangs. */
    override suspend fun beforeSignOut() {
        // On Default so the budget is real time wherever this is called from.
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(signOutBudget) {
                mutex.withLock {
                    registration?.let { unregister(it) }
                    registration = null
                }
            }
        }
        state.value = PushStatus.SignedOut
    }

    /** FCM rotated the token. */
    fun onNewToken(token: String) {
        scope.launch { register(token) }
    }

    /** Re-checks everything, for Settings → Notifications. */
    fun refresh() {
        scope.launch { register() }
    }

    suspend fun register(knownToken: String? = null) =
        mutex.withLock {
            try {
                registerLocked(knownToken)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Push registration failed: ${error.message}")
                state.value = PushStatus.Failed(error.message ?: "Push registration failed.")
            }
        }

    private suspend fun registerLocked(knownToken: String?) {
        // A build without Firebase can never register, signed in or not.
        if (!tokenProvider.isAvailable) {
            state.value = PushStatus.Unavailable
            return
        }
        if (backend.currentUser.value == null) {
            state.value = PushStatus.SignedOut
            return
        }
        state.value = PushStatus.Checking
        val fcmToken =
            knownToken ?: tokenProvider.token() ?: run {
                state.value = PushStatus.NoToken
                return
            }
        val serverUrl = backend.configuration.value.baseUrl
        val config = backend.pushConfig()
        var previous = registration

        val transport =
            config.transport ?: run {
                // Keep whatever is registered: an unknown answer is no reason
                // to stop receiving pokes (protocol C18).
                state.value = PushStatus.Failed("The server asked for push transport \"${config.transportName}\", which this version of Jolt doesn't know.")
                return
            }
        val relay = config.relay
        if (transport != PushTransport.RELAY || relay == null) {
            // The server stopped using the relay (or never did): drop what we had there.
            if (previous != null && previous.serverUrl == serverUrl) {
                unregister(previous)
                registration = null
            }
            state.value = PushStatus.ServerUnsupported(transport)
            return
        }
        val relayUrl = RelayClient.normalize(relay.url)
        val relayHost = runCatching { URI(relayUrl).host }.getOrNull().orEmpty()
        if (!allowList.allows(relayUrl)) {
            state.value = PushStatus.RelayNotAllowed(relayHost.ifEmpty { relay.url })
            return
        }

        if (previous != null &&
            previous.serverUrl == serverUrl &&
            previous.serverId == relay.serverId &&
            RelayClient.normalize(previous.relayUrl) == relayUrl &&
            previous.fcmToken == fcmToken
        ) {
            val key = payloadKeys.find(previous.serverId, previous.kid)
            if (key != null) {
                // Same registration: only re-assert which account it belongs
                // to, which is idempotent server-side and cheap.
                try {
                    backend.registerRelayPushToken(previous.relayToken, Base64Url.encode(key), previous.kid)
                    state.value = PushStatus.Registered(previous.serverId, relayHost, previous.relayToken.takeLast(8))
                    return
                } catch (error: JoltApiException.Server) {
                    if (error.status != 410 || error.code != RELAY_TOKEN_REVOKED) throw error
                    // The relay told the server this token is gone (protocol
                    // C6): it will never be re-enabled, so drop it with its
                    // key and register afresh below.
                    Log.i(TAG, "Relay token revoked; registering with the relay again")
                    payloadKeys.discard(previous.serverId, previous.kid)
                    registration = null
                    previous = null
                }
            }
        }

        val client = relayClient(relayUrl)
        val relayToken =
            try {
                val challenge = client.challenge().challenge
                val attestation = attestor.attest(challenge, fcmToken, relay.serverId)
                val body =
                    buildJsonObject {
                        put("platform", JsonPrimitive("android"))
                        put("provider", JsonPrimitive("fcm"))
                        put("token", JsonPrimitive(fcmToken))
                        put("appId", JsonPrimitive(appId))
                        put("serverId", JsonPrimitive(relay.serverId))
                        attestation?.let { put("attestation", it) }
                    }
                client.registerDevice(body)
            } finally {
                client.close()
            }

        val key = payloadKeys.rotate(relay.serverId)
        val kid = EnvelopeCrypto.kid(key)
        backend.registerRelayPushToken(relayToken, Base64Url.encode(key), kid)

        // A new relay token replaces the old registration, which goes on both
        // sides, or the phone would get every poke twice (protocol C7). The
        // old key stays for in-flight pushes when the server is the same.
        if (previous != null && previous.relayToken != relayToken) {
            unregister(previous, forgetKeys = previous.serverId != relay.serverId)
        }
        registration = Registration(serverUrl, relay.serverId, relayUrl, fcmToken, relayToken, kid)
        state.value = PushStatus.Registered(relay.serverId, relayHost, relayToken.takeLast(8))
    }

    /** Best effort, both halves: the server's binding and the relay's registration. */
    private suspend fun unregister(
        old: Registration,
        forgetKeys: Boolean = true,
    ) {
        if (old.serverUrl == backend.configuration.value.baseUrl) runCatching { backend.forgetRelayPushToken(old.relayToken) }
        val client = relayClient(RelayClient.normalize(old.relayUrl))
        runCatching { client.unregisterDevice(old.relayToken) }
        client.close()
        if (forgetKeys) payloadKeys.forget(old.serverId)
    }

    private companion object {
        const val TAG = "JoltPush"
        const val REGISTRATION_KEY = "relayRegistration"
        const val RELAY_TOKEN_REVOKED = "relay_token_revoked"
    }
}
