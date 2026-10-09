package cz.peelco.jolt.push

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
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
         * The value bound into the attestation:
         * `base64url(SHA-256("jolt-relay-v1|<challenge>|<token>|<serverId>"))`.
         * The same string App Attest hashes into its client data; the
         * protocol doesn't spell out the Play Integrity nonce, so this
         * mirrors it (see the report to the relay owners).
         */
        fun nonce(
            challenge: String,
            token: String,
            serverId: String,
        ): String = Base64Url.encode(EnvelopeCrypto.sha256("jolt-relay-v1|$challenge|$token|$serverId".toByteArray(Charsets.UTF_8)))
    }
}

/**
 * Play Integrity (classic request). Optional: the relay runs in `log` mode
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
            val request =
                IntegrityTokenRequest
                    .builder()
                    .setNonce(Attestor.nonce(challenge, token, serverId))
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build()
            val integrityToken = IntegrityManagerFactory.create(context).requestIntegrityToken(request).await().token()
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
