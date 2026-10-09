package cz.peelco.jolt.data.social

import cz.peelco.jolt.domain.model.ApiToken
import cz.peelco.jolt.domain.model.ApiTokenDraft
import cz.peelco.jolt.domain.model.ApiTokenFriendScope
import cz.peelco.jolt.domain.model.Friend
import cz.peelco.jolt.domain.model.FriendPermissionSet
import cz.peelco.jolt.domain.model.FriendRequest
import cz.peelco.jolt.domain.model.FriendRequestDirection
import cz.peelco.jolt.domain.model.PokeDeliveryStatus
import cz.peelco.jolt.domain.model.PokeDirection
import cz.peelco.jolt.domain.model.PokeEvent
import cz.peelco.jolt.domain.model.PokePushPayload
import cz.peelco.jolt.domain.model.PushConfig
import cz.peelco.jolt.domain.model.PushTransport
import cz.peelco.jolt.domain.model.RegisteredDevice
import cz.peelco.jolt.domain.model.ServerConfiguration
import cz.peelco.jolt.domain.model.ServerPolicies
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.StimulusPermission
import cz.peelco.jolt.domain.model.TestPushAck
import cz.peelco.jolt.domain.model.TestPushDeviceResult
import cz.peelco.jolt.domain.model.TestPushPath
import cz.peelco.jolt.domain.model.TestPushPayload
import cz.peelco.jolt.domain.model.TestPushStatus
import cz.peelco.jolt.domain.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/**
 * In-memory stand-in for a Jolt Server: the same contract, no network. Used
 * by UI tests and the demo build, so they need no server. Any sign-in
 * succeeds and seeds a small friend graph.
 */
