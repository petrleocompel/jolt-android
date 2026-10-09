package cz.peelco.jolt.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * Hosts the app may register push tokens with (protocol §8): a self-hosted
 * server cannot redirect this phone's FCM token to an arbitrary relay.
 */
class RelayAllowList(
    hosts: Collection<String>,
) {
    private val hosts = hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** HTTPS and an exact host match; no wildcards. */
    fun allows(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase() in hosts
    }

    val isEmpty: Boolean get() = hosts.isEmpty()

    companion object {
        fun parse(setting: String) = RelayAllowList(setting.split(','))
    }
}

class RelayException(
    val status: Int,
    /** The relay's `error` code, such as `unknown_server` or `attestation_failed`. */
    val code: String?,
    message: String,
) : Exception(message)

/** The app side of jolt-relay protocol v1 §4: challenge, register, unregister. */
class RelayClient(
    baseUrl: String,
    engine: HttpClientEngine,
) {
    private val base = normalize(baseUrl)
    private val json = Json { ignoreUnknownKeys = true }
    private val http =
        HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }

    @Serializable
    data class Challenge(
        val challenge: String,
        val expiresAt: String? = null,
    )

    @Serializable
    private data class Registration(
        val relayToken: String,
    )

    suspend fun challenge(): Challenge = json.decodeFromString(Challenge.serializer(), send(HttpMethod.Get, "v1/challenge"))

    /** `POST /v1/devices`; answers the new `relayToken`. */
    suspend fun registerDevice(body: JsonObject): String = json.decodeFromString(Registration.serializer(), send(HttpMethod.Post, "v1/devices", body)).relayToken

    /**
     * `POST /v1/devices/unregister`; 204 even for an unknown token. The token
     * goes in the body so it stays out of access logs (protocol C5).
     */
    suspend fun unregisterDevice(relayToken: String) {
        send(HttpMethod.Post, "v1/devices/unregister", buildJsonObject { put("relayToken", JsonPrimitive(relayToken)) })
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): String {
        val response =
            try {
                http.request(base + path) {
                    this.method = method
                    header(HttpHeaders.Accept, "application/json")
                    if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw RelayException(0, null, error.message ?: "Couldn't reach the push relay.")
            }
        val text = response.bodyAsText()
        val status = response.status.value
        if (status in 200..299) return text
        val code = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        throw RelayException(status, code, message(status, code))
    }

    fun close() = http.close()

    companion object {
        /**
         * The relay's base URL with exactly one trailing slash, so `v1/…`
         * resolves under any path prefix (protocol C1).
         */
        fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/"

        fun message(
            status: Int,
            code: String?,
        ): String =
            when (code) {
                "unknown_server" -> "The push relay doesn't know this server yet. Try again in a minute."
                "server_blocked" -> "The push relay has blocked this server."
                "attestation_failed" -> "The push relay couldn't verify this copy of Jolt."
                "app_not_allowed" -> "The push relay doesn't accept this build of Jolt."
                "registration_closed" -> "The push relay isn't taking new registrations right now."
                "provider_unavailable" -> "The push relay can't reach Firebase right now. Try again later."
                "invalid_request", "invalid_json" -> "The push relay didn't understand the registration."
                else -> "The push relay answered $status."
            }
    }
}
