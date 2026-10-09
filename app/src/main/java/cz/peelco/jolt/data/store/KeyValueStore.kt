package cz.peelco.jolt.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Small synchronous key-value storage, the Android stand-in for iOS
 * `UserDefaults`. Every setting is one small value read at launch and written
 * from a control, which is the shape `SharedPreferences` is for; secrets go
 * through `SecretStore` instead.
 */
interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String?,
    )

    fun getBoolean(
        key: String,
        default: Boolean = false,
    ): Boolean = getString(key)?.toBooleanStrictOrNull() ?: default

    fun putBoolean(
        key: String,
        value: Boolean,
    ) = putString(key, value.toString())
}

class SharedPreferencesKeyValueStore(
    context: Context,
    name: String = "jolt",
) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(
        key: String,
        value: String?,
    ) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
}

/** For tests and previews. */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    @Synchronized
    override fun getString(key: String): String? = values[key]

    @Synchronized
    override fun putString(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

internal val StoreJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowStructuredMapKeys = true
    }

/**
 * One JSON-encoded value with an observable current state. A blob that no
 * longer decodes (a format change) falls back to [default] rather than
 * failing launch, the same tolerance the iOS stores have.
 */
class JsonValueStore<T>(
    private val store: KeyValueStore,
    private val key: String,
    private val serializer: KSerializer<T>,
    private val default: T,
) {
    private val state = MutableStateFlow(load())
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    private fun load(): T =
        store.getString(key)?.let { runCatching { StoreJson.decodeFromString(serializer, it) }.getOrNull() } ?: default

    fun save(value: T) {
        store.putString(key, StoreJson.encodeToString(serializer, value))
        state.value = value
    }

    fun update(transform: (T) -> T) = save(transform(state.value))

    fun reset() {
        store.putString(key, null)
        state.value = default
    }
}

/** A boolean flag with an observable state. */
class BooleanSetting(
    private val store: KeyValueStore,
    private val key: String,
    private val default: Boolean = false,
) {
    private val state = MutableStateFlow(store.getBoolean(key, default))
    val flow: StateFlow<Boolean> = state.asStateFlow()
    val value: Boolean get() = state.value

    fun set(value: Boolean) {
        store.putBoolean(key, value)
        state.value = value
    }
}
