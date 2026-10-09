package cz.peelco.jolt.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Everything a Jolt Server request can fail with, phrased for the user. */
sealed class JoltApiException(
    message: String,
) : Exception(message) {
    /** The server answered with a JSON `Error` body. */
    class Server(
        val status: Int,
        message: String,
        /** The machine-readable `error` code, when the body carries one (`relay_token_revoked`). */
        val code: String? = null,
    ) : JoltApiException(message)

    /** 401: token missing, expired, or issued by a different server. */
    class Unauthorized : JoltApiException("Your session has expired. Sign in again.")

    /** Reached something, but it wasn't a Jolt Server speaking JSON. */
    class NotJolt : JoltApiException("That URL didn't respond like a Jolt server. Check the address includes /api/v1.")

    /** The request never got an answer. It may still have reached the server. */
    class Transport(
        detail: String,
    ) : JoltApiException(detail)
}

/** The JSON settings every Jolt request and response goes through. */
val JoltJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/**
 * Thin HTTP client for the Jolt Server contract (`openapi/jolt-v1.yaml`).
 * Transport only: URLs, JSON, status codes and the bearer token. Mapping the
 * contract onto the repositories is `HttpSocialBackend`'s job.
 */
class JoltApiClient(
    /** Full API base including `/api/v1`, no trailing slash. */
    private val baseUrl: String,
    engine: HttpClientEngine,
    @Volatile var token: String? = null,
) {
    private val http =
        HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 15_000
            }
        }

    fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
    ): JsonElement = JoltJson.encodeToJsonElement(serializer, value)

    /** Sends a request and decodes the answer. */
    suspend fun <T> send(
        method: HttpMethod,
        path: String,
        deserializer: DeserializationStrategy<T>,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): T {
        val text = perform(method, path, body, query)
        if (text.isBlank()) throw JoltApiException.NotJolt()
        return try {
            JoltJson.decodeFromString(deserializer, text)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw JoltApiException.NotJolt()
        }
    }

    /** For endpoints that answer `204 No Content`. */
    suspend fun sendIgnoringResponse(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
    ) {
        perform(method, path, body, emptyMap())
    }

    private suspend fun perform(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        query: Map<String, String>,
    ): String {
        val response =
            try {
                http.request("$baseUrl/$path") {
                    this.method = method
                    header(HttpHeaders.Accept, "application/json")
                    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    query.forEach { (name, value) -> parameter(name, value) }
                    if (body != null) setBody(TextContent(JoltJson.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw JoltApiException.Transport(error.message ?: "The connection failed.")
            }
        val text = response.bodyAsText()
        return when (val status = response.status.value) {
            in 200..299 -> text
            401 -> throw JoltApiException.Unauthorized()
            else -> throw JoltApiException.Server(status, errorMessage(text), errorCode(text))
        }
    }

    fun close() = http.close()

    companion object {
        /**
         * Pulls `{ "message": … }` out of an error body, falling back to
         * something readable for a proxy's HTML error page.
         */
        fun errorMessage(text: String): String =
            runCatching { JoltJson.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: "The server rejected that request."

        /** The `error` code of an error body, if any. */
        fun errorCode(text: String): String? =
            runCatching { JoltJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()

        /**
         * Probes a server without credentials. `GET /me` is the cheapest
         * authenticated endpoint: a Jolt Server answers 401, and anything that
         * isn't one almost certainly won't, so a 401 is success here.
         */
        suspend fun probe(
            baseUrl: String,
            engine: HttpClientEngine,
        ): Result<Unit> {
            val client = HttpClient(engine) { expectSuccess = false }
            return try {
                val response = client.request("$baseUrl/me") { header(HttpHeaders.Accept, "application/json") }
                when (val status = response.status.value) {
                    200, 401 -> Result.success(Unit)
                    404 -> Result.failure(JoltApiException.NotJolt())
                    else -> Result.failure(JoltApiException.Server(status, "Server answered $status."))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(JoltApiException.Transport(error.message ?: "The connection failed."))
            } finally {
                client.close()
            }
        }
    }
}
