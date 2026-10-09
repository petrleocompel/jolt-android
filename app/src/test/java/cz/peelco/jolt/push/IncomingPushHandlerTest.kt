package cz.peelco.jolt.push

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.app.Notifications
import cz.peelco.jolt.domain.model.PokeDeliveryStatus
import cz.peelco.jolt.domain.model.PokeEvent
import cz.peelco.jolt.domain.model.PokePushPayload
import cz.peelco.jolt.domain.model.RegisteredDevice
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.TestPushPath
import cz.peelco.jolt.domain.model.TestPushPayload
import cz.peelco.jolt.domain.model.TestPushStatus
import cz.peelco.jolt.domain.repository.PokeRepository
import cz.peelco.jolt.domain.repository.PushDiagnosticsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** What the FCM service does with a data message. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class IncomingPushHandlerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val key = EnvelopeCrypto.newKey()
    private val serverId = "srv_kzdvvj2umnduyauf35o36k6kw4"

    private val pokes =
        object : PokeRepository {
            val handled = mutableListOf<PokePushPayload>()
            override val activity: StateFlow<List<PokeEvent>> = MutableStateFlow(emptyList())

            override suspend fun sendPoke(
                friendId: UUID,
                stimulus: StimulusConfig,
                pokeId: UUID,
            ) = Unit

            override suspend fun handleIncomingPoke(payload: PokePushPayload): PokeDeliveryStatus {
                handled += payload
                return PokeDeliveryStatus.FIRED
            }

            override suspend fun simulateIncomingPoke(
                friendId: UUID,
                stimulus: StimulusConfig,
            ) = Unit
        }

    private val diagnostics =
        object : PushDiagnosticsRepository {
            val acks = mutableListOf<TestPushPath>()

            override suspend fun registeredDevices(): List<RegisteredDevice> = emptyList()

            override suspend fun sendTestPush(
                deviceId: UUID?,
                stimulus: StimulusConfig?,
            ): TestPushStatus = error("unused")

            override suspend fun testPushStatus(testId: UUID): TestPushStatus = error("unused")

            override suspend fun handleIncomingTestPush(
                payload: TestPushPayload,
                path: TestPushPath,
            ): PokeDeliveryStatus? {
                acks += path
                return null
            }
        }

    private val handler = IncomingPushHandler(context, { srv, kid -> key.takeIf { srv == serverId && kid == EnvelopeCrypto.kid(key) } }, pokes, diagnostics)

    private fun message(
        kind: String,
        plaintext: String,
        sealWith: ByteArray = key,
    ) = mapOf(
        "type" to kind,
        "srv" to serverId,
        "enc" to Json.encodeToString(Envelope.serializer(), EnvelopeCrypto.seal(sealWith, serverId, kind, plaintext.toByteArray())),
    )

    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(context)
    }

    @Test
    fun aTestDataMessageIsAckedAsBackground() =
        runTest {
            handler.handle(
                message(
                    "test",
                    """{"type":"test","test":{"testID":"${UUID.randomUUID()}","deviceID":"${UUID.randomUUID()}","serverId":"$serverId"}}""",
                ),
            )
            assertThat(diagnostics.acks).containsExactly(TestPushPath.BACKGROUND)
            assertThat(notifications).hasSize(1)
        }

    @Test
    fun aPokeIsShownAndHandled() =
        runTest {
            handler.handle(
                message(
                    "poke",
                    """{"type":"poke","poke":{"pokeID":"${UUID.randomUUID()}","serverId":"$serverId","senderHandle":"alice","senderDisplayName":"Alice","stimulus":{"kind":"zap","intensity":30,"repetitions":1}}}""",
                ),
            )
            assertThat(pokes.handled).hasSize(1)
            assertThat(shadowOf(notifications.single()).contentTitle).isEqualTo("Alice")
        }

    @Test
    fun anUnreadablePushShowsTheFallbackAndFiresNothing() =
        runTest {
            handler.handle(message("poke", """{"type":"poke"}""", sealWith = EnvelopeCrypto.newKey()))
            assertThat(pokes.handled).isEmpty()
            assertThat(shadowOf(notifications.single()).contentText).isEqualTo("You got a poke. Open Jolt to see it.")
        }

    @Test
    fun aPushWithoutAnEnvelopeIsDroppedSilently() =
        runTest {
            handler.handle(mapOf("type" to "poke", "srv" to serverId))
            assertThat(pokes.handled).isEmpty()
            assertThat(notifications).isEmpty()
        }
}
