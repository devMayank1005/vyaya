#!/usr/bin/env bash
# Launch prep: the minified release build actually runs (R8 can remove what is found by name only at run time).
# Signs the play release with the local debug key (for this check only), installs it on a running emulator or device
# (or boots the AVD named in $VYAYA_AVD headless), and checks:
#   Home opens; the widget's and tile's Add opens the add form; the widget's update and the tile service run; no crash.
# Usage: scripts/release-smoke.sh   (after ./gradlew :androidApp:assemblePlayRelease)
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
APK="androidApp/build/outputs/apk/play/release/androidApp-play-release-unsigned.apk"
OUT="$(mktemp -d)"
PKG="app.vyaya"
fail() { echo "SMOKE FAIL: $*"; "$ADB" logcat -d -b crash | tail -40 || true; exit 1; }

[ -f "$APK" ] || fail "no release APK; run ./gradlew :androidApp:assemblePlayRelease"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT/smoke.apk" "$APK"

if ! "$ADB" get-state >/dev/null 2>&1; then
  AVD="${VYAYA_AVD:-$("$SDK/emulator/emulator" -list-avds | head -1)}"
  echo "Booting $AVD (headless)…"
  "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot-save >"$OUT/emulator.log" 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
fi

"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
"$ADB" install "$OUT/smoke.apk" >/dev/null
"$ADB" logcat -c
screen() { sleep "${1:-4}"; "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" shell cat /sdcard/ui.xml; }
alive() { [ -n "$("$ADB" shell pidof "$PKG" | tr -d '\r')" ]; }

"$ADB" shell am start -W -n "$PKG/app.vyaya.android.MainActivity" >/dev/null
UI="$(screen 6)"; alive || fail "the app died on start"
echo "$UI" | grep -q 'text="Home"' || fail "Home didn't show"
echo "ok: Home"

# The flags the widget's and tile's PendingIntent carry (NEW_TASK | CLEAR_TOP | SINGLE_TOP).
"$ADB" shell am start -W -a app.vyaya.action.ADD -f 0x34000000 -n "$PKG/app.vyaya.android.MainActivity" >/dev/null
UI="$(screen)"; echo "$UI" | grep -q 'Add entry' || fail "the add form didn't open"
echo "ok: Add (widget and tile)"

# The widget's update is a protected broadcast (only the system sends it, not even the shell): leaving the app runs
# the app's own redraw instead. Then the real Quick Settings tile, clicked, must open the add form.
"$ADB" shell input keyevent KEYCODE_HOME; sleep 2; alive || fail "the app died when left (widget redraw)"
echo "ok: widget redraw on leaving the app"
"$ADB" shell cmd statusbar add-tile "$PKG/app.vyaya.android.QuickAddTile" >/dev/null 2>&1 || true
sleep 4 # the system binds the new tile before it takes clicks
"$ADB" shell cmd statusbar click-tile "$PKG/app.vyaya.android.QuickAddTile" >/dev/null 2>&1 || fail "the tile couldn't be clicked"
sleep 3; "$ADB" shell cmd statusbar collapse >/dev/null 2>&1 || true
UI="$(screen 2)"; echo "$UI" | grep -q 'Add entry' || fail "the tile didn't open the add form: $(echo "$UI" | grep -o 'text="[^"]\+"' | head -8 | tr '\n' ' ')"
"$ADB" shell cmd statusbar remove-tile "$PKG/app.vyaya.android.QuickAddTile" >/dev/null 2>&1 || true
TASKS="$("$ADB" shell dumpsys activity activities | grep -c "Hist .*$PKG/.android.MainActivity" || true)"
[ "$TASKS" -le 1 ] || fail "$TASKS copies of the app's screen are stacked"
echo "ok: Quick Settings tile"

if "$ADB" logcat -d -b crash | grep -q "$PKG"; then fail "a crash was logged"; fi
if "$ADB" logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" >/dev/null; then fail "a fatal exception was logged"; fi
echo "SMOKE OK: the minified release build runs"
