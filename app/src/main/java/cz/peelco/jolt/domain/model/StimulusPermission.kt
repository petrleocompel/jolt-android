package cz.peelco.jolt.domain.model

import kotlinx.serialization.Serializable

/**
 * One friend's poke rights for one stimulus kind: a friend might be fine to
 * vibe you but not zap you.
 */
@Serializable
data class StimulusPermission(
    val isAllowed: Boolean,
    val maxIntensity: Int,
    val cooldownSeconds: Int,
    /**
     * The granter's explicit answer to "may this friend's scripts send this
     * too?". Null means no answer yet, which follows `ServerPolicies`. Never
     * sent back as part of an ordinary edit, so moving a slider can't wipe it.
     */
    val automationAllowed: Boolean? = null,
    /**
     * Whether automated pokes are accepted right now. Null from a server that
     * predates automation consent, which is how the UI knows to hide it.
     */
    val automationAllowedEffective: Boolean? = null,
) {
    val supportsAutomationConsent: Boolean get() = automationAllowedEffective != null

    /** Same allow / cap / cooldown, whatever either side says about automation. */
    fun hasSameGrant(other: StimulusPermission): Boolean =
        isAllowed == other.isAllowed &&
            maxIntensity == other.maxIntensity &&
            cooldownSeconds == other.cooldownSeconds

    /** This grant, carrying `other`'s automation answer instead of its own. */
    fun keepingAutomationConsentOf(other: StimulusPermission): StimulusPermission =
        copy(automationAllowed = other.automationAllowed, automationAllowedEffective = other.automationAllowedEffective)

    companion object {
        val DISABLED = StimulusPermission(isAllowed = false, maxIntensity = 0, cooldownSeconds = 60)

        fun allowed(
            maxIntensity: Int = 40,
            cooldownSeconds: Int = 60,
        ) = StimulusPermission(isAllowed = true, maxIntensity = maxIntensity, cooldownSeconds = cooldownSeconds)
    }
}

/**
 * A full grant, one entry per kind. Two exist per friendship — what they grant
 * you and what you grant them — always edited from the granter's side.
 */
@Serializable
data class FriendPermissionSet(
    val zap: StimulusPermission,
    val vibe: StimulusPermission,
    val beep: StimulusPermission,
) {
    operator fun get(kind: StimulusKind): StimulusPermission =
        when (kind) {
            StimulusKind.ZAP -> zap
            StimulusKind.VIBE -> vibe
            StimulusKind.BEEP -> beep
        }

    fun with(
        kind: StimulusKind,
        permission: StimulusPermission,
    ): FriendPermissionSet =
        when (kind) {
            StimulusKind.ZAP -> copy(zap = permission)
            StimulusKind.VIBE -> copy(vibe = permission)
            StimulusKind.BEEP -> copy(beep = permission)
        }

    val allowedKinds: List<StimulusKind> get() = StimulusKind.entries.filter { get(it).isAllowed }

    companion object {
        val NONE = FriendPermissionSet(StimulusPermission.DISABLED, StimulusPermission.DISABLED, StimulusPermission.DISABLED)
    }
}

/** Server-wide rules the app explains rather than enforces. */
@Serializable
data class ServerPolicies(
    /**
     * True: a friend's automated pokes are blocked until you allow them. False:
     * allowed unless you block them. An explicit answer always wins.
     */
    val automationConsentRequired: Boolean,
) {
    val automationAllowedByDefault: Boolean get() = !automationConsentRequired
}
