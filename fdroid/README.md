# Store submission drafts

Notes and drafts for getting the app into the FOSS Android stores. Nothing here is read by the
app at build or run time.

| File | What it is |
|---|---|
| `app.so7o.ztrackpad.lite.yml` | Draft F-Droid metadata, to be submitted to the `fdroiddata` repository. |
| `izzyondroid-issue.md` | Draft `[AppRequest]` issue for IzzyOnDroid's tracker, which is now on [Codeberg](https://codeberg.org/IzzyOnDroid/repodata/issues). |

**IzzyOnDroid is the shorter path.** It ships developer-built APKs, so it takes the signed APK
already attached to each GitHub tagged release; the work is the fastlane metadata (in
`fastlane/metadata/android/en-US/`) plus an issue on their Codeberg tracker.

**F-Droid builds from source in an offline sandbox**, which needs two things this tree does not
yet have: the API 36 platform available to its builder (see the TODO in the yml), and a
reproducible build. `build.sh` gained `SKIP_SIGN=1` and an `ANDROID_JAR` override for that path.

Neither store applies Google Play's accessibility-service policy, so the app's use of an
accessibility service is not a barrier here.
