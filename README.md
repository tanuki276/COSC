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

- Applies a pause received during pixel processing before move confirmation or
  timeout handling. Missing lane confirmation reacquires the UI rather than
  terminating the run at an upgrade. The 9000 m/HP 7 stop trace is covered by a
  deterministic pause-during-analysis check.
- After a completed menu touch, samples for the return of both selected-lane
  borders and acknowledges visible gameplay before a slow AX tree finishes.
  Clears the dismissed choice and rejects tree requests begun before that visual
  acknowledgement, preventing old modal snapshots from reopening the pause gate.
- Preserves the existing control coordinates briefly after visual resume while
  fresh control nodes are reacquired. Normal input still checks the foreground.

- Rejects palette matches that have no contrast against the background beside
  the rock. This prevents the selected-cell fill from repeatedly creating a
  near-player phantom obstacle and provoking alternating lane changes.
- Gives an interceptable READY drill priority over rerouting, waits for cooldown
  when it can finish in time, and holds a recent route unless collision timing
  requires another move. One reachability planner chooses escape directions;
  a competing local rock-count fallback has been removed.
- Detects pause headings and missing gameplay controls independently of finding
  an upgrade button. A missing player/selection marker suspends optical tracking
  and requests a menu refresh, rather than timing out a move against a modal.
- Finds bounded, uniquely named actionable menu nodes even when WebView exposes
  them as Views, and traverses visible descendants of non-visible containers.
  Menu touches use recent concrete bounds and wait for observed gameplay controls
  before releasing the pause gate. A pending move is cleared when a menu is seen.
- Static pixel regressions cover an empty selected cell over repeated captures,
  all eight rock palettes near and far, an obscured board, and drilling the first
  of two rows rather than making an avoidable direction change.

- Reads the instantaneous selected-cell borders before falling back to animated
  rig pixels. A confirmed border permits control on the next captured frame;
  rig-only confirmation retains its animation-settling delay. Long paired border
  segments and local colour contrast reject warm backgrounds and partial marks.
- Holds a second movement until the first is observed. A protective drill pulse
  can cover either possible lane during the transition; thermal cancellation
  remains available. Other cancellation waits for lane confirmation.
- Includes the selected-cell fill and thermal-glow composites in near-player
  rock and ore colour detection. Recently exposed near-player ore can start a
  track after a menu, using the same drill/particle mask as rocks.
- Accepts post-command COOLING as evidence that a short pulse ran between HUD
  samples, so missing its ACTIVE sample cannot hold all toggles for 700 ms.
- Checks whole lane-occupancy intervals and adjacent-lane reachability across
  visible rows over a bounded two-second horizon. A rock crossing before the
  end of a movement blocks that destination. Destruction is not assumed when
  scoring escape paths.
- Can send a conservative escape immediately after an upgrade selection using
  a recent frozen snapshot, without waiting for the next capture. Fresh images
  remain authoritative after resume. Keeps measured speed across upgrade pauses.
- Rejects older HP observations, updates bit reach after observed selection confirmation,
  and prioritizes repair when HP is two or less. Adds DAMAGE records with the
  preceding scene.
- Exports immutable log files with temporary read-only URI permission instead
  of placing a long transcript in the share intent. The report menu also offers
  document saving and a short clipboard tail. Exports include current and
  preceding log segments; opening another run preserves the preceding log.

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
- Reacquires expired HUD node identities promptly when the live heat/mode read
  fails, instead of waiting for the normal 700 ms full-tree polling period.
- Uses recent verified control rectangles even when virtual AX node identities
  expire; foreground is still checked before the physical gesture.
- Derives wide-layout CSS scale from button centre spacing (253.5 CSS px), not
  AX gaps that may shrink when touch rectangles expand. Narrow layouts use the
  WebView's Android density.
- Freezes obstacle positions through upgrade menus and rebases the tracking
  clock to the completed resume touch. Refreshes the HUD/menu immediately after
  selection, and rejects pre-resume/transition frames.
- Retains rock side silhouettes near the drill after resume.
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

Version 0.15.0 is a control and diagnostic revision. Physical-device survival to
100000 m has not been verified. Validation for this revision consists of RGBA pixel-layout, lane-marker and control-state checks, lane-occupancy arithmetic, plus an APK
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