class MockSocialBackend(
    private val firer: LocalStimulusFirer,
    startSignedIn: Boolean = false,
) : SocialBackend {
    override val configuration: StateFlow<ServerConfiguration> = MutableStateFlow(ServerConfiguration("https://mock.jolt.invalid/api/v1"))
    override var sessionListener: SessionListener? = null

    private val user = MutableStateFlow<User?>(null)
    private val friendsState = MutableStateFlow<List<Friend>?>(null)
    private val incoming = MutableStateFlow<List<FriendRequest>>(emptyList())
    private val outgoing = MutableStateFlow<List<FriendRequest>>(emptyList())
    private val activityLog = MutableStateFlow<List<PokeEvent>>(emptyList())
    private val policies = MutableStateFlow<ServerPolicies?>(null)
    private val tokens = mutableListOf<ApiToken>()
    private val deviceId = UUID.fromString("9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d")

    override val currentUser: StateFlow<User?> = user.asStateFlow()
    override val friends: StateFlow<List<Friend>?> = friendsState.asStateFlow()
    override val incomingRequests: StateFlow<List<FriendRequest>> = incoming.asStateFlow()
    override val outgoingRequests: StateFlow<List<FriendRequest>> = outgoing.asStateFlow()
    override val activity: StateFlow<List<PokeEvent>> = activityLog.asStateFlow()
    override val serverPolicies: StateFlow<ServerPolicies?> = policies.asStateFlow()
    override val myHandle: String get() = user.value?.handle.orEmpty()
    override val myInviteCode: String get() = if (user.value != null) "JOLT-DEMO-42" else ""

    init {
        if (startSignedIn) signIn(User(UUID.randomUUID(), "you", "You", "you@example.com"))
    }

    override fun start() = Unit

    override suspend fun switchServer(configuration: ServerConfiguration) = logOut()

    private fun signIn(newUser: User) {
        user.value = newUser
        policies.value = ServerPolicies(automationConsentRequired = false)
        if (friendsState.value == null) {
            friendsState.value = seedFriends()
            incoming.value = listOf(FriendRequest(UUID.randomUUID(), "charlie", "Charlie", FriendRequestDirection.INCOMING, Instant.now().minusSeconds(3600)))
            outgoing.value = listOf(FriendRequest(UUID.randomUUID(), "dana", "Dana", FriendRequestDirection.OUTGOING, Instant.now().minusSeconds(7200)))
            activityLog.value = seedActivity()
        }
    }

    override suspend fun signUp(
        email: String,
        password: String,
        handle: String,
        displayName: String,
    ) = signIn(User(UUID.randomUUID(), handle.trim().lowercase(), displayName, email))

    override suspend fun logIn(
        email: String,
        password: String,
    ) = signIn(User(UUID.randomUUID(), "you", "You", email))

    override suspend fun logOut() {
        sessionListener?.beforeSignOut()
        user.value = null
        policies.value = null
        friendsState.value = null
        incoming.value = emptyList()
        outgoing.value = emptyList()
        activityLog.value = emptyList()
    }

    override suspend fun sendRequest(handle: String) {
        val normalized = handle.trim().removePrefix("@").lowercase()
        require(normalized.isNotEmpty()) { "Enter a handle." }
        outgoing.update { it + FriendRequest(UUID.randomUUID(), normalized, normalized.replaceFirstChar(Char::uppercase), FriendRequestDirection.OUTGOING, Instant.now()) }
    }

    override suspend fun sendRequestByInviteCode(inviteCode: String) {
        outgoing.update { it + FriendRequest(UUID.randomUUID(), "invited", "Invited friend", FriendRequestDirection.OUTGOING, Instant.now()) }
    }

    override suspend fun acceptRequest(id: UUID) {
        val request = incoming.value.firstOrNull { it.id == id } ?: return
        incoming.update { list -> list.filterNot { it.id == id } }
        friendsState.update { (it ?: emptyList()) + Friend(UUID.randomUUID(), request.handle, request.displayName, FriendPermissionSet.NONE, FriendPermissionSet.NONE) }
    }

    override suspend fun rejectRequest(id: UUID) {
        incoming.update { list -> list.filterNot { it.id == id } }
        outgoing.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun removeFriend(id: UUID) {
        friendsState.update { list -> list?.filterNot { it.id == id } }
    }

    override suspend fun updatePermission(
        friendId: UUID,
        kind: StimulusKind,
        permission: StimulusPermission,
    ) = editGrant(friendId, kind) { current -> permission.keepingAutomationConsentOf(current) }

    override suspend fun updateAutomationConsent(
        friendId: UUID,
        kind: StimulusKind,
        permission: StimulusPermission,
        allowed: Boolean?,
    ) = editGrant(friendId, kind) {
        permission.copy(automationAllowed = allowed, automationAllowedEffective = allowed ?: policies.value?.automationAllowedByDefault)
    }

    private fun editGrant(
        friendId: UUID,
        kind: StimulusKind,
        transform: (StimulusPermission) -> StimulusPermission,
    ) {
        friendsState.update { list ->
            list?.map { friend ->
                if (friend.id != friendId) {
                    friend
                } else {
                    friend.copy(permissionsIGranted = friend.permissionsIGranted.with(kind, transform(friend.permissionsIGranted[kind])))
                }
            }
        }
    }

    override suspend fun refreshFriends() = Unit

    override suspend fun sendPoke(
        friendId: UUID,
        stimulus: StimulusConfig,
        pokeId: UUID,
    ) {
        val friend = friendsState.value?.firstOrNull { it.id == friendId } ?: error("That friend is no longer on your list.")
        val grant = friend.permissionsGrantedToMe[stimulus.kind]
        check(grant.isAllowed) { "${friend.displayName} hasn't allowed ${stimulus.kind.displayName.lowercase()}." }
        check(stimulus.intensity <= grant.maxIntensity) { "Intensity exceeds their cap of ${grant.maxIntensity}." }
        if (activityLog.value.any { it.id == pokeId }) return
        activityLog.update {
            listOf(PokeEvent(pokeId, PokeDirection.SENT, friend.handle, friend.displayName, stimulus, PokeDeliveryStatus.FIRED, Instant.now(), Instant.now())) + it
        }
    }

    override suspend fun handleIncomingPoke(payload: PokePushPayload): PokeDeliveryStatus {
        val status = firer.fire(payload.pokeId, payload.stimulus)
        activityLog.update {
            listOf(
                PokeEvent(payload.pokeId, PokeDirection.RECEIVED, payload.senderHandle, payload.senderDisplayName, payload.stimulus, status, Instant.now(), Instant.now(), payload.viaApiToken),
            ) + it.filterNot { event -> event.id == payload.pokeId }
        }
        return status
    }

    override suspend fun simulateIncomingPoke(
        friendId: UUID,
        stimulus: StimulusConfig,
    ) {
        val friend = friendsState.value?.firstOrNull { it.id == friendId } ?: return
        handleIncomingPoke(PokePushPayload(UUID.randomUUID(), friend.handle, friend.displayName, myHandle, stimulus))
    }

    override suspend fun registeredDevices(): List<RegisteredDevice> =
        listOf(RegisteredDevice(deviceId, "android", "demo1234", true, Instant.now().minusSeconds(86_400), Instant.now()))

    override suspend fun sendTestPush(
        deviceId: UUID?,
        stimulus: StimulusConfig?,
    ): TestPushStatus {
        val testId = UUID.randomUUID()
        val status = handleIncomingTestPush(TestPushPayload(testId, this.deviceId, stimulus), TestPushPath.FOREGROUND)
        return TestPushStatus(
            testId = testId,
            sentAt = Instant.now(),
            stimulus = stimulus,
            devices = listOf(TestPushDeviceResult(this.deviceId, true)),
            acks = listOf(TestPushAck(this.deviceId, TestPushPath.FOREGROUND, status, Instant.now(), 120)),
        )
    }

    override suspend fun testPushStatus(testId: UUID): TestPushStatus = error("The mock server keeps no test history.")

    override suspend fun handleIncomingTestPush(
        payload: TestPushPayload,
        path: TestPushPath,
    ): PokeDeliveryStatus? = payload.stimulus?.let { firer.fire(payload.testId, it) }

    override suspend fun listTokens(): List<ApiToken> = tokens.toList()

    override suspend fun createToken(draft: ApiTokenDraft): ApiToken {
        val secret = "jolt_pat_demo${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val token =
            ApiToken(
                id = UUID.randomUUID(),
                name = draft.name,
                prefix = secret.take(13),
                createdAt = Instant.now(),
                expiresAt = draft.expiresInDays?.let { Instant.now().plusSeconds(it * 86_400L) },
                scopes = draft.scopes,
                friendScope = if (draft.friendIds == null) ApiTokenFriendScope.ALL else ApiTokenFriendScope.SELECTED,
                friendIds = draft.friendIds.orEmpty(),
                allowedKinds = draft.allowedKinds,
                maxIntensity = draft.maxIntensity,
                minIntervalSeconds = draft.minIntervalSeconds ?: 1,
            )
        tokens += token
        return token.copy(token = secret)
    }

    override suspend fun revokeToken(id: UUID) {
        tokens.removeAll { it.id == id }
    }

    override suspend fun pushConfig(): PushConfig = PushConfig(PushTransport.NONE)

    override suspend fun registerRelayPushToken(
        relayToken: String,
        payloadKey: String,
        keyId: String,
    ) = Unit

    override suspend fun forgetRelayPushToken(relayToken: String) = Unit

    private fun seedFriends(): List<Friend> {
        val generous =
            FriendPermissionSet(
                zap = StimulusPermission.allowed(maxIntensity = 40, cooldownSeconds = 60).copy(automationAllowedEffective = true),
                vibe = StimulusPermission.allowed(maxIntensity = 100, cooldownSeconds = 0).copy(automationAllowedEffective = true),
                beep = StimulusPermission.allowed(maxIntensity = 80, cooldownSeconds = 15).copy(automationAllowedEffective = true),
            )
        val cautious =
            FriendPermissionSet(
                zap = StimulusPermission.DISABLED.copy(automationAllowedEffective = true),
                vibe = StimulusPermission.allowed(maxIntensity = 60, cooldownSeconds = 30).copy(automationAllowedEffective = true),
                beep = StimulusPermission.DISABLED.copy(automationAllowedEffective = true),
            )
        return listOf(
            Friend(UUID.randomUUID(), "alice", "Alice", generous, cautious),
            Friend(UUID.randomUUID(), "bob", "Bob", cautious, generous),
        )
    }

    private fun seedActivity(): List<PokeEvent> {
        val now = Instant.now()
        return listOf(
            PokeEvent(UUID.randomUUID(), PokeDirection.RECEIVED, "alice", "Alice", StimulusConfig(StimulusKind.VIBE, 40, 1), PokeDeliveryStatus.FIRED, now.minusSeconds(600), now.minusSeconds(599)),
            PokeEvent(UUID.randomUUID(), PokeDirection.SENT, "bob", "Bob", StimulusConfig(StimulusKind.BEEP, 60, 2), PokeDeliveryStatus.PENDING, now.minusSeconds(3_600)),
            PokeEvent(UUID.randomUUID(), PokeDirection.RECEIVED, "alice", "Alice", StimulusConfig(StimulusKind.ZAP, 20, 1), PokeDeliveryStatus.MUTED, now.minusSeconds(86_400), now.minusSeconds(86_399), viaApiToken = true),
        )
    }
}
