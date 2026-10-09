package cz.peelco.jolt.data.api

import cz.peelco.jolt.domain.model.StimulusPermission
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Body of `PUT /friends/{friendId}/permissions/{stimulusKind}`.
 *
 * Not the permission itself: the grant's three keys overwrite, but
 * `automationAllowed` is three-valued on the wire. Absent leaves the stored
 * answer alone, `true`/`false` set it, and `null` resets it to the server
 * default. Sending the permission as read back would also echo the
 * read-only `automationAllowedEffective`.
 */
data class StimulusPermissionUpdate(
    val isAllowed: Boolean,
    val maxIntensity: Int,
    val cooldownSeconds: Int,
    val automation: Automation = Automation.Unchanged,
) {
    sealed interface Automation {
        /** Every ordinary edit, so a slider or a preset can never wipe an answer. */
        data object Unchanged : Automation

        data class Set(
            val allowed: Boolean,
        ) : Automation

        /** Back to "no answer", which follows the server's policy. */
        data object ResetToDefault : Automation

        companion object {
            /** The change that leaves `automationAllowed` equal to [answer]. */
            fun of(answer: Boolean?): Automation = answer?.let(::Set) ?: ResetToDefault
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("isAllowed", JsonPrimitive(isAllowed))
            put("maxIntensity", JsonPrimitive(maxIntensity))
            put("cooldownSeconds", JsonPrimitive(cooldownSeconds))
            when (automation) {
                Automation.Unchanged -> Unit
                is Automation.Set -> put("automationAllowed", JsonPrimitive(automation.allowed))
                Automation.ResetToDefault -> put("automationAllowed", JsonNull)
            }
        }

    companion object {
        fun of(
            permission: StimulusPermission,
            automation: Automation = Automation.Unchanged,
        ) = StimulusPermissionUpdate(permission.isAllowed, permission.maxIntensity, permission.cooldownSeconds, automation)
    }
}
