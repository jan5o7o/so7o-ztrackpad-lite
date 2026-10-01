# Changelog

Notable changes per release, newest first. The version is `versionName` in
`AndroidManifest.xml`, and every release also increments `versionCode` — Android refuses an
update that does not, so the two move together. Each entry here is the release's notes.

Keep this file free of development-tree names: it is exported to the public repo unchanged, so
it can only ever describe the app as shipped.

## Unreleased

Nothing yet.

## 0.1.0 — 2026-10-01

**The first release, and the one that removes the dependency.** So7o Z Trackpad Lite is a
derivative of [So7o Z Trackpad](https://github.com/jan5o7o/ztrackpad): same pad, same pointer,
same themes — and no Shizuku, no shell process, and no second app to keep running.

The reason is lifecycle. Shizuku does not survive a reboot without root, so upstream needs
wireless debugging re-established after every restart. This build needs one accessibility
service, enabled once.

- **Shizuku is gone**, along with the shell user service, the AIDL interface it was bound
  through, and the four vendored Shizuku jars. Every click, long press, scroll and drag is an
  accessibility gesture; the app now requests only `VIBRATE`.
- **Everything that depended on Shizuku is gone too**, not disabled: the virtual display, the
  display picker and pointer retargeting, the floating-window list, split-screen controls, real
  mouse drags, and the keys panel. The pad's `⌫` and `⏎` buttons go with them, since they need
  injected keycodes. The arrow keys stay — `performGlobalAction` sends those without a helper.
- **The CONTROLS panel is gone** with the settings it held, and so are its two links.
- **The pad keeps its identity**: the `●` bubble and edge-snap, the `≡ MOVE` / `≡ LOCKED`
  handle, the five theme presets and the opacity slider, the lock, four-corner resize, edge
  scrolling, click-through, and the drawn pointer.
- **Click-through now works on the gesture path.** Upstream applied it only when injecting
  through Shizuku; here a tap has to drop `FLAG_NOT_TOUCHABLE` too, or a dispatched gesture
  aimed under the pad would be delivered to the pad.
- **A scriptable `ControlReceiver`** (`op=status`, `op=lock`, `op=theme`) replaces the
  virtual-display receiver, so the pad's state stays readable and testable without tapping.
- **The status line is five fields**: `padlocked= pad= theme= w= h=`.
- **New release identity**: package `app.so7o.ztrackpad.lite`, a fresh keystore, and its own
  repository. It installs alongside the original. Do not enable both accessibility services at
  once — two overlay services will fight over the pointer.
- **The drag is approximate, and that is the honest cost.** A gesture-injected drag cannot
  sustain a touch stream, so it is built from continued strokes with a heartbeat that restarts
  the chain when the platform cancels it. For hover and precise window drags on an external
  display, use upstream.
