package cz.peelco.jolt.push

import cz.peelco.jolt.data.secure.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Relay `payloadKey`s, per server, encrypted at rest by [SecretStore] (the
 * Android Keystore). A new key is made for every relay registration; the
 * previous one is kept so a push already in flight under the old `kid` still
 * decrypts.
 */
class PayloadKeyStore(
    private val secrets: SecretStore,
) {
    @Serializable
    private data class StoredKey(
        val kid: String,
        val key: String,
    )

    private val serializer = ListSerializer(StoredKey.serializer())

    private fun storageKey(serverId: String) = "payloadKey:$serverId"

    private fun load(serverId: String): List<StoredKey> =
        secrets.get(storageKey(serverId))?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** A fresh key for a new registration with [serverId]; returns it. */
    @Synchronized
    fun rotate(serverId: String): ByteArray {
        val key = EnvelopeCrypto.newKey()
        val keys = listOf(StoredKey(EnvelopeCrypto.kid(key), Base64Url.encode(key))) + load(serverId).take(KEPT_PREVIOUS)
        secrets.put(storageKey(serverId), Json.encodeToString(serializer, keys))
        return key
    }

    /** The key with [kid] for [serverId], if this phone made it. */
    @Synchronized
    fun find(
        serverId: String,
        kid: String,
    ): ByteArray? = load(serverId).firstOrNull { it.kid == kid }?.let { Base64Url.decode(it.key) }

    @Synchronized
    fun forget(serverId: String) = secrets.remove(storageKey(serverId))

    private companion object {
        const val KEPT_PREVIOUS = 1
    }
}
