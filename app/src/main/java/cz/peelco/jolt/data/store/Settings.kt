package cz.peelco.jolt.data.store

import cz.peelco.jolt.domain.model.FiringInteractionSettings
import cz.peelco.jolt.domain.model.FriendPokeDraft
import cz.peelco.jolt.domain.model.PairedDeviceRecord
import cz.peelco.jolt.domain.model.PavlokAccount
import cz.peelco.jolt.domain.model.PokeFeedbackSettings
import cz.peelco.jolt.domain.model.PokeTrigger
import cz.peelco.jolt.domain.model.QuickPokeSettings
import cz.peelco.jolt.domain.model.RemoteDashboardLayout
import cz.peelco.jolt.domain.model.ServerConfiguration
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.StimulusSettings
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Every persisted setting in one place. Keys keep the iOS `cz.peelco.jolt.*`
 * names so the two apps read the same in a support conversation.
 */
class AppSettings(
    store: KeyValueStore,
    defaultServer: ServerConfiguration,
) {
    private val defaultServerConfiguration = defaultServer

    val server = JsonValueStore(store, "cz.peelco.jolt.serverConfiguration", ServerConfiguration.serializer(), defaultServer)
    val pairedDevice = JsonValueStore(store, "cz.peelco.jolt.pairedDevice", PairedDeviceRecord.serializer().nullable, null)
    val stimulusSettings = JsonValueStore(store, "cz.peelco.jolt.stimulusSettings", StimulusSettings.serializer(), StimulusSettings.DEFAULT)

    /** Pavlok 2/3 stimulus characteristic overrides, by kind. */
    val legacyStimulusCharacteristics =
        JsonValueStore(
            store,
            "cz.peelco.jolt.legacyStimulusCharacteristics",
            MapSerializer(StimulusKind.serializer(), String.serializer()),
            emptyMap(),
        )
    val pokeTrigger = JsonValueStore(store, "cz.peelco.jolt.pokeTrigger", PokeTrigger.serializer(), PokeTrigger.DEFAULT)
    val quickPoke = JsonValueStore(store, "cz.peelco.jolt.quickPoke", QuickPokeSettings.serializer(), QuickPokeSettings.DEFAULT)
    val firingModes =
        JsonValueStore(store, "cz.peelco.jolt.firingInteraction", FiringInteractionSettings.serializer(), FiringInteractionSettings.DEFAULT)
    val pokeFeedback = JsonValueStore(store, "cz.peelco.jolt.pokeFeedback", PokeFeedbackSettings.serializer(), PokeFeedbackSettings.DEFAULT)

    /** Last kind and intensity per friend id. */
    val friendPokeDrafts =
        JsonValueStore(
            store,
            "cz.peelco.jolt.friendPokeDrafts",
            MapSerializer(String.serializer(), FriendPokeDraft.serializer()),
            emptyMap(),
        )
    val dashboardLayout =
        JsonValueStore(store, "cz.peelco.jolt.remoteDashboardLayout", RemoteDashboardLayout.serializer(), RemoteDashboardLayout.DEFAULT)
    val pavlokAccount = JsonValueStore(store, "cz.peelco.jolt.pavlokAccount", PavlokAccount.serializer().nullable, null)

    /** "I'll use Jolt without a wearable": the first-run pairing offer was declined. */
    val didChooseNoDevice = BooleanSetting(store, "cz.peelco.jolt.didChooseNoDevice")

    /** Incoming pokes are logged and acked as muted, never fired. */
    val doNotDisturb = BooleanSetting(store, "cz.peelco.jolt.pokesDoNotDisturb")

    /** Keep the wearable linked through a foreground service. Android only. */
    val stayConnectedInBackground = BooleanSetting(store, "cz.peelco.jolt.stayConnected", default = true)

    val isCustomServer: Boolean get() = server.value != defaultServerConfiguration
    val defaultServer: ServerConfiguration get() = defaultServerConfiguration
}
