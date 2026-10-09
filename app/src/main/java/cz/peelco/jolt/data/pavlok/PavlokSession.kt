package cz.peelco.jolt.data.pavlok

import cz.peelco.jolt.data.secure.SecretStore
import cz.peelco.jolt.data.store.JsonValueStore
import cz.peelco.jolt.domain.model.PavlokAccount
import kotlinx.coroutines.flow.StateFlow

/**
 * The optional Pavlok account: the client plus its stored credentials. The
 * token is a secret and goes to [SecretStore]; the non-secret account summary
 * sits with the other settings so the UI can show who is signed in.
 */
class PavlokSession(
    val client: PavlokApiClient,
    private val secrets: SecretStore,
    private val accountStore: JsonValueStore<PavlokAccount?>,
) {
    val account: StateFlow<PavlokAccount?> = accountStore.flow

    /** Restores the saved token into the client; true when there was a session. */
    fun restore(): Boolean {
        val token = secrets.get(TOKEN_KEY) ?: return false
        if (accountStore.value == null) return false
        client.token = token
        return true
    }

    suspend fun signIn(
        email: String,
        password: String,
    ): PavlokAccount {
        val result = client.login(email.trim(), password)
        client.token = result.token
        secrets.put(TOKEN_KEY, result.token)
        accountStore.save(result.account)
        return result.account
    }

    fun signOut() {
        secrets.remove(TOKEN_KEY)
        accountStore.save(null)
        client.token = null
    }

    private companion object {
        const val TOKEN_KEY = "pavlokToken"
    }
}
