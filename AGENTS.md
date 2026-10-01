# AGENTS.md — So7o Z Trackpad Lite

## What this is

A standalone Android input app: a floating trackpad, a drawn pointer, a theme menu and a
pad lock. Built on-device in Termux with a **hand-rolled build** (`build.sh`) — there is
**no Gradle, no Kotlin, and no SDK install**; the app is plain Java. The build downloads
only the platform jar it compiles against.

It is a **derivative of So7o Z Trackpad**, which is MIT and lives at
`https://github.com/jan5o7o/ztrackpad`. This is an independent project, not a branch: it
has its own package id (`app.so7o.ztrackpad.lite`), its own release keystore, and it does
not merge from upstream.

**What was removed, and why.** Upstream injects input two ways: accessibility
`dispatchGesture()` *and* a Shizuku shell bridge. Shizuku is what upstream uses for real
`SOURCE_MOUSE` drags, arbitrary keycodes, and driving a second display. Without root,
Shizuku does not survive a reboot, so upstream needs wireless debugging re-established
after every restart. This build drops Shizuku entirely, and with it every feature that
depended on it. **The lifecycle cost was the reason; the removed features are the price.**

Reference environment — everything in the verification list was measured on an original
So7o Z Trackpad, on a **Galaxy Z Fold 4** (SM-F936B), aarch64, Android 16 / One UI, in
Termux, with no desktop. **Read "verified" in the list below as "verified for upstream".**
What has actually been run against *this* tree is stated separately, and it is much less.

## What this build does not have

Every one of these was cut, not merely disabled. Do not "restore" one by uncommenting:
the supporting code is gone.

| Removed | It needed Shizuku for |
|---|---|
| Virtual display (`VDisplayReceiver`, `ShellUserService`) | shell's `CAPTURE_VIDEO_OUTPUT` |
| Display picker / pointer retargeting | `dispatchGesture()` has no display parameter |
| The `▤` floating-window list | `dumpsys activity activities` + `am task focus` |
| Split-screen geometry and the divider drag | `dumpsys window`, and touch injection |
| Real window drags (DeX / freeform) | `SOURCE_MOUSE` events |
| The keys panel and its bubble | arbitrary keycodes |
| The pad's `⌫` / `⏎` buttons | `KEYCODE_DEL` / `KEYCODE_ENTER` |
| Hover, right-click, the system pointer's visibility | `InputManager` / pointer icons |
| The CONTROLS panel and the CLI help links | it only held settings for removed features |

The arrow row on the pad **stays**: `performGlobalAction(GLOBAL_ACTION_DPAD_*)` needs no
Shizuku. Those four keys are the only keycodes this build can send.

## Branches and releases

`main` is **releases only**: a pull request is required, the `build` check has to pass, and
nothing may be pushed to it directly — not even by the maintainer. `dev` is where work lands.
Every push to either branch, and every pull request, runs
`.github/workflows/build.yml`: it installs the platform and build-tools, builds, signs with
a **throwaway** key, and asserts that no development identity is in the tracked tree or
inside `classes.dex`. It cannot run `tests/smoke.sh` — that needs a device — so the device
checks stay manual, which is the whole reason the verification list below exists.

Release steps live in [RELEASING.md](RELEASING.md).

Two invariants matter more than the tooling: **no secret in a tracked file** (the signing
password comes from `$KSPASS` or `~/.ztrackpad-lite-kspass`, nowhere else), and **the release
keystore never enters the repository** — it is what lets an existing install update in
place, and losing it means no install can ever update again. Screenshots and recordings need
the same care, because every check here reads text and cannot see inside an image.

This repository is a fork of an upstream tree, so its history contains upstream's files. If
you ever re-clone or copy rather than `git clone`, remember that **`keystore.jks` is
gitignored and therefore absent from a clone but present in a copied working directory** —
copy the tree and you copy the release key. Use `git clone`.

## What an agent cannot do here

Stop and ask rather than improvising around these. They are the only steps in the whole
workflow that are not shell-doable, and a human is required for each.

