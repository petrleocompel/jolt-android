package cz.peelco.jolt.data.pavlok

import cz.peelco.jolt.domain.model.InstantSerializer
import cz.peelco.jolt.domain.model.PavlokAccount
import cz.peelco.jolt.domain.model.PavlokFriend
import cz.peelco.jolt.domain.model.PavlokPokePermission
import cz.peelco.jolt.domain.model.PavlokStimulusLogEntry
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

sealed class PavlokApiException(
    message: String,
) : Exception(message) {
    class Unauthorized : PavlokApiException("Your Pavlok session has expired. Sign in again.")

    class Server(
        val status: Int,
        message: String,
    ) : PavlokApiException(message)

    class Transport(
        detail: String,
    ) : PavlokApiException(detail)

    class NotPavlok : PavlokApiException("That didn't look like the Pavlok API.")
}

/**
 * HTTP client for a Pavlok account (`api.pavlok.com/api/v5`). Every endpoint
 * and shape was verified against a real account on iOS (see the iOS
 * repository's `docs/PAVLOK-API.md`): login credentials are nested under
 * `user`, and the JWT lives at `user.token`.
 */
class PavlokApiClient(
    engine: HttpClientEngine,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    @Volatile var token: String? = null

    private val http =
        HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }

    data class LoginResult(
        val account: PavlokAccount,
        val token: String,
    )

    suspend fun login(
        email: String,
        password: String,
    ): LoginResult {
        // Nested under "user": a flat body is rejected with 422.
        val body =
            buildJsonObject {
                put("user", buildJsonObject { put("email", email); put("password", password) })
            }
        val json = send(HttpMethod.Post, "users/login", body, authorized = false)
        val user = json["user"] as? JsonObject ?: throw PavlokApiException.NotPavlok()
        val token = user.string("token") ?: throw PavlokApiException.NotPavlok()
        val id = user.int("id") ?: throw PavlokApiException.NotPavlok()
        return LoginResult(PavlokAccount(id, user.string("email") ?: email, user.string("firstName"), user.string("lastName")), token)
    }

    suspend fun friends(): List<PavlokFriend> =
        send(HttpMethod.Get, "friendships/get-friends").array("users").mapNotNull { row ->
            val id = row.int("id") ?: return@mapNotNull null
            PavlokFriend(id, row.string("firstName"), row.string("lastName"), row.string("username"), row.string("profilePictureUrl"))
        }

    /**
     * What each friend allows me to send them, keyed by friend id. On
     * `/received`, `userId` is the granting friend.
     */
    suspend fun receivedPokePermissions(): Map<Int, PavlokPokePermission> =
        send(HttpMethod.Get, "poke-permissions/received").array("pokePermissions").mapNotNull { row ->
            val granter = row.int("userId") ?: return@mapNotNull null
            granter to
                PavlokPokePermission(
                    friendId = granter,
                    canVibrate = row.bool("canVibrate"),
                    canChime = row.bool("canChime"),
                    canZap = row.bool("canZap"),
                    maxZapValue = row.int("maxZapValue") ?: 0,
                )
        }.toMap()

    /** The caller checks the permission first; the server enforces it too. */
    suspend fun sendPoke(
        friendId: Int,
        stimulus: StimulusConfig,
    ) {
        val body =
            buildJsonObject {
                put(
                    "stimulus",
                    buildJsonObject {
                        put("type", stimulusName(stimulus.kind))
                        put("intensity", stimulus.intensity)
                        put("count", stimulus.repetitions)
                    },
                )
            }
        send(HttpMethod.Post, "pokes/send/user/$friendId", body)
    }

    data class DeviceRecord(
        val id: Int,
        val macAddress: String,
        val name: String,
    )

    suspend fun devices(): List<DeviceRecord> =
        send(HttpMethod.Get, "user-devices/").array("devices").mapNotNull { row ->
            val id = row.int("id") ?: return@mapNotNull null
            val mac = row.string("macAddress") ?: return@mapNotNull null
            DeviceRecord(id, mac, row.string("name") ?: "Pavlok")
        }

    /** The device's own uploaded log. No sender attribution. */
    suspend fun stimulusJournal(
        macAddress: String,
        page: Int = 1,
        pageSize: Int = 25,
    ): List<PavlokStimulusLogEntry> {
        val query =
            listOf("mac_address" to macAddress, "page" to page.toString(), "page_size" to pageSize.toString()) +
                // Repeated `types` params, exactly as the official app sends them.
                listOf("Zap", "Beep", "Vibe").map { "types" to it }
        return send(HttpMethod.Get, "diagnostic_logs/", query = query).array("items").mapIndexedNotNull { index, row ->
            val stamp = row.string("ts") ?: return@mapIndexedNotNull null
            val date = runCatching { InstantSerializer.parseInstant(stamp) }.getOrNull() ?: return@mapIndexedNotNull null
            val name = row.string("name") ?: "?"
            // `id` comes back as 0 for every row, so it cannot be the identity.
            PavlokStimulusLogEntry("$stamp-$name-$index", stimulusKind(name), name, date)
        }
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        authorized: Boolean = true,
        query: List<Pair<String, String>> = emptyList(),
    ): JsonObject {
        val bearer = if (authorized) token ?: throw PavlokApiException.Unauthorized() else null
        val response =
            try {
                http.request("$baseUrl/$path") {
                    this.method = method
                    header(HttpHeaders.Accept, "application/json")
                    bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    query.forEach { (name, value) -> parameter(name, value) }
                    if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw PavlokApiException.Transport(error.message ?: "The connection failed.")
            }
        if (response.status.value == 401) throw PavlokApiException.Unauthorized()
        val json = runCatching { Json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
        if (response.status.value !in 200..299) throw PavlokApiException.Server(response.status.value, errorMessage(json, response.status.value))
        return json ?: JsonObject(emptyMap())
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.pavlok.com/api/v5"

        /** Pavlok's vocabulary, matching the journal's `types=` filter. */
        fun stimulusName(kind: StimulusKind): String =
            when (kind) {
                StimulusKind.ZAP -> "Zap"
                StimulusKind.BEEP -> "Beep"
                StimulusKind.VIBE -> "Vibe"
            }

        fun stimulusKind(name: String): StimulusKind? =
            when (name.lowercase()) {
                "zap" -> StimulusKind.ZAP
                "beep", "chime" -> StimulusKind.BEEP
                "vibe", "vibrate", "motor" -> StimulusKind.VIBE
                else -> null
            }

        /**
         * Pavlok reports errors as `{"errors": [...]}` holding plain strings or
         * FastAPI validation objects (`{"loc": …, "msg": …}`).
         */
        fun errorMessage(
            json: JsonObject?,
            status: Int,
        ): String {
            val errors = (json?.get("errors") as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return "Pavlok returned HTTP $status."
            val parts =
                errors.mapNotNull { entry ->
                    when (entry) {
                        is JsonPrimitive -> entry.contentOrNull
                        is JsonObject -> {
                            val field = (entry["loc"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.lastOrNull()
                            val message = entry.string("msg") ?: "invalid"
                            field?.let { "$it: $message" } ?: message
                        }
                        else -> null
                    }
                }
            return if (parts.isEmpty()) "Pavlok returned HTTP $status." else parts.joinToString(", ")
        }
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.array(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
