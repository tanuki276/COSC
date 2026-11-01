# COSC

External controller for the unmodified offline CO-DUG Android app
(`com.tanuki276.codug`). Requires Android 8.0 or later.

## Install and run

1. Install the COSC debug APK from the latest successful **Build COSC APK** run.
2. In Android Settings → Accessibility, enable **COSC**. If Android blocks a
   sideloaded accessibility service, open COSC's App info → menu → Allow
   restricted settings, then return to Accessibility.
3. Open COSC and choose **Start external auto**; allow full-screen capture.
4. Keep CO-DUG in portrait orientation. Stop AUTO before rotating the device.
5. Stop with the notification action or **Stop auto** in COSC.

Shizuku is not required. The earlier shell-per-touch path has been
replaced by semantic accessibility button clicks, with gesture fallback only when a click is rejected. Screen capture detects the canvas;
accessibility reads visible HUD text and menu buttons. No game memory, save-file
editing, APK patching, or injection into CO-DUG is used.

## Controller

- Detects all eight rock fill palettes, including dark basalt/void/error stone.
- Tracks rock/ore centres directly across captured images, estimates scroll speed,
  and retains occluded targets. Rounded/asynchronous HUD depth does not position rocks.
- Uses CO-DUG's real 39 CSS pixel drill collision window and an input lead time.
- Starts a drill only when the visible HUD says READY and the heat stage permits.
- Cancels only when the visible HUD confirms an active drill; waits for actual
  cooldown feedback rather than an assumed fixed timeout.
- Checks neighboring lanes down to the body contact zone before moving.
- Chooses actual accessible upgrade buttons, prioritizing cooling and cooldown.
- Turns the x2 accelerator off through its visible button before normal control.
- Confirms lane changes visually and does not repeat an unconfirmed move.
- Allows escape movement while a drill acknowledgement is pending.
- Requires live HUD mode for toggles and calibrated actual button spacing for vision.
- Requests pause and stops if capture stalls during play.
- Restricts clicks, gestures and HUD reads to CO-DUG's foreground package.
- Keeps a bounded local decision/input trace. Use **実行記録を共有** in COSC to
  export it explicitly after a run. Nothing is uploaded automatically.

## Validation status

Version 0.5.0 is a control and diagnostic revision. Physical-device survival to
100000 m has not been verified. This revision is compiled only; the replay and
emulator workflows are manual and have not been run for this revision.

## Build and optional historical harness

```sh
gradle assembleDebug
```

The **Original CO-DUG 100000m replay** workflow checks out CO-DUG at the fixed
commit `267b0b54eb687cf74daedfbee62f1bc216030d5c` into a separate test directory.
It runs the original row generation, upgrades, collision, heat and update code
without changing the checkout. Canvas frames from the original rock/ore/player
renderer are fed to the same Java `GameAnalyzer` shipped in COSC. The Java bridge
only substitutes pixel storage for Android Bitmap; it does not replace the
analyzer. Results and end-state images are uploaded as workflow artifacts.

The replay includes full 60 Hz rendering, camera shake, particles, and 65 ms
input latency; perception samples at 12 fps.

The replay uses a small DOM adapter for menus and structured HUD observations;
it does not emulate Android accessibility, WebView font rendering, system
capture permissions or device input scheduling. Replay success alone is not a
promise of a particular distance on every physical device. Check the exact
workflow result and seed metrics before interpreting a build as validated.

The target repository is read-only throughout this workflow.
