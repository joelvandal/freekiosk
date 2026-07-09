#!/usr/bin/env bash
# Provision a FreeKiosk tablet over ADB:
#   - install the release APK
#       * auto-recovers from a signature mismatch on ROOTED panels
#         (removes Device Owner, reboots, uninstalls, reinstalls)
#   - set FreeKiosk as Device Owner
#   - grant WRITE_SECURE_SETTINGS and WRITE_SETTINGS
#   - set time/timezone and preconfigure the kiosk URL
#   - (ROOTED panels) remove the software navigation bar permanently
#
# Usage: ./install.sh [device-serial]
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

cd "$APK_DIR"

if [[ $# -ge 1 ]]; then
  ADB=(adb -s "$1")
else
  ADB=(adb)
fi

echo "==> Removing existing Device Admin (ignored if absent or already Device Owner)"
"${ADB[@]}" shell dpm remove-active-admin "$ADMIN" 2>/dev/null || true

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

echo "==> Setting Device Owner ($ADMIN)"
# Non-fatal: already-Device-Owner (in-place update) is an expected 'failure' here.
"${ADB[@]}" shell dpm set-device-owner "$ADMIN" 2>/dev/null || true

echo "==> Granting WRITE_SECURE_SETTINGS to $PKG"
"${ADB[@]}" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS

echo "==> Granting WRITE_SETTINGS to $PKG (app-controlled system-bar hiding)"
"${ADB[@]}" shell appops set "$PKG" android:write_settings allow || true

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
