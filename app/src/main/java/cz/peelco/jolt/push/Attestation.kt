package cz.peelco.jolt.push

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Proves to the relay that this is a genuine build, where the platform can. */
fun interface Attestor {
    /** The `attestation` object for `POST /v1/devices`, or null to register without one. */
    suspend fun attest(
        challenge: String,
        token: String,
        serverId: String,
    ): JsonObject?

    companion object {
        val NONE = Attestor { _, _, _ -> null }

        /**
         * The client data both platforms bind into attestation (protocol C11):
         * `jolt-relay-v1|<challenge>|<token>|<serverId>`, with the challenge
         * exactly as `/v1/challenge` returned it.
         */
        fun clientData(
            challenge: String,
            token: String,
            serverId: String,
        ): String = "jolt-relay-v1|$challenge|$token|$serverId"

        /** Play Integrity's `requestHash`: base64url(SHA-256(client data)). */
        fun requestHash(
            challenge: String,
            token: String,
            serverId: String,
        ): String = Base64Url.encode(EnvelopeCrypto.sha256(clientData(challenge, token, serverId).toByteArray(Charsets.UTF_8)))
    }
}

/**
 * Play Integrity, standard request, binding the registration through
 * `requestHash` (protocol C11). Optional: the relay runs in `log` mode
 * until launch, so a phone without Play Services, a sideloaded build or a
 * missing cloud project number registers without attestation instead of not
 * at all.
 */
class PlayIntegrityAttestor(
    private val context: Context,
    private val cloudProjectNumber: Long,
) : Attestor {
    override suspend fun attest(
        challenge: String,
        token: String,
        serverId: String,
    ): JsonObject? {
        if (cloudProjectNumber <= 0) return null
        return try {
            val provider =
                IntegrityManagerFactory
                    .createStandard(context)
                    .prepareIntegrityToken(StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build())
                    .await()
            val integrityToken =
                provider
                    .request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(Attestor.requestHash(challenge, token, serverId)).build())
                    .await()
                    .token()
            buildJsonObject {
                put("type", JsonPrimitive("play-integrity"))
                put("challenge", JsonPrimitive(challenge))
                put("token", JsonPrimitive(integrityToken))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("JoltPush", "Play Integrity unavailable, registering without attestation: ${error.message}")
            null
        }
    }
}
