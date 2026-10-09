package cz.peelco.jolt.push

import cz.peelco.jolt.domain.model.IncomingPush
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RFC 4648 §5 base64url without padding, the relay protocol's only encoding. */
object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Tolerates padding, though the protocol never sends it. */
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))
}

/** `{ "v": 1, "kid": …, "n": …, "ct": … }`: an encrypted push body. */
@Serializable
data class Envelope(
    val v: Int,
    val kid: String,
    val n: String,
    val ct: String,
)

/**
 * Payload envelope v1 from jolt-relay `spec/protocol-v1.md` §6: AES-256-GCM
 * under the registration's `payloadKey`, a fresh 12-byte nonce, and AAD that
 * binds the ciphertext to the server and the message kind.
 */
object EnvelopeCrypto {
    const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val json = Json { ignoreUnknownKeys = true }

    class EnvelopeException(
        message: String,
    ) : Exception(message)

    /** `base64url(SHA-256(payloadKey)[0..8])`. */
    fun kid(key: ByteArray): String = Base64Url.encode(sha256(key).copyOfRange(0, 8))

    /** `jolt-push-v1|<serverId>|<kind>`. */
    fun aad(
        serverId: String,
        kind: String,
    ): ByteArray = "jolt-push-v1|$serverId|$kind".toByteArray(Charsets.UTF_8)

    fun newKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(32).also(random::nextBytes)

    /** Encrypts [plaintext]; the server's half, kept for tests and symmetry. */
    fun seal(
        key: ByteArray,
        serverId: String,
        kind: String,
        plaintext: ByteArray,
        nonce: ByteArray = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes),
    ): Envelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(serverId, kind))
        return Envelope(VERSION, kid(key), Base64Url.encode(nonce), Base64Url.encode(cipher.doFinal(plaintext)))
    }

    /** Decrypts [envelope]; throws on a wrong key, server, kind or a tampered body. */
    fun open(
        key: ByteArray,
        serverId: String,
        kind: String,
        envelope: Envelope,
    ): ByteArray {
        if (envelope.v != VERSION) throw EnvelopeException("Unsupported envelope version ${envelope.v}.")
        val nonce = Base64Url.decode(envelope.n)
        if (nonce.size != NONCE_BYTES) throw EnvelopeException("Bad nonce length.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(serverId, kind))
        return try {
            cipher.doFinal(Base64Url.decode(envelope.ct))
        } catch (error: java.security.GeneralSecurityException) {
            throw EnvelopeException("Decryption failed: ${error.javaClass.simpleName}")
        }
    }

    /** What an FCM data message turned out to be. */
    sealed interface Decoded {
        /** Decrypted and checked; safe to act on. */
        data class Push(
            val push: IncomingPush,
            val serverId: String,
        ) : Decoded

        /**
         * A relay push of a known kind that can't be read here: no key, wrong
         * key, or a failed check. The message is dropped and only the generic
         * fallback text is shown.
         */
        data class Unreadable(
            val kind: String,
            val reason: String,
        ) : Decoded

        /** Not a relay push at all. */
        data object NotJolt : Decoded
    }

    /**
     * Decodes `{"type", "srv", "enc"}` from an FCM data message (§5 and §6):
     *
     * 1. look up the key for `srv` and the envelope's `kid`;
     * 2. decrypt with AAD built from `srv` and `type`;
     * 3. check the decrypted `type` equals the outer one;
     * 4. check the `serverId` inside equals `srv`.
     */
    fun decode(
        data: Map<String, String>,
        keyFor: (serverId: String, kid: String) -> ByteArray?,
    ): Decoded {
        val kind = data["type"]?.takeIf { it == "poke" || it == "test" } ?: return Decoded.NotJolt
        val serverId = data["srv"] ?: return Decoded.Unreadable(kind, "missing srv")
        val envelope =
            data["enc"]?.let { runCatching { json.decodeFromString(Envelope.serializer(), it) }.getOrNull() }
                ?: return Decoded.Unreadable(kind, "missing or malformed envelope")
        val key = keyFor(serverId, envelope.kid) ?: return Decoded.Unreadable(kind, "no payload key for $serverId/${envelope.kid}")
        val plaintext =
            try {
                open(key, serverId, kind, envelope)
            } catch (error: EnvelopeException) {
                return Decoded.Unreadable(kind, error.message ?: "decryption failed")
            }
        val body = runCatching { json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return Decoded.Unreadable(kind, "plaintext is not JSON")
        if (body["type"]?.jsonPrimitive?.contentOrNull != kind) return Decoded.Unreadable(kind, "inner type differs from outer")
        val innerServerId = (body[kind] as? JsonObject)?.get("serverId")?.jsonPrimitive?.contentOrNull
        if (innerServerId != serverId) return Decoded.Unreadable(kind, "serverId inside differs from srv")
        val push = IncomingPush.parse(body) ?: return Decoded.Unreadable(kind, "payload doesn't decode")
        return Decoded.Push(push, serverId)
    }

    internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** `srv_` + lowercase base32 of the first 16 bytes of SHA-256(Ed25519 public key). */
object ServerId {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun fromPublicKey(publicKey: ByteArray): String = "srv_" + base32(EnvelopeCrypto.sha256(publicKey).copyOfRange(0, 16))

    /** RFC 4648 base32, lowercase, no padding. */
    fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }
}