- **Enabling wireless debugging** — a Developer-options toggle. Assume it is already on,
  then verify with `adb devices`. The mDNS record for it lingers after the toggle goes off,
  so a discovered port can refuse the connection; that is a stale advertisement, not a bug.
- **Tapping the UI** — possible via `adb shell input tap X Y`, but fragile: the pad moves
  and resizes. Prefer the broadcast API in `ControlReceiver` (`op=status`, `op=lock`,
  `op=theme`).

Everything else — packages, fetching the jar, build, sign, `adb install`, enabling the
accessibility service, verifying — is shell-only.

## Build & install

```bash
cd ~/ztrackpad-lite
./build.sh                      # aapt2 -> javac -> d8 -> apksigner
adb install -r out/ztrackpad-lite.apk
```

`tests/smoke.sh` covers everything that can be checked without fingers: device and package
discovery, the 5-field status schema, the theme round-trip, the pad-lock round-trip (both
restored to their prior value afterwards), the implicit-broadcast trap, and an assertion
that the installed package mentions Shizuku nowhere. Run it after installing, against
whatever is installed.

Signing needs the keystore password, which is **deliberately not in the repo**: set
`KSPASS` in the environment, or keep it in `~/.ztrackpad-lite-kspass`. `build.sh` fails
closed when neither is present.

- `sdk/platforms/android-36/android.jar` is gitignored; restore it from
  `https://dl.google.com/android/repository/platform-36_r02.zip`.
- `libs/androidx-annotation.jar` is committed so the build is reproducible. It is the only
  jar — upstream's four Shizuku jars are gone.
- `keystore.jks` (signing key) and `out/`, `build/` are gitignored.
- If `adb install` fails, copy APK to `/sdcard` and `pm install -r` via adb shell.
- Installing alongside upstream is fine — different package id, so both can live on the
  device. **Do not enable both accessibility services at once**: two overlay services would
  both inject input and fight over the pointer.

## Architecture

| File | Role |
|---|---|
| `java/.../TrackpadService.java` | the whole UI + gesture logic (accessibility service) |
| `java/.../ControlReceiver.java` | broadcast entry point so scripts can read the pad's state and drive the lock |
| `java/.../Theme.java` | the five visual presets and every themed colour/radius |
| `java/.../MainActivity.java` | the launcher screen: status text and a shortcut to Accessibility settings |
| `build.sh` | the hand-rolled build pipeline |
| `tools/make-icon.py` | regenerates `res/mipmap-*/ic_launcher_foreground.png` from `tools/pointer.png` |
| `libs/androidx-annotation.jar` | the one vendored jar |

Key mechanism: every click, long press, scroll and drag is an accessibility gesture sent
with `dispatchGesture()`. There is no shell process and no second package. The cost is
stated in the code: `dispatchGesture()` has no display parameter, cannot hover, and cannot
sustain a finger-driven drag — the drag is built from continued strokes with a heartbeat
that restarts the chain when the platform cancels it.

There is **one display**. The pointer's coordinate space is the screen the panels are on, so
`cursorX`/`cursorY` are screen coordinates. Upstream carried a surface/target pair and all
the arithmetic that came with retargeting; that is gone, and the folded-vs-unfolded
display-id swap that used to bite is now impossible rather than handled.

## Conventions

- **Java 8 syntax** (`javac -source 8 -target 8`), no lambdas (d8 desugaring
  risk) — use anonymous classes everywhere.
- Haptics: `tick()` → `VibrationEffect.createPredefined(EFFECT_CLICK)`, gated on
  `haptic_feedback_enabled`.
- Auto-repeat: `attachRepeat(view, action, repeatable)` — 420ms delay, 60ms
  interval; never tick per repeat.
- Press feedback: `keyBgState(normal, pressed)` = state-list drawable.
- Window geometry helpers are generic (`saveGeometry(lp, prefix)` etc.).
- **Resize grips**: all four corners resize, but only the bottom-right one is drawn
  (`addResizeGrips(..., includeTopLeft)`) and it is a `GripView` — three diagonal strokes,
  no background. An invisible grip is just a `View` with no background carrying the same
  touch listener, so do not "remove" a grip to tidy the UI: that removes the resize too.
  The pad passes `includeTopLeft=true` now that the theme dot sits below the handle rather
  than in that corner.
