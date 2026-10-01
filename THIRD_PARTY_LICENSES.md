# Third-party licences

So7o Z Trackpad Lite is MIT ([`LICENSE`](LICENSE)). It vendors one jar in `libs/` so the
build needs no network, and `build.sh` feeds it to `d8` — so its code ends up inside
`classes.dex` and its terms ship with the APK. That licence requires its text to be passed
on, which is what this file and `licenses/` are for.

The upstream So7o Z Trackpad also vendored four Shizuku client jars. Those are **not** here:
this build injects input with accessibility gestures only, and depends on no other app, no
shell UID and no root.

## androidx.annotation — Apache-2.0

| file | what it is |
|---|---|
| `libs/androidx-annotation.jar` | AndroidX `annotation` (Google), licensed Apache-2.0 |

Full text: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)
