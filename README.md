# Jolt for Android

[![CI](https://github.com/petrleocompel/jolt-android/actions/workflows/ci.yml/badge.svg)](https://github.com/petrleocompel/jolt-android/actions/workflows/ci.yml)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](LICENSE)

An independent Android client for Pavlok wearables: fire stimuli, set alarms
that live on the device, and let friends poke you from anywhere. It is the
Android counterpart of [Jolt for iOS](https://github.com/petrleocompel/jolt-ios)
and talks to the same self-hostable [Jolt Server].

Not affiliated with or endorsed by Pavlok Inc. Works with Pavlok 2 and
Pavlok 3; Shock Clock Max connects but cannot fire a stimulus yet (see
[Known gaps](#known-gaps)).

## Features

- **Zap, vibe and beep** at any intensity and repeat count the device supports,
  with tap, hold or confirm firing per stimulus.
- **Device alarms** stored on the wearable and fired by its own clock, and
  **phone alarms** that ring on the alarm stream over the lock screen.
- **Wake-up guarantee**: solve a math puzzle, do jumping jacks or scan a QR code
  before an alarm stops.
- **Button configuration**, live battery, firmware and connection state, and a
  GATT inspector and protocol lab for diagnostics.
- **Friends and pokes** through a [Jolt Server]: per-friend, per-stimulus
  permissions with intensity caps and cooldowns, Do Not Disturb, opt-in
  automated pokes, quick poke and poking a friend from a button on the Pavlok.
- **Pavlok account** (optional): sign in to poke your existing Pavlok friends.
- Controlling your own device needs no account and no network.

`PLAN.md` lists every iOS feature and where it lives here.

## Install

- **Google Play**: coming soon.
- **GitHub Releases**: a signed APK is attached to every `v*` release. Apps
  such as [Obtainium](https://github.com/ImranR98/Obtainium) can follow them.

Both are signed with the same app-signing key, so either installs over the
other and updates carry on (version codes are the commit count on `main`, the
same in both). The GitHub APK is built without Firebase, so it receives no push
notifications: pokes then reach you while Jolt is open, and everything else
works. Distribution beyond these two is weighed in
[docs/distribution.md](docs/distribution.md).

## Build from source

Requirements: JDK 17 or newer to launch Gradle (the daemon itself runs on JDK
21, which Gradle finds or provisions), and the Android SDK with platform 37.

```bash
git clone https://github.com/petrleocompel/jolt-android.git
cd jolt-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

Bluetooth needs a real phone; the emulator can't reach a wearable.

### Build settings

Everything that differs per build is a Gradle property. Set it with `-P` on the
command line, an `ORG_GRADLE_PROJECT_<name>` environment variable, or your
`~/.gradle/gradle.properties`. Never commit real values.

| Property | Default | Purpose |
|---|---|---|
| `JOLT_DEFAULT_SERVER_URL` | `https://jolt.example.com/api/v1` | Server a fresh install points at. Users can change it in **Settings → Server**. |
| `JOLT_RELAY_ALLOWED_HOSTS` | empty | Comma-separated push relay hosts the app may register with. A server can only send pushes through a relay on this list. |
| `JOLT_FIREBASE_PROJECT_ID`, `JOLT_FIREBASE_APPLICATION_ID`, `JOLT_FIREBASE_API_KEY`, `JOLT_FIREBASE_SENDER_ID` | empty | Firebase options, used when `app/google-services.json` is absent. |

### Push notifications

Android pushes always go through the Jolt push relay: the server encrypts each
poke for this phone, the relay forwards it as an FCM data message, and the app
decrypts it, shows the notification and fires the stimulus. The server never
sees the FCM token and the relay never sees the poke.

That needs Firebase. Either drop the Firebase project's `google-services.json`
into `app/` (it is git-ignored; the Google Services plugin is only applied when
it exists) or set the `JOLT_FIREBASE_*` properties. Without either, the app
builds and runs, and **Settings → Notifications** says push is unavailable.

If the server reports `transport: apns` or `none` from `GET /push/config`, it
cannot reach Android phones, and the app says so.

## Server

Friends, permissions and pokes go through a [Jolt Server], which you can host
yourself (its `docs/SELFHOSTING.md` covers running one with Docker Compose).
Accounts are per instance: switching servers signs you out, and friends and
poke history stay behind on the old one. The session token is encrypted with a
key in the Android Keystore, scoped to the server that issued it.

**Settings → Notifications** sends a test push through that server to this
phone and waits for the phone to confirm it, so "Arrived in 1.2s" means it
genuinely got here. **Settings → API tokens** mints and revokes personal access
tokens for scripts.

## Releasing

[fastlane](https://fastlane.tools) wraps the release steps (`bundle install`
first):

```bash
bundle exec fastlane test                  # unit and UI smoke tests
bundle exec fastlane build_release         # signed AAB for Google Play
bundle exec fastlane build_release_apk     # signed APK
bundle exec fastlane internal              # upload the AAB to the internal track
bundle exec fastlane promote_to_production # internal → production
bundle exec fastlane metadata              # store listing from fastlane/metadata
```

Release builds are signed when `ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` are
set, and take their version from `VERSION_CODE` / `VERSION_NAME`. The store
listing lives in `fastlane/metadata/android/<locale>/`, the layout Google Play,
F-Droid and IzzyOnDroid all read.

The maintainer's private pipeline uploads Play builds; GitHub Actions builds
the release APK when a `v*` tag is pushed.

## Development

```bash
./gradlew lint test assembleDebug
```

Unit tests cover the domain logic, the HTTP layer (against `ktor-client-mock`),
the BLE payloads and codecs, and the push envelope against the relay's test
vectors. UI smoke tests run under Robolectric against the in-memory mock
backend and the fake device, so they need neither a server nor a wearable.

### Project layout

```
app/src/main/java/cz/peelco/jolt/
  app/        Application, composition root, main activity, navigation
  domain/     models and repository interfaces (no Android imports)
  data/       HTTP and mock backends, stores, device repositories, Pavlok API
  ble/        GATT transport, Pavlok 2/3, Shock Clock Max, ESF codec
  push/       relay client, envelope crypto, FCM service, registration
  features/   Compose screens and view models per feature
  ui/theme/   colours and typography
```

## Known gaps

- **Shock Clock Max wire protocol is unimplemented**, as on iOS. It connects
  and reads standard GATT (battery, device info) but cannot fire a stimulus.
- **The Pavlok 2/3 device-alarm payload is unverified**, as on iOS.
- Some phone makers stop foreground services aggressively. If pokes stop firing
  in the background, exempt Jolt from battery optimisation.

## Contributing

Issues and pull requests are welcome on GitHub; see
[CONTRIBUTING.md](CONTRIBUTING.md). Google Play builds are made by the
maintainer's private CI; contributions go through GitHub pull requests. Report
security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[Mozilla Public License 2.0](LICENSE). The license covers the code, not the
Jolt name or logo. Pavlok is a trademark of Pavlok Inc.

[Jolt Server]: https://github.com/petrleocompel/jolt-server
