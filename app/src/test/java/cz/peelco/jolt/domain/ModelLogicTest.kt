package cz.peelco.jolt.domain

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.AlarmLocation
import cz.peelco.jolt.domain.model.DeviceFamily
import cz.peelco.jolt.domain.model.FiringInteractionMode
import cz.peelco.jolt.domain.model.FiringInteractionSettings
import cz.peelco.jolt.domain.model.FriendPermissionSet
import cz.peelco.jolt.domain.model.FriendPokeDraft
import cz.peelco.jolt.domain.model.PokeFeedbackProfile
import cz.peelco.jolt.domain.model.PokeFeedbackSettings
import cz.peelco.jolt.domain.model.QuickPokeSettings
import cz.peelco.jolt.domain.model.RemoteDashboardLayout
import cz.peelco.jolt.domain.model.RemoteWidgetKind
import cz.peelco.jolt.domain.model.ServerConfiguration
import cz.peelco.jolt.domain.model.StimulusConfig
import cz.peelco.jolt.domain.model.StimulusKind
import cz.peelco.jolt.domain.model.StimulusPermission
import cz.peelco.jolt.domain.model.StimulusSettings
import cz.peelco.jolt.domain.model.Weekday
import cz.peelco.jolt.domain.model.parseHexBytes
import cz.peelco.jolt.domain.model.toHexString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class ServerConfigurationTest {
    private fun parse(text: String) = ServerConfiguration.parse(text)

    @Test
    fun acceptsHttpsAndDropsATrailingSlash() {
        assertThat(parse(" https://jolt.example.com/api/v1/ "))
            .isEqualTo(ServerConfiguration.ParseResult.Valid(ServerConfiguration("https://jolt.example.com/api/v1")))
    }

    @Test
    fun rejectsEmptyMalformedAndCleartext() {
        assertThat(parse("  ")).isEqualTo(ServerConfiguration.ParseResult.Invalid(ServerConfiguration.ValidationError.EMPTY))
        assertThat(parse("not a url")).isEqualTo(ServerConfiguration.ParseResult.Invalid(ServerConfiguration.ValidationError.MALFORMED))
        assertThat(parse("http://jolt.example.com/api/v1"))
            .isEqualTo(ServerConfiguration.ParseResult.Invalid(ServerConfiguration.ValidationError.INSECURE_SCHEME))
    }

    @Test
    fun anUnusableBuildDefaultFallsBackToThePlaceholder() {
        assertThat(ServerConfiguration.bundledDefault("")).isEqualTo(ServerConfiguration.PLACEHOLDER)
        assertThat(ServerConfiguration.bundledDefault("https://a.example/api/v1")).isEqualTo(ServerConfiguration("https://a.example/api/v1"))
        assertThat(ServerConfiguration("https://a.example/api/v1").host).isEqualTo("a.example")
    }
}

class DeviceFamilyTest {
    @Test
    fun classifiesByAdvertisedName() {
        assertThat(DeviceFamily.matching("Pavlok-3 A1B2")).isEqualTo(DeviceFamily.PAVLOK3)
        assertThat(DeviceFamily.matching("pavlok-2")).isEqualTo(DeviceFamily.PAVLOK2)
        assertThat(DeviceFamily.matching("Pavlok-1")).isEqualTo(DeviceFamily.PAVLOK2)
        assertThat(DeviceFamily.matching("ShockClockMax")).isEqualTo(DeviceFamily.SHOCK_CLOCK_MAX)
    }

    @Test
    fun aBarePavlokNameAndRingsAreNotMatched() {
        assertThat(DeviceFamily.matching("Pavlok")).isNull()
        assertThat(DeviceFamily.matching("Pavlok-Smart-Ring")).isNull()
    }

    @Test
    fun candidatesNarrowTheMatch() {
        assertThat(DeviceFamily.matching("Pavlok-3", setOf(DeviceFamily.PAVLOK2))).isNull()
    }
}

