# Contributing to Jolt for Android

Thanks for helping. Bug reports, protocol findings and pull requests are all
welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on
  the approach.
- Server-side changes (friends, pokes, push delivery) belong in
  [petrleocompel/jolt-server](https://github.com/petrleocompel/jolt-server).
  Push relay changes belong in jolt-relay, and its `spec/protocol-v1.md` is a
  contract shared with the iOS app: change it there first.
- Features should match the iOS app ([jolt-ios](https://github.com/petrleocompel/jolt-ios)).
  `PLAN.md` tracks parity; update it when you close or open a gap.

## Development setup

See [Build from source](README.md#build-from-source). In short:

```bash
./gradlew lint test assembleDebug
```

## Pull requests

- Keep each PR to one change, with a commit message that says why.
- `./gradlew lint test assembleDebug` must pass. GitHub Actions runs the same
  checks on every PR.
- Add or update tests for behaviour changes. UI tests run against the mock
  backend and the fake device, so they need neither a server nor a wearable.
- Bluetooth protocol changes: say which device and firmware you verified
  against, and update the iOS repository's `docs/RE-FINDINGS.md` too.
- Don't commit Pavlok's assets, strings or decompiled code; write fresh
  implementations from documented behaviour.
- Never commit `google-services.json`, keystores, server or relay hosts.

## License

By contributing, you agree that your contributions are licensed under the
[Mozilla Public License 2.0](LICENSE), the same license as the project.
