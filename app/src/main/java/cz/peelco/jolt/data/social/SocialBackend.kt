package cz.peelco.jolt.data.social

import cz.peelco.jolt.domain.model.PushConfig
import cz.peelco.jolt.domain.model.ServerConfiguration
import cz.peelco.jolt.domain.repository.ApiTokensRepository
import cz.peelco.jolt.domain.repository.AuthRepository
import cz.peelco.jolt.domain.repository.FriendsRepository
import cz.peelco.jolt.domain.repository.PokeRepository
import cz.peelco.jolt.domain.repository.PushDiagnosticsRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * The server-side calls push registration needs. Kept apart from the
 * repositories the UI uses: only `push/` talks to these.
 */
interface PushServerApi {
    /** `GET /push/config`. A server that predates it can only do APNs. */
    suspend fun pushConfig(): PushConfig

    /** `POST /devices/push-token` in the relay shape. */
    suspend fun registerRelayPushToken(
        relayToken: String,
        payloadKey: String,
        keyId: String,
    )

    /** `DELETE /devices/push-token` with a relay token. Best effort. */
    suspend fun forgetRelayPushToken(relayToken: String)
}

/**
 * Hooks around the session for push registration, which has to happen while
 * the session token is still valid on the way out.
 */
interface SessionListener {
    /** A session exists: just signed in, or restored at launch. */
    suspend fun onSignedIn()

    /** About to sign out; the token still works. */
    suspend fun beforeSignOut()

    /** A poke arrived for another account: the registration is stale. */
    suspend fun onStaleRegistration()
}

/** Everything the app needs from a Jolt server, real or mock. */
interface SocialBackend :
    AuthRepository,
    FriendsRepository,
    PokeRepository,
    PushDiagnosticsRepository,
    ApiTokensRepository,
    PushServerApi {
    val configuration: StateFlow<ServerConfiguration>

    var sessionListener: SessionListener?

    /** Restores the saved session, if any. Called once at launch. */
    fun start()

    /**
     * Signs out of the current server and points at [configuration]. Accounts
     * don't transfer between servers, so the user signs in again.
     */
    suspend fun switchServer(configuration: ServerConfiguration)
}