- **Never test Gravity bits with `&`.** `Gravity.LEFT` is 3 (`0b011`) and `Gravity.RIGHT`
  is 5 (`0b101`), so they share bit 0 and `(side & Gravity.RIGHT) != 0` is true for LEFT
  as well. That silently gave every docked dot a `rightMargin` and no `leftMargin`, so the
  theme dot sat flush against the pad edge while its mirror was inset correctly — a
  visible asymmetry with no error anywhere. Pass an explicit `alignRight` boolean.
- **The theme + opacity menu** hangs off the `◐` dot just under the pad's title bar, NOT in
  the handle: inside it the button fights the drag gesture, and the whole bar should stay
  draggable. The lock is a second dot beside it (`slot` counts inward on a side, so two
  dots never land on each other). Opacity scales only panel *fills* via `fill()`, and the
  slider applies on release: `applyTheme()` rebuilds the panels, which inside a `SeekBar`
  touch callback would delete the slider mid-drag, so the release posts the rebuild instead.
- Overlay z-order follows **add order**; raise via remove+re-add (`raise()`).
- **The pad's gesture grammar is decided at ACTION_DOWN**, never on first move: the hold
  timer (`scheduleHold`), the tap-then-drag window and the edge strip all have to claim a
  touch there, or a slow press gets misread as a drag (or a drag as a click). The edge
  strip is scroll-only on purpose - the pointer is elsewhere, so a click from there would
  land where the finger never was - and it does not disturb `dragArmed`, so an armed drag
  survives a scroll.
- **`updateViewLayout` queues, it does not apply.** `setPanelsTouchable(false)` returns
  before the window manager has acted, so click-through must post the injection a couple of
  frames later and hold `FLAG_NOT_TOUCHABLE` for the gesture's whole duration
  (`injectThroughPanels(inject, gestureMs)`, `TOUCHABLE_SETTLE_MS`). Injecting immediately
  after the flag request looked correct and silently did nothing whenever the pointer was
  over a panel - and a long press needed the flag up for all of `HOLD_MS`, or its UP landed
  on a touchable window again.
- **Click-through is applied in this build's tap paths** (`click()`, `longPress()`), which
  upstream only did on its Shizuku path. That is deliberate: without it, a gesture
  dispatched at a point covered by the pad would be delivered *to the pad*. The drag path
  still must NOT do it — the finger is down there.
- **A docked dot or grip beats the touch surface underneath it.** The strips live in the
  surface, so the theme/lock dots and the four corner grips own their slice of the pad.
  Fine, but it is why the strip can never be touched there.
- **The launcher icon is generated, not hand-edited.** `tools/make-icon.py` draws the
  pointer, recolours it and adds the `lite` label on an 864px master, then downsamples it to
  all five densities, so the buckets cannot drift apart. It reads `tools/pointer.png` (the
  pristine white arrow) rather than `res/`, because it writes into `res/` — reading there
  would make a second run compound the label onto the previous one. Two rules for the
  artwork: everything must stay inside the **66dp safe circle** (a 108dp adaptive icon is
  only guaranteed to show its central 66dp, so anything nearer the edge gets clipped by a
  circular mask), and the label has to be positioned by **ink, not by its line box** — the
  arrow PNG has ~100px of empty margin, and placing the image rather than the drawing puts
  the arrow straight through the text.
- **Colours and radii come from `Theme`, never from a literal.** Fields are named by role.
  Adding a preset = one `static` block + one `PRESETS` entry.
- **An emoji in overlay text ignores `setTextColor`.** The padlock was an emoji and rendered
  in the emoji font's own colours no matter what the theme said, so the lock is now drawn
  (`LockDot`: ring + a thin outlined body with a shackle arc) in `textDim`. Any icon in a
  themed dot has the same choice to make.
- **A theme change rebuilds the panels** (`applyTheme`), because every background is a
  generated drawable built at construction time. Save geometry first, or the rebuild falls
  back to defaults, and restore the theme panel's own visibility — otherwise you cannot try
  presets in a row. The bubble is restyled in place instead, since its position is not
  persisted.
