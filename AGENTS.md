# Agent notes

Read `README.md`, `CONTRIBUTING.md` and `PLAN.md` first. The server API is
jolt-server's `openapi/jolt-v1.yaml`; push relay behaviour is jolt-relay's
`spec/protocol-v1.md`, whose test vectors are copied under
`app/src/test/resources/vectors/` and must keep passing.

`./gradlew lint test assembleDebug` must pass before every commit.

## Remotes and mobile builds

- GitHub is the public repository; pull requests go there.
- If you are petrleocompel's agent: `origin` in this clone is his private GitLab, which builds and ships the iOS and Android apps, and `github` is the public repository. Push every `main` commit to both (`git push origin main && git push github main`), and push to `github` only `main` and release tags.
- Everyone else: mobile builds run on a private pipeline; GitHub Actions cover checks and releases.

## Project

- Gradle Kotlin DSL with the version catalog in `gradle/libs.versions.toml`. Pin exact versions.
- The Gradle daemon runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`), whatever JDK launches the wrapper.
- Keep the layers apart: `domain` has no Android imports, `features` never touches `BluetoothGatt`, and only `push` knows about Firebase.
- Never commit credentials, server or relay hosts, `google-services.json` or signing material. They come from Gradle properties and CI variables (see `README.md`).
