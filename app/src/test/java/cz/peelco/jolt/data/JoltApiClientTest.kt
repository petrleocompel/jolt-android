package cz.peelco.jolt.data

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.data.api.JoltApiClient
import cz.peelco.jolt.data.api.JoltApiException
import cz.peelco.jolt.data.api.StimulusPermissionUpdate
import cz.peelco.jolt.domain.model.Me
import cz.peelco.jolt.domain.model.StimulusPermission
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class JoltApiClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val me = """{"id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","handle":"bob","displayName":"Bob","email":"b@example.com","inviteCode":"X"}"""

    @Test
    fun keepsTheBasePathAndSendsTheBearerToken() =
        runTest {
            var seenUrl = ""
            var seenAuth: String? = null
            val engine =
                MockEngine { request ->
                    seenUrl = request.url.toString()
                    seenAuth = request.headers[HttpHeaders.Authorization]
                    respond(me, HttpStatusCode.OK, json)
                }
            val client = JoltApiClient("https://jolt.example.com/api/v1", engine, token = "t0k")
            val profile = client.send(HttpMethod.Get, "me", Me.serializer())
            assertThat(seenUrl).isEqualTo("https://jolt.example.com/api/v1/me")
            assertThat(seenAuth).isEqualTo("Bearer t0k")
            assertThat(profile.policies).isNull()
        }

    @Test
    fun mapsStatusCodesToErrors() =
        runTest {
            val unauthorized = JoltApiClient("https://x.example/api/v1", MockEngine { respondError(HttpStatusCode.Unauthorized) })
            assertThat(runCatching { unauthorized.send(HttpMethod.Get, "me", Me.serializer()) }.exceptionOrNull())
                .isInstanceOf(JoltApiException.Unauthorized::class.java)

            val refused =
                JoltApiClient("https://x.example/api/v1", MockEngine { respond("""{"message":"They haven't allowed zap."}""", HttpStatusCode.Forbidden, json) })
            val error = runCatching { refused.send(HttpMethod.Get, "me", Me.serializer()) }.exceptionOrNull()
            assertThat(error).isInstanceOf(JoltApiException.Server::class.java)
            assertThat(error!!.message).isEqualTo("They haven't allowed zap.")

            val proxy = JoltApiClient("https://x.example/api/v1", MockEngine { respond("<html>502</html>", HttpStatusCode.BadGateway) })
            assertThat(runCatching { proxy.send(HttpMethod.Get, "me", Me.serializer()) }.exceptionOrNull()!!.message)
                .isEqualTo("The server rejected that request.")
        }

    @Test
    fun aNonJsonSuccessIsNotAJoltServer() =
        runTest {
            val client = JoltApiClient("https://x.example/api/v1", MockEngine { respond("<html>hi</html>", HttpStatusCode.OK) })
            assertThat(runCatching { client.send(HttpMethod.Get, "me", Me.serializer()) }.exceptionOrNull())
                .isInstanceOf(JoltApiException.NotJolt::class.java)
        }

    @Test
    fun aDroppedConnectionIsATransportError() =
        runTest {
            val client = JoltApiClient("https://x.example/api/v1", MockEngine { throw IOException("Connection reset") })
            assertThat(runCatching { client.send(HttpMethod.Get, "me", Me.serializer()) }.exceptionOrNull())
                .isInstanceOf(JoltApiException.Transport::class.java)
        }

    @Test
    fun theProbeTreats401AsAJoltServer() =
        runTest {
            assertThat(JoltApiClient.probe("https://x.example/api/v1", MockEngine { respondError(HttpStatusCode.Unauthorized) }).isSuccess).isTrue()
            assertThat(JoltApiClient.probe("https://x.example/api/v1", MockEngine { respondError(HttpStatusCode.NotFound) }).exceptionOrNull())
                .isInstanceOf(JoltApiException.NotJolt::class.java)
        }
}

class StimulusPermissionUpdateTest {
    private val permission = StimulusPermission(isAllowed = true, maxIntensity = 40, cooldownSeconds = 60, automationAllowed = true, automationAllowedEffective = true)

    @Test
    fun anOrdinaryEditLeavesAutomationOut() {
        val body = StimulusPermissionUpdate.of(permission).toJson().toString()
        assertThat(body).isEqualTo("""{"isAllowed":true,"maxIntensity":40,"cooldownSeconds":60}""")
    }

    @Test
    fun anAnswerIsSentAndResetIsAnExplicitNull() {
        assertThat(StimulusPermissionUpdate.of(permission, StimulusPermissionUpdate.Automation.of(false)).toJson().toString())
            .isEqualTo("""{"isAllowed":true,"maxIntensity":40,"cooldownSeconds":60,"automationAllowed":false}""")
        assertThat(StimulusPermissionUpdate.of(permission, StimulusPermissionUpdate.Automation.of(null)).toJson().toString())
            .isEqualTo("""{"isAllowed":true,"maxIntensity":40,"cooldownSeconds":60,"automationAllowed":null}""")
    }

    @Test
    fun theReadOnlyEffectiveValueIsNeverSent() {
        assertThat(StimulusPermissionUpdate.of(permission).toJson().containsKey("automationAllowedEffective")).isFalse()
    }
}
