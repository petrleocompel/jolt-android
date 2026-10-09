package cz.peelco.jolt.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One of the three physical outputs every Pavlok device can fire. Names match
 * the official Android app's `zap` / `motor` (vibe) / `piezo` (beep) split.
 */
@Serializable
enum class StimulusKind(
    val displayName: String,
    /** For activity sentences ("Alice zapped you"); "zaped" reads wrong. */
    val pastTenseVerb: String,
) {
    @SerialName("zap")
    ZAP("Zap", "zapped"),

    @SerialName("vibe")
    VIBE("Vibe", "buzzed"),

    @SerialName("beep")
    BEEP("Beep", "beeped"),
    ;

    /** The wire name, `zap` / `vibe` / `beep`. */
    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWireName(name: String): StimulusKind? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Intensity is device-relative, not volts or amps: the legacy and Shock Clock
 * Max protocols each map 0...100 onto their own hardware scale.
 */
@Serializable
data class StimulusConfig(
    val kind: StimulusKind,
    val intensity: Int = 30,
    val repetitions: Int = 1,
) {
    /** Intensity clamped to 0...100 and at least one repetition. */
    fun normalized(): StimulusConfig =
        copy(intensity = intensity.coerceIn(INTENSITY_RANGE), repetitions = repetitions.coerceAtLeast(1))

    companion object {
        val INTENSITY_RANGE = 0..100
        val REPETITIONS_RANGE = 1..5

        fun of(
            kind: StimulusKind,
            intensity: Int = 30,
            repetitions: Int = 1,
        ): StimulusConfig = StimulusConfig(kind, intensity, repetitions).normalized()
    }
}

/**
 * Per-kind stimulus defaults. A comfortable vibe and a useful zap are nowhere
 * near the same number, so each output keeps its own, as the official app does.
 *
 * Stored as `{"configsByKind": {"zap": {...}}}`, the same blob iOS keeps.
 */
@Serializable
data class StimulusSettings(
    private val configsByKind: Map<StimulusKind, StimulusConfig> = emptyMap(),
) {
    operator fun get(kind: StimulusKind): StimulusConfig = configsByKind[kind] ?: DEFAULTS.getValue(kind)

    fun with(config: StimulusConfig): StimulusSettings = StimulusSettings(configsByKind + (config.kind to config))

    val all: List<StimulusConfig> get() = StimulusKind.entries.map { get(it) }

    companion object {
        /** Safe on first launch: a zap starts low, vibe and beep are noticeable. */
        private val DEFAULTS =
            mapOf(
                StimulusKind.ZAP to StimulusConfig(StimulusKind.ZAP, 20, 1),
                StimulusKind.VIBE to StimulusConfig(StimulusKind.VIBE, 60, 1),
                StimulusKind.BEEP to StimulusConfig(StimulusKind.BEEP, 60, 1),
            )
        val DEFAULT = StimulusSettings()
    }
}

/** Where a saved stimulus config ended up. */
sealed interface StimulusSyncState {
    /** Phone only: no device connected, or the device rejected the write. */
    data class LocalOnly(
        val reason: String?,
    ) : StimulusSyncState

    /** Phone and wearable, so its own button and alarms use it too. */
    data object SyncedToDevice : StimulusSyncState
}
