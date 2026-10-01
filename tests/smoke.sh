#!/usr/bin/env bash
# tests/smoke.sh - end-to-end checks for a running trackpad that need no fingers.
#
# Everything here talks to the app's own broadcast API and reads its one-line status
# reply, so there is no tapping, no screenshot and no eyes needed. This is the
# counterpart to the verification list in AGENTS.md: those rows need a human finger
# (two-finger gestures, how a thing *feels*), these do not.
#
#   tests/smoke.sh [package-id]
#
# The package id defaults to the built-in one and is overridable by argument or
# $TRACKPAD_PKG, because the virtual-display-free build and any private mirror of it
# differ only in that string.
#
# The original So7o Z Trackpad (app.so7o.ztrackpad) may be installed alongside this one -
# they coexist on purpose. Do NOT enable both accessibility services at once: two overlay
# services would both inject input and fight over the pointer.
set -uo pipefail

DEFAULT_PKG=app.so7o.ztrackpad.lite
pkg="${1:-}"

pass=0; fail=0
ok()   { printf '  PASS  %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }
info() { printf '        %s\n' "$1"; }

# A field out of the status line: "padlocked=false pad=shown ..." -> "false"
field() { printf '%s' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p"; }

# The whole broadcast dance, returning just the data="..." payload (empty on failure).
# The -n component is mandatory: an implicit broadcast never reaches a manifest-declared
# receiver on API 26+, and the failure is silent.
state_line() {
    adb shell am broadcast -n "$pkg/.ControlReceiver" -a "$pkg.CONTROL" \
        --es op "${1:-status}" "${@:2}" 2>&1 \
        | sed -n 's/.*data="\(.*\)"[[:space:]]*$/\1/p' | head -1
}

echo "== smoke: a device, a package, and a live service"

if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
    bad "no adb device (adb get-state)"
    info "connect first: adb connect <phone-ip>:<port> (wireless debugging)"
    exit 1
fi
ok "adb device present"

if [ -z "$pkg" ] && [ -n "${TRACKPAD_PKG:-}" ]; then pkg="$TRACKPAD_PKG"; fi
[ -n "$pkg" ] || pkg="$DEFAULT_PKG"
if adb shell pm list packages 2>/dev/null | grep -qx "package:$pkg"; then
    ok "package installed: $pkg"
else
    bad "$pkg is not installed"
    info "build and install it first: ./build.sh && adb install -r out/ztrackpad-lite.apk"
    exit 1
fi

st=$(state_line status)
if [ -z "$st" ]; then
    bad "status returned nothing - is the accessibility service enabled?"
    info "raw reply: $(adb shell am broadcast -n "$pkg/.ControlReceiver" -a "$pkg.CONTROL" --es op status 2>&1 | tail -2 | tr '\n' ' ')"
    exit 1
fi
ok "status replied"
info "$st"

echo
echo "== schema"

missing=""
for k in padlocked pad theme w h; do
    [ -n "$(field "$st" "$k")" ] || missing="$missing $k"
done
if [ -z "$missing" ]; then
    ok "status line carries all 5 documented fields"
else
    bad "status is missing:$missing"
    info "README documents these as the status schema"
fi

if [ "$(field "$st" w)" = "0" ] || [ -z "$(field "$st" w)" ]; then
    bad "screen width reads as 0 - readScreenMetrics did not run"
else
    ok "screen metrics present ($(field "$st" w)x$(field "$st" h))"
fi

echo
echo "== theme round-trip (original restored afterwards)"

was=$(field "$(state_line status)" theme)
if [ -n "$was" ]; then
    ok "theme read back ($was)"
else
    bad "status carried no theme="
fi

other=contrast
[ "$was" = "contrast" ] && other=dark
state_line theme --es arg "$other" >/dev/null
if [ "$(field "$(state_line status)" theme)" = "$other" ]; then
    ok "theme $other accepted"
else
    bad "theme did not switch to $other (status says '$(field "$(state_line status)" theme)')"
fi

# An unknown id must be refused, not silently fall back to the default: Theme.byId()
# returns DEFAULT for anything it does not know, so a typo would otherwise look like it
# worked and quietly reset the theme.
if state_line theme --es arg sideways | grep -q '^error'; then
    ok "theme rejects an unknown preset"
else
    bad "theme accepted an unknown preset name"
fi

if [ -n "$was" ]; then
    state_line theme --es arg "$was" >/dev/null
    if [ "$(field "$(state_line status)" theme)" = "$was" ]; then
        ok "theme restored ($was)"
    else
        bad "theme not restored to $was"
    fi
fi

echo
echo "== pad lock round-trip (original restored afterwards)"

was=$(field "$(state_line status)" padlocked)
state_line lock --es arg on >/dev/null
if [ "$(field "$(state_line status)" padlocked)" = "true" ]; then
    ok "lock on -> padlocked=true"
else
    bad "lock on did not report padlocked=true"
fi
state_line lock --es arg off >/dev/null
if [ "$(field "$(state_line status)" padlocked)" = "false" ]; then
    ok "lock off -> padlocked=false"
else
    bad "lock off did not report padlocked=false"
fi
if state_line lock --es arg sideways | grep -q '^error'; then
    ok "lock rejects anything but on|off|toggle"
else
    bad "lock accepted an invalid argument"
fi
if [ "$was" = "true" ]; then
    state_line lock --es arg on >/dev/null
    info "pad was locked before this run, left locked"
fi

echo
echo "== the documented implicit-broadcast trap"

implicit=$(adb shell am broadcast -a "$pkg.CONTROL" --es op status 2>&1)
if printf '%s' "$implicit" | grep -q 'data="'; then
    bad "an implicit broadcast reached the receiver - README/AGENTS say it must not"
    info "on API 26+ a manifest receiver is not an implicit-broadcast target; -n is required"
else
    ok "implicit broadcast did not reach it (the -n component is required)"
fi

echo
echo "== no Shizuku anywhere"

# The point of this build. A Shizuku reference in the manifest would mean the app asks for
# an API permission it must not need, and the service would run as shell again.
if adb shell dumpsys package "$pkg" 2>/dev/null | grep -qi shizuku; then
    bad "the installed package still references Shizuku"
else
    ok "installed package declares no Shizuku permission or provider"
fi

echo
if [ "$fail" = "0" ]; then
    printf 'smoke: %d passed\n' "$pass"
    exit 0
fi
printf 'smoke: %d passed, %d FAILED\n' "$pass" "$fail"
exit 1
