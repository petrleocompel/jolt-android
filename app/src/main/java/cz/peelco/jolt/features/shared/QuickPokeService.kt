package cz.peelco.jolt.features.shared

import cz.peelco.jolt.data.store.JsonValueStore
import cz.peelco.jolt.domain.model.QuickPokeSettings
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.repository.PokeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Backs the Remote tab's quick poke: the friend and stimulus chosen in
 * Settings, sent through the normal poke path. Shared by Remote and its
 * Settings screen so a change in one shows in the other at once.
 */
class QuickPokeService(
    private val store: JsonValueStore<QuickPokeSettings>,
    private val pokes: PokeRepository,
    private val feedback: PokeFeedbackService,
    private val scope: CoroutineScope,
) {
    val settings: StateFlow<QuickPokeSettings> = store.flow

    private val lastSent = MutableStateFlow<Instant?>(null)
    private val error = MutableStateFlow<String?>(null)
    val lastPokeSentAt: StateFlow<Instant?> = lastSent.asStateFlow()
    val lastError: StateFlow<String?> = error.asStateFlow()

    fun setEnabled(enabled: Boolean) = store.update { it.copy(isEnabled = enabled) }

    fun setTarget(
        friendId: UUID,
        name: String,
    ) = store.update { it.copy(targetFriendId = friendId, targetFriendName = name) }

    fun clearTarget() = store.update { it.copy(targetFriendId = null, targetFriendName = null) }

    fun setStimulus(stimulus: StimulusConfig) = store.update { it.copy(stimulus = stimulus) }

    fun dismissError() {
        error.value = null
    }

    fun sendQuickPoke() {
        val current = settings.value
        val friendId = current.targetFriendId ?: return
        error.value = null
        val name = current.targetFriendName ?: "friend"
        scope.launch {
            try {
                pokes.sendPoke(friendId, current.stimulus)
                lastSent.value = Instant.now()
                feedback.noteSuccess("Poked $name · ${current.stimulus.kind.displayName} ${current.stimulus.intensity}%")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failure.message
            }
        }
    }
}
