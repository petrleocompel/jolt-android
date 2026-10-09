package cz.peelco.jolt.features.shared

import cz.peelco.jolt.data.store.JsonValueStore
import cz.peelco.jolt.domain.model.PokeFeedbackProfile
import cz.peelco.jolt.domain.model.PokeFeedbackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays the configured success feedback after a poke goes out: banner,
 * flash, haptic, a temporary "Sent" label. Shared by Quick Poke on Remote and
 * the Friends composer, so tap, hold and confirm all end in one place.
 */
class PokeFeedbackService(
    private val store: JsonValueStore<PokeFeedbackSettings>,
    private val scope: CoroutineScope,
    private val haptic: () -> Unit,
) {
    val settings: StateFlow<PokeFeedbackSettings> = store.flow

    private val message = MutableStateFlow<String?>(null)
    private val flashing = MutableStateFlow(false)
    private val successLabel = MutableStateFlow(false)
    val lastSuccessMessage: StateFlow<String?> = message.asStateFlow()
    val isFlashing: StateFlow<Boolean> = flashing.asStateFlow()
    val isShowingSuccessLabel: StateFlow<Boolean> = successLabel.asStateFlow()

    private var bannerJob: Job? = null
    private var flashJob: Job? = null
    private var labelJob: Job? = null

    fun applyProfile(profile: PokeFeedbackProfile) = store.update { it.applyingProfile(profile) }

    fun setShowBanner(value: Boolean) = store.update { it.copy(showBanner = value).reconciled() }

    fun setFlashButton(value: Boolean) = store.update { it.copy(flashButton = value).reconciled() }

    fun setPlayHaptic(value: Boolean) = store.update { it.copy(playHaptic = value).reconciled() }

    fun setSwapButtonLabel(value: Boolean) = store.update { it.copy(swapButtonLabel = value).reconciled() }

    /** Call after a poke is accepted; each effect no-ops when its toggle is off. */
    fun noteSuccess(text: String) {
        val current = settings.value
        if (current.playHaptic) runCatching(haptic)
        if (current.showBanner) {
            message.value = text
            bannerJob?.cancel()
            bannerJob = scope.launch { delay(3_000); message.value = null }
        }
        if (current.flashButton) {
            flashing.value = true
            flashJob?.cancel()
            flashJob = scope.launch { delay(220); flashing.value = false }
        }
        if (current.swapButtonLabel) {
            successLabel.value = true
            labelJob?.cancel()
            labelJob = scope.launch { delay(2_000); successLabel.value = false }
        }
    }

    fun clearSuccessMessage() {
        bannerJob?.cancel()
        message.value = null
    }
}
