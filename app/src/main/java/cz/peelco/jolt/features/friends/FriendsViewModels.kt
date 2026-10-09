package cz.peelco.jolt.features.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.peelco.jolt.data.api.JoltApiException
import cz.peelco.jolt.domain.model.Friend
import cz.peelco.jolt.domain.model.FriendRequest
import cz.peelco.jolt.domain.model.PokeEvent
import cz.peelco.jolt.domain.model.ServerPolicies
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.StimulusPermission
import cz.peelco.jolt.domain.model.User
import cz.peelco.jolt.domain.repository.AuthRepository
import cz.peelco.jolt.domain.repository.FriendsRepository
import cz.peelco.jolt.domain.repository.PokeRepository
import cz.peelco.jolt.domain.repository.PokeSendException
import cz.peelco.jolt.features.shared.PokeFeedbackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

class AuthViewModel(
    private val repository: AuthRepository,
) : ViewModel() {
    val currentUser: StateFlow<User?> = repository.currentUser

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    val isBusy: StateFlow<Boolean> = busy.asStateFlow()
    val lastError: StateFlow<String?> = error.asStateFlow()

    fun dismissError() {
        error.value = null
    }

    private fun run(block: suspend () -> Unit) {
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (failure: Exception) {
                error.value = failure.message
            } finally {
                busy.value = false
            }
        }
    }

    fun signUp(
        email: String,
        password: String,
        handle: String,
        displayName: String,
    ) = run { repository.signUp(email, password, handle, displayName) }

    fun logIn(
        email: String,
        password: String,
    ) = run { repository.logIn(email, password) }

    fun logOut() {
        viewModelScope.launch { repository.logOut() }
    }
}

class FriendsViewModel(
    private val repository: FriendsRepository,
) : ViewModel() {
    /** Null until the first fetch lands, so "not loaded" differs from "empty". */
    private val local = MutableStateFlow<List<Friend>?>(null)
    val friends: StateFlow<List<Friend>?> = local.asStateFlow()
    val incomingRequests: StateFlow<List<FriendRequest>> = repository.incomingRequests
    val outgoingRequests: StateFlow<List<FriendRequest>> = repository.outgoingRequests
    val serverPolicies: StateFlow<ServerPolicies?> = repository.serverPolicies
    val myHandle: String get() = repository.myHandle
    val myInviteCode: String get() = repository.myInviteCode

    private val error = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = error.asStateFlow()

    init {
        // Mirrored rather than exposed directly, so an edit can show at once
        // and be rolled back if the server refuses it.
        viewModelScope.launch { repository.friends.collect { local.value = it } }
    }

    fun dismissError() {
        error.value = null
    }

    /** Sends a request; true when it went through. */
    suspend fun sendRequest(handle: String): Boolean = attempt { repository.sendRequest(handle) }

    suspend fun sendRequestByInviteCode(code: String): Boolean = attempt { repository.sendRequestByInviteCode(code) }

    private suspend fun attempt(block: suspend () -> Unit): Boolean {
        error.value = null
        return try {
            block()
            true
        } catch (failure: Exception) {
            error.value = failure.message
            false
        }
    }

    fun accept(request: FriendRequest) {
        viewModelScope.launch { runCatching { repository.acceptRequest(request.id) } }
    }

    fun reject(request: FriendRequest) {
        viewModelScope.launch { runCatching { repository.rejectRequest(request.id) } }
    }

    fun remove(friend: Friend) {
        viewModelScope.launch { runCatching { repository.removeFriend(friend.id) } }
    }

    fun refresh() {
        viewModelScope.launch { repository.refreshFriends() }
    }

    private fun currentGrant(
        friendId: UUID,
        kind: StimulusKind,
    ): StimulusPermission? = local.value?.firstOrNull { it.id == friendId }?.permissionsIGranted?.get(kind)

    private fun setGrant(
        friendId: UUID,
        kind: StimulusKind,
        permission: StimulusPermission,
    ) {
        local.value = local.value?.map { if (it.id == friendId) it.copy(permissionsIGranted = it.permissionsIGranted.with(kind, permission)) else it }
    }

    /**
     * Applied locally before the request returns: flip "Allow" and nudge a
     * slider straight after, and the second write must not carry the
     * pre-toggle state. Touches the grant only; the automation answer stays.
     */
    fun updatePermission(
        friend: Friend,
        kind: StimulusKind,
        permission: StimulusPermission,
    ) {
        val previous = currentGrant(friend.id, kind) ?: friend.permissionsIGranted[kind]
        setGrant(friend.id, kind, permission.keepingAutomationConsentOf(previous))
        viewModelScope.launch {
            try {
                repository.updatePermission(friend.id, kind, permission)
            } catch (failure: Exception) {
                // Only the grant goes back; an automation answer made
                // meanwhile is its own request.
                val now = currentGrant(friend.id, kind) ?: previous
                setGrant(friend.id, kind, previous.keepingAutomationConsentOf(now))
                error.value = failure.message
            }
        }
    }

    /** "May this friend's scripts send me this?" Null hands it back to the server default. */
    fun updateAutomationConsent(
        friend: Friend,
        kind: StimulusKind,
        allowed: Boolean?,
    ) {
        val previous = currentGrant(friend.id, kind) ?: return
        val updated =
            previous.copy(
                automationAllowed = allowed,
                // What the server will report back, so "Default (…)" reads right at once.
                automationAllowedEffective = allowed ?: serverPolicies.value?.automationAllowedByDefault ?: previous.automationAllowedEffective,
            )
        setGrant(friend.id, kind, updated)
        viewModelScope.launch {
            try {
                repository.updateAutomationConsent(friend.id, kind, updated, allowed)
            } catch (failure: Exception) {
                val now = currentGrant(friend.id, kind) ?: updated
                setGrant(friend.id, kind, now.keepingAutomationConsentOf(previous))
                error.value = failure.message
            }
        }
    }
}

