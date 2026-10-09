package cz.peelco.jolt.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import cz.peelco.jolt.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Where this phone's push provider token comes from. */
interface PushTokenProvider {
    /** False when the build has no Firebase configuration at all. */
    val isAvailable: Boolean

    suspend fun token(): String?
}

/**
 * FCM. Firebase is initialised from `google-services.json` when the build had
 * one (the Google Services plugin wires that up), otherwise from the
 * `JOLT_FIREBASE_*` build settings, otherwise not at all, and push is reported
 * as unavailable while everything else keeps working.
 */
class FirebasePushTokenProvider(
    context: Context,
) : PushTokenProvider {
    override val isAvailable: Boolean = initialize(context)

    override suspend fun token(): String? {
        if (!isAvailable) return null
        return try {
            // getToken() is deprecated in favour of register() + onRegistered,
            // but it is still the only call that hands the token back to the
            // caller, which registration needs right here.
            @Suppress("DEPRECATION")
            FirebaseMessaging.getInstance().token.await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "No FCM token: ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "JoltPush"

        fun initialize(context: Context): Boolean {
            if (FirebaseApp.getApps(context).isNotEmpty()) return true
            if (listOf(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APPLICATION_ID, BuildConfig.FIREBASE_API_KEY).any { it.isEmpty() }) {
                Log.i(TAG, "No Firebase configuration in this build; push is unavailable")
                return false
            }
            val options =
                FirebaseOptions
                    .Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
            return runCatching { FirebaseApp.initializeApp(context, options) }.isSuccess
        }
    }
}
