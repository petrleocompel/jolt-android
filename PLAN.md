# Jolt for Android — plan

This is the plan for an Android app that does what the iOS app
([jolt-ios](https://github.com/petrleocompel/jolt-ios)) does. It covers parity
with iOS, the architecture, the milestones and the platform risks. Items still
open are marked **OPEN** in the parity matrix and collected again in
[Open items](#open-items).

Sources of truth:

- iOS behaviour: the `jolt-ios` repository (`main`), read-only from here.
- Server API: jolt-server `openapi/jolt-v1.yaml`.
- Push relay: jolt-relay `spec/protocol-v1.md`, including its section 9
  clarifications C1–C20 (commit `eb16aae`), and `spec/vectors/`. These are
  normative. Copies of the vectors live in `app/src/test/resources/vectors/`.

## 1. Parity matrix

Legend: **Done** means implemented on Android to the same behaviour.
**Adapted** means implemented with a deliberate platform difference, explained
in the notes. **OPEN** means not at parity yet.

### App shell

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| Composition root | `App/AppDependencies.swift` | `app/AppContainer.kt` | Done. Manual DI, see §2.2. |
| Tabs: Remote, Alarms, Friends, Settings | `App/RootView.swift` | `app/JoltRoot.kt` (`NavigationBar` + `NavHost`) | Done. |
| Splash | `App/SplashView.swift` | `core-splashscreen` theme + launch icon | Adapted. Android 12+ owns the splash; no 1.4 s hold. |
| Snapshot / fake-device flags (`-snapshotMode`, `-fakeDevice`, `-noDevice`, `-fakeDeviceLink`, `-ringAlarm`) | `App/AppEnvironment.swift` | `app/AppEnvironment.kt` | Adapted. An environment object instead of launch arguments; UI tests pick it through a test `Application`. |
| Notification routing | `App/AppNotificationDelegate.swift`, `App/JoltAppDelegate.swift` | `push/JoltMessagingService.kt`, `MainActivity` intent handling | Adapted. See Push below. |

### Onboarding and pairing

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| First-run pairing offer, "not now" | `Features/Onboarding/OnboardingView.swift`, `Domain/Models/DevicePairing.swift` | `features/onboarding/OnboardingScreen.kt` | Done. |
| Device scanner list with RSSI | `Features/Onboarding/DeviceScannerList.swift`, `PairDeviceSheet.swift` | `features/onboarding/DeviceScanner.kt` | Done. |
| Runtime permissions | Info.plist usage strings | `features/shared/Permissions.kt` | Adapted. Android asks for `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (12+), location (8–11) and `POST_NOTIFICATIONS` (13+) at runtime. |

### Wearable control (BLE)

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| GATT transport: scan, connect, discover, read, write, notify, timeouts | `BLE/Transport/*`, `BLE/Support/WaiterRegistry.swift` | `ble/transport/GattConnection.kt`, `BleCentral.kt` | Adapted. Android GATT allows one outstanding operation, so every call goes through a serial operation queue. |
| Pavlok 2/3 legacy GATT map | `BLE/Legacy/LegacyGATT.swift` | `ble/legacy/LegacyGatt.kt` | Done. Same UUIDs. |
| Fire / store stimulus (`0x80` / `0x40` flag, 2- and 5-byte layouts) | `BLE/Legacy/LegacyDeviceController.swift` | `ble/legacy/LegacyDeviceController.kt` | Done. Payload builder unit-tested. |
| Device alarms on the wearable | `LegacyDeviceController.syncAlarm/deleteAlarm` | same | Done, with the same unverified payload iOS has. |
| Shock Clock Max | `BLE/SCMax/*` | `ble/scmax/*` | **OPEN** on both platforms: connects and reads DIS/battery; fire/alarms throw "not implemented" because the opcodes are not recovered. ESF/LEB128 codec ported with tests. |
| Device Information + battery service | `BLE/DIS/*` | `ble/DeviceInformationReader.kt` | Done. |
| Composite repository: auto-reconnect, power state, unexpected drops, battery notify/poll, device-info retry | `Data/CompositeDeviceRepository*.swift`, `BLE/Support/ConnectionWaiter.swift` | `data/device/CompositeDeviceRepository.kt`, `ble/ConnectionWaiter.kt` | Adapted. Bluetooth state from `BluetoothAdapter.ACTION_STATE_CHANGED`; no CoreBluetooth state restoration — a foreground service keeps the link instead (see §4). |
| Background link | `UIBackgroundModes: bluetooth-central` | `ble/WearableConnectionService.kt` (foreground service, type `connectedDevice`) | Adapted. Visible ongoing notification while a device is paired and "Stay connected in background" is on. |
| Paired device store, legacy UUID overrides | `Data/PairedDeviceStore.swift`, `Data/LegacyProtocolStore.swift` | `data/store/Settings.kt` | Done. Peripheral identity is the MAC address on Android. |
| Fake device | `Data/FakeDeviceRepository.swift` | `data/device/FakeDeviceRepository.kt` | Done. |
| Button configuration (write, report query `01 01`, frame parse) | `Data/CompositeDeviceRepository+ButtonConfig.swift`, `Domain/Models/ButtonConfig*.swift`, `ButtonActionRecord.swift` | `domain/model/ButtonConfig.kt`, repository | Done. Payload lengths and report parsing unit-tested. |
| Diagnostics: GATT dump, protocol lab raw writes, BLE log, event capture | `Features/DeviceControl/DeviceDiagnosticsView.swift`, `ProtocolLabView.swift`, `BluetoothLogView.swift`, `DeviceEventCaptureView.swift`, `BLE/Support/BLELog.swift` | `features/device/DiagnosticsScreens.kt` | Done. |
| Per-kind stimulus defaults, sync to device | `Domain/Models/StimulusSettings.swift`, `Data/StimulusSettingsStore.swift` | `domain/model/Stimulus.kt`, `data/store/Settings.kt` | Done. |

### Remote tab

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| Dashboard: pinned device card, zap/vibe/beep, quick poke, next alarm, recent activity | `Features/DeviceControl/RemoteDashboard*.swift`, `StimulusCard.swift`, `RemoteControlView.swift` | `features/remote/RemoteDashboardScreen.kt` | Done. |
| Customize: reorder, hide, gallery | `RemoteCustomizeView.swift`, `RemoteDashboardLayoutService.swift`, `Domain/Models/RemoteWidget.swift` | `features/remote/RemoteSheets.kt`, `domain/model/Preferences.kt` | Done. |
| Device detail | `RemoteDeviceDetailView.swift` | `features/remote/DeviceDetailScreen.kt` | Done. |
| Firing gestures: tap / hold / confirm per stimulus | `Features/Shared/FireControl.swift`, `Domain/Models/FiringInteraction*.swift`, `Features/Settings/FiringModeService.swift` | `features/shared/FireControl.kt` | Done. |
| Theme and brand colours | `Features/Shared/RemoteTheme.swift`, `Assets.xcassets` | `ui/theme/*`, `features/shared/Components.kt` | Adapted to Material 3 with the same brand colours. |

### Accounts and server

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| Sign up / log in / log out | `Features/Friends/AuthView.swift`, `AuthViewModel.swift`, `Data/HTTPSocialBackend.swift` | `features/friends/FriendsScreens.kt`, `data/social/HttpSocialBackend.kt` | Done. |
| Custom server URL, probe, sign-out on change | `Features/Settings/ServerSettingsView.swift`, `Domain/Models/ServerConfiguration.swift`, `Data/ServerSettingsStore.swift` | `features/settings/SettingsScreens.kt` | Adapted. Android switches the backend in-process instead of asking for a relaunch. |
| Default server from build config | `JOLT_DEFAULT_SERVER_URL` | Gradle property `JOLT_DEFAULT_SERVER_URL` → `BuildConfig` | Done. Placeholder `https://jolt.example.com/api/v1`. |
| Bearer token per server, encrypted | `Data/AuthTokenStore.swift` (keychain) | `data/secure/SecureStore.kt` (Android Keystore AES-GCM) | Done. |
| HTTP client: error mapping, lowercase UUIDs, flexible dates, 401 → log out | `Data/JoltAPIClient.swift` | `data/api/JoltApiClient.kt` (Ktor + OkHttp) | Done. |
| Mock backend for UI tests / demo | `Data/MockSocialBackend*.swift` | `data/social/MockSocialBackend.kt` | Done. |

### Friends and pokes

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| Friends list, incoming/outgoing requests, accept/reject/cancel, remove | `Features/Friends/FriendsListView.swift`, `FriendsRootView.swift`, `FriendsViewModel.swift` | `features/friends/FriendsScreens.kt`, `FriendsViewModels.kt` | Done. |
| Add friend by handle or invite code, own QR, scan QR | `AddFriendView.swift`, `Features/Shared/QRCodeView.swift`, `QRScannerView.swift` | `features/friends/FriendsScreens.kt` (`AddFriendSheet`), `features/shared/Qr.kt` | Done. ZXing for the code, CameraX + ML Kit for scanning. |
| Per-stimulus permissions: allow, cap, cooldown, presets | `PermissionEditView.swift`, `Domain/Models/StimulusPermission.swift` | `features/friends/PermissionEditScreen.kt` | Done, optimistic updates and rollback as on iOS. |
| Automation consent (Default / Allow / Block, absent vs null on the wire) | `PermissionEditView.swift`, `Data/StimulusPermissionUpdate.swift`, `Domain/Models/ServerPolicies.swift` | `data/api/StimulusPermissionUpdate.kt` | Done. Encoding unit-tested against the three-valued rule. |
| Poke composer, remembered draft per friend, clamping | `PokeComposerCard.swift`, `Domain/Models/FriendPokeDraft.swift`, `Data/FriendPokeDraftStore.swift` | `features/friends/PokeComposer.kt` | Done. |
| Idempotent retry with `pokeId`, not-sent vs unconfirmed | `PokeViewModel.swift`, `HTTPSocialBackend+Pokes.swift`, `Domain/Repositories/PokeRepository.swift` | `features/friends/FriendsViewModels.kt` (`PokeViewModel`) | Done. |
| Activity log, poke detail, automation marker | `PokeActivityView.swift`, `PokeDetailView.swift`, `Domain/Models/PokeEvent.swift` | `features/friends/FriendsScreens.kt` | Done. |
| Profile (handle, invite code, sign out) | `ProfileView.swift` | `features/friends/FriendsScreens.kt` | Done. |
| Incoming poke: wrong addressee drop, DND (muted), fire once, ack | `HTTPSocialBackend+Pokes.swift`, `Data/LocalStimulusFirer.swift`, `Domain/Models/PokeSettings.swift` | `data/social/LocalStimulusFirer.kt` | Done. Same settled/in-flight idempotency. |
| Quick poke | `Features/Friends/QuickPokeService.swift`, `Features/Settings/QuickPokeSettingsView.swift`, `Domain/Models/QuickPokeSettings.swift` | `features/shared/QuickPokeService.kt`, `features/settings/SettingsScreens.kt` | Done. |
| Poke trigger from a wearable button (find-my-phone event, learned signature) | `Features/Friends/PokeTriggerService.swift`, `Features/Settings/PokeTriggerSettingsView.swift`, `Domain/Models/PokeTrigger.swift`, `DeviceEvent.swift` | `features/shared/PokeTriggerService.kt`, `features/settings/SettingsScreens.kt` | Done. |
| Poke success feedback (banner, flash, haptic, label) | `Features/Settings/PokeFeedback*.swift`, `Domain/Models/PokeFeedbackSettings.swift` | `features/shared/PokeFeedbackService.kt`, `features/settings/SettingsScreens.kt` | Done. |

### Push

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| Push transport | APNs direct (`JoltAppDelegate`) and relay (in progress on `feature/push-relay`) | FCM through jolt-relay only | Adapted. jolt-server has no FCM sender; Android always uses the relay. |
| `GET push/config` | relay branch | `HttpSocialBackend.pushConfig`, `push/PushRegistrar.kt` | Done. `apns` or `none` → the app says the server does not support push for Android; an unknown transport is an error that keeps the current registration (C18). |
| Relay host allow-list | relay branch | `BuildConfig.RELAY_ALLOWED_HOSTS` | Done. Empty by default. The relay URL is normalised to one trailing slash and may carry a path prefix (C1). |
| Device registration (`/v1/devices`, `/v1/devices/unregister`) | relay branch (App Attest) | `push/RelayClient.kt` | Adapted. No attestation and no challenge on Android, so installs from GitHub or other stores register like Play installs (C22); iOS keeps App Attest. Unregistering is `POST /v1/devices/unregister` (C5). |
| `payloadKey` per (device, server), keystore-encrypted | relay branch (keychain) | `push/PayloadKeyStore.kt` | Done. A fresh key per relay token; the previous one opens pushes for 24 hours (C8). |
| Server registration (`POST/DELETE devices/push-token`, relay shape) | `HTTPSocialBackend.registerPushToken` | `push/PushRegistrar.kt` | Done. A replaced registration is removed on the server and the relay (C7); `410 relay_token_revoked` triggers a fresh relay registration (C6). |
| Envelope v1 decrypt (AES-256-GCM, AAD, type/serverId checks) | relay branch (NSE) | `push/EnvelopeCrypto.kt` | Done, tested against `envelope-v1.json` and `server-id.json`. |
| Data message → notification + fire + ack | `AppNotificationDelegate`, `JoltAppDelegate` | `push/JoltMessagingService.kt`, `push/IncomingPushHandler.kt` | Adapted. Android draws its own notification and fires from the data message; no alert/silent pair. Acked with `path: background` (C20); a push without `enc` is dropped (C9). |
| Fallback text when decryption fails | APNs `loc-key` | `R.string.push_fallback_*` | Done: a localised generic notification, nothing fires (C10). |
| Push diagnostics: devices, test push, polling acks | `Features/Settings/NotificationTest*.swift`, `Domain/Models/PushDiagnostics.swift` | `features/settings/NotificationTestScreen.kt` | Done. Also shows the push config and relay registration state. This phone is matched in `GET /devices` by the relay token's last 8 characters (C12), and `pushTransport` decides whether the server can deliver (C19). |

### Alarms

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| Alarm model, next occurrence, QR code match | `Domain/Models/Alarm.swift`, `DismissChallenge.swift` | `domain/model/Alarm.kt` | Done. |
| Alarm list / edit / toggle / delete | `Features/Alarms/AlarmsListView.swift`, `AlarmEditView.swift`, `AlarmsViewModel.swift` | `features/alarms/AlarmsScreens.kt`, `AlarmController.kt` | Done. Swipe-to-delete is a delete button. |
| Storage | SwiftData (`Data/AlarmEntity.swift`, `SwiftDataAlarmRepository.swift`) | JSON list in `SharedPreferences` (`data/store/AlarmStore.kt`) | Adapted. A handful of rows; no Room/KSP needed. |
| Phone alarms | `PhoneAlarmScheduler.swift` (local notifications) | `features/alarms/AlarmScheduler.kt` (`AlarmManager.setAlarmClock`), `AlarmReceivers.kt` | Adapted and better than iOS: rings on the alarm channel with a full-screen intent, re-armed after reboot, update, time and time-zone change. Two deliberate differences: the alarm's stimulus also fires when a wearable is connected, and a one-off alarm switches itself off after ringing, as clock apps do. |
| Snooze 9 min | `PhoneAlarmScheduler.snooze` | same | Done. |
| Ringing screen + challenges: math, jumping jacks, QR | `ActiveAlarmView.swift`, `AlarmScreenStyle.swift`, `Challenges/*` | `features/alarms/ActiveAlarmActivity.kt`, `challenges/*` | Done. Jumping jacks use the phone's accelerometer, as on iOS. |

### Pavlok cloud account

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| Login, friends, received permissions, send poke, device journal | `Data/PavlokAPIClient.swift`, `Data/PavlokCredentialStore.swift`, `Features/Pavlok/*`, `Domain/Models/PavlokAccount.swift` | `data/pavlok/*`, `features/pavlok/PavlokAccountScreen.kt` | Done. |

### Settings and the rest

| Feature | iOS files | Android | Status / notes |
|---|---|---|---|
| Settings hub, DND for incoming pokes, device section | `Features/Settings/SettingsView.swift` | `features/settings/SettingsScreens.kt` | Done. Adds "Stay connected in background" for the foreground service. |
| Firing modes | `FiringModesSettingsView.swift` | `features/settings/SettingsScreens.kt` | Done. |
| About | `AboutView.swift` | `features/settings/SettingsScreens.kt` | Done. |
| API tokens (list, mint, revoke) | none — iOS leaves this to the web dashboard | `features/settings/ApiTokensScreen.kt` | Beyond parity. Asked for in the Android brief; uses the existing `/me/tokens` API. |
| Localization | none — iOS is English-only, no string catalogs | `res/values/strings.xml` for system-facing text (notifications, channels, permissions, push fallback) | **OPEN**: UI copy is English in code like iOS; extracting it and a Czech translation are follow-ups. |
| App Store screenshots, design-reference tests, website | `UITests/*`, `fastlane/*`, `site/*` | — | **OPEN**: out of scope for the first Android build. |

## 2. Architecture

### 2.1 Stack

- Kotlin 2.4, Jetpack Compose, Material 3, coroutines and Flow.
- kotlinx.serialization for every JSON shape.
- Ktor client on the OkHttp engine. Chosen over Retrofit because the iOS
  `JoltAPIClient` is a thin `send(method, path, body)` and Ktor keeps that
  shape, and because `ktor-client-mock` makes the HTTP layer testable without
  a server.
- `SharedPreferences` for settings, JSON-encoded where iOS stores a
  `Codable` blob in `UserDefaults`. Synchronous like `UserDefaults`, which the
  push handler and the stimulus firer rely on; each value is exposed as a
  `StateFlow`.
- Android Keystore (AES-256-GCM, non-exportable key) wrapping secrets in
  `SharedPreferences`: session tokens, the Pavlok token and relay
  `payloadKey`s. `androidx.security:security-crypto` is deprecated, so the
  wrapper is ours and small.
- Firebase Cloud Messaging for push. No Play Integrity: the app is also
  distributed outside Google Play (protocol C22).
- CameraX + ML Kit barcode scanning for QR, ZXing core for QR generation.

### 2.2 Dependency injection

Manual, through one `AppContainer` built in `JoltApplication`. The iOS app
does the same (`AppDependencies`), the graph is small, and it avoids KSP and
Hilt's annotation processing in a single-module app. View models get their
dependencies through a `viewModelFactory`.

### 2.3 Layers

The packages mirror the iOS folders:

```
cz.peelco.jolt
├── app/        Application, AppContainer, MainActivity, navigation   (iOS App/)
├── domain/     models and repository interfaces, no Android imports   (iOS Domain/)
├── data/       HTTP backends, stores, device repository, mock/fake    (iOS Data/)
├── ble/        GATT transport, legacy Pavlok, Shock Clock Max, ESF   (iOS BLE/)
├── push/       relay client, envelope crypto, FCM service, registrar (new)
├── features/   Compose screens and view models per feature           (iOS Features/)
└── ui/theme/   colours, typography                                   (iOS RemoteTheme)
```

`domain` has no Android dependencies, so it is unit-tested on the plain JVM.
Async streams (`AsyncStream` + `StreamHub` on iOS) become `StateFlow` and
`SharedFlow`, which are multi-consumer by construction.

### 2.4 Push flow on Android

1. FCM hands the app a registration token (`onNewToken` or `getToken()`).
2. Whenever a session exists and a token is known, `PushRegistrar` asks the
   server `GET push/config`.
3. `transport: relay`:
   1. Check `relay.url` is HTTPS and its host is in `RELAY_ALLOWED_HOSTS`.
   2. Load or create the `payloadKey` for `relay.serverId`.
   3. `POST /v1/devices` with `platform: android`, `provider: fcm` and no
      attestation (C22).
   4. `POST devices/push-token` on the server with the relay shape.
   5. Remember (server, serverId, normalised relay URL, FCM token,
      relayToken, kid). An unchanged registration only re-posts its binding
      to the server. A change in any of them registers again and removes the
      old registration on the server and the relay; the previous key keeps
      opening in-flight pushes for 24 hours.
   6. If the server answers the binding with `410 relay_token_revoked`, drop
      that token and its key and start again at step 3.
   7. If the relay answers `429`, wait at least its `Retry-After`, keep the
      current registration, and revoke any registration the relay refused to
      drop once the wait is over (C21).
4. `transport: apns` or `none`: nothing is registered, and Settings →
   Notifications says the server does not support push for Android.
5. A data message `{type, srv, enc}` arrives in `JoltMessagingService`. The
   envelope is decrypted with the key for `srv`/`kid`, the inner type and
   `serverId` are checked, and the payload goes to the same handler as on iOS:
   notification, wrong-addressee check, DND, fire once, ack with
   `path: background`. A failed decryption shows the fallback text and
   nothing fires; a push with no envelope at all is dropped.
6. Sign-out deletes the registration on the server (`DELETE devices/push-token`)
   and the relay (`POST /v1/devices/unregister`), and forgets the keys.

## 3. Milestones

Each milestone is one or more commits; `./gradlew lint test assembleDebug`
passes at each.

1. **Plan** — this document.
2. **Scaffold** — Gradle Kotlin DSL, version catalog, app shell, theme,
   conditional Google Services plugin, README/AGENTS/CONTRIBUTING/LICENSE,
   GitHub Actions and GitLab CI.
3. **Domain** — models and repository interfaces with their logic and tests.
4. **Data** — Jolt API client, HTTP and mock social backends, secure storage,
   settings stores, Pavlok API client, tests.
5. **BLE** — GATT queue, legacy controller, Shock Clock Max stub, ESF codec,
   composite and fake repositories, foreground service, tests.
6. **Push** — envelope crypto with vectors, relay client, push config,
   registrar, FCM service, incoming handling, tests.
7. **Features** — Remote, onboarding, Friends, Settings (server,
   notifications, quick poke, trigger, firing modes, feedback, API tokens),
   Pavlok account, diagnostics.
8. **Alarms** — scheduler, ringing activity, challenges, boot receiver.
9. **UI smoke tests and polish** — Robolectric Compose tests against the mock
   backend and fake device.

## 4. Risks and platform differences

| Risk | Impact | Mitigation |
|---|---|---|
| **BLE in the background.** Android kills or freezes background processes; GATT callbacks stop when the process is cached. | Incoming pokes can't fire if the link dropped while the app was in the background. | `WearableConnectionService`, a foreground service of type `connectedDevice`, holds the GATT connection while a device is paired. `autoConnect = true` for reconnects so the controller re-links when the wearable returns. |
| **Doze and App Standby.** Network and alarms are deferred in Doze. | A poke data message can arrive late; acks may not go out. | The relay sends FCM `priority: high`, which wakes the app from Doze and grants a short network window. The handler finishes within that window (bounded wait for the link, then ack). Overuse of high priority (no user-visible notification) gets messages deprioritised, so every poke and test shows a notification. |
| **Starting the foreground service from the background** (Android 12+ restrictions). | `ForegroundServiceStartNotAllowedException`. | Start it from the visible activity, from `BOOT_COMPLETED` (allowed for `connectedDevice`) and from a high-priority FCM message (exempt). Failures are caught and logged; firing then falls back to a direct reconnect attempt. |
| **`FOREGROUND_SERVICE_CONNECTED_DEVICE` (Android 14+)** needs a Bluetooth permission at start. | Crash on start without `BLUETOOTH_CONNECT`. | The service is only started after the permission is granted. |
| **Exact alarms** (Android 12+: `SCHEDULE_EXACT_ALARM` user-revocable; 13+: `USE_EXACT_ALARM` for alarm-clock apps). | Phone alarms ring late or not at all. | `setAlarmClock()` with `USE_EXACT_ALARM`; on 12 `SCHEDULE_EXACT_ALARM` and a settings deep link when `canScheduleExactAlarms()` is false. Device alarms on the wearable are unaffected. |
| **Full-screen intents (Android 14+)** restricted to alarm and calling apps. | Ringing screen does not open over the lock screen. | Declared as an alarm app; when `canUseFullScreenIntent()` is false the app explains and links to the setting; the notification still rings. |
| **Notification permission (Android 13+)**. | No poke, test or alarm notifications. | Asked in onboarding and from Settings → Notifications, with state shown there. |
| **No attestation on Android** (C22). | The relay can't tell a genuine build from a script using the same API. | Rate limits on the relay (C21, section 5) are the abuse control; the app backs off on `429`. |
| **FCM credentials** (`google-services.json`) are not in the tree. | Build or runtime failure without them. | The Google Services plugin is applied only when the file exists; without it Firebase can be initialised from `FIREBASE_*` Gradle properties; with neither, push is reported as unavailable and everything else works. |
| **Peripheral identity.** iOS uses a per-phone UUID; Android uses the MAC. | Different persisted shape. | `PairedDeviceRecord.address`; no migration needed for a new app. |
| **Shock Clock Max opcodes unknown** (also on iOS). | SCMax cannot fire. | Same stub and error message as iOS. |
| **Vendor background killers** (some OEMs kill foreground services). | Link drops. | Documented; the app reconnects on next foreground. **OPEN**: an in-app battery-optimisation hint. |

## Open items

- Shock Clock Max wire protocol (shared with iOS).
- Device alarm payload on Pavlok 2/3 is unverified (shared with iOS).
- UI copy extraction to `strings.xml` and a Czech translation.
- Screenshot automation, design-reference comparison and website.
- Battery-optimisation guidance for aggressive OEMs.
- Store listing screenshots (`fastlane/metadata/android/en-US/images/phoneScreenshots`).
- A `foss` build flavour without Firebase, then IzzyOnDroid and F-Droid with
  reproducible builds; push for it means UnifiedPush and a relay protocol
  change (see `docs/distribution.md`).
- Instrumented tests on a device: the UI is covered by Robolectric smoke
  tests only; Bluetooth against a real Pavlok and FCM delivery through a real
  relay are untested.