- `Theme.DEFAULT` mirrors the original hardcoded values exactly. Keep it that way.
- **The `-n` component is mandatory on every broadcast**: an implicit broadcast never
  reaches a manifest-declared receiver on API 26+, and the failure is silent (result=0 with
  no `data=`), which is easy to misread as success. `ControlReceiver` is exported with no
  permission on purpose — the worst case is a pad that will not move — and it still should
  not be treated as a private channel.

## Known behaviours / gotchas

- Click-through (`injectThroughPanels`) drops `FLAG_NOT_TOUCHABLE` for ~110ms while
  injecting; only used from tap paths (finger already up). The drag path must NOT use it.
- Samsung `FreecessHandler` may freeze the background app — the accessibility service keeps
  it alive once enabled.
- `GLOBAL_ACTION_DPAD_*` was added in API **33** (Android 13), so the arrow keys genuinely
  need Android 13+, while `minSdk` is 30. The honest floor for the arrows is 13.
- The `status` reply is `padlocked= pad= theme= w= h=` — one line of key=value pairs.
  `tests/smoke.sh` asserts exactly those five.

## Verification status

Keep this list honest — do not move rows up without actually re-testing.

**Verified for this tree**

- The tree **compiles, packages, signs and aligns**: `./build.sh` produces a 60 KB signed
  APK, and the alignment report passes.
- The packaged app is **Shizuku-free**: only `android.permission.VIBRATE` is requested, and
  `strings` finds zero `shizuku` occurrences in `classes.dex`.

**Not verified for this tree — the on-device list is empty**

- **`tests/smoke.sh` has never run against this build.** When it was written the device was
  not reachable (wireless debugging off), so nothing here has been installed, let alone
  exercised. Run it first and treat its result as the entry bar.
- **Nothing has been run on a device at all**: no install, no enable, no tap, no drag, no
  scroll, no theme switch, no lock toggle, no click-through measurement.

**Inherited from upstream, expected to hold but not re-measured here**

These describe code carried over unchanged, so they are plausible rather than confirmed for
this build. Each one is a candidate for the first real test session.

- Overlay windows: bubble, drawn pointer and pad are `TYPE_ACCESSIBILITY_OVERLAY`, with drag
  + 4-corner resize + geometry persistence.
- Tap → click; hold-still-then-move → drag; tap-then-drag (300ms window).
- Haptics: `EFFECT_CLICK`, `usage: TOUCH`, attributed to this app.
- Two-finger tap → right-click (unverifiable via adb: it needs real multitouch).
- Pad lock: the handle reads `≡ LOCKED` and geometry is frozen; the flag persists across a
  service restart.
- Edge scrolling: a touch starting within `dp(28)` of the left or right edge scrolls instead
  of moving the pointer, at `EDGE_SCROLL_FACTOR` 0.25 in `EDGE_FLUSH_PX` 12px flushes.
- Bubble edge snap: dragging a floating dot and releasing sends it to the nearer vertical
  edge.
- Z-order: **cursor > pad > bubble** (via `raise()`).
- Theme presets render distinctly and keep panel positions; the opacity slider scales panel
  fills only.
- Click-through: a click aimed under the pad reaches the app beneath it.

**Genuinely uncertain, inherited from the no-Shizuku fallback path**

Upstream's README claimed that without Shizuku "drag does nothing", while its code
contained the chunked-stroke drag implemented below. Both cannot be right. **This build's
drag is entirely that fallback path, so this is the single most important thing to measure
first.** Suspect it if drag feels like it stalls or restarts mid-press; the heartbeat in
`startBeat()` is what recovers it, and `dispatchGesture()` returning false means a stroke
was refused because another was already in flight.

## Device notes (Galaxy Z Fold 4 / F936B, One UI, Android 16)

- Wireless ADB port changes; discover via mDNS
  (`_adb-tls-connect._tcp`, see `~/adbdiscover.py`) then `adb connect`. Stale records linger
  after the toggle goes off, so a refused connection usually means wireless debugging is
  simply not on.
