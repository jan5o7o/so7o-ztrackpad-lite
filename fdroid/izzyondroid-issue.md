# IzzyOnDroid inclusion request — draft

File this as a new issue in the IzzyOnDroid repo (gitlab.com/IzzyOnDroid/repo). It takes the
signed APK from the GitHub tagged release, so nothing has to change in the build.

---

**Title:** Inclusion request: So7o Z Trackpad Lite (app.so7o.ztrackpad.lite)

**Body:**

App name: So7o Z Trackpad Lite
Package: `app.so7o.ztrackpad.lite`
License: MIT
Source: https://github.com/jan5o7o/so7o-ztrackpad-lite
Releases (APK attached to each tag): https://github.com/jan5o7o/so7o-ztrackpad-lite/releases
Issue tracker: https://github.com/jan5o7o/so7o-ztrackpad-lite/issues

What it is: a floating trackpad and pointer for Android. It uses a single accessibility service
to inject clicks, scrolls and drags as accessibility gestures, so it needs no root, no Shizuku
and no companion app. Intended use case: on an unfolded foldable, the thumbs rest on the
bottom half while typing, and the trackpad parked by the free thumb drives the pointer across
the rest of the screen.

Why it fits the repo policy:

- FOSS (MIT), and the only bundled jar (`libs/androidx-annotation.jar`) is Apache-2.0.
- No ads, no trackers, no analytics.
- Requests only `android.permission.VIBRATE`; there is no `INTERNET` permission at all, so it
  makes no network connections.
- No self-updater and no download of extra binaries.
- Release-signed with a key I control (not a debug or shared test certificate); the APK is not
  `debuggable` and not `testOnly`.
- APK is attached to GitHub tagged releases, so it can be picked up for updates.

Notes:

- The app requires the user to enable an accessibility service, which it explains on first run
  and links to in Settings.
- Certificate pinning (SHA-256): `c601e32b788f954a40234e6db0151b246cbff1267ec793f31185651268585004`
- Fastlane metadata is in the repo under `fastlane/metadata/android/en-US/`.
- Source tree builds with a hand-rolled script (`./build.sh`), plain Java, no Gradle; the
  release APK in the GitHub release is the artifact to use.
