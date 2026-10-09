# Releasing

A release is a signed APK attached to a GitHub release. The source is the primary path — a
release is a convenience for anyone without a Termux build setup.

Work reaches `main` only through a pull request, and `main` requires both the PR and a passing
`build` check, so what ships is always a tree CI has built. `dev` is where work lands;
feature branches pull-request into `dev`, then `dev` pull-requests into `main`.

```bash
VERSION=0.2.0                                          # the version this release will carry

# 1. on dev: the version lives in AndroidManifest.xml - bump BOTH numbers there.
#    versionCode must increase every release, or Android refuses the update.
sed -i "s/versionName=\"[^\"]*\"/versionName=\"$VERSION\"/" AndroidManifest.xml
sed -i 's/versionCode="\([0-9]*\)"/versionCode="\1"/' AndroidManifest.xml   # edit by hand

# 2. the changelog entry IS the release notes - write it above "## Unreleased"
$EDITOR CHANGELOG.md

# 3. open the release PR (dev into main) and wait for the build check, then merge it.
#    A merge commit, not a squash: a squash mints a new SHA and leaves dev needing a re-align
#    that a merge commit never needs.

# 4. build from the merged commit, in this clone, with the release key
git switch main && git pull --ff-only
git status --porcelain                 # must be empty - an APK from a dirty tree matches no source
KSPASS=$(cat ~/.ztrackpad-lite-kspass) ./build.sh

# 5. prove the artefact before publishing it
aapt2 dump badging out/ztrackpad-lite.apk | grep -E '^package|application-label:'
unzip -p out/ztrackpad-lite.apk classes.dex | strings | grep -c 'com\.pi\.'    # must be 0
sha256sum out/ztrackpad-lite.apk

# 6. tag the commit you built, and attach the APK with the notes from the changelog
git tag -a "v$VERSION" -m "So7o Z Trackpad Lite $VERSION" && git push origin "v$VERSION"
awk -v v="$VERSION" '$0 ~ "^## " v "($| )" {f=1;next} /^## /{f=0} f' CHANGELOG.md > "$TMPDIR/notes.md"
echo "sha256: $(sha256sum out/ztrackpad-lite.apk | cut -d' ' -f1)" >> "$TMPDIR/notes.md"
gh release create "v$VERSION" out/ztrackpad-lite.apk \
    --title "So7o Z Trackpad Lite $VERSION" --notes-file "$TMPDIR/notes.md"
```

Rules that came from getting one of these wrong:

- **`versionCode` must increase**, and the tag should carry the same version as the changelog
  heading.
- **Build from a clean tree, at the commit you tag.** `git status --porcelain` has to be empty.
- **The release keystore never enters the repository.** It lives in this clone as
  `keystore.jks`, with its password in `~/.ztrackpad-lite-kspass`. It is what lets an existing
  install update in place: lose it and no install of this app can ever update again. Back it up.
- **Never copy the keystore from another tree.** This project has its own release identity; a
  keystore copied out of a sibling clone would sign two apps with one key and, worse, invite
  keeping only one of them.
- **Put the SHA-256 in the release notes**, and say the APK is signed with the maintainer's key,
  so anyone holding a self-built copy understands why Android will not update across the two.
- **CI cannot see inside an APK that is attached to a release**, so the `strings` check in step 5
  is not optional even though the workflow runs its own copy of it on the tree.
