@echo off
REM Provision a FreeKiosk tablet over ADB:
REM   - install the release APK
REM   - set FreeKiosk as Device Owner
REM   - grant WRITE_SECURE_SETTINGS and WRITE_SETTINGS
REM   - set time/timezone and preconfigure the kiosk URL
REM   - (ROOTED panels only) remove the software navigation bar permanently
REM
REM Usage: install.bat [device-serial]
REM        If no serial is given, the only connected device is used.
REM
REM See docs/securing-the-tablet.md for the full explanation of each step.

setlocal

set "APK_DIR=%~dp0..\android\app\release"
set "APK=app-release.apk"
set "PKG=com.freekiosk"
set "ADMIN=%PKG%/.DeviceAdminReceiver"

if "%~1"=="" (
    set "ADB=adb"
) else (
    set "ADB=adb -s %~1"
)

pushd "%APK_DIR%" || (echo Cannot cd to %APK_DIR% & exit /b 1)

echo ==^> Removing existing Device Admin (ignored if absent)
%ADB% shell dpm remove-active-admin %ADMIN%

echo ==^> Installing %APK%
%ADB% install -r "%APK%"
if errorlevel 1 goto :fail

echo ==^> Setting Device Owner (%ADMIN%)
%ADB% shell dpm set-device-owner %ADMIN%
if errorlevel 1 goto :fail

echo ==^> Granting WRITE_SECURE_SETTINGS to %PKG%
%ADB% shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
if errorlevel 1 goto :fail

echo ==^> Granting WRITE_SETTINGS to %PKG% (app-controlled system-bar hiding)
%ADB% shell appops set %PKG% android:write_settings allow

echo ==^> Enabling auto date/time (NTP) and auto timezone
%ADB% shell settings put global auto_time 1
%ADB% shell settings put global auto_time_zone 1
%ADB% shell settings put global ntp_server time.google.com
%ADB% shell setprop persist.sys.timezone "America/Montreal"

echo ==^> Preconfiguring kiosk URL
%ADB% shell am start -n com.freekiosk/.MainActivity --es url "https://kiosk.dev.sirsteward.com" --es pin "1234" --ez kiosk_enabled true --es auto_relaunch "true"
if errorlevel 1 goto :fail

echo ==^> Removing software navigation bar (ROOTED panels only; skipped otherwise)
echo     qemu.hw.mainkeys=1 tells Android there are hardware keys, so the OS never
echo     draws a software nav bar -- no swipe can bring it back. Requires 'adb root'.
%ADB% root >nul 2>&1
%ADB% shell "id" | find "uid=0" >nul
if errorlevel 1 (
    echo     Device not rooted -- skipping nav-bar removal.
) else (
    %ADB% remount
    %ADB% shell "grep -q qemu.hw.mainkeys /system/build.prop || echo qemu.hw.mainkeys=1 >> /system/build.prop"
    echo ==^> Verifying build.prop entry (should print qemu.hw.mainkeys=1)
    %ADB% shell "grep mainkeys /system/build.prop"
    %ADB% unroot
    echo ==^> Rebooting to apply
    %ADB% reboot
)

echo ==^> Done
popd
endlocal
exit /b 0

:fail
echo.
echo ==^> FAILED
popd
endlocal
exit /b 1
