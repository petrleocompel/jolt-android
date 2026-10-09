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
import java.time.Instant

class RelayClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val seen = mutableListOf<String>()

    private fun client(baseUrl: String) =
        RelayClient(
            baseUrl,
            MockEngine { request ->
                seen += "${request.method.value} ${request.url} ${(request.body as? TextContent)?.text.orEmpty()}".trim()
                when {
                    request.url.encodedPath.endsWith("v1/devices") -> respond("""{"error":"app_not_allowed"}""", HttpStatusCode.Forbidden, json)
                    else -> respond("", HttpStatusCode.NoContent)
                }
            },
        )

    @Test
    fun theBaseUrlResolvesWithOrWithoutATrailingSlashAndUnderAPathPrefix() =
        runTest {
            for (base in listOf("https://relay.example", "https://relay.example/", "https://push.example/jolt-relay", "https://push.example/jolt-relay/")) {
                client(base).unregisterDevice("rt_x")
            }
            assertThat(seen.map { it.substringBefore(" {") })
                .containsExactly(
                    "POST https://relay.example/v1/devices/unregister",
                    "POST https://relay.example/v1/devices/unregister",
                    "POST https://push.example/jolt-relay/v1/devices/unregister",
                    "POST https://push.example/jolt-relay/v1/devices/unregister",
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

    @Test
    fun retryAfterIsReadAsSecondsOrADateWithAMinuteAsTheFallback() {
        val now = Instant.parse("2026-10-09T12:00:00Z")
        assertThat(RelayClient.retryAfterSeconds("30", now)).isEqualTo(30)
        assertThat(RelayClient.retryAfterSeconds("Fri, 09 Oct 2026 12:02:00 GMT", now)).isEqualTo(120)
        assertThat(RelayClient.retryAfterSeconds(null, now)).isEqualTo(RelayClient.DEFAULT_RETRY_AFTER_SECONDS)
        assertThat(RelayClient.retryAfterSeconds("soon", now)).isEqualTo(RelayClient.DEFAULT_RETRY_AFTER_SECONDS)
    }

    @Test
    fun aRateLimitCarriesItsRetryAfter() =
        runTest {
            val client =
                RelayClient(
                    "https://relay.example/",
                    MockEngine {
                        respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "45"))
                    },
                )
            val error = runCatching { client.unregisterDevice("rt_x") }.exceptionOrNull() as RelayException
            assertThat(error.isRateLimited).isTrue()
            assertThat(error.retryAfterSeconds).isEqualTo(45)
        }
}
