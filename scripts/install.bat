@echo off
REM Provision a FreeKiosk tablet over ADB:
REM   - auto-install Android platform-tools (adb) if adb is not on PATH
REM   - install the release APK
REM       * auto-recovers from a signature mismatch on ROOTED panels
REM         (removes Device Owner, reboots, uninstalls, reinstalls)
REM   - set FreeKiosk as Device Owner
REM   - grant WRITE_SECURE_SETTINGS and WRITE_SETTINGS
REM   - set time/timezone and preconfigure the kiosk URL
REM   - (ROOTED panels) remove the software navigation bar permanently
REM
REM Usage: install.bat [device-serial] [--build] [--debug]
REM        --build  Build the release APK first (gradlew assembleRelease) and copy it
REM                 into android\app\release\ before installing.
REM        --debug  Verbose: trace every command, show hidden errors, dump diagnostics.
REM        If no serial is given, the only connected device is used.
REM
REM See docs/securing-the-tablet.md for the full explanation of each step.

setlocal enabledelayedexpansion

REM Capture the script dir BEFORE the arg loop: 'shift' below also shifts %0,
REM which would corrupt %~dp0 in the --build block.
set "SCRIPT_DIR=%~dp0"
set "APK_DIR=%SCRIPT_DIR%..\android\app\release"
set "APK=app-release.apk"
set "PKG=com.freekiosk"
set "ADMIN=%PKG%/.DeviceAdminReceiver"
set "OUT=%TEMP%\fk_install.txt"

REM --- parse args: a bare token is the serial, --debug enables verbose mode ---
set "DEBUG="
set "DOBUILD="
set "SERIAL="
:parseargs
if "%~1"=="" goto :argsdone
if /I "%~1"=="--debug" (
    set "DEBUG=1"
) else if /I "%~1"=="--build" (
    set "DOBUILD=1"
) else (
    set "SERIAL=%~1"
)
shift
goto :parseargs
:argsdone

if "%SERIAL%"=="" (set "ADB=adb") else (set "ADB=adb -s %SERIAL%")

REM Redirections that hide noise in normal mode but are shown under --debug.
if defined DEBUG (set "Q=") else (set "Q=2>nul")
if defined DEBUG (set "RQ=") else (set "RQ=>nul 2>&1")

REM Ensure adb is available; download Android platform-tools if it is missing.
where adb >nul 2>&1 && goto :adbready
set "TOOLS_ROOT=%LOCALAPPDATA%\FreeKiosk"
set "TOOLS_DIR=%TOOLS_ROOT%\platform-tools"
if exist "%TOOLS_DIR%\adb.exe" (
    set "PATH=%TOOLS_DIR%;%PATH%"
    goto :adbready
)
echo ==^> adb not found -- downloading Android platform-tools...
if not exist "%TOOLS_ROOT%" mkdir "%TOOLS_ROOT%"
curl -L -o "%TEMP%\platform-tools.zip" https://dl.google.com/android/repository/platform-tools-latest-windows.zip
if errorlevel 1 (echo ==^> Download failed ^(need curl^). Install adb manually and re-run. & goto :fail)
tar -xf "%TEMP%\platform-tools.zip" -C "%TOOLS_ROOT%"
if errorlevel 1 (echo ==^> Extraction failed ^(need tar, Windows 10+^). Install adb manually. & goto :fail)
del "%TEMP%\platform-tools.zip" 2>nul
if not exist "%TOOLS_DIR%\adb.exe" (echo ==^> adb.exe missing after extract. & goto :fail)
set "PATH=%TOOLS_DIR%;%PATH%"
echo ==^> platform-tools installed to %TOOLS_DIR%
:adbready

REM --build: produce a fresh release APK and stage it into APK_DIR before installing.
if not defined DOBUILD goto :skipbuild
echo ==^> Building release APK ^(gradlew assembleRelease^)...
pushd "%SCRIPT_DIR%..\android"
call gradlew.bat assembleRelease
if errorlevel 1 (
    popd
    echo ==^> Build FAILED
    goto :fail
)
popd
if not exist "%APK_DIR%" mkdir "%APK_DIR%"
copy /Y "%SCRIPT_DIR%..\android\app\build\outputs\apk\release\app-release.apk" "%APK_DIR%\%APK%" >nul
if errorlevel 1 (
    echo ==^> Could not copy the built APK
    goto :fail
)
echo ==^> Built and staged %APK_DIR%\%APK%
:skipbuild

pushd "%APK_DIR%" || (echo Cannot cd to %APK_DIR% & exit /b 1)

if defined DEBUG (
    echo [debug] verbose mode ON  ^(ADB=%ADB%^)
    call :diag
    echo on
)

echo ==^> Removing existing Device Admin (ignored if absent or already Device Owner)
%ADB% shell dpm remove-active-admin %ADMIN% %Q%

