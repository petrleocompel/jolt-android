package cz.peelco.jolt.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cz.peelco.jolt.domain.model.GattCharacteristicDump
import cz.peelco.jolt.features.alarms.AlarmsListScreen
import cz.peelco.jolt.features.device.BluetoothLogScreen
import cz.peelco.jolt.features.device.ButtonConfigScreen
import cz.peelco.jolt.features.device.DeviceControlViewModel
import cz.peelco.jolt.features.device.DeviceEventsScreen
import cz.peelco.jolt.features.device.DiagnosticsScreen
import cz.peelco.jolt.features.device.ProtocolLabScreen
import cz.peelco.jolt.features.friends.ActivityScreen
import cz.peelco.jolt.features.friends.AuthScreen
import cz.peelco.jolt.features.friends.AuthViewModel
import cz.peelco.jolt.features.friends.FriendDetailScreen
import cz.peelco.jolt.features.friends.FriendsListScreen
import cz.peelco.jolt.features.friends.FriendsViewModel
import cz.peelco.jolt.features.friends.PermissionEditScreen
import cz.peelco.jolt.features.friends.PokeDetailScreen
import cz.peelco.jolt.features.friends.PokeViewModel
import cz.peelco.jolt.features.friends.ProfileScreen
import cz.peelco.jolt.features.onboarding.OnboardingScreen
import cz.peelco.jolt.features.pavlok.PavlokAccountScreen
import cz.peelco.jolt.features.remote.DeviceDetailScreen
import cz.peelco.jolt.features.remote.DeviceTool
import cz.peelco.jolt.features.remote.RemoteDashboardScreen
import cz.peelco.jolt.features.settings.AboutScreen
import cz.peelco.jolt.features.settings.ApiTokensScreen
import cz.peelco.jolt.features.settings.FiringModesScreen
import cz.peelco.jolt.features.settings.NotificationTestScreen
import cz.peelco.jolt.features.settings.PokeFeedbackScreen
import cz.peelco.jolt.features.settings.PokeTriggerSettingsScreen
import cz.peelco.jolt.features.settings.QuickPokeSettingsScreen
import cz.peelco.jolt.features.settings.ServerSettingsScreen
import cz.peelco.jolt.features.settings.SettingsDestination
import cz.peelco.jolt.features.settings.SettingsScreen
import java.util.UUID

private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    REMOTE("remote", "Remote", Icons.Filled.Bolt),
    ALARMS("alarms", "Alarms", Icons.Filled.Alarm),
    FRIENDS("friends", "Friends", Icons.Filled.Group),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/**
 * The first-run pairing offer, or the four tabs. A device that is paired but
 * unreachable goes straight to the tabs; the Remote card shows the reconnect.
 */
@Composable
fun JoltRoot() {
    val container = LocalAppContainer.current
    val deviceViewModel = containerViewModel { DeviceControlViewModel(it.deviceRepository, onPaired = it::keepWearableConnected) }
    val connected by deviceViewModel.connectedDevice.collectAsStateWithLifecycle()
    val paired by deviceViewModel.pairedDevice.collectAsStateWithLifecycle()
    val choseNoDevice by container.settings.didChooseNoDevice.flow.collectAsStateWithLifecycle()

    if (connected == null && paired == null && !choseNoDevice) {
        OnboardingScreen(deviceViewModel) { container.settings.didChooseNoDevice.set(true) }
    } else {
        MainTabs(deviceViewModel)
    }
}

@Composable
private fun MainTabs(deviceViewModel: DeviceControlViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentTab = Tab.entries.firstOrNull { backStack?.destination?.route?.startsWith(it.route) == true } ?: Tab.REMOTE

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == currentTab,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(selectedTextColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("tab_${tab.route}"),
                    )
                }
            }
        },
    ) { padding ->
        Destinations(navController, deviceViewModel, Modifier.padding(bottom = padding.calculateBottomPadding()))
    }
}

