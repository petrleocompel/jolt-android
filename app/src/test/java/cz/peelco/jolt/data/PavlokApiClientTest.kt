package cz.peelco.jolt.data

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.data.pavlok.PavlokApiClient
import cz.peelco.jolt.data.pavlok.PavlokApiException
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class PavlokApiClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun loginNestsCredentialsAndReadsTheTokenFromUser() =
        runTest {
            var sent = ""
            val client =
                PavlokApiClient(
                    MockEngine { request ->
                        sent = (request.body as TextContent).text
                        respond("""{"user":{"id":42,"email":"a@example.com","firstName":"Ann","token":"jwt"}}""", HttpStatusCode.OK, json)
                    },
                )
            val result = client.login("a@example.com", "pw")
            assertThat(sent).isEqualTo("""{"user":{"email":"a@example.com","password":"pw"}}""")
            assertThat(result.token).isEqualTo("jwt")
            assertThat(result.account.userId).isEqualTo(42)
            assertThat(result.account.displayName).isEqualTo("Ann")
        }

    @Test
    fun permissionsAreKeyedByTheGrantingFriend() =
        runTest {
            val client =
                PavlokApiClient(
                    MockEngine {
                        respond(
                            """{"pokePermissions":[{"userId":7,"friendId":42,"canVibrate":true,"canChime":false,"canZap":true,"maxZapValue":30}]}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    },
                ).apply { token = "jwt" }
            val grant = client.receivedPokePermissions().getValue(7)
            assertThat(grant.allows(StimulusKind.ZAP)).isTrue()
            assertThat(grant.allows(StimulusKind.BEEP)).isFalse()
            assertThat(grant.maxIntensity(StimulusKind.ZAP)).isEqualTo(30)
            assertThat(grant.maxIntensity(StimulusKind.VIBE)).isEqualTo(100)
        }

    @Test
    fun aPokeUsesPavloksVocabulary() =
        runTest {
            var sent = ""
            val client =
                PavlokApiClient(
                    MockEngine { request ->
                        sent = (request.body as TextContent).text
                        respond("{}", HttpStatusCode.OK, json)
                    },
                ).apply { token = "jwt" }
            client.sendPoke(7, StimulusConfig(StimulusKind.BEEP, 50, 2))
            assertThat(sent).isEqualTo("""{"stimulus":{"type":"Beep","intensity":50,"count":2}}""")
        }

    @Test
    fun withoutATokenNothingIsSent() =
        runTest {
            val client = PavlokApiClient(MockEngine { error("must not be called") })
            assertThat(runCatching { client.friends() }.exceptionOrNull()).isInstanceOf(PavlokApiException.Unauthorized::class.java)
        }

    @Test
    fun errorMessagesReadBothShapes() {
        val strings = Json.parseToJsonElement("""{"errors":["Timestamp must be without timezone"]}""").jsonObject
        assertThat(PavlokApiClient.errorMessage(strings, 422)).isEqualTo("Timestamp must be without timezone")
        val fastApi = Json.parseToJsonElement("""{"errors":[{"loc":["query","mac_address"],"msg":"field required"}]}""").jsonObject
        assertThat(PavlokApiClient.errorMessage(fastApi, 422)).isEqualTo("mac_address: field required")
        assertThat(PavlokApiClient.errorMessage(null, 500)).isEqualTo("Pavlok returned HTTP 500.")
    }

    @Test
    fun journalNamesMapToKinds() {
        assertThat(PavlokApiClient.stimulusKind("Chime")).isEqualTo(StimulusKind.BEEP)
        assertThat(PavlokApiClient.stimulusKind("motor")).isEqualTo(StimulusKind.VIBE)
        assertThat(PavlokApiClient.stimulusKind("Light")).isNull()
    }
}
