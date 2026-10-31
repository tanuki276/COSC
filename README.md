# COSC

CO-DUG Offline Smart Controller.

Android controller for the local/offline `tanuki276/co-dugapk` build.

## What it does

- Connects to Shizuku with the official UserService API.
- Launches `com.tanuki276.codug`.
- Captures the screen from the Shizuku shell process.
- Scans the three CO-DUG lanes for nearby large objects.
- Injects DPAD left/right and SPACE key events to dodge or drill.
- Runs as a foreground service with a persistent notification.

This repository is intentionally scoped to the CO-DUG app package and is not a generic game automation tool.

## Requirements

- Android 8.0+ (minSdk 26)
- Shizuku running and permission granted to COSC
- CO-DUG installed as `com.tanuki276.codug`

## Build

```bash
gradle assembleDebug
```

The GitHub Actions workflow also publishes `app-debug.apk` as an artifact.

## Usage

1. Start Shizuku.
2. Open COSC.
3. Tap **Grant Shizuku** and approve it.
4. Tap **Start auto**.
5. COSC launches CO-DUG and starts the controller loop.
6. Use **Stop auto** from COSC or the notification when you want to stop.

## Notes

The lane detector is deliberately conservative: it prioritizes survival, moves away from large nearby blobs when a lane is clear, and drills when no clear path is available. It is an MVP and can be tuned in `GameAnalyzer.java`.