class StimulusTest {
    @Test
    fun configClampsIntensityAndRepetitions() {
        assertThat(StimulusConfig.of(StimulusKind.ZAP, 150, 0)).isEqualTo(StimulusConfig(StimulusKind.ZAP, 100, 1))
        assertThat(StimulusConfig.of(StimulusKind.ZAP, -5, 3)).isEqualTo(StimulusConfig(StimulusKind.ZAP, 0, 3))
    }

    @Test
    fun settingsFallBackToSafeDefaultsAndRoundTrip() {
        val settings = StimulusSettings.DEFAULT.with(StimulusConfig(StimulusKind.VIBE, 45, 2))
        assertThat(settings[StimulusKind.ZAP].intensity).isEqualTo(20)
        assertThat(settings[StimulusKind.VIBE]).isEqualTo(StimulusConfig(StimulusKind.VIBE, 45, 2))
        val json = Json.encodeToString(StimulusSettings.serializer(), settings)
        assertThat(json).contains("\"vibe\"")
        assertThat(Json.decodeFromString(StimulusSettings.serializer(), json)).isEqualTo(settings)
    }

    @Test
    fun hexParsingAcceptsTheLabFormats() {
        assertThat(parseHexBytes("01 14")!!.toHexString()).isEqualTo("01 14")
        assertThat(parseHexBytes("0x0114")!!.toHexString()).isEqualTo("01 14")
        assertThat(parseHexBytes("01,14")!!.toHexString()).isEqualTo("01 14")
        assertThat(parseHexBytes("1")).isNull()
        assertThat(parseHexBytes("zz")).isNull()
        assertThat(parseHexBytes("")).isNull()
    }
}

class FriendPokeDraftTest {
    private val permissions =
        FriendPermissionSet(
            zap = StimulusPermission.allowed(maxIntensity = 20),
            vibe = StimulusPermission.DISABLED,
            beep = StimulusPermission.allowed(maxIntensity = 50),
        )

    @Test
    fun keepsTheSavedKindWhenStillAllowedAndClampsToItsCap() {
        assertThat(FriendPokeDraft.resolved(FriendPokeDraft(StimulusKind.ZAP, 80), permissions))
            .isEqualTo(FriendPokeDraft(StimulusKind.ZAP, 20))
    }

    @Test
    fun fallsBackToVibeThenTheFirstAllowedKind() {
        val withVibe = permissions.with(StimulusKind.VIBE, StimulusPermission.allowed(maxIntensity = 100))
        assertThat(FriendPokeDraft.resolved(null, withVibe)).isEqualTo(FriendPokeDraft(StimulusKind.VIBE, 30))
        assertThat(FriendPokeDraft.resolved(FriendPokeDraft(StimulusKind.VIBE, 40), permissions))
            .isEqualTo(FriendPokeDraft(StimulusKind.ZAP, 20))
    }

    @Test
    fun nothingAllowedMeansNoDraft() {
        assertThat(FriendPokeDraft.resolved(null, FriendPermissionSet.NONE)).isNull()
    }
}

class SettingsModelsTest {
    @Test
    fun feedbackTogglesSnapBackToAPresetOrBecomeCustom() {
        val standard = PokeFeedbackSettings.DEFAULT
        assertThat(standard.profile).isEqualTo(PokeFeedbackProfile.STANDARD)
        val custom = standard.copy(showBanner = false).reconciled()
        assertThat(custom.profile).isEqualTo(PokeFeedbackProfile.CUSTOM)
        assertThat(custom.copy(swapButtonLabel = true, showBanner = true).reconciled().profile).isEqualTo(PokeFeedbackProfile.RICH)
        assertThat(standard.applyingProfile(PokeFeedbackProfile.OFF).isEnabled).isFalse()
        assertThat(standard.applyingProfile(PokeFeedbackProfile.CUSTOM)).isEqualTo(standard)
    }

