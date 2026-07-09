# Securing / Provisioning a FreeKiosk Tablet

This guide lists the ADB commands used to lock a tablet down for kiosk use: install
FreeKiosk, make it **Device Owner**, grant the permissions it needs, set the clock, push
an initial configuration, and — on **rooted panels** — permanently remove the on-screen
navigation bar.

The `scripts/install.bat` (Windows) and `scripts/install.sh` (macOS/Linux) scripts run all
of these steps in order. This document explains what each step does so you can run them
manually or adapt them.

> Package: `com.freekiosk` · Admin receiver: `com.freekiosk/.DeviceAdminReceiver`

---

## 0. Prerequisites

- **ADB** installed on your computer (`adb devices` lists the tablet).
- **USB debugging** enabled on the tablet (Settings → Developer options).
- The release APK built at `android/app/release/app-release.apk`
  (`cd android && ./gradlew assembleRelease`, then copy/point to it).
- The tablet must have **no other Device Owner / work profile** already set, and ideally no
  accounts added, or `dpm set-device-owner` will fail.

Target a specific device when several are connected: append its serial, e.g.
`install.bat 0123456789ABCDEF` or `adb -s 0123456789ABCDEF ...`.

---

## 1. Install the APK

```
adb install -r android/app/release/app-release.apk
```

> Always deploy a **release** build to a standalone panel. A debug build has no embedded JS
> bundle and will crash with *"Unable to load script… run Metro"* unless Metro is reachable.

## 2. Make FreeKiosk the Device Owner

```
adb shell dpm remove-active-admin com.freekiosk/.DeviceAdminReceiver   REM ignored if none
adb shell dpm set-device-owner com.freekiosk/.DeviceAdminReceiver
```

Device Owner unlocks lock-task (true kiosk) mode, status-bar lockdown, screen-capture
blocking, and the rest of the hardening APIs.

## 3. Grant the extra permissions

```
adb shell pm grant com.freekiosk android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.freekiosk android:write_settings allow
```

- **WRITE_SECURE_SETTINGS** — lets FreeKiosk change secure/global settings (rotation lock,
  accessibility auto-enable, and the immersive `policy_control` fallback).
- **WRITE_SETTINGS** — lets the app write `Settings.System` (used by the *Hide Navigation
  Bar* option to set OEM keys such as `hide_system_bar` on panels that honor them).

## 4. Clock & timezone (recommended)

```
adb shell settings put global auto_time 1
adb shell settings put global auto_time_zone 1
adb shell settings put global ntp_server time.google.com
adb shell setprop persist.sys.timezone "America/Montreal"
```

## 5. Preconfigure the kiosk (optional)

Push URL / PIN / kiosk flags in one shot via the ADB config intent:

```
adb shell am start -n com.freekiosk/.MainActivity ^
  --es url "https://kiosk.dev.sirsteward.com" --es pin "1234" ^
  --ez kiosk_enabled true --es auto_relaunch "true"
```

---

## 6. Permanently remove the software navigation bar (ROOTED panels)

Android's **immersive mode cannot fully hide the navigation bar**: a swipe from the bottom
edge always brings it back transiently — this is by design and cannot be disabled by an
app. On many OEM panels (e.g. **Rockchip RK3399 industrial tablets**) the vendor's own
settings only *hide* the bar (`hide_system_bar=1`) but keep the reveal gesture active.

The only reliable way to make the bar **truly absent** (no bar, no gesture) is the
build-time property **`qemu.hw.mainkeys=1`**, which tells Android there are hardware
navigation keys so the OS never creates a software nav bar. Setting it requires **root**.

```
adb root
adb remount
adb shell "grep -q qemu.hw.mainkeys /system/build.prop || echo qemu.hw.mainkeys=1 >> /system/build.prop"
adb shell "grep mainkeys /system/build.prop"    REM should print: qemu.hw.mainkeys=1
adb reboot
```

Notes & troubleshooting:

- **`adb remount` fails (dm-verity):** disable verity first, then retry —
  ```
  adb disable-verity
  adb reboot
  adb root
  adb remount
  ```
