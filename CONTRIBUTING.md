# Contributing

**Bug reports and ideas: please open an issue.**

**Pull requests are welcome.** This is an ordinary repository — there is no export step and no
generated tree, so a merged PR stays merged.

## How the branches work

- **`main` is releases only.** It takes changes through a pull request, and the `build` check
  has to pass. Nothing is pushed to it directly, not even by the maintainer.
- **`dev` is where work lands.** Branch from `dev`, open the PR against `dev`, and it gets
  merged there.
- **Releases go `dev` → `main`** as a pull request, with a merge commit rather than a squash.

`.github/workflows/build.yml` runs on every push to either branch and on every pull request.
It installs the platform and build-tools, builds, signs with a **throwaway** key, and asserts
that no development identity is in the tracked tree or inside `classes.dex`. It cannot run
`tests/smoke.sh`, which needs a phone — so if your change touches anything a finger does,
say in the PR what you tested on a device and what you did not.

## Practical notes

- **`build.sh` builds and signs locally.** It needs `KSPASS` or `~/.ztrackpad-lite-kspass`;
  the release keystore is not in this repository. Your own throwaway key is fine for testing.
- **Never commit a keystore or a password.** They are gitignored, and `build.sh` fails closed
  without one — that is deliberate.
- **`tests/smoke.sh` discovers the package id** rather than hardcoding it, so it needs no
  renaming. Follow that pattern for anything scriptable: the pad's broadcast API
  (`ControlReceiver`) is there so behaviour can be checked without tapping, and a new op is
  usually a better contribution than a new screenshot.
- **Keep the removed features removed.** This build's whole point is that it does not depend on
  Shizuku. A change that reintroduces a shell bridge, a second process, or a permission belongs
  in [upstream](https://github.com/jan5o7o/ztrackpad), not here.
- **Anything a finger has to check belongs in the verification list in
  [AGENTS.md](AGENTS.md).** If you verify a row, move it and say so; if you cannot, leave it
  where it is.