    @Test
    fun firingSummaryIsMixedUnlessUniform() {
        assertThat(FiringInteractionSettings.DEFAULT.summaryLabel).isEqualTo("Tap")
        assertThat(FiringInteractionSettings.DEFAULT.with(StimulusKind.ZAP, FiringInteractionMode.HOLD).summaryLabel).isEqualTo("Mixed")
    }

    @Test
    fun quickPokeNeedsAFriendToBeConfigured() {
        assertThat(QuickPokeSettings(isEnabled = true).isConfigured).isFalse()
        assertThat(QuickPokeSettings(isEnabled = true, targetFriendId = UUID.randomUUID()).isConfigured).isTrue()
    }

    @Test
    fun dashboardLayoutMovesHidesShowsAndReconciles() {
        var layout = RemoteDashboardLayout.DEFAULT
        layout = layout.moving(RemoteWidgetKind.ZAP, 1)
        assertThat(layout.visible.take(2)).containsExactly(RemoteWidgetKind.VIBE, RemoteWidgetKind.ZAP).inOrder()
        assertThat(layout.moving(RemoteWidgetKind.VIBE, -1)).isEqualTo(layout)
        layout = layout.hiding(RemoteWidgetKind.BEEP)
        assertThat(layout.hidden).containsExactly(RemoteWidgetKind.BEEP)
        layout = layout.showing(RemoteWidgetKind.BEEP)
        assertThat(layout.visible.last()).isEqualTo(RemoteWidgetKind.BEEP)
        val partial = RemoteDashboardLayout(listOf(RemoteWidgetKind.ZAP), emptyList()).reconciled()
        assertThat(partial.hidden).hasSize(RemoteWidgetKind.entries.size - 1)
    }
}

class AlarmTest {
    private val zone = ZoneId.of("Europe/Prague")

    // A Wednesday.
    private val now = ZonedDateTime.of(2026, 10, 7, 8, 0, 0, 0, zone)

    private fun alarm(
        hour: Int,
        minute: Int,
        days: Set<Weekday> = emptySet(),
    ) = Alarm(UUID.randomUUID(), AlarmLocation.PHONE, hour, minute, repeatDays = days)

    @Test
    fun aOneShotAlarmRingsTodayOrTomorrow() {
        assertThat(alarm(9, 30).nextOccurrence(now)).isEqualTo(now.withHour(9).withMinute(30))
        assertThat(alarm(7, 0).nextOccurrence(now)).isEqualTo(now.withHour(7).plusDays(1))
    }

    @Test
    fun aRepeatingAlarmPicksTheSoonestWeekday() {
        val next = alarm(7, 0, setOf(Weekday.MONDAY, Weekday.WEDNESDAY)).nextOccurrence(now)
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, zone))
        val later = alarm(9, 0, setOf(Weekday.WEDNESDAY)).nextOccurrence(now)
        assertThat(later).isEqualTo(now.withHour(9))
    }

    @Test
    fun aDisabledAlarmNeverRings() {
        assertThat(alarm(9, 0).copy(isEnabled = false).nextOccurrence(now)).isNull()
    }

    @Test
    fun aSavedCodeIsTheOnlyOneAccepted() {
        assertThat(alarm(7, 0).acceptsDismissCode("anything")).isTrue()
        val withCode = alarm(7, 0).copy(dismissQrCode = "bedroom")
        assertThat(withCode.acceptsDismissCode("bedroom")).isTrue()
        assertThat(withCode.acceptsDismissCode("kitchen")).isFalse()
    }

    @Test
    fun alarmsRoundTripThroughJson() {
        val original = alarm(6, 45, setOf(Weekday.SATURDAY)).copy(dismissQrCode = "x")
        val json = Json.encodeToString(Alarm.serializer(), original)
        assertThat(Json.decodeFromString(Alarm.serializer(), json)).isEqualTo(original)
    }
}
