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
replaced by physical gestures at refreshed bounds of concrete HTML buttons. Clickable containers
are never selected as controls or menus. Screen capture detects the canvas;
accessibility reads visible HUD text and menu buttons. No game memory, save-file
editing, APK patching, or injection into CO-DUG is used.

## Controller

- Uses current decision time and a HUD snapshot refreshed after pixel detection;
  processing time is included in the obstacle projection. Diagnostic frames
  include decision age, pixel sampling time, and vision time.
- Samples RGBA image planes directly into a 360-pixel-wide analysis frame, with
  row/pixel stride and crop handling. No full-resolution Bitmap is allocated.
  Perception is capped at 20 frames/s, and the capture worker uses Android's
  default OS thread priority rather than Java's unrelated priority constant.
- While the drill is active, a rock is considered missed only when its captured
  position is beyond the window without destruction evidence. A new activation's
  latency is not applied again to an active pulse.

- Excludes intro images without a live HUD and resets on a dispatched new-game
  start, while preserving upgrade-pause snapshots within the same game.
- Uses recent verified control rectangles even when virtual AX node identities
  expire; foreground is still checked before the physical gesture.
- Derives wide-layout CSS scale from button centre spacing (253.5 CSS px), not
  AX gaps that may shrink when touch rectangles expand. Narrow layouts use the
  WebView's Android density.
- Freezes obstacle positions through upgrade menus and rebases the tracking
  clock to the completed resume touch. Refreshes the HUD/menu immediately after
  selection, and rejects pre-resume/transition frames.
- Retains rock side silhouettes near the drill after resume.
- Evaluates the next two rows with adjacent-lane reachability, landing safety,
  pulse coverage and recovery time before committing to a route.
- Detects all eight rock fill palettes, including dark basalt/void/error stone.
- Tracks rock/ore centres directly across captured images, estimates scroll speed,
  and retains occluded targets. Rounded/asynchronous HUD depth does not position rocks.
- Uses CO-DUG's real 39 CSS pixel drill collision window and an input lead time.
- Starts a drill only when the visible HUD says READY and the heat stage permits.
- Cancels only when the visible HUD confirms an active drill; waits for actual
  cooldown feedback rather than an assumed fixed timeout.
- Starts escape based on where a rock will be when the input arrives, rather
  than waiting for it to pass the drill window on screen.
- Rejects destination lanes with rocks in the projected landing/body collision
  zone; waits through cooldown when recovery can finish before interception.
- A row must have overlapped an active drill window before its disappearance
  can clear its track. Late pulses cannot erase a missed rock from the planner.
- Includes capture cadence in the bounded activation lead and confirms moves
  with one stable target-lane observation after the animation settling period.
- Chooses actual accessible upgrade buttons, prioritizing cooling and cooldown.
- Turns the x2 accelerator off through its visible button before normal control.
- Confirms lane changes visually and does not repeat an unconfirmed move.
- Selects only concrete button nodes; descendant text cannot turn a WebView or
  overlay container into a menu button. Live accelerator touches preserve tracks.
- Uses gestures because an accepted semantic click did not produce game actions
  in the FCNT M06 device trace. Menu dispatch and observed confirmation are logged
  separately. HUD state from a previous run is invalidated on start.
- Allows escape movement while a drill acknowledgement is pending.
- Expires touch requests that miss their dispatch deadline. A dispatched touch
  whose completion callback times out remains pending until game feedback,
  preventing an immediate duplicate toggle. UNKNOWN HUD state never acknowledges
  cancellation.
- Requires live HUD mode for toggles and calibrated actual button spacing for vision.
- Requests pause and stops if capture stalls during play.
- Restricts clicks, gestures and HUD reads to CO-DUG's foreground package.
- Keeps a bounded local decision/input trace. Use **実行記録を共有** in COSC to
  export it explicitly after a run. Nothing is uploaded automatically.

## Validation status

Version 0.11.0 is a control and diagnostic revision. Physical-device survival to
100000 m has not been verified. Validation for this revision consists of RGBA pixel-layout checks and an APK
build. The replay and emulator workflows are manual and have not been run for
this revision.

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
