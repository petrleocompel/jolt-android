package cz.peelco.jolt.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import cz.peelco.jolt.BuildConfig
import cz.peelco.jolt.ble.WearableConnectionService
import cz.peelco.jolt.ble.transport.BleCentral
import cz.peelco.jolt.data.device.CompositeDeviceRepository
import cz.peelco.jolt.data.device.FakeDeviceRepository
import cz.peelco.jolt.data.pavlok.PavlokApiClient
import cz.peelco.jolt.data.pavlok.PavlokSession
import cz.peelco.jolt.data.secure.InMemorySecretStore
import cz.peelco.jolt.data.secure.KeystoreSecretStore
import cz.peelco.jolt.data.secure.SecretStore
import cz.peelco.jolt.data.social.HttpSocialBackend
import cz.peelco.jolt.data.social.LocalStimulusFirer
import cz.peelco.jolt.data.social.MockSocialBackend
import cz.peelco.jolt.data.social.SocialBackend
import cz.peelco.jolt.data.store.AlarmStore
import cz.peelco.jolt.data.store.AppSettings
import cz.peelco.jolt.data.store.InMemoryKeyValueStore
import cz.peelco.jolt.data.store.KeyValueStore
import cz.peelco.jolt.data.store.SharedPreferencesKeyValueStore
import cz.peelco.jolt.domain.model.ServerConfiguration
import cz.peelco.jolt.domain.repository.DeviceRepository
import cz.peelco.jolt.features.shared.PokeFeedbackService
import cz.peelco.jolt.features.shared.PokeTriggerService
import cz.peelco.jolt.features.shared.QuickPokeService
import cz.peelco.jolt.push.Attestor
import cz.peelco.jolt.push.FirebasePushTokenProvider
import cz.peelco.jolt.push.IncomingPushHandler
import cz.peelco.jolt.push.PayloadKeyStore
import cz.peelco.jolt.push.PlayIntegrityAttestor
import cz.peelco.jolt.push.PushRegistrar
import cz.peelco.jolt.push.PushTokenProvider
import cz.peelco.jolt.push.RelayAllowList
import cz.peelco.jolt.push.RelayClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The composition root, built once by [JoltApplication]; the iOS
 * `AppDependencies`. Manual DI: the graph is small and this keeps annotation
 * processing out of the build.
 */
class AppContainer(
    val context: Context,
    val environment: AppEnvironment = AppEnvironment.DEFAULT,
) {
    /** Lives as long as the process; everything long-running hangs off it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val keyValues: KeyValueStore =
        if (environment.inMemoryStorage) InMemoryKeyValueStore() else SharedPreferencesKeyValueStore(context)
    val secrets: SecretStore = if (environment.inMemoryStorage) InMemorySecretStore() else KeystoreSecretStore(context)

    val settings = AppSettings(keyValues, ServerConfiguration.bundledDefault(BuildConfig.JOLT_DEFAULT_SERVER_URL))

    val httpEngine: () -> HttpClientEngine = { OkHttp.create() }

    val deviceRepository: DeviceRepository =
        if (environment.useFakeDevice) {
            FakeDeviceRepository(startsPaired = !environment.startsWithoutDevice, unreachableState = environment.fakeDeviceLink)
        } else {
            CompositeDeviceRepository(context, BleCentral(context), settings, scope)
        }

    val alarms = AlarmStore(keyValues)

    private val firer = LocalStimulusFirer(deviceRepository, scope) { settings.doNotDisturb.value }

    val social: SocialBackend =
        if (environment.useMockBackend) {
            MockSocialBackend(firer, startSignedIn = environment.startsSignedIn)
        } else {
            HttpSocialBackend(settings.server, secrets, httpEngine, firer, scope)
        }

    val pavlok = PavlokSession(PavlokApiClient(httpEngine()), secrets, settings.pavlokAccount)

    // Push.

    private val payloadKeys = PayloadKeyStore(secrets)
    val pushTokenProvider: PushTokenProvider =
        if (environment.useMockBackend) {
            object : PushTokenProvider {
                override val isAvailable = false

                override suspend fun token(): String? = null
            }
        } else {
            FirebasePushTokenProvider(context)
        }
    val relayAllowList = RelayAllowList.parse(BuildConfig.RELAY_ALLOWED_HOSTS)
    val pushRegistrar =
        PushRegistrar(
            backend = social,
            secrets = secrets,
            payloadKeys = payloadKeys,
            tokenProvider = pushTokenProvider,
            attestor = if (BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT > 0) PlayIntegrityAttestor(context, BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT) else Attestor.NONE,
            allowList = relayAllowList,
            relayClient = { url -> RelayClient(url, httpEngine()) },
            appId = BuildConfig.APPLICATION_ID,
            scope = scope,
        )

    private val foreground = AtomicBoolean(false)

    /** Updated by [JoltApplication] from the process lifecycle. */
    fun setForeground(value: Boolean) = foreground.set(value)

    val incomingPushHandler =
        IncomingPushHandler(
            context = context,
            keyFor = pushRegistrar::payloadKey,
            pokes = social,
            diagnostics = social,
            isAppInForeground = foreground::get,
        )

    // Long-lived feature services.

    val pokeFeedback = PokeFeedbackService(settings.pokeFeedback, scope, ::playSuccessHaptic)
    val quickPoke = QuickPokeService(settings.quickPoke, social, pokeFeedback, scope)
    val pokeTrigger = PokeTriggerService(settings.pokeTrigger, deviceRepository, social, scope)

    init {
        social.sessionListener = pushRegistrar
    }

    /** Starts what runs for the app's lifetime. */
    fun start() {
        deviceRepository.start()
        social.start()
        pokeTrigger.start()
    }

    /**
     * Starts the foreground service that keeps the wearable linked, when a
     * device is paired and the user hasn't turned it off. Safe to call often.
     */
    fun keepWearableConnected() {
        if (environment.useFakeDevice) return
        if (deviceRepository.pairedDevice.value == null || !settings.stayConnectedInBackground.value) return
        if (!BleCentral(context).hasConnectPermission()) return
        WearableConnectionService.start(context)
    }

    private fun playSuccessHaptic() {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
