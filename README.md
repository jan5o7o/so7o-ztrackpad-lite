# So7o Z Trackpad Lite

A floating **trackpad and pointer** for Android, with **no root and no companion app**.
Nothing to install alongside it, nothing to restart after a reboot, no adb in the loop
after the first setup: enable one accessibility service and you are done.

It is a derivative of [So7o Z Trackpad](https://github.com/jan5o7o/ztrackpad), which does
more and asks more for it. This one keeps the pad and cuts everything that needed a second
process.

![The pad over the home screen, handle reading ≡ LOCKED, with the theme and opacity menu open
beside it and the drawn pointer on the pad](docs/media/demo.gif)

*The pad and its theme menu. Scrolling lands when you lift your finger — see
[Fidelity, honestly](#fidelity-honestly).*

▶ **[Full demo on YouTube](https://youtube.com/shorts/MGnJgh8sBgY)** — the whole ~1:45
walkthrough, pad and pointer over Chrome and Termux.

```
    ≡ MOVE              ← drag handle (tap to re-centre; reads ≡ LOCKED when locked)
 ◐     ┌─ surface ─────┐  🔒   one finger moves · tap = click · hold then move = drag
       │  ·         ·  │      the dotted columns mark each dp(28) scroll strip
       └──────────────┘
   ◀ ▦ ⋮ ●  ← → ↑ ↓    back · recents · right-click · hide pointer · arrow keys
```

## Why this exists

Upstream injects input two ways: accessibility gestures, and a **Shizuku** shell bridge
running as the shell UID. Shizuku is what buys it real mouse events, arbitrary keycodes and
the ability to drive a second display — and without root it does not survive a reboot, so it
has to be restarted through wireless debugging after every restart. That ritual is the
reason this build exists. Removing Shizuku removes it, along with every feature that
depended on it.

**The lifecycle cost was the reason. The removed features are the price** —
[they are listed below](#what-it-does-not-do), and it is a real price.

## Requirements

- **Android 11+** (`minSdk` 30) to install. The arrow keys use
  `GLOBAL_ACTION_DPAD_*`, added in **Android 13**, so the honest floor for those is 13.
- **Nothing else.** No root, no Shizuku, no companion APK, no VPN, no device-owner setup.

## Setup

1. Install the APK, open the app, tap **Open Accessibility Settings** — the button is the
   first thing under the title, so a fresh install never has to scroll to find it.
2. Enable **So7o Z Trackpad Lite** under *Installed services*.
3. Come back — a small `●` dot appears on screen. Tap it to show the pad.

That survives reboots. There is no step 4.

> Installing this alongside the original So7o Z Trackpad is fine — different package ids.
> **Do not enable both accessibility services at once**: both draw overlays and inject input,
> and they will fight over the pointer.

## The controls

- **`●`** — the drawn dot. Tap to show or hide the trackpad; drag it and it snaps to the
  nearer vertical edge.
- **The pad**
  - **`≡ MOVE`** — the handle. Drag to move the pad; tap to re-centre. Reads `≡ LOCKED`
    when the lock is on, `≡ SCROLL` mid-scroll, `≡ DRAG ARMED` when a drag is armed.
  - **`◐`** and the **lock** — two round dots just below the handle, one per side of the
    pad's top: `◐` on the left opens the theme menu (five presets plus an opacity slider);
    the lock on the right freezes the pad's position and size and persists across restarts.
  - **The dotted columns** down each side of the surface mark the edge-scroll strips.
  - **Corners** — drag any corner to resize. Only the bottom-right one is drawn; the other
    three are live but invisible.
  - The button row: **`◀`** back, **`▦`** the running-apps switcher, **`⋮`** right-click at
    the pointer, **`●`** hide/show the pointer, and the four **arrow** keys. Back and
    recents go out as accessibility global actions — the same no-permission mechanism as
    the arrows.

## Gestures

| | |
|---|---|
| drag one finger | move the pointer |
| tap | click at the pointer — **through** the pad, if the pad is covering the target |
| hold still | long press |
| two-finger drag | scroll |
| swipe the left or right edge | scroll, like a laptop's strip |
| two-finger tap | right-click (as a long press) |
| tap, then touch again within 300ms | arm a press-and-drag |

**Scrolling lands when you lift your finger**, not while it moves. That is not a bug to
report: an injected gesture cannot run while a real touch is in progress, so the distance is
accumulated and spent on release. It is the ceiling for an app with no second process to
inject through, and the reason [upstream](https://github.com/jan5o7o/ztrackpad) uses Shizuku.

## Scripting it

The pad's state can be read and driven over a broadcast, which is also how the test suite
checks it:

```bash
adb shell am broadcast -n app.so7o.ztrackpad.lite/.ControlReceiver \
    -a app.so7o.ztrackpad.lite.CONTROL --es op status
```

| op | arg | reply |
|---|---|---|
| `status` | — | `padlocked=false pad=shown theme=default w=1812 h=2176` |
| `lock` | `on` \| `off` \| `toggle` | `ok lock <status>` |
| `theme` | a preset id, or nothing to read it | `ok theme contrast` |

The `-n` component is **mandatory**. An implicit broadcast does not reach a manifest-declared
receiver on API 26+, and the failure is silent.

## Build

No Gradle, no Kotlin, no SDK install — plain Java, built with `aapt2`, `javac`, `d8` and
`apksigner`:

```bash
./build.sh                      # -> out/ztrackpad-lite.apk
adb install -r out/ztrackpad-lite.apk
tests/smoke.sh                  # device checks that need no fingers
```

Signing needs a keystore password: set `KSPASS`, or keep it in `~/.ztrackpad-lite-kspass`.
The build fails closed without it. `sdk/platforms/android-36/android.jar` is gitignored —
see [Prerequisites](#prerequisites) below.

## What it does not do

Everything here was **removed, not disabled**, and each line is a feature upstream has:

- **No second display.** The pointer drives the screen the pad is on. The display picker and
  pointer retargeting are gone, and so is the virtual display — no floating display, no
  headless one.
- **No floating-window list.** The `▤` panel that listed pop-up windows is gone.
- **No keys panel.** The programmable keyboard and its `⌨` bubble are gone, and so are the
  pad's `⌫` and `⏎` buttons: they need injected keycodes. The arrow keys stay, because
  `performGlobalAction` sends those without any helper — and so do **back** and **recents**
  (`◀`/`▦` on the pad), which are the same global-action mechanism.
- **No split-screen controls**, and no divider dragging.
- **No real window drags** on DeX or freeform windows — those need injected mouse events, and
  a gesture-injected drag cannot grab a title bar.
- **No hover**, and no way to hide the system pointer if one is present.
- **No right-click proper.** Two-finger tap becomes a long press.

## In use

Driving a page in Chrome — the pad sits down the right edge and the pointer clicks whatever is
under it, including the pad's own footprint:

![The pad over Chrome, driving github.com/jan5o7o/so7o-ztrackpad-lite](docs/screenshots/browser-and-pad.jpg)

The theme and opacity menu, opened from the `◐` dot under the handle:

![The theme and opacity menu open beside the pad over the home screen](docs/screenshots/pad-theme-and-opacity.jpg)

## Fidelity, honestly

Click, long press, scroll and drag are all sent as accessibility gestures. That is what
makes the app dependency-free, and it is also its cost: there is no hover, and **the drag is
approximate** — built from continued strokes with a heartbeat that restarts the chain when
the platform cancels it. If drag ever feels like it stalls mid-press, that is why, and it is
the one thing most worth measuring before trusting it.

For precise, hover-capable window drags on a monitor, use
[upstream](https://github.com/jan5o7o/ztrackpad) with Shizuku.

## Licence

MIT — see [LICENSE](LICENSE). Third-party terms are in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
