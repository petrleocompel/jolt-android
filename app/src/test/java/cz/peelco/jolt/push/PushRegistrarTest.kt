package cz.peelco.jolt.push

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.data.device.FakeDeviceRepository
import cz.peelco.jolt.data.secure.InMemorySecretStore
import cz.peelco.jolt.data.social.HttpSocialBackend
import cz.peelco.jolt.data.social.LocalStimulusFirer
import cz.peelco.jolt.data.store.InMemoryKeyValueStore
import cz.peelco.jolt.data.store.JsonValueStore
import cz.peelco.jolt.domain.model.PushTransport
import cz.peelco.jolt.domain.model.ServerConfiguration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PushRegistrarTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val serverId = "srv_kzdvvj2umnduyauf35o36k6kw4"
    private val me = """{"id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","handle":"bob","displayName":"Bob","email":"b@example.com","inviteCode":"I"}"""

    /** Requests seen by the server and the relay, with their bodies. */
    private val server = mutableListOf<Pair<String, String?>>()
    private val relay = mutableListOf<Pair<String, String?>>()
    private var pushConfig = """{"transport":"relay","relay":{"url":"https://relay.example/","serverId":"$serverId"}}"""
    private var relayTokens = 0

    /** Relay tokens the server reports as revoked by the relay (C6). */
    private val revokedTokens = mutableSetOf<String>()

    private val secrets = InMemorySecretStore()
    private val keys = PayloadKeyStore(secrets)

    private fun TestScope.setUp(
        fcmToken: String? = "fcm-1",
        attestor: Attestor = Attestor.NONE,
        allowed: String = "relay.example",
    ): Pair<HttpSocialBackend, PushRegistrar> {
        val serverEngine =
            MockEngine { request ->
                val path = request.url.encodedPath.removePrefix("/api/v1/")
                server += "${request.method.value} $path" to (request.body as? TextContent)?.text
                when (path) {
                    "auth/login" -> respond("""{"token":"t","user":$me}""", HttpStatusCode.OK, json)
                    "push/config" -> respond(pushConfig, HttpStatusCode.OK, json)
                    "friends", "pokes" -> respond("[]", HttpStatusCode.OK, json)
                    "friends/requests" -> respond("""{"incoming":[],"outgoing":[]}""", HttpStatusCode.OK, json)
                    "devices/push-token" ->
                        if (request.method == HttpMethod.Post && revokedTokens.any { (request.body as? TextContent)?.text.orEmpty().contains(it) }) {
                            respond("""{"error":"relay_token_revoked","message":"This relay token was revoked."}""", HttpStatusCode.Gone, json)
                        } else {
                            respond("", HttpStatusCode.NoContent)
                        }
                    else -> respond("", HttpStatusCode.NoContent)
                }
            }
        val relayEngine =
            MockEngine { request ->
                // One relay in the tests is mounted under /jolt (C1).
                val path = request.url.encodedPath.removePrefix("/").removePrefix("jolt/")
                relay += "${request.method.value} $path" to (request.body as? TextContent)?.text
                when {
                    path == "v1/challenge" -> respond("""{"challenge":"Y2hhbGxlbmdl","expiresAt":"2026-10-09T12:00:00Z"}""", HttpStatusCode.OK, json)
                    path == "v1/devices" && request.method == HttpMethod.Post -> respond("""{"relayToken":"rt_${++relayTokens}aaaaaaaa"}""", HttpStatusCode.Created, json)
                    else -> respond("", HttpStatusCode.NoContent)
                }
            }
        val store = JsonValueStore(InMemoryKeyValueStore(), "server", ServerConfiguration.serializer(), ServerConfiguration("https://jolt.example.com/api/v1"))
        val backend =
            HttpSocialBackend(store, secrets, { serverEngine }, LocalStimulusFirer(FakeDeviceRepository(), backgroundScope) { false }, backgroundScope)
        val tokens =
            object : PushTokenProvider {
                override val isAvailable = true

                override suspend fun token() = fcmToken
            }
        val registrar = PushRegistrar(backend, secrets, keys, tokens, attestor, RelayAllowList.parse(allowed), { RelayClient(it, relayEngine) }, "cz.peelco.jolt", backgroundScope)
        return backend to registrar
    }

    private fun body(text: String?) = Json.parseToJsonElement(text!!).jsonObject

    @Test
    fun registersWithTheRelayAndHandsTheServerTheKey() =
        runTest {
            val (backend, registrar) = setUp()
            backend.logIn("b@example.com", "pw")
            registrar.register()

            val registration = body(relay.single { it.first == "POST v1/devices" }.second)
            assertThat(registration["platform"]!!.jsonPrimitive.content).isEqualTo("android")
            assertThat(registration["provider"]!!.jsonPrimitive.content).isEqualTo("fcm")
            assertThat(registration["token"]!!.jsonPrimitive.content).isEqualTo("fcm-1")
            assertThat(registration["appId"]!!.jsonPrimitive.content).isEqualTo("cz.peelco.jolt")
            assertThat(registration["serverId"]!!.jsonPrimitive.content).isEqualTo(serverId)
            assertThat(registration.containsKey("attestation")).isFalse()
            assertThat(registration.containsKey("environment")).isFalse()

            val binding = body(server.last { it.first == "POST devices/push-token" }.second)
            assertThat(binding["transport"]!!.jsonPrimitive.content).isEqualTo("relay")
            assertThat(binding["platform"]!!.jsonPrimitive.content).isEqualTo("android")
            assertThat(binding["relayToken"]!!.jsonPrimitive.content).isEqualTo("rt_1aaaaaaaa")
            val key = Base64Url.decode(binding["payloadKey"]!!.jsonPrimitive.content)
            assertThat(key).hasLength(32)
            assertThat(binding["keyId"]!!.jsonPrimitive.content).isEqualTo(EnvelopeCrypto.kid(key))
            assertThat(registrar.payloadKey(serverId, EnvelopeCrypto.kid(key))).isEqualTo(key)
            assertThat(registrar.status.value).isEqualTo(PushStatus.Registered(serverId, "relay.example", "1aaaaaaaa".takeLast(8)))
        }

    @Test
    fun anUnchangedRegistrationOnlyReassertsTheBinding() =
        runTest {
            val (backend, registrar) = setUp()
            backend.logIn("b@example.com", "pw")
            registrar.register()
            registrar.register()
            assertThat(relay.count { it.first == "POST v1/devices" }).isEqualTo(1)
            assertThat(server.count { it.first == "POST devices/push-token" }).isEqualTo(2)
        }

    @Test
    fun aNewFcmTokenRegistersAgainAndKeepsTheKeyForInFlightPushes() =
        runTest {
            val (backend, registrar) = setUp()
            backend.logIn("b@example.com", "pw")
            registrar.register()
            val firstKid = body(server.last { it.first == "POST devices/push-token" }.second)["keyId"]!!.jsonPrimitive.content
            registrar.register(knownToken = "fcm-2")
            assertThat(relay.count { it.first == "POST v1/devices" }).isEqualTo(2)
            // The old registration goes on both sides (C7).
            assertThat(server.filter { it.first == "DELETE devices/push-token" }.map { it.second }).containsExactly("""{"relayToken":"rt_1aaaaaaaa"}""")
            assertThat(relay.filter { it.first == "POST v1/devices/unregister" }.map { it.second }).contains("""{"relayToken":"rt_1aaaaaaaa"}""")
            assertThat(registrar.payloadKey(serverId, firstKid)).isNotNull()
        }

    @Test
    fun attestationIsSentWhenAvailable() =
        runTest {
            val attestor = Attestor { challenge, token, server -> buildJsonObject { put("type", JsonPrimitive("play-integrity")); put("challenge", JsonPrimitive(challenge)); put("token", JsonPrimitive("$token@$server")) } }
            val (backend, registrar) = setUp(attestor = attestor)
            backend.logIn("b@example.com", "pw")
            registrar.register()
            val attestation = body(relay.single { it.first == "POST v1/devices" }.second)["attestation"]!!.jsonObject
            assertThat(attestation["challenge"]!!.jsonPrimitive.content).isEqualTo("Y2hhbGxlbmdl")
            assertThat(attestation["token"]!!.jsonPrimitive.content).isEqualTo("fcm-1@$serverId")
        }

    @Test
    fun anApnsOrUnconfiguredServerCannotReachAndroid() =
        runTest {
            val (backend, registrar) = setUp()
            backend.logIn("b@example.com", "pw")
            pushConfig = """{"transport":"apns","apnsEnvironment":"production"}"""
            registrar.register()
            assertThat(registrar.status.value).isEqualTo(PushStatus.ServerUnsupported(PushTransport.APNS))
            pushConfig = """{"transport":"none"}"""
            registrar.register()
            assertThat(registrar.status.value).isEqualTo(PushStatus.ServerUnsupported(PushTransport.NONE))
            assertThat(relay).isEmpty()
        }

    @Test
    fun aRelayOffTheAllowListIsRefused() =
        runTest {
            val (backend, registrar) = setUp(allowed = "other.example")
            backend.logIn("b@example.com", "pw")
            registrar.register()
            assertThat(registrar.status.value).isEqualTo(PushStatus.RelayNotAllowed("relay.example"))
            assertThat(relay).isEmpty()
        }

    @Test
    fun noTokenMeansNoRegistration() =
        runTest {
            val (backend, registrar) = setUp(fcmToken = null)
            backend.logIn("b@example.com", "pw")
            registrar.register()
            assertThat(registrar.status.value).isEqualTo(PushStatus.NoToken)
        }

    @Test
    fun signingOutRevokesBothHalvesAndForgetsTheKey() =
        runTest {
            val (backend, registrar) = setUp()
            backend.sessionListener = registrar
            backend.logIn("b@example.com", "pw")
            registrar.register()
            val kid = body(server.last { it.first == "POST devices/push-token" }.second)["keyId"]!!.jsonPrimitive.content
            backend.logOut()
            assertThat(server.map { it.first }).contains("DELETE devices/push-token")
            assertThat(body(server.last { it.first == "DELETE devices/push-token" }.second)["relayToken"]!!.jsonPrimitive.content).isEqualTo("rt_1aaaaaaaa")
            assertThat(relay.filter { it.first == "POST v1/devices/unregister" }.map { it.second }).contains("""{"relayToken":"rt_1aaaaaaaa"}""")
            assertThat(registrar.payloadKey(serverId, kid)).isNull()
            assertThat(registrar.status.value).isEqualTo(PushStatus.SignedOut)
        }

    @Test
    fun aRevokedRelayTokenIsReplacedWithAFreshRegistration() =
        runTest {
            val (backend, registrar) = setUp()
            backend.logIn("b@example.com", "pw")
            registrar.register()
            val firstKid = body(server.last { it.first == "POST devices/push-token" }.second)["keyId"]!!.jsonPrimitive.content

            // The relay reported rt_1 unregistered; the server now answers 410 (C6).
            revokedTokens += "rt_1aaaaaaaa"
            registrar.register()

            assertThat(relay.count { it.first == "POST v1/devices" }).isEqualTo(2)
            val binding = body(server.last { it.first == "POST devices/push-token" }.second)
            assertThat(binding["relayToken"]!!.jsonPrimitive.content).isEqualTo("rt_2aaaaaaaa")
            assertThat(binding["keyId"]!!.jsonPrimitive.content).isNotEqualTo(firstKid)
            assertThat(registrar.payloadKey(serverId, firstKid)).isNull()
            assertThat(registrar.status.value).isEqualTo(PushStatus.Registered(serverId, "relay.example", "2aaaaaaaa".takeLast(8)))
        }

    @Test
    fun anUnknownTransportKeepsTheCurrentRegistration() =
        runTest {
            val (backend, registrar) = setUp()
            backend.logIn("b@example.com", "pw")
            registrar.register()
            pushConfig = """{"transport":"carrier-pigeon"}"""
            registrar.register()
            assertThat(registrar.status.value).isInstanceOf(PushStatus.Failed::class.java)
            assertThat(server.none { it.first == "DELETE devices/push-token" }).isTrue()
            assertThat(relay.none { it.first == "POST v1/devices/unregister" }).isTrue()

            // Back to normal: still the same registration, nothing new at the relay (C18).
            pushConfig = """{"transport":"relay","relay":{"url":"https://relay.example/","serverId":"$serverId"}}"""
            registrar.register()
            assertThat(relay.count { it.first == "POST v1/devices" }).isEqualTo(1)
        }

    @Test
    fun theRelayUrlIsComparedNormalised() =
        runTest {
            val (backend, registrar) = setUp()
            backend.logIn("b@example.com", "pw")
            registrar.register()
            pushConfig = """{"transport":"relay","relay":{"url":"https://relay.example","serverId":"$serverId"}}"""
            registrar.register()
            assertThat(relay.count { it.first == "POST v1/devices" }).isEqualTo(1)
        }

    @Test
    fun movingToAnotherRelayRegistersThereAndLeavesTheOldOne() =
        runTest {
            val (backend, registrar) = setUp(allowed = "relay.example,push.example")
            backend.logIn("b@example.com", "pw")
            registrar.register()
            pushConfig = """{"transport":"relay","relay":{"url":"https://push.example/jolt","serverId":"$serverId"}}"""
            registrar.register()
            assertThat(relay.count { it.first == "POST v1/devices" }).isEqualTo(2)
            assertThat(relay.single { it.first == "POST v1/devices/unregister" }.second).isEqualTo("""{"relayToken":"rt_1aaaaaaaa"}""")
            assertThat(server.single { it.first == "DELETE devices/push-token" }.second).isEqualTo("""{"relayToken":"rt_1aaaaaaaa"}""")
        }
}
