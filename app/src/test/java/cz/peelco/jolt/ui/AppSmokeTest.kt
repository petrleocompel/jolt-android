package cz.peelco.jolt.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.app.JoltApplication
import cz.peelco.jolt.app.MainActivity
import cz.peelco.jolt.data.device.FakeDeviceRepository
import cz.peelco.jolt.domain.model.StimulusKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Launches the whole app against the mock server and the fake wearable. */
@RunWith(AndroidJUnit4::class)
@Config(application = SnapshotApplication::class)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<JoltApplication>().container

    @Test
    fun theRemoteTabShowsTheConnectedDevice() {
        compose.onNodeWithText("CONNECTED").assertIsDisplayed()
        compose.onNodeWithText("Pavlok-3 Demo").assertIsDisplayed()
        compose.onNodeWithText("Customize home screen").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingAStimulusFiresItOnTheWearable() {
        compose.onNodeWithTag("fire_vibe").performScrollTo().performClick()
        compose.waitUntil(3_000) { (container.deviceRepository as FakeDeviceRepository).fired.isNotEmpty() }
        val fired = (container.deviceRepository as FakeDeviceRepository).fired.single()
        assertThat(fired.kind).isEqualTo(StimulusKind.VIBE)
        assertThat(fired.intensity).isEqualTo(60)
        compose.waitUntil(3_000) { compose.onAllNodesWithText("Vibe sent at 60%").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun friendsListsTheSignedInAccountsFriendsAndPokesOne() {
        compose.onNodeWithTag("tab_friends").performClick()
        compose.onNodeWithTag("friend_alice").assertIsDisplayed().performClick()
        compose.onNodeWithTag("pokeButton").performScrollTo().performClick()
        compose.waitUntil(3_000) { container.social.activity.value.any { it.friendHandle == "alice" && it.direction.name == "SENT" } }
    }

    @Test
    fun anAlarmCanBeAdded() {
        compose.onNodeWithTag("tab_alarms").performClick()
        compose.onNodeWithText("No alarms").assertIsDisplayed()
        compose.onNodeWithTag("addAlarmButton").performClick()
        compose.onNodeWithText("Label").performTextInput("Wake up")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(3_000) { container.alarms.store.alarms.value.isNotEmpty() }
        compose.onNode(hasText("Wake up")).assertIsDisplayed()
    }

    @Test
    fun settingsReportsThatThisBuildHasNoPush() {
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithText("Notifications").performScrollTo().performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("Not configured in this build").fetchSemanticsNodes().isNotEmpty() }
    }
}

/** The first-run pairing offer and its way out. */
@RunWith(AndroidJUnit4::class)
@Config(application = NoDeviceApplication::class)
class OnboardingSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aFirstRunOffersPairingAndCanBeSkipped() {
        compose.onNodeWithText("Find your Pavlok").assertIsDisplayed()
        compose.onNodeWithTag("continueWithoutDeviceButton").performClick()
        compose.onNodeWithText("NO DEVICE").assertIsDisplayed()
        compose.onNodeWithText("Pair a device").assertIsDisplayed()
    }
}
