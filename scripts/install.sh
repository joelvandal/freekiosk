#!/usr/bin/env bash
# Provision a FreeKiosk tablet over ADB:
#   - auto-install Android platform-tools (adb) if adb is not on PATH
#   - install the release APK
#       * auto-recovers from a signature mismatch on ROOTED panels
#         (removes Device Owner, reboots, uninstalls, reinstalls)
#   - set FreeKiosk as Device Owner
#   - grant WRITE_SECURE_SETTINGS and WRITE_SETTINGS
#   - set time/timezone and preconfigure the kiosk URL
#   - (ROOTED panels) remove the software navigation bar permanently
#
# Usage: ./install.sh [device-serial] [--build] [--debug]
#        --build  Build the release APK first (./gradlew assembleRelease) and copy it
#                 into android/app/release/ before installing.
#        --debug  Verbose: trace every command, show hidden errors, dump diagnostics.
#        If no serial is given, the only connected device is used.
#
# See docs/securing-the-tablet.md for the full explanation of each step.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_DIR="${SCRIPT_DIR}/../android/app/release"
APK="app-release.apk"
PKG="com.freekiosk"
ADMIN="${PKG}/.DeviceAdminReceiver"
OUT="$(mktemp)"

# --- parse args: a bare token is the serial, flags toggle features ---
DEBUG=""
DOBUILD=""
SERIAL=""
for arg in "$@"; do
  case "$arg" in
    --debug) DEBUG=1 ;;
    --build) DOBUILD=1 ;;
    *)       SERIAL="$arg" ;;
  esac
done

# Ensure adb is available; download Android platform-tools if it is missing.
if ! command -v adb >/dev/null 2>&1; then
  TOOLS_DIR="$HOME/.freekiosk/platform-tools"
  if [[ -x "$TOOLS_DIR/adb" ]]; then
    export PATH="$TOOLS_DIR:$PATH"
  else
    echo "==> adb not found -- downloading Android platform-tools..."
    case "$(uname -s)" in
      Darwin) PT_OS=darwin ;;
      Linux)  PT_OS=linux ;;
      *) echo "==> Unsupported OS for auto-install; install adb manually."; exit 1 ;;
    esac
    mkdir -p "$HOME/.freekiosk"
    ZIP="$(mktemp).zip"
    curl -L -o "$ZIP" "https://dl.google.com/android/repository/platform-tools-latest-${PT_OS}.zip"
    unzip -q -o "$ZIP" -d "$HOME/.freekiosk"
    rm -f "$ZIP"
    export PATH="$TOOLS_DIR:$PATH"
  fi
  command -v adb >/dev/null 2>&1 || { echo "==> adb still not available."; exit 1; }
fi

# --build: produce a fresh release APK and stage it into APK_DIR before installing.
if [[ -n "$DOBUILD" ]]; then
  echo "==> Building release APK (./gradlew assembleRelease)..."
  ( cd "$SCRIPT_DIR/../android" && ./gradlew assembleRelease )
  mkdir -p "$APK_DIR"
  cp -f "$SCRIPT_DIR/../android/app/build/outputs/apk/release/app-release.apk" "$APK_DIR/$APK"
  echo "==> Built and staged $APK_DIR/$APK"
fi

cd "$APK_DIR"

if [[ -n "$SERIAL" ]]; then ADB=(adb -s "$SERIAL"); else ADB=(adb); fi

# In normal mode hide the noisy stderr of best-effort commands; in debug show it.
if [[ -n "$DEBUG" ]]; then ERR=/dev/stderr; else ERR=/dev/null; fi

diag() {
  echo
  echo "[debug] ---- diagnostics ----"
  "${ADB[@]}" devices -l || true
  echo "[debug] installed $PKG:"
  "${ADB[@]}" shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionName=|versionCode=" || true
  echo "[debug] device owner:"
  "${ADB[@]}" shell dumpsys device_policy 2>/dev/null | grep -iE "device owner|admin=" || true
  echo "[debug] ---------------------"
  echo
}

if [[ -n "$DEBUG" ]]; then
  echo "[debug] verbose mode ON (ADB=${ADB[*]})"
  diag
  set -x
fi

echo "==> Removing existing Device Admin (ignored if absent or already Device Owner)"
"${ADB[@]}" shell dpm remove-active-admin "$ADMIN" 2>"$ERR" || true