@Composable
private fun Destinations(
    nav: NavHostController,
    deviceViewModel: DeviceControlViewModel,
    modifier: Modifier,
) {
    val authViewModel = containerViewModel { AuthViewModel(it.social) }
    val friendsViewModel = containerViewModel { FriendsViewModel(it.social) }
    val pokeViewModel = containerViewModel { PokeViewModel(it.social, it.pokeFeedback) }
    val user by authViewModel.currentUser.collectAsStateWithLifecycle()
    // The table Diagnostics already read, handed to the protocol lab.
    var labGatt by remember { mutableStateOf<List<GattCharacteristicDump>?>(null) }
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(nav, startDestination = Tab.REMOTE.route, modifier = modifier) {
        composable(Tab.REMOTE.route) { RemoteDashboardScreen(deviceViewModel) { nav.navigate("remote/device") } }
        composable("remote/device") {
            DeviceDetailScreen(deviceViewModel, back) { tool ->
                when (tool) {
                    DeviceTool.DIAGNOSTICS -> nav.navigate("remote/diagnostics")
                    DeviceTool.PROTOCOL_LAB -> {
                        labGatt = null
                        nav.navigate("remote/lab")
                    }
                    DeviceTool.BLUETOOTH_LOG -> nav.navigate("remote/log")
                    DeviceTool.BUTTON_CONFIG -> nav.navigate("remote/buttons")
                }
            }
        }
        composable("remote/diagnostics") {
            DiagnosticsScreen(
                deviceViewModel,
                back,
                onOpenProtocolLab = { gatt ->
                    labGatt = gatt
                    nav.navigate("remote/lab")
                },
                onOpenLog = { nav.navigate("remote/log") },
                onOpenEvents = { listening -> nav.navigate("remote/events/$listening") },
            )
        }
        composable("remote/lab") { ProtocolLabScreen(deviceViewModel, labGatt, back) { nav.navigate("remote/log") } }
        composable("remote/log") { BluetoothLogScreen(back) }
        composable("remote/events/{listening}", listOf(navArgument("listening") { type = NavType.BoolType })) { entry ->
            DeviceEventsScreen(entry.arguments?.getBoolean("listening") ?: false, back)
        }
        composable("remote/buttons") { ButtonConfigScreen(deviceViewModel, back) }

        composable(Tab.ALARMS.route) { AlarmsListScreen() }

        composable(Tab.FRIENDS.route) {
            if (user == null) {
                AuthScreen(authViewModel)
            } else {
                FriendsListScreen(
                    friendsViewModel,
                    onOpenFriend = { nav.navigate("friends/friend/$it") },
                    onOpenProfile = { nav.navigate("friends/profile") },
                    onOpenActivity = { nav.navigate("friends/activity") },
                )
            }
        }
        composable("friends/friend/{id}") { entry ->
            val id = UUID.fromString(entry.arguments?.getString("id"))
            FriendDetailScreen(id, friendsViewModel, pokeViewModel, back) { nav.navigate("friends/friend/$id/permissions") }
        }
        composable("friends/friend/{id}/permissions") { entry -> PermissionEditScreen(UUID.fromString(entry.arguments?.getString("id")), friendsViewModel, back) }
        composable("friends/activity") { ActivityScreen(pokeViewModel, back) { nav.navigate("friends/poke/$it") } }
        composable("friends/poke/{id}") { entry -> PokeDetailScreen(UUID.fromString(entry.arguments?.getString("id")), pokeViewModel, back) }
        composable("friends/profile") {
            ProfileScreen(authViewModel, friendsViewModel) {
                nav.popBackStack()
            }
        }

        composable(Tab.SETTINGS.route) { SettingsScreen(deviceViewModel) { nav.navigate("settings/${it.name.lowercase()}") } }
        SettingsDestination.entries.forEach { destination ->
            composable("settings/${destination.name.lowercase()}") {
                when (destination) {
                    SettingsDestination.POKE_TRIGGER -> PokeTriggerSettingsScreen(back)
                    SettingsDestination.QUICK_POKE -> QuickPokeSettingsScreen(back)
                    SettingsDestination.FIRING -> FiringModesScreen(back)
                    SettingsDestination.POKE_FEEDBACK -> PokeFeedbackScreen(back)
                    SettingsDestination.NOTIFICATIONS -> NotificationTestScreen(back)
                    SettingsDestination.PAVLOK -> PavlokAccountScreen(back)
                    SettingsDestination.SERVER -> ServerSettingsScreen(back)
                    SettingsDestination.API_TOKENS -> ApiTokensScreen(back)
                    SettingsDestination.ABOUT -> AboutScreen(back)
                }
            }
        }
    }
    // Signing out from the profile lands back on the sign-in form.
    LaunchedEffect(user) {
        if (user == null && nav.currentBackStackEntry?.destination?.route?.startsWith("friends/") == true) {
            nav.popBackStack(Tab.FRIENDS.route, inclusive = false)
        }
    }
}
