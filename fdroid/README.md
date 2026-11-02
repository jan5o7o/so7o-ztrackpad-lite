# Store submission drafts

Notes and drafts for getting the app into the FOSS Android stores. Nothing here is read by the
app at build or run time.

| File | What it is |
|---|---|
| `app.so7o.ztrackpad.lite.yml` | The F-Droid metadata, the same bytes the merge request adds to `fdroiddata`. |
| `mr-description.md` | The merge request description, template checkboxes included. |
| `izzyondroid-issue.md` | Draft `[AppRequest]` issue for IzzyOnDroid's tracker, which is on [Codeberg](https://codeberg.org/IzzyOnDroid/repodata/issues). |
| `fdroid-rfp-issue.md` | The inclusion request as filed on 2026-10-09. |

## Where the F-Droid request stands

Filed as `fdroiddata#4102` on 2026-10-09, moved the same day by Licaon_Kter to the submission
queue as [rfp#4537](https://gitlab.com/fdroid/rfp/-/work_items/4537), and unchanged there for
three and a half weeks while about fifty requests from the same week stayed open. The queue
README says packaging the app yourself and opening a merge request saves everyone time, so the
metadata was written and [fdroiddata!55455](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/55455)
opened on 2026-11-02.

The recipe is `TARGET_SDK=33 SKIP_SIGN=1 ./build.sh`, with
`output: out/ztrackpad-lite-unsigned.apk`, built from tag `v0.3.1`. android-33 is the newest
platform the buildserver provisions, and the tree compiles against it unchanged, so the GitHub
release still targets 36 while the F-Droid build targets 33. `SKIP_SIGN=1` leaves the APK
unsigned for F-Droid to sign.

0.3.1 is a build-only release, cut because the 0.3.0 tag's `build.sh` demanded a keystore and
hardcoded the platform path. Its only changes are `TARGET_SDK` support and the unsigned
packaging step.

The file passes `fdroid readmeta`, `fdroid rewritemeta`, `fdroid lint` and `fdroid checkupdates`
against fdroidserver 2.4.5, with `checkupdates` resolving `v0.3.1` from the tags. The merge
request pipeline ran the real build on the buildserver, which is how the 0.3.0 problem showed
up.

Still unanswered: whether android-33 is really the newest platform there or that provisioning
file is stale, and what the reproducibility check looks at beyond the pinned mtimes the zip
step already writes.

**IzzyOnDroid is the shorter path.** It ships developer-built APKs, so it takes the signed APK
already attached to each GitHub tagged release; the work is the fastlane metadata (in
`fastlane/metadata/android/en-US/`) plus an issue on their Codeberg tracker. That issue has
not been filed yet.

Neither store applies Google Play's accessibility-service policy, so the app's use of an
accessibility service is not a barrier here.
