# COSC

CO-DUG Offline Smart Controller.

Android controller for the local/offline `tanuki276/co-dugapk` build.

## What it does

- Connects to Shizuku with the official UserService API.
- Launches `com.tanuki276.codug`.
- Captures the full display with MediaProjection (Shizuku is used for input).
- Scans the three CO-DUG lanes for nearby large objects.
- Taps the CO-DUG touch controls to dodge or drill.
- Runs as a foreground service with a persistent notification.

This repository is intentionally scoped to the CO-DUG app package and is not a generic game automation tool.

## Requirements

- Android 8.0+ (minSdk 26)
- Shizuku running and permission granted to COSC
- CO-DUG installed as `com.tanuki276.codug`

## Build

```bash
gradle testDebugUnitTest assembleDebug
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

## Compatibility fixes

Target inspected: `tanuki276/co-dugapk` commit `267b0b54eb687cf74daedfbee62f1bc216030d5c`.
No changes to CO-DUG are required.

- Android 14+ requests the full display, avoiding app-only capture coordinates.
- Capture uses real display dimensions, including the immersive game area.
- Yellow button scans use a consistent pixel grid at every screen height.
- Static frames alone never trigger an upgrade tap; a filled choice card with
  bright text and a yellow label must be visible.
- Each new session clears lane and image history.
- Drill cancellation is limited to before the base 500 ms expiration. Late
  frames discard the assumed active state without toggling it on again, and
  use a conservative 1100 ms recovery interval after cancellation.

Screen recognition remains heuristic. The regression tests cover synthetic
button/card frames, scan parity, and static gameplay. A connected Android device
with Shizuku is required to verify real WebView rendering, input latency and
long-run survival. Rotate the device only after stopping AUTO, then start a new
capture session. Full-screen capture may include other apps; input is guarded
by the target package foreground check.
