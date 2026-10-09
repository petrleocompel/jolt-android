# Distributing Jolt for Android

Where the Android app can be published besides Google Play, what each channel
needs, and what Firebase means for it. Researched on 2026-10-09 from the
channels' own documentation; every claim links its source. "Inference" marks
our own reasoning rather than something a source states.

## Decisions already taken

- **One app-signing key for every channel.** The app is created in Play Console
  with our own key uploaded to Play App Signing, and GitHub release APKs are
  signed with the same key. A user can then move between Play and GitHub (and,
  later, IzzyOnDroid or F-Droid reproducible builds) without reinstalling.
  Play would otherwise generate its own key, and the certificates would differ
  for good ([Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)).
- **Version code = commit count on `main`.** The same commit gets the same
  version code on the private pipeline, on GitHub and in a rebuild, so builds
  from different channels upgrade over each other in order. IzzyOnDroid's
  reproducible-build hints rule out timestamp-derived version codes
  ([RB hints](https://izzyondroid.org/docs/reproducibleBuilds/RBDevHints/)).
- **GitHub APKs are built without Firebase.** They receive no push; everything
  else works. Play builds include Firebase when `GOOGLE_SERVICES_JSON` is set.
- **No attestation.** Play Integrity was removed (relay protocol C22), so a
  GitHub or F-Droid install registers with the relay exactly like a Play
  install.

## Channels

| Channel | How it publishes | fastlane | Metadata | Signing | Firebase build accepted? |
|---|---|---|---|---|---|
| Google Play | `fastlane supply` uploads the AAB ([supply](https://docs.fastlane.tools/actions/supply/)) | Yes | `fastlane/metadata/android` | Play App Signing, our key uploaded | Yes |
| GitHub Releases | Release workflow on `v*` tags | — | — | Our key | Yes (we ship it without) |
| Obtainium | Reads GitHub Releases directly; no submission ([README](https://github.com/ImranR98/Obtainium)) | — | Release page | Ours | Yes |
| IzzyOnDroid | Inclusion request on Codeberg; pulls the signed APK from GitHub Releases daily ([inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/), [FAQ](https://izzyondroid.org/faq/)) | Reads metadata only | `fastlane/metadata/android` at the tag | Ours, pinned at inclusion ([repo info](https://apt.izzysoft.de/fdroid/index/info)) | Only as an exception, labelled NonFreeComp/NonFreeNet ([issue #883](https://gitlab.com/IzzyOnDroid/repo/-/issues/883)); unverified for Jolt |
| F-Droid | Merge request to fdroiddata; F-Droid builds from source ([quick start](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/)) | Reads metadata only | `fastlane/metadata/android` at the repo root ([descriptions](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/)) | F-Droid's, or ours through reproducible builds ([RB](https://f-droid.org/en/docs/Reproducible_Builds/)) | **No**: Firebase is banned and the build scanner rejects it ([inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/), [scanner](https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/scanner.py)) |
| Accrescent | Developer console, allowlisted accounts only | No | Console | Ours | Not stated; **closed to new apps** ([new app guide](https://accrescent.app/docs/guide/getting-started/new-app.html), [blog](https://accrescent.app/blog/posts/the-future-of-accrescent/)) |
| Amazon Appstore | `fastlane-plugin-amazon_appstore` ([gem](https://rubygems.org/gems/fastlane-plugin-amazon_appstore)) | Plugin | fastlane layout | Ours | Fire devices have no Play Services; the Android-phone store closed on 2025-08-20 ([Amazon](https://developer.amazon.com/apps-and-games/blogs/2025/02/upcoming-changes-to-amazon-appstore-for-android-devices-and-coins-program)) |
| Huawei AppGallery | `fastlane-plugin-huawei_appgallery_connect` ([README](https://raw.githubusercontent.com/shr3jn/fastlane-plugin-huawei_appgallery_connect/master/README.md)) | Plugin | Its own `fastlane/metadata/huawei` layout | Ours | FCM won't deliver without Google services (inference); review rules unverified |

**fastlane only publishes to Google Play** among these (plus the Amazon and
Huawei plugins). F-Droid and IzzyOnDroid don't take uploads at all; they read
the same `fastlane/metadata/android/<locale>/` tree from the repository, so
one tree serves Play, F-Droid and IzzyOnDroid.

### Metadata rules that differ

From [F-Droid](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/),
[IzzyOnDroid](https://izzyondroid.org/docs/general/Fastlane/) and
[Play](https://support.google.com/googleplay/android-developer/answer/9859152):

- `title.txt` at most 30 characters (Play and IzzyOnDroid; F-Droid allows 50).
- `short_description.txt` at most 80, `full_description.txt` at most 4000.
- `changelogs/<versionCode>.txt` at most 500 bytes, committed **before** the
  tag is built. With the commit-count version code, the changelog commit is
  itself the tagged commit, so its file is named after the count *including*
  that commit. Play falls back to `changelogs/default.txt`; F-Droid and
  IzzyOnDroid don't.
- `en-US` is the fallback locale everywhere.
- IzzyOnDroid shows phone screenshots only (PNG or JPG, at most 2:1), and
  wants to be told when new metadata elements appear.

## Firebase and the F-Droid problem

Firebase Cloud Messaging is a proprietary library. F-Droid rejects any build
that contains it; IzzyOnDroid tolerates it at best as a labelled exception and
recommends UnifiedPush instead
([IzzyOnDroid on push](https://izzyondroid.org/docs/devpractices/PushNotifications/)).
Apps in the same position split into flavours:

- **Home Assistant**: `full` on Play, `minimal` (no Play Services, local push
  over a WebSocket) on GitHub and F-Droid ([flavors](https://companion.home-assistant.io/docs/core/android-flavors/)).
- **ntfy** and **Element X**: F-Droid builds an `fdroid` flavour with the
  Firebase module removed ([ntfy](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/io.heckel.ntfy.yml),
  [Element X](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/io.element.android.x.yml)).
- **Molly**: FCM plus UnifiedPush in one flavour, UnifiedPush only in
  Molly-FOSS ([README](https://github.com/mollyim/mollyim-android)).

For Jolt this means a `gplay` flavour (today's build, with Firebase) and a
`foss` flavour without it. Today the GitHub APK is simply the same build with no
`google-services.json`, which keeps the Firebase library in the APK even though
it is never initialised. That is fine for GitHub and Obtainium, but F-Droid's
scanner would still reject it, so a real `foss` flavour that drops the
dependency is the precondition for F-Droid.

### What UnifiedPush would mean for the relay protocol

Not implemented; this is the impact to plan for.

[UnifiedPush](https://unifiedpush.org/developers/intro/) lets the user choose
a distributor app (ntfy, Sunup, NextPush, …) that hands Jolt a Web Push
**endpoint**: an HTTPS URL that anyone holding it can POST to
([spec](https://unifiedpush.org/developers/spec/android/)).

- **A new provider.** `POST /v1/devices` would gain
  `provider: "unifiedpush"` (or `"webpush"`) with an endpoint URL and the RFC
  8291 `p256dh` and `auth` keys instead of an FCM token, and the platform rule
  C14 (`android` ⇔ `fcm`) would widen.
- **Encryption on top of encryption.** The spec requires RFC 8291
  (`aes128gcm`) on every message, so the Jolt envelope would travel inside a
  Web Push layer. The Jolt envelope already keeps content from the relay, so
  the outer layer adds nothing for privacy but is mandatory.
- **Size.** RFC 8291 leaves at most 3993 bytes of plaintext
  ([RFC 8291 §4](https://www.rfc-editor.org/rfc/rfc8291#section-4)); with the
  3072-byte envelope cap (C15) and the `{type, srv, enc}` wrapper that fits,
  but with less headroom than today.
- **Sending.** The sender POSTs with a `TTL` header and possibly a VAPID
  `Authorization` header; `413` and `400` need handling
  ([RFC 8030](https://www.rfc-editor.org/rfc/rfc8030#section-7.2)), and the
  relay's result mapping (§5) needs Web Push equivalents for `unregistered`.
- **Is the relay needed at all?** No longer technically: the relay exists
  because FCM needs a credential bound to our Firebase project. A Web Push
  endpoint takes a POST from anyone, so jolt-server could send to it directly
  and the relay would stop seeing even metadata. Keeping one path through the
  relay is simpler for the server, at the cost of a hop.
- **New risks for whoever POSTs.** User-supplied URLs mean SSRF: reject
  endpoints resolving to non-global addresses on every send, validate a new
  registration with an echo message, and rate-limit per device and per server
  ([UnifiedPush intro](https://unifiedpush.org/developers/intro/)).

## Reproducible builds

F-Droid can publish **our** signed APK if its own rebuild from the tag matches
it byte for byte (`Binaries:` plus `AllowedAPKSigningKeys`), and skips a
version that doesn't reproduce ([F-Droid RB](https://f-droid.org/en/docs/Reproducible_Builds/)).
IzzyOnDroid runs independent verification builders and ties cross-updates to
them ([IzzyOnDroid RB](https://izzyondroid.org/docs/reproducibleBuilds/)).

Jolt is already partly there: every dependency is pinned in the version
catalog, the version code is deterministic, and the build runs on Temurin 21.
Still to check before applying:

- baseline profiles (`baseline.prof`, which Compose brings in) are a known
  source of differences; disable the ArtProfile tasks or verify them;
- build from a clean tree at the tag and keep
  `META-INF/version-control-info.textproto`;
- F-Droid needs `apksigner` from build-tools 34 to copy signatures
  ([RB hints](https://f-droid.org/en/docs/Reproducible_Builds/));
- `-dontobfuscate` helps verifiers ([IzzyOnDroid hints](https://izzyondroid.org/docs/reproducibleBuilds/RBDevHints/)).

## Anti-features

From [F-Droid's list](https://f-droid.org/en/docs/Anti-Features/) (our
assessment):

- **NonFreeNet**: possibly, for the optional Pavlok account that talks to
  Pavlok's cloud. Declare it up front; the core (Bluetooth and a self-hostable
  server) doesn't depend on it.
- **TetheredNet**: no, because the server and relay are configurable and open
  source.
- **Tracking**: no; Jolt has no analytics or crash reporting.
- **NonFreeComp** (IzzyOnDroid only): only for a build that still contains
  Firebase.

## Recommendation

1. Now: Google Play through fastlane, and the signed APK on GitHub Releases
   with Obtainium as the update path. Both done.
2. Next: split `gplay` and `foss` flavours, with the `foss` APK on GitHub
   Releases. Then request IzzyOnDroid inclusion: it picks up GitHub releases
   daily and is the quickest open store.
3. Then: make the `foss` build reproducible and submit it to F-Droid with
   `gradle: foss`, `Binaries:` and our certificate in
   `AllowedAPKSigningKeys`.
4. Push for the `foss` flavour: UnifiedPush, which needs the relay protocol
   change above, decided together with jolt-server and the iOS app.
5. Skip Accrescent (closed), Amazon (Fire devices only, no Play Services) and
   Huawei unless there is demand; Huawei would need the `foss` flavour and its
   own metadata tree.
