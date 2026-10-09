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

/** The app side of jolt-relay protocol v1 §4: challenge, register, revoke. */
class RelayClient(
    baseUrl: String,
    engine: HttpClientEngine,
) {
    private val base = baseUrl.trimEnd('/')
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

    /** `DELETE /v1/devices/{relayToken}`; 204 even for an unknown token. */
    suspend fun deleteDevice(relayToken: String) {
        send(HttpMethod.Delete, "v1/devices/$relayToken")
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): String {
        val response =
            try {
                http.request("$base/$path") {
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
        fun message(
            status: Int,
            code: String?,
        ): String =
            when (code) {
                "unknown_server" -> "The push relay doesn't know this server yet. Try again in a minute."
                "server_blocked" -> "The push relay has blocked this server."
                "attestation_failed" -> "The push relay couldn't verify this copy of Jolt."
                else -> "The push relay answered $status."
            }
    }
}