- The `>>` **appends** a line; it never rewrites existing entries. Do not edit other lines
  in `build.prop`.
- **Magisk (systemless) alternative** — don't touch `/system`; create
  `/data/adb/post-fs-data.d/hidenav.sh` containing:
  ```
  #!/system/bin/sh
  resetprop qemu.hw.mainkeys 1
  ```
  then `chmod 755` it. Applied on every boot.

### Verify

```
adb shell getprop qemu.hw.mainkeys      REM -> 1
```

After reboot the bottom bar is gone and no swipe can reveal it.

### Clean up the (now-redundant) software flags

Once `qemu.hw.mainkeys=1` is active, the OS handles everything, so turn **off** the in-app
*Settings → Security → Hide Navigation Bar* toggle and clear the software overrides:

```
adb shell settings delete global policy_control
adb shell settings delete global navigationbar_is_min
adb shell settings put system hide_system_bar 0
```

### Roll back (re-enable the bar)

```
adb root
adb remount
adb shell "sed -i '/qemu.hw.mainkeys=1/d' /system/build.prop"
adb reboot
```

---

## If the panel is NOT rooted

There is **no software way** to fully block the swipe-reveal without root. Best effort:

1. In FreeKiosk, enable **Settings → Security → 📴 Hide Navigation Bar** (needs Device Owner
   + kiosk mode). The app hides the bar and re-hides it quickly after any swipe (brief flash
   instead of a persistent bar).
2. Check the panel's own firmware for a built-in *Navigation bar / SystemBar* toggle
   (sometimes under Display) — some OEM ROMs remove the bar cleanly from there.
3. Otherwise, ask the panel vendor for firmware built with `qemu.hw.mainkeys=1`.

---

## Troubleshooting install failures

### `INSTALL_FAILED_VERSION_DOWNGRADE`
The device already has a **higher `versionCode`** than the APK you built. The scripts use
`adb install -r -d` (the `-d` flag allows the downgrade). If installing manually, add `-d`.

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`
The installed app was signed with a **different key** than your local build. By default a
local release build falls back to `debug.keystore` (see `android/app/build.gradle`), which
won't match an app that was signed with the real upload key. Android cannot update across
signatures, and because FreeKiosk is **Device Owner** it can't simply be uninstalled. Two
ways out:

- **Sign the local build with the upload key** (keeps the current Device Owner + config).
  Put the credentials in `android/gradle.properties` and rebuild:
  ```
  FREEKIOSK_UPLOAD_STORE_FILE=freekiosk-upload.jks      # path relative to android/app/
  FREEKIOSK_UPLOAD_STORE_PASSWORD=********
  FREEKIOSK_UPLOAD_KEY_ALIAS=********
  FREEKIOSK_UPLOAD_KEY_PASSWORD=********
  ```
  Never commit that file or the keystore.

- **Clean reset to the local (debug-signed) build** — requires **root**. Removes Device
  Owner so the old app can be uninstalled, then reprovisions. **The install scripts do this
  automatically**: when the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, they run
  `adb root`, delete the Device Owner config, reboot, wait for boot, uninstall, and reinstall
  — no manual steps. Just run `scripts\install.bat` (or `scripts/install.sh`). After the
  first reset, future local builds install in place (same debug key), so the reset does not
  repeat. The equivalent manual sequence, for reference:
  ```
  adb root
  adb shell "rm -f /data/system/device_owner_2.xml /data/system/device_owner.xml /data/system/device_policies.xml"
  adb reboot
  # after boot:
  adb uninstall com.freekiosk
  scripts\install.bat        # or scripts/install.sh
  ```

> The `dpm remove-active-admin` line at the start of the scripts prints a Java
> `SecurityException` when the app is already Device Owner (a DO can't be removed that way
> on production ROMs). It is harmless — the scripts suppress it and continue.

## Quick reference — run everything

```
# Windows
scripts\install.bat [device-serial]

# macOS / Linux
scripts/install.sh [device-serial]
```

The nav-bar removal section (step 6) runs automatically **only if `adb root` succeeds**; on
a non-rooted device it is skipped, and the rest of the provisioning still completes.
