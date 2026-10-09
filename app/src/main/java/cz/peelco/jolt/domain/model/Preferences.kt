package cz.peelco.jolt.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a fire-able control turns a touch into a send. `TAP` reproduces the
 * original one-tap behaviour and is the default.
 */
@Serializable
enum class FiringInteractionMode(
    val displayName: String,
    val description: String,
    /** "Hold to fire" vs "Tap to fire"; confirm reads as a tap. */
    val actionVerb: String,
) {
    @SerialName("tap")
    TAP("Tap", "A single tap fires immediately.", "Tap"),

    @SerialName("hold")
    HOLD(
        "Hold",
        "Press and hold to fire — releasing early cancels it. A high-intensity stimulus asks you to hold a second time to confirm.",
        "Hold",
    ),

    @SerialName("confirm")
    CONFIRM("Confirm dialog", "Tapping asks you to confirm before it fires.", "Tap"),
}

/** Per-stimulus firing gesture. */
@Serializable
data class FiringInteractionSettings(
    val zap: FiringInteractionMode = FiringInteractionMode.TAP,
    val vibe: FiringInteractionMode = FiringInteractionMode.TAP,
    val beep: FiringInteractionMode = FiringInteractionMode.TAP,
) {
    operator fun get(kind: StimulusKind): FiringInteractionMode =
        when (kind) {
            StimulusKind.ZAP -> zap
            StimulusKind.VIBE -> vibe
            StimulusKind.BEEP -> beep
        }

    fun with(
        kind: StimulusKind,
        mode: FiringInteractionMode,
    ): FiringInteractionSettings =
        when (kind) {
            StimulusKind.ZAP -> copy(zap = mode)
            StimulusKind.VIBE -> copy(vibe = mode)
            StimulusKind.BEEP -> copy(beep = mode)
        }

    /** One name when every kind matches, otherwise "Mixed". */
    val summaryLabel: String
        get() = listOf(zap, vibe, beep).distinct().singleOrNull()?.displayName ?: "Mixed"

    companion object {
        val DEFAULT = FiringInteractionSettings()
    }
}

/** Last kind and intensity used in a friend's composer; repetitions are not kept. */
@Serializable
data class FriendPokeDraft(
    val kind: StimulusKind,
    val intensity: Int,
) {
    companion object {
        val PREFERRED_DEFAULT = FriendPokeDraft(StimulusKind.VIBE, 30)

        /**
         * Resolves a draft against what the friend allows now: the remembered
         * kind if still allowed, else vibe, else the first allowed; intensity
         * clamped to that kind's cap. Null when nothing is allowed.
         */
        fun resolved(
            saved: FriendPokeDraft?,
            permissions: FriendPermissionSet,
        ): FriendPokeDraft? {
            val allowed = permissions.allowedKinds
            if (allowed.isEmpty()) return null
            val kind =
                when {
                    saved != null && saved.kind in allowed -> saved.kind
                    StimulusKind.VIBE in allowed -> StimulusKind.VIBE
                    else -> allowed.first()
                }
            val cap = permissions[kind].maxIntensity.coerceAtLeast(0)
            val raw = saved?.intensity ?: PREFERRED_DEFAULT.intensity
            return FriendPokeDraft(kind, raw.coerceIn(0, cap))
        }
    }
}

@Serializable
enum class PokeFeedbackProfile(
    val displayName: String,
) {
    @SerialName("off")
    OFF("Off"),

    @SerialName("minimal")
    MINIMAL("Minimal"),

    @SerialName("standard")
    STANDARD("Standard"),

    @SerialName("rich")
    RICH("Rich"),

    @SerialName("custom")
    CUSTOM("Custom"),
}

/** What happens after a poke is sent: banner, flash, haptic, "Sent" label. */
@Serializable
data class PokeFeedbackSettings(
    val profile: PokeFeedbackProfile,
    val showBanner: Boolean,
    val flashButton: Boolean,
    val playHaptic: Boolean,
    val swapButtonLabel: Boolean,
) {
    fun applyingProfile(profile: PokeFeedbackProfile): PokeFeedbackSettings = if (profile == PokeFeedbackProfile.CUSTOM) this else preset(profile)

    /** After a toggle edit: the matching preset, or custom. */
    fun reconciled(): PokeFeedbackSettings {
        val match =
            listOf(PokeFeedbackProfile.OFF, PokeFeedbackProfile.MINIMAL, PokeFeedbackProfile.STANDARD, PokeFeedbackProfile.RICH)
                .firstOrNull { preset(it).copy(profile = profile) == this }
        return copy(profile = match ?: PokeFeedbackProfile.CUSTOM)
    }

    val isEnabled: Boolean get() = showBanner || flashButton || playHaptic || swapButtonLabel

    companion object {
        fun preset(profile: PokeFeedbackProfile): PokeFeedbackSettings =
            when (profile) {
                PokeFeedbackProfile.OFF -> PokeFeedbackSettings(profile, false, false, false, false)
                PokeFeedbackProfile.MINIMAL -> PokeFeedbackSettings(profile, false, true, false, false)
                PokeFeedbackProfile.STANDARD -> PokeFeedbackSettings(profile, true, true, true, false)
                PokeFeedbackProfile.RICH -> PokeFeedbackSettings(profile, true, true, true, true)
                PokeFeedbackProfile.CUSTOM -> DEFAULT
            }

        val DEFAULT = preset(PokeFeedbackProfile.STANDARD)
    }
}

