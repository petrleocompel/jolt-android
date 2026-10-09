package cz.peelco.jolt.push

import android.content.Context
import android.content.Intent
import android.util.Log
import cz.peelco.jolt.app.Notifications
import cz.peelco.jolt.domain.model.IncomingPush
import cz.peelco.jolt.domain.model.PokePushPayload
import cz.peelco.jolt.domain.model.TestPushPath
import cz.peelco.jolt.domain.model.TestPushPayload
import cz.peelco.jolt.domain.repository.PokeRepository
import cz.peelco.jolt.domain.repository.PushDiagnosticsRepository
import kotlinx.serialization.json.Json

/** Puts a decrypted push into a notification's tap intent and reads it back. */
object PushTapExtras {
    private const val POKE = "cz.peelco.jolt.push.poke"
    private const val TEST = "cz.peelco.jolt.push.test"
    private val json = Json { ignoreUnknownKeys = true }

    fun putPoke(
        intent: Intent,
        payload: PokePushPayload,
    ) {
        intent.putExtra(POKE, json.encodeToString(PokePushPayload.serializer(), payload))
    }

    fun putTest(
        intent: Intent,
        payload: TestPushPayload,
    ) {
        intent.putExtra(TEST, json.encodeToString(TestPushPayload.serializer(), payload))
    }

    fun read(intent: Intent): IncomingPush? {
        intent.getStringExtra(POKE)?.let { text -> return runCatching { IncomingPush.Poke(json.decodeFromString(PokePushPayload.serializer(), text)) }.getOrNull() }
        intent.getStringExtra(TEST)?.let { text -> return runCatching { IncomingPush.Test(json.decodeFromString(TestPushPayload.serializer(), text)) }.getOrNull() }
        return null
    }

    fun clear(intent: Intent) {
        intent.removeExtra(POKE)
        intent.removeExtra(TEST)
    }
}

/**
 * The one "a push arrived" path on Android, the counterpart of iOS's
 * `JoltAppDelegate` and `AppNotificationDelegate` together.
 *
 * There is no alert/silent pair here: the relay sends one FCM data message,
 * and the app draws the notification itself, then fires and acks through the
 * same repository calls iOS uses. A tap on that notification runs the poke
 * again; [LocalStimulusFirer][cz.peelco.jolt.data.social.LocalStimulusFirer]
 * keeps that from firing twice.
 */
class IncomingPushHandler(
    private val context: Context,
    private val keyFor: (serverId: String, kid: String) -> ByteArray?,
    private val pokes: PokeRepository,
    private val diagnostics: PushDiagnosticsRepository,
    private val isAppInForeground: () -> Boolean,
) {
    suspend fun handle(data: Map<String, String>) {
        when (val decoded = EnvelopeCrypto.decode(data, keyFor)) {
            EnvelopeCrypto.Decoded.NotJolt -> Unit
            is EnvelopeCrypto.Decoded.Unreadable -> {
                // Dropped, as the protocol says; only the generic text shows,
                // and nothing fires.
                Log.w(TAG, "Dropped an unreadable ${decoded.kind} push: ${decoded.reason}")
                Notifications.showFallback(context, decoded.kind)
            }
            is EnvelopeCrypto.Decoded.Push -> deliver(decoded.push, if (isAppInForeground()) TestPushPath.FOREGROUND else TestPushPath.BACKGROUND, notify = true)
        }
    }

    /** The user tapped one of our notifications. */
    suspend fun handleTap(push: IncomingPush) = deliver(push, TestPushPath.ALERT, notify = false)

    private suspend fun deliver(
        push: IncomingPush,
        path: TestPushPath,
        notify: Boolean,
    ) {
        when (push) {
            is IncomingPush.Poke -> {
                if (notify) Notifications.showPoke(context, push.payload)
                pokes.handleIncomingPoke(push.payload)
            }
            is IncomingPush.Test -> {
                if (notify) Notifications.showTest(context, push.payload)
                diagnostics.handleIncomingTestPush(push.payload, path)
            }
        }
    }

    private companion object {
        const val TAG = "JoltPush"
    }
}
