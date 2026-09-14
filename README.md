# Linodea for Android

Companion app for [Linodea](https://github.com/SaputraTanuwijaya/linodea), a local-first
desktop reminder app.

**Status: scaffold only.** No functionality yet — this repository currently contains an
empty Compose project that builds and installs.

## What it will do

Receive a reminder's schedule from the Linodea desktop app, and then fire it from the
phone itself — with the PC switched off and the phone offline.

This is deliberately **not** cloud sync:

- **Receive-only.** The phone never creates, edits or browses reminders. Capture stays on
  the desktop.
- **The phone's own alarm fires it.** Nothing runs a server on your behalf at reminder
  time.
- **The handoff happens over your own network.** There is no account, and no server holds
  your reminders.

## Requirements

- Android 8.0 (API 26) or newer
- The Linodea desktop app, paired once

## Build

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## License

PolyForm Noncommercial 1.0.0 — see [LICENSE](LICENSE). Free for noncommercial use.
