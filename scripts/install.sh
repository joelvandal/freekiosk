#!/usr/bin/env bash
# Provision a FreeKiosk tablet over ADB:
#   - install the release APK
#   - set FreeKiosk as Device Owner
#   - grant WRITE_SECURE_SETTINGS and WRITE_SETTINGS
#   - set time/timezone and preconfigure the kiosk URL
#   - (ROOTED panels only) remove the software navigation bar permanently
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

cd "$APK_DIR"

if [[ $# -ge 1 ]]; then
  SERIAL="$1"
  ADB=(adb -s "$SERIAL")
else
  ADB=(adb)
fi

echo "==> Removing existing Device Admin (ignored if absent)"
"${ADB[@]}" shell dpm remove-active-admin "$ADMIN" || true

echo "==> Installing $APK"
"${ADB[@]}" install -r "$APK"

echo "==> Setting Device Owner ($ADMIN)"
"${ADB[@]}" shell dpm set-device-owner "$ADMIN"

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
# qemu.hw.mainkeys=1 tells Android there are hardware keys, so the OS never draws a
# software nav bar -- no swipe can bring it back. Requires 'adb root'. On a non-rooted
# device 'adb root' fails and this section is a harmless no-op.
echo "==> Removing software navigation bar (ROOTED panels only; skipped otherwise)"
if "${ADB[@]}" root; then
  "${ADB[@]}" remount || true
  "${ADB[@]}" shell 'grep -q qemu.hw.mainkeys /system/build.prop || echo qemu.hw.mainkeys=1 >> /system/build.prop' || true
  echo "==> Verifying build.prop entry (should print qemu.hw.mainkeys=1 on rooted panels)"
  "${ADB[@]}" shell 'grep mainkeys /system/build.prop' || true
  "${ADB[@]}" unroot || true
  echo "==> Rebooting to apply"
  "${ADB[@]}" reboot || true
else
  echo "    'adb root' not available (device not rooted) -- skipping nav-bar removal."
fi

echo "==> Done"
