package cz.peelco.jolt.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage for secrets: session tokens, the Pavlok token and relay payload
 * keys. The Android counterpart of the iOS keychain items.
 */
interface SecretStore {
    fun get(key: String): String?

    fun put(
        key: String,
        value: String,
    )

    fun remove(key: String)

    /** Every stored key starting with [prefix]. */
    fun keys(prefix: String): Set<String>
}

/**
 * Values encrypted with AES-256-GCM under a non-exportable Android Keystore
 * key, ciphertext kept in a private `SharedPreferences` file. The key has no
 * user-authentication requirement, so a push can be decrypted and acked while
 * the phone is locked (the iOS "after first unlock" class).
 *
 * `androidx.security:security-crypto` would do this too, but it is deprecated.
 */
class KeystoreSecretStore(
    context: Context,
    private val alias: String = "cz.peelco.jolt.secrets",
) : SecretStore {
    private val prefs = context.getSharedPreferences("jolt.secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    override fun put(
        key: String,
        value: String,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(key, Base64.getEncoder().encodeToString(sealed)) }
    }

    @Synchronized
    override fun remove(key: String) = prefs.edit { remove(key) }

    override fun keys(prefix: String): Set<String> = prefs.all.keys.filterTo(mutableSetOf()) { it.startsWith(prefix) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** For tests and the demo build. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }

    @Synchronized
    override fun keys(prefix: String): Set<String> = values.keys.filterTo(mutableSetOf()) { it.startsWith(prefix) }
}
