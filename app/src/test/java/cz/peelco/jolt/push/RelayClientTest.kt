package cz.peelco.jolt.push

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class RelayClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val seen = mutableListOf<String>()

    private fun client(baseUrl: String) =
        RelayClient(
            baseUrl,
            MockEngine { request ->
                seen += "${request.method.value} ${request.url} ${(request.body as? TextContent)?.text.orEmpty()}".trim()
                when {
                    request.url.encodedPath.endsWith("v1/challenge") -> respond("""{"challenge":"abc"}""", HttpStatusCode.OK, json)
                    request.url.encodedPath.endsWith("v1/devices") -> respond("""{"error":"app_not_allowed"}""", HttpStatusCode.Forbidden, json)
                    else -> respond("", HttpStatusCode.NoContent)
                }
            },
        )

    @Test
    fun theBaseUrlResolvesWithOrWithoutATrailingSlashAndUnderAPathPrefix() =
        runTest {
            client("https://relay.example").challenge()
            client("https://relay.example/").challenge()
            client("https://push.example/jolt-relay").challenge()
            client("https://push.example/jolt-relay/").challenge()
            assertThat(seen)
                .containsExactly(
                    "GET https://relay.example/v1/challenge",
                    "GET https://relay.example/v1/challenge",
                    "GET https://push.example/jolt-relay/v1/challenge",
                    "GET https://push.example/jolt-relay/v1/challenge",
                ).inOrder()
            assertThat(RelayClient.normalize(" https://push.example/jolt-relay// ")).isEqualTo("https://push.example/jolt-relay/")
        }

    @Test
    fun unregisteringPostsTheTokenInTheBody() =
        runTest {
            client("https://relay.example/").unregisterDevice("rt_abc")
            assertThat(seen.single()).isEqualTo("""POST https://relay.example/v1/devices/unregister {"relayToken":"rt_abc"}""")
        }

    @Test
    fun relayErrorCodesBecomeReadableMessages() =
        runTest {
            val error = runCatching { client("https://relay.example/").registerDevice(JsonObject(emptyMap())) }.exceptionOrNull() as RelayException
            assertThat(error.status).isEqualTo(403)
            assertThat(error.code).isEqualTo("app_not_allowed")
            assertThat(error.message).isEqualTo("The push relay doesn't accept this build of Jolt.")
            assertThat(RelayClient.message(503, "provider_unavailable")).contains("Firebase")
            assertThat(RelayClient.message(500, null)).isEqualTo("The push relay answered 500.")
        }
}
