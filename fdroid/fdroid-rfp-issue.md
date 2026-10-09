# F-Droid inclusion request — draft

File as an issue in the fdroiddata repository (gitlab.com/fdroid/fdroiddata/-/issues), using the
app-inclusion template if one is offered.

**Title:** RFP: So7o Z Trackpad Lite - floating trackpad and pointer, no root

---

**App name:** So7o Z Trackpad Lite
**Package:** app.so7o.ztrackpad.lite
**License:** MIT
**Source:** https://github.com/jan5o7o/so7o-ztrackpad-lite
**Issue tracker:** https://github.com/jan5o7o/so7o-ztrackpad-lite/issues
**Releases (APK attached to each tag):** https://github.com/jan5o7o/so7o-ztrackpad-lite/releases

**Summary:** a floating trackpad and pointer for Android. A single accessibility service injects
clicks, scrolls and drags as accessibility gestures, so it needs no root, no Shizuku and no
companion app. Intended use: on an unfolded foldable the thumbs rest on the bottom half while
typing, and a trackpad parked by the free thumb drives the pointer over the rest of the screen.

**Why it fits the inclusion policy:**

- MIT, and the only bundled jar (`libs/androidx-annotation.jar`) is Apache-2.0.
- No ads, no trackers, no analytics.
- No `INTERNET` permission at all; it requests only `VIBRATE`.
- No self-updater and no download of extra binaries.
- Actively maintained, and the published source is the whole app.

**Build:** hand-rolled rather than Gradle, plain Java: `./build.sh` runs aapt2 -> javac -> d8 ->
apksigner and needs only the Android SDK tools. `SKIP_SIGN=1 ./build.sh` stops after packaging
and leaves `out/ztrackpad-lite-unsigned.apk` for a builder that signs with its own key, and
`ANDROID_JAR` can be overridden to point at the buildserver's platform jar.

**Two things I would like help with:**

1. The build needs the Android 16 (API 36) platform on disk. `build.sh` honours an `ANDROID_JAR`
   override, but I do not know whether API 36 is available on the buildserver yet. If it is not,
   what is the recommended way to provide the platform with no network in the build sandbox?
2. Reproducibility. The zip step already pins a fixed mtime; a pointer to what else the rebuild
   check needs would be welcome.

A draft metadata file is in the repository at `fdroid/app.so7o.ztrackpad.lite.yml`.
