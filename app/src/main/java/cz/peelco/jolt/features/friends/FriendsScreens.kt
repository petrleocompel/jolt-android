package cz.peelco.jolt.features.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PortableWifiOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.peelco.jolt.BuildConfig
import cz.peelco.jolt.app.LocalAppContainer
import cz.peelco.jolt.domain.model.PokeDeliveryStatus
import cz.peelco.jolt.domain.model.PokeDirection
import cz.peelco.jolt.domain.model.PokeEvent
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.features.remote.TitledSheet
import cz.peelco.jolt.features.shared.BannerStyle
import cz.peelco.jolt.features.shared.FormButton
import cz.peelco.jolt.features.shared.FormDivider
import cz.peelco.jolt.features.shared.FormRow
import cz.peelco.jolt.features.shared.FormScreen
import cz.peelco.jolt.features.shared.FormSection
import cz.peelco.jolt.features.shared.InlineBanner
import cz.peelco.jolt.features.shared.LabeledValue
import cz.peelco.jolt.features.shared.NavigationRow
import cz.peelco.jolt.features.shared.QrCodeImage
import cz.peelco.jolt.features.shared.QrScanner
import cz.peelco.jolt.features.shared.copyToClipboard
import cz.peelco.jolt.features.shared.icon
import cz.peelco.jolt.features.shared.initials
import cz.peelco.jolt.features.shared.relativeTime
import cz.peelco.jolt.features.shared.shareText
import cz.peelco.jolt.ui.theme.JoltColors
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Log in or sign up; the Friends tab shows this while signed out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    var signUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val canSubmit = email.isNotBlank() && password.isNotBlank() && (!signUp || (handle.isNotBlank() && displayName.isNotBlank()))

    FormScreen(title = "Friends", onBack = null, bottomBar = { InlineBanner(error, BannerStyle.ERROR, onDismiss = viewModel::dismissError) }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("Connect with friends", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Log in or create an account to add friends and send pokes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Log In", "Sign Up").forEachIndexed { index, label ->
                    SegmentedButton(selected = signUp == (index == 1), onClick = { signUp = index == 1 }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("emailField"))
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("passwordField"))
            if (signUp) {
                OutlinedTextField(handle, { handle = it }, label = { Text("Handle (e.g. alice)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = { if (signUp) viewModel.signUp(email, password, handle, displayName) else viewModel.logIn(email, password) },
                enabled = canSubmit && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp).testTag("authSubmit"),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (signUp) "Sign Up" else "Log In", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Requests, friends, pending requests and the activity link. */
@Composable
fun FriendsListScreen(
    viewModel: FriendsViewModel,
    onOpenFriend: (UUID) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val incoming by viewModel.incomingRequests.collectAsStateWithLifecycle()
    val outgoing by viewModel.outgoingRequests.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    FormScreen(
        title = "Friends",
        onBack = null,
        actions = {
            IconButton(onClick = onOpenProfile) { Icon(Icons.Filled.AccountCircle, contentDescription = "Profile") }
            IconButton(onClick = { showAdd = true }, modifier = Modifier.testTag("addFriendButton")) { Icon(Icons.Filled.PersonAdd, contentDescription = "Add friend") }
        },
        bottomBar = { InlineBanner(error, BannerStyle.ERROR, onDismiss = viewModel::dismissError) },
    ) {
        if (incoming.isNotEmpty()) {
            FormSection(header = "Requests") {
                incoming.forEachIndexed { index, request ->
                    if (index > 0) FormDivider()
                    FormRow {
                        Column(Modifier.weight(1f)) {
                            Text(request.displayName, fontWeight = FontWeight.SemiBold)
                            Text("@${request.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { viewModel.accept(request) }) { Text("Accept") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.reject(request) }) { Text("Ignore") }
                    }
                }
            }
        }
        FormSection(header = "Friends") {
            val list = friends.orEmpty()
            if (list.isEmpty()) {
                FormRow { Text("No friends yet — add one to send or receive pokes.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            list.forEachIndexed { index, friend ->
                if (index > 0) FormDivider()
                FormRow(Modifier.clickable { onOpenFriend(friend.id) }.testTag("friend_${friend.handle}")) {
                    Column(Modifier.weight(1f)) {
                        Text(friend.displayName, fontWeight = FontWeight.SemiBold)
                        val canSend = friend.permissionsGrantedToMe.allowedKinds.joinToString(", ") { it.displayName }.ifEmpty { "nothing" }
                        Text("@${friend.handle} · can send: $canSend", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (outgoing.isNotEmpty()) {
            FormSection(header = "Pending") {
                outgoing.forEach { request ->
                    FormRow {
                        Text("@${request.handle}", modifier = Modifier.weight(1f))
                        Text("Pending", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        FormSection { NavigationRow("Poke activity", onClick = onOpenActivity) }
    }
    if (showAdd) AddFriendSheet(viewModel) { showAdd = false }
}

/** Add by handle, by invite code (typed or scanned), and show your own code. */
@Composable
fun AddFriendSheet(
    viewModel: FriendsViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var handle by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    TitledSheet(title = "Add friend", onDismiss = onDismiss, leading = "Close" to onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (scanning) {
                QrScanner(
                    onScanned = {
                        code = it
                        scanning = false
                    },
                    modifier = Modifier.padding(16.dp).fillMaxWidth().aspectRatio(1f),
                )
            }
            FormSection(header = "Add by handle") {
                OutlinedTextField(handle, { handle = it }, label = { Text("@handle") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                FormButton("Send request", enabled = handle.isNotBlank()) {
                    scope.launch { if (viewModel.sendRequest(handle)) onDismiss() }
                }
            }
            FormSection(header = "Add by invite code") {
                OutlinedTextField(code, { code = it }, label = { Text("Invite code") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                FormButton("Send request", enabled = code.isNotBlank()) {
                    scope.launch { if (viewModel.sendRequestByInviteCode(code)) onDismiss() }
                }
                FormButton("Scan a QR code instead") { scanning = true }
            }
            FormSection(header = "Your code") {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCodeImage(viewModel.myInviteCode, 180.dp)
                    Text("@${viewModel.myHandle}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(viewModel.myInviteCode, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let { FormSection { FormRow { Text(it, color = MaterialTheme.colorScheme.error) } } }
        }
    }
}

/** A friend: the composer, their permissions, and removal. */
@Composable
fun FriendDetailScreen(
    friendId: UUID,
    friendsViewModel: FriendsViewModel,
    pokeViewModel: PokeViewModel,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val container = LocalAppContainer.current
    val friends by friendsViewModel.friends.collectAsStateWithLifecycle()
    val feedback by container.pokeFeedback.lastSuccessMessage.collectAsStateWithLifecycle()
    val friend = friends?.firstOrNull { it.id == friendId }
    if (friend == null) {
        FormScreen(title = "Friend", onBack = onBack) {
            Text("Friend not found", modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    FormScreen(title = friend.displayName, onBack = onBack, bottomBar = { InlineBanner(feedback, BannerStyle.SUCCESS) }) {
        FormSection(header = "Poke") { Box(Modifier.padding(16.dp)) { PokeComposer(friend, pokeViewModel) } }
        FormSection {
            NavigationRow("Permissions you've granted ${friend.displayName}", onClick = onOpenPermissions)
            if (BuildConfig.DEBUG) {
                FormDivider()
                FormButton("Simulate incoming poke from ${friend.displayName}") { pokeViewModel.simulateIncoming(friend, StimulusConfig(StimulusKind.ZAP, 20)) }
            }
            FormDivider()
            FormButton("Remove friend", color = MaterialTheme.colorScheme.error) {
                friendsViewModel.remove(friend)
                onBack()
            }
        }
    }
}

/** Every poke, sent and received, newest first. */
@Composable
fun ActivityScreen(
    viewModel: PokeViewModel,
    onBack: () -> Unit,
    onOpenPoke: (UUID) -> Unit,
) {
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    FormScreen(title = "Activity", onBack = onBack) {
        if (activity.isEmpty()) {
            Text("No pokes yet", modifier = Modifier.fillMaxWidth().padding(48.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FormSection {
                activity.forEachIndexed { index, event ->
                    if (index > 0) FormDivider()
                    FormRow(Modifier.clickable { onOpenPoke(event.id) }) {
                        Column(Modifier.weight(1f)) {
                            Text(event.sentence, style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (event.isAutomated) Icon(Icons.Filled.SettingsSuggest, contentDescription = "Sent by a script", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${relativeTime(event.createdAt)} · ${event.stimulus.kind.displayName} ${event.stimulus.intensity}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val (icon, tint) = statusIcon(event.status)
                        Icon(icon, contentDescription = event.status.title, tint = tint)
                    }
                }
            }
        }
    }
}

@Composable
private fun statusIcon(status: PokeDeliveryStatus): Pair<ImageVector, Color> =
    when (status) {
        PokeDeliveryStatus.PENDING -> Icons.Filled.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        PokeDeliveryStatus.FIRED -> Icons.Filled.CheckCircle to JoltColors.Success
        PokeDeliveryStatus.DEVICE_NOT_CONNECTED -> Icons.Filled.PortableWifiOff to JoltColors.Warning
        PokeDeliveryStatus.NOT_ALLOWED -> Icons.Filled.Block to JoltColors.Danger
        PokeDeliveryStatus.MUTED -> Icons.Filled.NotificationsOff to MaterialTheme.colorScheme.onSurfaceVariant
    }

/** One poke's stimulus, people, delivery and id. */
@Composable
fun PokeDetailScreen(
    pokeId: UUID,
    viewModel: PokeViewModel,
    onBack: () -> Unit,
) {
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val event = activity.firstOrNull { it.id == pokeId }
    FormScreen(title = "Poke", onBack = onBack) {
        if (event == null) {
            Text("This poke is no longer in your activity.", modifier = Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@FormScreen
        }
        PokeDetail(event)
    }
}

@Composable
private fun PokeDetail(event: PokeEvent) {
    val sent = event.direction == PokeDirection.SENT
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(JoltColors.Violet.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(event.stimulus.kind.icon, contentDescription = null, tint = JoltColors.VioletInk)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(event.sentence, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (event.isAutomated) Icon(Icons.Filled.SettingsSuggest, contentDescription = "Sent by a script", modifier = Modifier.size(14.dp))
                Text(relativeTime(event.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    FormSection(header = "Stimulus") {
        LabeledValue("Type", event.stimulus.kind.displayName)
        LabeledValue("Intensity", "${event.stimulus.intensity}%")
        LabeledValue("Repetitions", "${event.stimulus.repetitions}")
    }
    FormSection(header = "Who") {
        LabeledValue(if (sent) "Recipient" else "Sender", event.friendDisplayName)
        LabeledValue("Handle", "@${event.friendHandle}")
        LabeledValue("Direction", if (sent) "Sent" else "Received")
        if (event.isAutomated) {
            LabeledValue(
                "Sent by",
                if (sent) event.apiTokenName?.let { "Script “$it”" } ?: "Your script" else "${event.friendDisplayName}'s script",
            )
        }
    }
    val them = if (sent) event.friendDisplayName else "You"
    val theirs = if (sent) "${event.friendDisplayName}'s" else "your"
    val explanation =
        when (event.status) {
            PokeDeliveryStatus.PENDING -> "Accepted and pushed, but no device has reported back yet. Push delivery isn't guaranteed, so a poke can stay pending."
            PokeDeliveryStatus.FIRED -> "Reached the Pavlok and actuated it."
            PokeDeliveryStatus.DEVICE_NOT_CONNECTED -> "The push arrived, but $theirs Pavlok wasn't connected to fire it on."
            PokeDeliveryStatus.NOT_ALLOWED -> "The receiving side refused it — the permission re-check rejected this stimulus."
            PokeDeliveryStatus.MUTED -> "$them had \"Do not disturb\" for pokes switched on."
        }
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss").withZone(ZoneId.systemDefault())
    FormSection(header = "Delivery") {
        val (icon, tint) = statusIcon(event.status)
        FormRow {
            Text("Status", modifier = Modifier.weight(1f))
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(" ${event.status.title}")
        }
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        LabeledValue("Sent", formatter.format(event.createdAt))
        event.ackedAt?.let { LabeledValue("Confirmed", formatter.format(it)) }
        event.timeToAckMillis?.let { millis -> LabeledValue("Took", if (millis < 1000) "$millis ms" else "%.1f s".format(millis / 1000.0)) }
    }
    FormSection(header = "Reference") {
        FormRow {
            Text("Poke ID", modifier = Modifier.weight(1f))
            SelectionContainer { Text(event.id.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Your own account: invite code, counts, the linked Pavlok account, log out. */
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    friendsViewModel: FriendsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val user by authViewModel.currentUser.collectAsStateWithLifecycle()
    val friends by friendsViewModel.friends.collectAsStateWithLifecycle()
    val incoming by friendsViewModel.incomingRequests.collectAsStateWithLifecycle()
    val outgoing by friendsViewModel.outgoingRequests.collectAsStateWithLifecycle()
    val pavlok by container.pavlok.account.collectAsStateWithLifecycle()
    val handle = user?.handle.orEmpty()
    FormScreen(title = "Profile", onBack = onBack) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Text(initials(user?.displayName ?: ""), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(user?.displayName?.ifEmpty { null } ?: "@$handle", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text("@$handle", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FormSection {
            LabeledValue("Friends", "${friends?.size ?: 0}")
            LabeledValue("Pending requests", "${incoming.size + outgoing.size}")
        }
        FormSection(header = "Invite", footer = "Anyone with this handle, code, or QR can send you a friend request.") {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCodeImage(friendsViewModel.myInviteCode, 160.dp)
                Text(friendsViewModel.myInviteCode, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            FormButton("Share my handle") { shareText(context, "Add me on Jolt: @$handle") }
            FormButton("Copy handle") { copyToClipboard(context, "Handle", handle) }
        }
        FormSection(header = "Linked accounts", footer = "Manage Pavlok sign-in from Settings → Account.") {
            LabeledValue("Pavlok account", pavlok?.displayName ?: "Not signed in")
        }
        FormSection { FormButton("Log out", color = MaterialTheme.colorScheme.error) { authViewModel.logOut() } }
    }
}
