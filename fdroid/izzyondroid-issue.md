# IzzyOnDroid inclusion request — draft

**File at https://codeberg.org/IzzyOnDroid/repodata/issues/new**, not on GitLab. IzzyOnDroid
moved its tracker to Codeberg around March 2026: the GitLab `IzzyOnDroid/repo` issue tracker is
retired (creating an issue there returns `403 Forbidden`, and its newest issue predates the
move), while `IzzyOnDroid/repodata` on Codeberg takes `[AppRequest]` issues daily.

Requires a Codeberg account. Title: `[AppRequest] So7o Z Trackpad Lite`.

---

### Guidelines

- [x] I am the developer of the app.
- [x] The app complies with the [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/).
- [x] The app is not already listed in the repo or issue tracker.
- [x] The [Fastlane](https://izzyondroid.org/docs/general/Fastlane/) folder is available in the app's repo.

### Link to the source code

https://github.com/jan5o7o/so7o-ztrackpad-lite

### Link to app in another app store

_No response_

### License used

MIT

### Categories

System

### Summary

A floating trackpad and pointer for Android. One accessibility service, no root, no companion app.

### Description

A floating trackpad and pointer for Android, with no root and no companion app. Enable one
accessibility service once, and it stays enabled across reboots.

Intended use: on an unfolded foldable your thumbs rest on the bottom half of the screen, usually
over a keyboard, and the top half is out of reach. Park the trackpad beside the thumb that is not
typing, and one thumb drives the pointer across the whole screen. On a Galaxy Fold with the
keyboard on the left, the trackpad sits on the right.

- One finger moves the pointer. Tap to click, hold still for a long press, two-finger tap for right-click.
- Clicks land through the pad: tapping sends the click at the pointer, even where the pad covers the target.
- Edge scroll strips down both sides, like a laptop's; scrolling lands when you lift your finger.
- Five colour themes and a panel-opacity slider, a lock, and resize from any corner.
- Back, recents and the four arrow keys, sent as accessibility global actions.

The app uses an accessibility service, which the user enables in Settings, to send taps, scrolls
and drags. It requests only `VIBRATE`, has no `INTERNET` permission, no ads and no trackers. It is
a spin-off of So7o Z Trackpad with its own package name, name, icon and screenshots.

### Further Notices

- APKs are attached to GitHub tagged releases: https://github.com/jan5o7o/so7o-ztrackpad-lite/releases
- Signing certificate SHA-256: `c601e32b788f954a40234e6db0151b246cbff1267ec793f31185651268585004`
- Built with a hand-rolled script (`./build.sh`), plain Java, no Gradle.