class PokeViewModel(
    private val repository: PokeRepository,
    private val feedback: PokeFeedbackService?,
) : ViewModel() {
    /** The three failures need different words: only one means "definitely not poked". */
    enum class SendFailure { REFUSED, NOT_SENT, UNCONFIRMED }

    data class SendState(
        val isSending: Boolean = false,
        val error: String? = null,
        val failure: SendFailure? = null,
    )

    val activity: StateFlow<List<PokeEvent>> = repository.activity

    private val state = MutableStateFlow(SendState())
    val sendState: StateFlow<SendState> = state.asStateFlow()

    /** Retry resends this, id included, so a poke that did land isn't sent twice. */
    private var lastAttempt: Triple<Friend, StimulusConfig, UUID>? = null

    fun send(
        friend: Friend,
        stimulus: StimulusConfig,
    ) = send(friend, stimulus, UUID.randomUUID())

    fun retryLastSend() {
        val (friend, stimulus, id) = lastAttempt ?: return
        send(friend, stimulus, id)
    }

    fun dismissError() {
        state.value = SendState()
    }

    private fun send(
        friend: Friend,
        stimulus: StimulusConfig,
        pokeId: UUID,
    ) {
        if (state.value.isSending) return
        lastAttempt = Triple(friend, stimulus, pokeId)
        state.value = SendState(isSending = true)
        viewModelScope.launch {
            try {
                repository.sendPoke(friend.id, stimulus, pokeId)
                state.value = SendState()
                feedback?.noteSuccess("Poked ${friend.displayName} · ${stimulus.kind.displayName} ${stimulus.intensity}%")
            } catch (failure: Exception) {
                state.value = SendState(error = failure.message, failure = classify(failure))
            }
        }
    }

    fun simulateIncoming(
        friend: Friend,
        stimulus: StimulusConfig,
    ) {
        viewModelScope.launch { repository.simulateIncomingPoke(friend.id, stimulus) }
    }

    companion object {
        fun classify(error: Exception): SendFailure =
            when (error) {
                is PokeSendException.NotSent -> SendFailure.NOT_SENT
                is PokeSendException.Unconfirmed -> SendFailure.UNCONFIRMED
                // A dropped connection the repository couldn't check may have landed.
                is JoltApiException.Transport, is IOException -> SendFailure.UNCONFIRMED
                else -> SendFailure.REFUSED
            }
    }
}