/** A one-tap "poke this friend" shortcut on the Remote tab. */
@Serializable
data class QuickPokeSettings(
    val isEnabled: Boolean = false,
    val targetFriendId: SerialUuid? = null,
    val targetFriendName: String? = null,
    val stimulus: StimulusConfig = StimulusConfig(StimulusKind.VIBE, 30, 1),
) {
    /** Enough to show the Remote button. */
    val isConfigured: Boolean get() = isEnabled && targetFriendId != null

    companion object {
        val DEFAULT = QuickPokeSettings()
    }
}

/**
 * "When I press this on my Pavlok, poke this friend." The device only
 * announces presses of a button set to a phone-side action, so the trigger is
 * a button reconfigured to find-my-phone plus the resulting `[0x0C, …]`
 * announcement. A learned raw signature is the fallback for other firmware.
 */
@Serializable
data class PokeTrigger(
    val isEnabled: Boolean = false,
    val targetFriendId: SerialUuid? = null,
    val targetFriendName: String? = null,
    val stimulus: StimulusConfig = StimulusConfig(StimulusKind.VIBE, 30, 1),
    /** The button the app sets to find-my-phone. Setting it switches to the decoded path. */
    val buttonSlot: DeviceButtonSlot? = null,
    val learnedCharacteristicUuid: String? = null,
    /** Hex of the learned frame. */
    val learnedBytesHex: String? = null,
    val matchMode: MatchMode = MatchMode.EXACT,
    /** Repeats within this window are one press. */
    val debounceSeconds: Double = 2.0,
) {
    @Serializable
    enum class MatchMode(
        val displayName: String,
    ) {
        @SerialName("exact")
        EXACT("Exact"),

        /**
         * Compares only byte 0, the event type: every byte after it is state
         * that changes between presses of the same button.
         */
        @SerialName("prefix")
        PREFIX("Tolerant"),
        ;

        companion object {
            const val TOLERATED_PREFIX_LENGTH = 1
        }
    }

    val learnedBytes: ByteArray? get() = learnedBytesHex?.let(::parseHexBytes)

    /** Enabled, with a friend and some gesture (a button or a learned signature). */
    val isArmed: Boolean get() = isEnabled && targetFriendId != null && (buttonSlot != null || learnedBytesHex != null)

    fun matches(event: DeviceEvent): Boolean = if (buttonSlot != null) event.isFindMyPhoneEvent else matchesLearnedSignature(event)

    private fun matchesLearnedSignature(event: DeviceEvent): Boolean {
        val characteristic = learnedCharacteristicUuid ?: return false
        val learned = learnedBytes?.takeIf { it.isNotEmpty() } ?: return false
        if (!event.characteristicUuid.equals(characteristic, ignoreCase = true)) return false
        return when (matchMode) {
            MatchMode.EXACT -> event.data.contentEquals(learned)
            MatchMode.PREFIX -> {
                val length = minOf(learned.size, MatchMode.TOLERATED_PREFIX_LENGTH)
                event.data.size >= length && event.data.copyOfRange(0, length).contentEquals(learned.copyOfRange(0, length))
            }
        }
    }

    companion object {
        val DEFAULT = PokeTrigger()
    }
}

/** One reorderable, hideable card on the Remote dashboard. */
@Serializable
enum class RemoteWidgetKind(
    val displayName: String,
    val hint: String,
    val stimulusKind: StimulusKind? = null,
) {
    @SerialName("zap")
    ZAP("Zap", "Hold to fire a shock at your saved intensity", StimulusKind.ZAP),

    @SerialName("vibe")
    VIBE("Vibe", "Hold to fire a vibration at your saved intensity", StimulusKind.VIBE),

    @SerialName("beep")
    BEEP("Beep", "Hold to fire a beep at your saved intensity", StimulusKind.BEEP),

    @SerialName("quickPoke")
    QUICK_POKE("Quick poke", "One-tap poke to the friend you chose in Settings"),

    @SerialName("nextAlarm")
    NEXT_ALARM("Next alarm", "Time, stimulus and challenge for your next alarm"),

    @SerialName("recentActivity")
    RECENT_ACTIVITY("Recent activity", "Pokes sent and received"),
}

/**
 * Which widgets are on the dashboard, in order, and which wait in the gallery.
 * Reordering only ever happens within [visible].
 */
@Serializable
data class RemoteDashboardLayout(
    val visible: List<RemoteWidgetKind>,
    val hidden: List<RemoteWidgetKind>,
) {
    fun moving(
        kind: RemoteWidgetKind,
        offset: Int,
    ): RemoteDashboardLayout {
        val index = visible.indexOf(kind)
        val target = index + offset
        if (index < 0 || target !in visible.indices) return this
        val reordered = visible.toMutableList().apply { add(target, removeAt(index)) }
        return copy(visible = reordered)
    }

    fun hiding(kind: RemoteWidgetKind): RemoteDashboardLayout = if (kind in visible) RemoteDashboardLayout(visible - kind, hidden + kind) else this

    fun showing(kind: RemoteWidgetKind): RemoteDashboardLayout = if (kind in hidden) RemoteDashboardLayout(visible + kind, hidden - kind) else this

    /** Folds in kinds a newer version added, into the gallery. */
    fun reconciled(): RemoteDashboardLayout {
        val known = (visible + hidden).toSet()
        return copy(hidden = hidden + RemoteWidgetKind.entries.filter { it !in known })
    }

    companion object {
        val DEFAULT = RemoteDashboardLayout(RemoteWidgetKind.entries.toList(), emptyList())
    }
}
