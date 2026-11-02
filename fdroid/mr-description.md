## Abstract for new app inclusion

So7o Z Trackpad Lite puts a floating trackpad and pointer on an Android screen. One
accessibility service turns taps, holds and drags on the pad into gestures on whatever sits
under the pointer, so the app needs no root, no Shizuku and no companion app.

It was written for a foldable held in two hands: the thumbs rest on the bottom half, usually
over a keyboard, and the top half is out of reach. Park the pad next to the thumb that is not
typing and one thumb drives the pointer across the whole screen. The same pad works
one-handed on any phone.

Screenshots and the listing text are in the upstream fastlane folder
(`fastlane/metadata/android/en-US/`), so nothing is duplicated in the metadata.

Why this app exists: the pointer tools in F-Droid either target another device (remote
mouse and keyboard servers) or need root or adb to inject input locally. This one injects
gestures through a single accessibility service and has no network permission at all, so it
works on a stock phone with nothing installed alongside it.

### Build

The tree is plain Java with a hand-rolled build script: `./build.sh` runs aapt2 -> javac ->
d8 -> zip -> apksigner and needs only the Android SDK tools, no Gradle. The recipe therefore
just supplies the platform and the signing switch:

    Builds:
      - versionName: 0.3.0
        versionCode: 3
        commit: v0.3.0
        output: out/ztrackpad-lite-unsigned.apk
        build: TARGET_SDK=33 SKIP_SIGN=1 ./build.sh

`TARGET_SDK=33` is there because the buildserver provisioning
(`fdroidserver/buildserver/provision-android-sdk`) tops out at android-33. The code uses
nothing newer than API 33, and the tree was verified to compile and package against the
platform-33 jar, producing an APK at `targetSdkVersion 33`. The GitHub release APK still
targets 36. If android-33 is stale and the buildserver does ship 36, dropping `TARGET_SDK=33`
is all that is needed.

`SKIP_SIGN=1` stops the script after packaging and leaves `out/ztrackpad-lite-unsigned.apk`
for F-Droid to sign.

## Checklist

### Policy

* [x] The app complies with the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy). MIT, no ads or trackers, no `INTERNET` permission (only `VIBRATE`), no self-updater, no downloaded binaries.
* [x] The original app author has been notified (and does not oppose the inclusion). I am the author.
* [x] The upstream app source code repo contains the app metadata in a [Fastlane](https://gitlab.com/snippets/1895688) folder structure, `en-US` included, with changelog (`changelogs/3.txt`), icon and screenshots.

### Docs

* [x] Please read [the guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md) first if this is your first contribution.
* [x] Please make sure your metadata follows the best practice in [our templates](https://gitlab.com/fdroid/fdroiddata/tree/master/templates).
* [x] Please read [the Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and make sure your metadata is valid.
* [x] Please read the [Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

### Merge Request Setup

* [x] The title of this merge request should follow "New app: app name" format.
* [x] Please make sure your fdroiddata fork is public and your branch is not protected.
* [x] Please read [our Git guide](https://gitlab.com/fdroid/wiki/-/wikis/Tips-for-fdroiddata-contributors/Git-Usage). The branch is based on current `master`.
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request: this closes fdroid/rfp#4537, which was moved from fdroiddata#4102.
* [x] Please only submit one app in one MR.

### Metadata

* [x] Metadata must be put in `metadata/<applicationId>.yml`.
* [x] Metadata must be a valid YAML file.
* [x] Metadata must use LF as line ending.
* [x] Don't add summary/description/changelog/images or anything that should be provided in upstream repo. The diff touches one file.
* [x] Releases are tagged and auto update is enabled (`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`).

Checked locally with fdroidserver 2.4.5: `fdroid readmeta`, `fdroid rewritemeta` and
`fdroid lint` are clean, and `fdroid checkupdates` resolves `v0.3.0` from the tags. The
recipe itself has not run on a machine with the SDK here, so this pipeline is its first
build.

The RFP thread asked what the rebuild check needs for a hand-rolled build. The zip step pins
a fixed mtime for every entry and pads uncompressed entries to 4-byte alignment, but I would
like to know what else the reproducibility check looks at before claiming a verified build.
