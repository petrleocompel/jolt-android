package cz.peelco.jolt.data

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.data.device.FakeDeviceRepository
import cz.peelco.jolt.data.secure.InMemorySecretStore
import cz.peelco.jolt.data.social.HttpSocialBackend
import cz.peelco.jolt.data.social.LocalStimulusFirer
import cz.peelco.jolt.data.social.SessionListener
import cz.peelco.jolt.data.store.InMemoryKeyValueStore
import cz.peelco.jolt.data.store.JsonValueStore
import cz.peelco.jolt.domain.model.PokeDeliveryStatus
import cz.peelco.jolt.domain.model.PokePushPayload
import cz.peelco.jolt.domain.model.PushTransport
import cz.peelco.jolt.domain.model.ServerConfiguration
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.repository.PokeSendException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.UUID

class HttpSocialBackendTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val me =
        """{"id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","handle":"bob","displayName":"Bob","email":"b@example.com",
        "inviteCode":"INV","policies":{"automationConsentRequired":true}}"""
    private val requests = mutableListOf<Pair<HttpMethod, String>>()
    private val bodies = mutableMapOf<String, String>()

    private fun TestScope.backend(
        device: FakeDeviceRepository = FakeDeviceRepository().apply { start() },
        dnd: Boolean = false,
        route: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpSocialBackend {
        val server = JsonValueStore(InMemoryKeyValueStore(), "server", ServerConfiguration.serializer(), ServerConfiguration("https://jolt.example.com/api/v1"))
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath.removePrefix("/api/v1/")
                requests += request.method to path
                (request.body as? TextContent)?.let { bodies["${request.method.value} $path"] = it.text }
                route(request)
            }
        return HttpSocialBackend(server, InMemorySecretStore(), { engine }, LocalStimulusFirer(device, backgroundScope) { dnd }, backgroundScope)
    }

    private fun MockRequestHandleScope.ok(body: String = "") = respond(body, if (body.isEmpty()) HttpStatusCode.NoContent else HttpStatusCode.OK, json)

    private fun MockRequestHandleScope.signedIn(request: HttpRequestData): HttpResponseData? {
        val path = request.url.encodedPath.removePrefix("/api/v1/")
        return when (path) {
            "auth/login" -> ok("""{"token":"t","user":$me}""")
            "friends" -> ok("[]")
            "friends/requests" -> ok("""{"incoming":[],"outgoing":[]}""")
            "pokes" -> ok("[]")
            else -> null
        }
    }

    @Test
    fun loggingInAdoptsTheProfileAndPolicies() =
        runTest {
            val backend = backend { signedIn(it) ?: ok() }
            backend.logIn(" b@example.com ", "pw")
            assertThat(backend.currentUser.value?.handle).isEqualTo("bob")
            assertThat(backend.myInviteCode).isEqualTo("INV")
            assertThat(backend.serverPolicies.value?.automationConsentRequired).isTrue()
            assertThat(backend.friends.value).isEmpty()
            assertThat(bodies["POST auth/login"]).contains("\"email\":\"b@example.com\"")
        }

    @Test
    fun aDroppedPokeThatNeverLandedIsReportedNotSent() =
        runTest {
            val backend = backend { request -> if (request.url.encodedPath.endsWith("pokes") && request.method == HttpMethod.Post) throw IOException("reset") else signedIn(request) ?: ok() }
            backend.logIn("b@example.com", "pw")
            val error = runCatching { backend.sendPoke(UUID.randomUUID(), StimulusConfig(StimulusKind.VIBE)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(PokeSendException.NotSent::class.java)
        }

    @Test
    fun aDroppedPokeThatCannotBeCheckedIsUnconfirmed() =
        runTest {
            var loggedIn = false
            val backend =
                backend { request ->
                    if (loggedIn && request.url.encodedPath.endsWith("pokes")) throw IOException("offline")
                    signedIn(request) ?: ok()
                }
            backend.logIn("b@example.com", "pw")
            loggedIn = true
            val error = runCatching { backend.sendPoke(UUID.randomUUID(), StimulusConfig(StimulusKind.VIBE)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(PokeSendException.Unconfirmed::class.java)
        }

    @Test
    fun aPokeForAnotherAccountIsDroppedWithoutFiring() =
        runTest {
            val device = FakeDeviceRepository().apply { start() }
            val backend = backend(device) { signedIn(it) ?: ok() }
            backend.logIn("b@example.com", "pw")
            var stale = 0
            backend.sessionListener =
                object : SessionListener {
                    override suspend fun onSignedIn() = Unit

                    override suspend fun beforeSignOut() = Unit

                    override suspend fun onStaleRegistration() {
                        stale++
                    }
                }
            val status = backend.handleIncomingPoke(PokePushPayload(UUID.randomUUID(), "alice", "Alice", "carol", StimulusConfig(StimulusKind.ZAP)))
            assertThat(status).isEqualTo(PokeDeliveryStatus.NOT_ALLOWED)
            assertThat(device.fired).isEmpty()
            assertThat(stale).isEqualTo(1)
        }

    @Test
    fun anIncomingPokeFiresOnceAndAcks() =
        runTest {
            val device = FakeDeviceRepository().apply { start() }
            val backend = backend(device) { signedIn(it) ?: ok() }
            backend.logIn("b@example.com", "pw")
            val payload = PokePushPayload(UUID.randomUUID(), "alice", "Alice", "bob", StimulusConfig(StimulusKind.ZAP, 25))
            assertThat(backend.handleIncomingPoke(payload)).isEqualTo(PokeDeliveryStatus.FIRED)
            assertThat(backend.handleIncomingPoke(payload)).isEqualTo(PokeDeliveryStatus.FIRED)
            assertThat(device.fired).hasSize(1)
            assertThat(bodies["POST pokes/${payload.pokeId}/ack"]).isEqualTo("""{"status":"fired"}""")
        }

    @Test
    fun doNotDisturbMutesWithoutFiring() =
        runTest {
            val device = FakeDeviceRepository().apply { start() }
            val backend = backend(device, dnd = true) { signedIn(it) ?: ok() }
            val status = backend.handleIncomingPoke(PokePushPayload(UUID.randomUUID(), "alice", "Alice", null, StimulusConfig(StimulusKind.ZAP)))
            assertThat(status).isEqualTo(PokeDeliveryStatus.MUTED)
            assertThat(device.fired).isEmpty()
        }

    @Test
    fun aFailedAttemptIsRetriedOnTheNextArrival() =
        runTest {
            val device = FakeDeviceRepository()
            val backend = backend(device) { signedIn(it) ?: ok() }
            val payload = PokePushPayload(UUID.randomUUID(), "alice", "Alice", null, StimulusConfig(StimulusKind.VIBE))
            assertThat(backend.handleIncomingPoke(payload)).isEqualTo(PokeDeliveryStatus.DEVICE_NOT_CONNECTED)
            device.start()
            assertThat(backend.handleIncomingPoke(payload)).isEqualTo(PokeDeliveryStatus.FIRED)
        }

    @Test
    fun loggingOutUnbindsPushWhileTheTokenStillWorks() =
        runTest {
            val backend = backend { signedIn(it) ?: ok() }
            backend.logIn("b@example.com", "pw")
            backend.sessionListener =
                object : SessionListener {
                    override suspend fun onSignedIn() = Unit

                    override suspend fun beforeSignOut() = backend.forgetRelayPushToken("rt_abc")

                    override suspend fun onStaleRegistration() = Unit
                }
            backend.logOut()
            val deleteIndex = requests.indexOf(HttpMethod.Delete to "devices/push-token")
            val logoutIndex = requests.indexOf(HttpMethod.Post to "auth/logout")
            assertThat(deleteIndex).isAtLeast(0)
            assertThat(deleteIndex).isLessThan(logoutIndex)
            assertThat(bodies["DELETE devices/push-token"]).isEqualTo("""{"relayToken":"rt_abc"}""")
            assertThat(backend.currentUser.value).isNull()
            assertThat(backend.friends.value).isNull()
        }

    @Test
    fun relayRegistrationUsesTheRelayShape() =
        runTest {
            val backend = backend { signedIn(it) ?: ok() }
            backend.registerRelayPushToken("rt_1", "key", "kid")
            assertThat(bodies["POST devices/push-token"])
                .isEqualTo("""{"transport":"relay","platform":"android","relayToken":"rt_1","payloadKey":"key","keyId":"kid"}""")
        }

    @Test
    fun aServerWithoutPushConfigIsTreatedAsApnsOnly() =
        runTest {
            val backend = backend { respond("""{"message":"Not found"}""", HttpStatusCode.NotFound, json) }
            assertThat(backend.pushConfig().transport).isEqualTo(PushTransport.APNS)
            val relay =
                backend {
                    ok("""{"transport":"relay","relay":{"url":"https://relay.example/","serverId":"srv_kzdvvj2umnduyauf35o36k6kw4"}}""")
                }
            assertThat(relay.pushConfig().relay?.serverId).isEqualTo("srv_kzdvvj2umnduyauf35o36k6kw4")
        }
}
