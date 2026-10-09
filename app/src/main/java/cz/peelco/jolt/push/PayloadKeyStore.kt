package cz.peelco.jolt.push

import cz.peelco.jolt.data.secure.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Relay `payloadKey`s, per server, encrypted at rest by [SecretStore] (the
 * Android Keystore).
 *
 * Every new relay token comes with a fresh key. The one it replaces is kept
 * for [PREVIOUS_KEY_LIFETIME_MILLIS], selected by `kid`, so a push already in
 * flight under it still decrypts (protocol C8); after that it no longer
 * opens anything.
 */
class PayloadKeyStore(
    private val secrets: SecretStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class StoredKey(
        val kid: String,
        val key: String,
        /** When a newer key replaced this one; null for the current key. */
        val retiredAt: Long? = null,
    )

    private val serializer = ListSerializer(StoredKey.serializer())

    private fun storageKey(serverId: String) = "payloadKey:$serverId"

    private fun load(serverId: String): List<StoredKey> =
        secrets.get(storageKey(serverId))?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun save(
        serverId: String,
        keys: List<StoredKey>,
    ) {
        if (keys.isEmpty()) secrets.remove(storageKey(serverId)) else secrets.put(storageKey(serverId), Json.encodeToString(serializer, keys))
    }

    private fun StoredKey.isUsable(time: Long): Boolean = retiredAt == null || time - retiredAt < PREVIOUS_KEY_LIFETIME_MILLIS

    /** A fresh key for a new registration with [serverId]; the current one becomes the previous one. */
    @Synchronized
    fun rotate(serverId: String): ByteArray {
        val time = now()
        val key = EnvelopeCrypto.newKey()
        val previous = load(serverId).firstOrNull { it.retiredAt == null }?.copy(retiredAt = time)
        save(serverId, listOfNotNull(StoredKey(EnvelopeCrypto.kid(key), Base64Url.encode(key)), previous))
        return key
    }

    /** The key with [kid] for [serverId], if this phone made it and it hasn't expired. */
    @Synchronized
    fun find(
        serverId: String,
        kid: String,
    ): ByteArray? = load(serverId).firstOrNull { it.kid == kid && it.isUsable(now()) }?.let { Base64Url.decode(it.key) }

    /** Drops one key, as when the server reports its relay token revoked (protocol C6). */
    @Synchronized
    fun discard(
        serverId: String,
        kid: String,
    ) = save(serverId, load(serverId).filterNot { it.kid == kid })

    @Synchronized
    fun forget(serverId: String) = secrets.remove(storageKey(serverId))

    companion object {
        const val PREVIOUS_KEY_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L
    }
}