install_apk() {
  # Prints adb output, returns 0 on Success.
  "${ADB[@]}" install -r -d "$APK" >"$OUT" 2>&1 || true
  cat "$OUT"
  grep -q "Success" "$OUT"
}

echo "==> Installing $APK (-d allows reinstalling over a higher versionCode)"
if ! install_apk; then
  if ! grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" "$OUT"; then
    echo "==> Install failed for a reason other than a signature mismatch."
    exit 1
  fi
  echo
  echo "==> Signature mismatch: the installed app is Device Owner and signed with a"
  echo "    different key. Attempting an automatic clean reset (REQUIRES ROOT)..."
  echo

  echo "==> [reset] Switching adbd to root"
  if ! "${ADB[@]}" root; then
    echo "==> 'adb root' failed -- cannot auto-remove Device Owner on a non-rooted device."
    echo "    Root the panel, or sign the build with the upload key. See docs/securing-the-tablet.md."
    exit 1
  fi

  echo "==> [reset] Removing Device Owner / device policies"
  "${ADB[@]}" shell 'rm -f /data/system/device_owner_2.xml /data/system/device_owner.xml /data/system/device_policies.xml' || true

  echo "==> [reset] Rebooting the device"
  "${ADB[@]}" reboot || true
  echo "==> [reset] Waiting for the device to come back online..."
  "${ADB[@]}" wait-for-device

  echo "==> [reset] Waiting for boot to complete..."
  tries=0
  until [[ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    tries=$((tries + 1))
    if (( tries >= 60 )); then echo "==> Timed out waiting for boot."; exit 1; fi
    sleep 3
  done
  sleep 3   # let the framework settle

  echo "==> [reset] Uninstalling the old app"
  "${ADB[@]}" uninstall "$PKG" || true

  echo "==> [reset] Reinstalling $APK"
  if ! install_apk; then
    echo "==> Reinstall still failed."
    exit 1
  fi
fi

[[ -n "$DEBUG" ]] && diag || true

echo "==> Setting Device Owner ($ADMIN)"
# Non-fatal: already-Device-Owner (in-place update) is an expected 'failure' here.
"${ADB[@]}" shell dpm set-device-owner "$ADMIN" 2>"$ERR" || true

echo "==> Granting WRITE_SECURE_SETTINGS to $PKG"
"${ADB[@]}" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS

echo "==> Granting WRITE_SETTINGS to $PKG (app-controlled system-bar hiding)"
"${ADB[@]}" shell appops set "$PKG" android:write_settings allow || true

echo "==> Granting camera + microphone permissions to $PKG"
"${ADB[@]}" shell pm grant "$PKG" android.permission.CAMERA 2>"$ERR" || true
"${ADB[@]}" shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>"$ERR" || true

echo "==> Setting FreeKiosk as the default Home / launcher (no 'Select a Home app' prompt)"
"${ADB[@]}" shell cmd package set-home-activity com.freekiosk/com.freekiosk.MainActivity 2>"$ERR" || true

echo "==> Enabling auto date/time (NTP) and auto timezone"
"${ADB[@]}" shell settings put global auto_time 1
"${ADB[@]}" shell settings put global auto_time_zone 1
"${ADB[@]}" shell settings put global ntp_server time.google.com
"${ADB[@]}" shell setprop persist.sys.timezone "America/Montreal"

echo "==> Preconfiguring kiosk URL"
"${ADB[@]}" shell am start -n com.freekiosk/.MainActivity \
  --es url "https://kiosk.dev.sirsteward.com" --es pin "1234" \
  --ez kiosk_enabled true --es auto_relaunch "true"

# Remove the software navigation bar permanently (ROOTED panels only).
echo "==> Removing software navigation bar (ROOTED panels only; skipped otherwise)"
if "${ADB[@]}" root >/dev/null 2>&1 && "${ADB[@]}" shell id | grep -q "uid=0"; then
  "${ADB[@]}" remount || true
  "${ADB[@]}" shell 'grep -q qemu.hw.mainkeys /system/build.prop || echo qemu.hw.mainkeys=1 >> /system/build.prop' || true
  echo "==> Verifying build.prop entry (should print qemu.hw.mainkeys=1)"
  "${ADB[@]}" shell 'grep mainkeys /system/build.prop' || true
  "${ADB[@]}" unroot || true
  echo "==> Rebooting to apply"
  "${ADB[@]}" reboot || true
else
  echo "    'adb root' not available (device not rooted) -- skipping nav-bar removal."
fi

rm -f "$OUT"
echo "==> Done"
