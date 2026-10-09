# Security policy

## Reporting a vulnerability

Please report security issues privately, not in public issues or pull requests:

- **Preferred:** [GitHub private vulnerability reporting](https://github.com/petrleocompel/jolt-android/security/advisories/new)
  (Security tab → Report a vulnerability).
- **Email:** [petrleocompel@gmail.com](mailto:petrleocompel@gmail.com).

Include what you found, how to reproduce it, and the app version or commit.
You'll get an acknowledgement within a week; fixes ship in the next Google Play
and GitHub release, and the advisory is published once a fixed build is out.

## Scope

This repository is the Android app: local storage of tokens, keys and settings,
the Bluetooth link to the wearable, how the app talks to a Jolt Server, and how
it registers with and decrypts pushes from the Jolt push relay. Issues in the
server belong to [petrleocompel/jolt-server](https://github.com/petrleocompel/jolt-server),
issues in the relay protocol to jolt-relay, and issues in Pavlok's own devices,
firmware or API to Pavlok Inc.

Only the latest release and `main` receive security fixes.