echo ==^> Installing %APK% (-d allows reinstalling over a higher versionCode)
%ADB% install -r -d "%APK%" > "%OUT%" 2>&1
type "%OUT%"
findstr /C:"Success" "%OUT%" >nul && goto :installed

REM Only a signature mismatch is auto-recoverable (and only on a rooted device).
findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%OUT%" >nul || (
    echo ==^> Install failed for a reason other than a signature mismatch.
    goto :fail
)

echo.
echo ==^> Signature mismatch: the installed app is Device Owner and signed with a
echo     different key. Attempting an automatic clean reset ^(REQUIRES ROOT^)...
echo.

echo ==^> [reset] Switching adbd to root
%ADB% root
if errorlevel 1 (
    echo ==^> 'adb root' failed -- cannot auto-remove Device Owner on a non-rooted device.
    echo     Root the panel, or sign the build with the upload key. See docs/securing-the-tablet.md.
    goto :fail
)

echo ==^> [reset] Removing Device Owner / device policies
%ADB% shell "rm -f /data/system/device_owner_2.xml /data/system/device_owner.xml /data/system/device_policies.xml"

echo ==^> [reset] Rebooting the device
%ADB% reboot
echo ==^> [reset] Waiting for the device to come back online...
%ADB% wait-for-device

echo ==^> [reset] Waiting for boot to complete...
set /a _tries=0
:waitboot
set "BOOT="
for /f "usebackq delims=" %%i in (`%ADB% shell getprop sys.boot_completed 2^>nul`) do set "BOOT=%%i"
if defined DEBUG echo [debug] boot_completed='!BOOT!' try=!_tries!
echo !BOOT! | findstr "1" >nul && goto :booted
set /a _tries+=1
if !_tries! GEQ 60 (echo ==^> Timed out waiting for boot. & goto :fail)
timeout /t 3 /nobreak >nul
goto :waitboot
:booted
REM let the framework settle after boot_completed
timeout /t 3 /nobreak >nul

echo ==^> [reset] Uninstalling the old app
%ADB% uninstall %PKG%

echo ==^> [reset] Reinstalling %APK%
%ADB% install -r -d "%APK%" > "%OUT%" 2>&1
type "%OUT%"
findstr /C:"Success" "%OUT%" >nul || (echo ==^> Reinstall still failed. & goto :fail)

:installed
if defined DEBUG call :diag
echo ==^> Setting Device Owner (%ADMIN%)
REM Non-fatal: if the app is already Device Owner from a previous in-place update, this
REM prints an error and we keep going (the desired end state is already reached).
%ADB% shell dpm set-device-owner %ADMIN% %Q%

echo ==^> Granting WRITE_SECURE_SETTINGS to %PKG%
%ADB% shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS

echo ==^> Granting WRITE_SETTINGS to %PKG% (app-controlled system-bar hiding)
%ADB% shell appops set %PKG% android:write_settings allow

echo ==^> Granting camera + microphone permissions to %PKG%
%ADB% shell pm grant %PKG% android.permission.CAMERA %Q%
%ADB% shell pm grant %PKG% android.permission.RECORD_AUDIO %Q%

echo ==^> Setting FreeKiosk as the default Home / launcher (no "Select a Home app" prompt)
%ADB% shell cmd package set-home-activity com.freekiosk/com.freekiosk.MainActivity %Q%

echo ==^> Enabling auto date/time (NTP) and auto timezone
%ADB% shell settings put global auto_time 1
%ADB% shell settings put global auto_time_zone 1
%ADB% shell settings put global ntp_server time.google.com
%ADB% shell setprop persist.sys.timezone "America/Montreal"

echo ==^> Preconfiguring kiosk URL
%ADB% shell am start -n com.freekiosk/.MainActivity --es url "https://kiosk.dev.sirsteward.com" --es pin "1234" --ez kiosk_enabled true --es auto_relaunch "true"

echo ==^> Removing software navigation bar (ROOTED panels only; skipped otherwise)
echo     qemu.hw.mainkeys=1 tells Android there are hardware keys, so the OS never
echo     draws a software nav bar -- no swipe can bring it back. Requires 'adb root'.
%ADB% root %RQ%
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
del "%OUT%" 2>nul
popd
endlocal
exit /b 0

:diag
echo(
echo [debug] ---- diagnostics ----
%ADB% devices -l
echo [debug] installed %PKG%:
%ADB% shell dumpsys package %PKG% | findstr /C:"versionName=" /C:"versionCode="
echo [debug] device owner:
%ADB% shell dumpsys device_policy | findstr /I /C:"Device Owner" /C:"admin="
echo [debug] ---------------------
echo(
goto :eof

:fail
echo.
echo ==^> FAILED
del "%OUT%" 2>nul
popd
endlocal
exit /b 1
